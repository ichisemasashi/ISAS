(ns isas.gantt
  (:require [clojure.string :as str]
            [isas.db :as db]
            [isas.geo :as geo]
            [isas.log :as log]
            [isas.paints :as paints]
            [isas.time :as time]))

(declare finalize-missing-days-for-user)

(def ^:private backfill-max-days 90)

(defn- has-fields? [sys user-id]
  (pos? (count (db/list-fields (:ds sys) user-id))))

(defn- require-fields [sys user-id]
  (if (has-fields? sys user-id)
    {:ok true}
    (do
      (log/warn "圃場が無いのでガントを使えません" :user-id user-id)
      {:ok false :code "no_fields"})))

(defn normalize-title [s]
  (let [t (str/trim (str (or s "")))]
    (cond
      (str/blank? t)
      (do (log/warn "作業タイトルが空です")
          {:ok false :code "title_required"})
      (> (count t) 200)
      (do (log/warn "作業タイトルが長すぎます" :length (count t))
          {:ok false :code "title_too_long"})
      :else {:ok true :title t})))

(defn normalize-times [start end]
  (cond
    (not (and (time/local-minute-ok? start) (time/local-minute-ok? end)))
    (do (log/warn "日時の形式が不正です" :start start :end end)
        {:ok false :code "time_invalid"})

    (not (.isBefore (time/parse-local-minute start) (time/parse-local-minute end)))
    (do (log/warn "終了が開始以前です" :start start :end end)
        {:ok false :code "time_order"})

    :else
    {:ok true :start-at (str start) :end-at (str end)}))

(defn- normalize-optional-work-name [s]
  (let [t (str/trim (str (or s "")))]
    (cond
      (str/blank? t) {:ok true :work-name nil}
      :else (paints/normalize-work-name t))))

(defn- normalize-field-ids [sys user-id ids]
  (let [raw (cond
              (nil? ids) []
              (sequential? ids) ids
              :else [ids])
        ints (mapv geo/as-int raw)]
    (if (some nil? ints)
      (do (log/warn "対象圃場の ID が不正です" :user-id user-id)
          {:ok false :code "field_not_found"})
      (let [uniq (vec (distinct ints))]
        (if (every? #(db/find-field (:ds sys) user-id %) uniq)
          {:ok true :field-ids uniq}
          (do (log/warn "対象圃場が見つかりません" :user-id user-id)
              {:ok false :code "field_not_found"}))))))

(defn- present-title [t]
  {:id (:id t)
   :name (:name t)})

(defn- present-row [ds row]
  {:id (:id row)
   :title_id (:title_id row)
   :title (:title row)
   :start_at (:start_at row)
   :end_at (:end_at row)
   :work_name (:work_name row)
   :execution_status (:execution_status row)
   :field_ids (db/list-gantt-targets ds (:id row))})

(defn- child-summary [ds gantt-id]
  (let [wt (db/count-gantt-work-times ds gantt-id)
        cl (db/count-gantt-checklist ds gantt-id)
        done (:done cl)
        total (:total cl)]
    {:work_time_count (long (if (nil? wt) 0 wt))
     :checklist_done (long (if (nil? done) 0 done))
     :checklist_total (long (if (nil? total) 0 total))}))

(defn- present-daily-row [ds row]
  (merge (present-row ds row)
         (child-summary ds (:id row))))

(def ^:private execution-statuses #{"not_started" "in_progress" "done"})

(defn- normalize-execution-status [s]
  (let [t (str/trim (str (or s "")))]
    (if (contains? execution-statuses t)
      {:ok true :execution-status t}
      (do
        (log/warn "実行状態が不正です" :value s)
        {:ok false :code "execution_status_invalid"}))))

(defn- parse-daily-statuses [raw]
  (if (nil? raw)
    {:ok true :statuses ["not_started" "in_progress"]}
    (let [parts (->> (str/split (str raw) #",")
                     (map str/trim)
                     (remove str/blank?)
                     vec)]
      (if (every? #(contains? execution-statuses %) parts)
        {:ok true :statuses (vec (distinct parts))}
        (do
          (log/warn "日次の状態フィルタが不正です" :statuses raw)
          {:ok false :code "statuses_invalid"})))))

(defn- daily-window [range-key]
  (case (str range-key)
    "today" {:ok true :window (time/tokyo-today-window)}
    "days7" {:ok true :window (time/tokyo-days7-window)}
    "all" {:ok true :window :all}
    (do
      (log/warn "日次の期間が不正です" :range range-key)
      {:ok false :code "range_invalid"})))

(defn- overlaps-window? [row window-start window-end]
  (and (pos? (compare window-end (str (:start_at row))))
       (pos? (compare (str (:end_at row)) window-start))))

(defn list-daily [sys user-id {:keys [range statuses]}]
  (let [win (daily-window range)
        st (parse-daily-statuses statuses)]
    (cond
      (not (:ok win)) win
      (not (:ok st)) st
      :else
      (let [status-set (set (:statuses st))
            base (->> (db/list-gantt-rows (:ds sys) user-id)
                      (filter #(contains? status-set (:execution_status %))))
            rows (if (= :all (:window win))
                   (mapv #(present-daily-row (:ds sys) %) base)
                   (let [[w0 w1] (:window win)]
                     (->> base
                          (filter #(overlaps-window? % w0 w1))
                          (mapv #(present-daily-row (:ds sys) %)))))]
        (log/info "日次一覧を返しました"
                  :user-id user-id :range (str range) :statuses (:statuses st) :count (count rows))
        {:ok true :rows rows}))))

(defn list-admin-daily [sys {:keys [range statuses]}]
  (let [win (daily-window range)
        st (parse-daily-statuses statuses)]
    (cond
      (not (:ok win)) win
      (not (:ok st)) st
      :else
      (let [status-set (set (:statuses st))
            window (:window win)
            users (db/list-active-users (:ds sys))
            rows (->> users
                      (mapcat
                       (fn [u]
                         (let [uid (:id u)
                               email (:email u)
                               base (->> (db/list-gantt-rows (:ds sys) uid)
                                         (filter #(contains? status-set (:execution_status %))))
                               filtered (if (= :all window)
                                          base
                                          (let [[w0 w1] window]
                                            (filter #(overlaps-window? % w0 w1) base)))]
                           (map (fn [row]
                                  (assoc (present-daily-row (:ds sys) row)
                                         :user_id uid
                                         :user_email email))
                                filtered))))
                      (sort-by (juxt :start_at :user_id :id))
                      vec)]
        (log/info "管理者日次一覧を返しました"
                  :range (str range) :statuses (:statuses st) :count (count rows))
        {:ok true :rows rows}))))

(defn- active-row? [row]
  (nil? (:deleted_at row)))

(defn- active-title? [t]
  (nil? (:deleted_at t)))

(defn- resolve-title-id [sys user-id raw]
  (let [s (str/trim (str (or raw "")))]
    (if (str/blank? s)
      {:ok true :title-id nil}
      (let [tid (geo/as-int s)
            t (when tid (db/find-gantt-title (:ds sys) user-id tid))]
        (cond
          (nil? tid)
          (do (log/warn "題名 ID が不正です" :user-id user-id :title-id raw)
              {:ok false :code "title_not_found"})
          (or (nil? t) (not (active-title? t)))
          (do (log/warn "題名が見つかりません" :user-id user-id :title-id tid)
              {:ok false :code "title_not_found"})
          :else {:ok true :title-id tid})))))

(defn list-titles [sys user-id]
  (let [gate (require-fields sys user-id)]
    (if-not (:ok gate)
      gate
      (do
        (log/info "ガント題名を一覧しました" :user-id user-id)
        {:ok true
         :titles (mapv present-title (db/list-gantt-titles (:ds sys) user-id))}))))

(defn create-title [sys user-id body]
  (let [gate (require-fields sys user-id)
        name (normalize-title (:name body))]
    (cond
      (not (:ok gate)) gate
      (not (:ok name)) name
      :else
      (let [t (db/insert-gantt-title! (:ds sys) {:user-id user-id :name (:title name)})]
        (log/info "ガント題名を足しました" :user-id user-id :title-id (:id t) :name (:title name))
        {:ok true :title (present-title t)}))))

(defn update-title [sys user-id id body]
  (let [gate (require-fields sys user-id)
        tid (geo/as-int id)
        t (when tid (db/find-gantt-title (:ds sys) user-id tid))
        name (normalize-title (:name body))]
    (cond
      (not (:ok gate)) gate
      (or (nil? tid) (nil? t) (not (active-title? t)))
      (do
        (log/warn "ガント題名が見つかりません" :user-id user-id :title-id id)
        {:ok false :code "title_not_found"})
      (not (:ok name)) name
      :else
      (do
        (db/update-gantt-title! (:ds sys) tid (:title name))
        (log/info "ガント題名を直しました" :user-id user-id :title-id tid :name (:title name))
        {:ok true :title (present-title (db/find-gantt-title (:ds sys) user-id tid))}))))

(defn soft-delete-title [sys user-id id]
  (let [gate (require-fields sys user-id)
        tid (geo/as-int id)
        t (when tid (db/find-gantt-title (:ds sys) user-id tid))]
    (cond
      (not (:ok gate)) gate
      (or (nil? tid) (nil? t) (not (active-title? t)))
      (do
        (log/warn "消すガント題名がありません" :user-id user-id :title-id id)
        {:ok false :code "title_not_found"})
      :else
      (let [rows (db/list-gantt-rows-for-title (:ds sys) user-id tid)
            row-n (count rows)
            wt-n (reduce + 0 (map #(db/count-gantt-work-times (:ds sys) (:id %)) rows))
            ci-n (reduce + 0 (map #(:total (db/count-gantt-checklist (:ds sys) (:id %))) rows))]
        (db/soft-delete-gantt-work-times-for-title! (:ds sys) tid)
        (db/soft-delete-gantt-checklist-items-for-title! (:ds sys) tid)
        (db/soft-delete-gantt-rows-for-title! (:ds sys) tid)
        (db/soft-delete-gantt-title! (:ds sys) tid)
        (log/info "ガント題名をソフト削除しました"
                  :user-id user-id :title-id tid
                  :rows row-n :work-times wt-n :checklist-items ci-n)
        {:ok true}))))

(defn list-rows
  "自分の未削除作業一覧。第2版工程5から圃場0枚でも成功（空可）。作成・更新は require-fields のまま。"
  [sys user-id]
  (finalize-missing-days-for-user sys user-id)
  (let [titles (mapv present-title (db/list-gantt-titles (:ds sys) user-id))
        rows (mapv #(present-row (:ds sys) %) (db/list-gantt-rows (:ds sys) user-id))]
    (log/info "ガント行を一覧しました" :user-id user-id :count (count rows) :title-count (count titles))
    {:ok true :titles titles :rows rows}))

(defn list-admin-rows
  "管理者向け候補。全利用者の未削除行。user_email 付き。"
  [sys]
  (let [email-by-id (into {} (map (fn [u] [(:id u) (:email u)]) (db/list-active-users (:ds sys))))
        rows (mapv (fn [row]
                     {:id (:id row)
                      :title (:title row)
                      :work_name (:work_name row)
                      :user_id (:user_id row)
                      :user_email (get email-by-id (:user_id row))
                      :start_at (:start_at row)
                      :end_at (:end_at row)})
                   (db/list-gantt-rows-undeleted-all (:ds sys)))]
    (log/info "管理者ガント候補を一覧しました" :count (count rows))
    {:ok true :rows rows}))

(defn- validate-body [sys user-id body]
  (let [title (normalize-title (:title body))
        times (normalize-times (:start_at body) (:end_at body))
        wn (normalize-optional-work-name (:work_name body))
        fields (normalize-field-ids sys user-id (:field_ids body))
        tid (resolve-title-id sys user-id (:title_id body))]
    (cond
      (not (:ok tid)) tid
      (not (:ok title)) title
      (not (:ok times)) times
      (not (:ok wn)) wn
      (not (:ok fields)) fields
      (and (seq (:field-ids fields)) (nil? (:work-name wn)))
      (do
        (log/warn "対象があるのに作業名がありません" :user-id user-id)
        {:ok false :code "work_name_required"})
      :else
      {:ok true
       :title-id (:title-id tid)
       :title (:title title)
       :start-at (:start-at times)
       :end-at (:end-at times)
       :work-name (:work-name wn)
       :field-ids (:field-ids fields)})))

(defn create-row [sys user-id body]
  (let [gate (require-fields sys user-id)]
    (if-not (:ok gate)
      gate
      (let [v (validate-body sys user-id body)]
        (if-not (:ok v)
          v
          (let [row (db/insert-gantt-row! (:ds sys) {:user-id user-id
                                                     :title-id (:title-id v)
                                                     :title (:title v)
                                                     :start-at (:start-at v)
                                                     :end-at (:end-at v)
                                                     :work-name (:work-name v)
                                                     :execution-status "not_started"})]
            (db/replace-gantt-targets! (:ds sys) (:id row) (:field-ids v))
            (log/info "ガント作業を足しました"
                      :user-id user-id :gantt-id (:id row) :title-id (:title-id v)
                      :title (:title v) :fields (count (:field-ids v))
                      :execution-status "not_started")
            {:ok true :row (present-row (:ds sys) row)}))))))

(defn update-row [sys user-id id body]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)
        row (when gid (db/find-gantt-row (:ds sys) user-id gid))]
    (cond
      (not (:ok gate)) gate
      (or (nil? gid) (nil? row) (not (active-row? row)))
      (do
        (log/warn "ガント作業が見つかりません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})
      :else
      (let [v (validate-body sys user-id body)
            st (when (contains? body :execution_status)
                 (normalize-execution-status (:execution_status body)))]
        (cond
          (not (:ok v)) v
          (and st (not (:ok st))) st
          :else
          (let [status (if st (:execution-status st) (:execution_status row))]
            (db/update-gantt-row! (:ds sys) gid {:title-id (:title-id v)
                                                 :title (:title v)
                                                 :start-at (:start-at v)
                                                 :end-at (:end-at v)
                                                 :work-name (:work-name v)
                                                 :execution-status status})
            (db/replace-gantt-targets! (:ds sys) gid (:field-ids v))
            (log/info "ガント作業を直しました"
                      :user-id user-id :gantt-id gid :title-id (:title-id v)
                      :title (:title v) :fields (count (:field-ids v))
                      :execution-status status)
            {:ok true :row (present-row (:ds sys) (db/find-gantt-row (:ds sys) user-id gid))}))))))

(defn progress-percent [numerator denominator all-done?]
  (let [n (double (or numerator 0.0))
        d (double (or denominator 0.0))]
    (cond
      (<= d 0.0) nil
      all-done? 100
      :else
      (let [p (long (Math/floor (* 100.0 (/ n d))))]
        (if (>= p 100) 99 p)))))

(defn- compute-progress [sys user-id row]
  (let [gid (:id row)
        fids (db/list-gantt-targets (:ds sys) gid)
        wn (:work_name row)]
    (if (or (empty? fids) (str/blank? (str wn)))
      {:applicable false :percent nil :numerator_m2 0 :denominator_m2 0 :fields []}
      (let [summaries (mapv (fn [fid]
                              (let [field (db/find-field (:ds sys) user-id fid)
                                    paints (db/list-paints-for-field-name (:ds sys) fid wn)]
                                (paints/field-paint-summary field paints)))
                            fids)
            den (reduce + 0 (map :field_area_m2 summaries))
            num (reduce + 0 (map (fn [s] (min (:area_m2 s) (:field_area_m2 s))) summaries))
            all-done? (every? #(= "done" (:status %)) summaries)
            pct (progress-percent num den all-done?)]
        {:applicable true
         :percent pct
         :numerator_m2 (long num)
         :denominator_m2 (long den)
         :fields (mapv #(select-keys % [:id :status :area_m2 :field_area_m2]) summaries)}))))

(defn- snapshot-day!
  "未確定の日だけ確定％を書く。既存日はスキップ。戻り値は挿入した件数 0/1。"
  [sys user-id row day finalized-by]
  (let [gid (:id row)
        existing (db/find-gantt-progress-day (:ds sys) gid day)]
    (if existing
      0
      (let [p (compute-progress sys user-id row)]
        (db/insert-gantt-progress-day! (:ds sys)
                                       {:gantt-id gid
                                        :day day
                                        :percent (:percent p)
                                        :applicable (:applicable p)
                                        :finalized-by finalized-by})
        1))))

(defn- row-start-day [row]
  (let [s (str (:start_at row))]
    (when (>= (count s) 10)
      (subs s 0 10))))

(defn- backfill-days-for-row [sys user-id row through-day finalized-by]
  (let [start (row-start-day row)
        through (or (time/parse-work-date through-day) nil)
        from (when start (time/parse-work-date start))]
    (if (or (nil? from) (nil? through) (.isAfter from through))
      0
      (let [earliest (.minusDays through (long (dec backfill-max-days)))
            from' (if (.isBefore from earliest) earliest from)
            days (time/days-inclusive (time/format-work-date from') through-day)]
        (reduce + 0 (map #(snapshot-day! sys user-id row % finalized-by) days))))))

(defn finalize-missing-days-for-user
  "東京時間の前日まで、未確定の日次％を自動確定する。"
  [sys user-id]
  (let [yesterday (time/yesterday-work-date)
        rows (db/list-gantt-rows-all (:ds sys) user-id)
        n (reduce + 0 (map #(backfill-days-for-row sys user-id % yesterday "auto") rows))]
    (when (pos? n)
      (log/info "ガント日次進捗を自動確定しました" :user-id user-id :count n :through yesterday))
    {:ok true :finalized n}))

(defn finalize-day-for-user
  "指定日の未確定分を確定。戻り値は確定件数。"
  [sys user-id day finalized-by]
  (let [rows (db/list-gantt-rows-all (:ds sys) user-id)]
    (reduce + 0 (map #(snapshot-day! sys user-id % day finalized-by) rows))))

(defn admin-finalize-progress
  "管理者による日次確定。day 省略=東京の前日。email 省略=全利用者。"
  [sys {:keys [day email]}]
  (let [day' (let [raw (str/trim (str (or day "")))]
               (cond
                 (str/blank? raw) (time/yesterday-work-date)
                 (time/work-date-ok? raw) raw
                 :else nil))]
    (cond
      (nil? day')
      (do
        (log/warn "進捗確定の日付が不正です" :day day)
        {:ok false :code "time_invalid"})

      (not (str/blank? (str (or email ""))))
      (let [em (str/trim (str email))
            user (db/find-user-by-email (:ds sys) em)]
        (if (nil? user)
          (do
            (log/warn "進捗確定の利用者が見つかりません" :email em)
            {:ok false :code "user_not_found"})
          (let [n (finalize-day-for-user sys (:id user) day' "admin")]
            (log/info "利用者のガント日次進捗を確定しました"
                      :email em :day day' :count n)
            {:ok true :day day' :finalized n})))

      :else
      (let [users (db/list-active-users (:ds sys))
            n (reduce + 0 (map #(finalize-day-for-user sys (:id %) day' "admin") users))]
        (log/info "全利用者のガント日次進捗を確定しました" :day day' :count n)
        {:ok true :day day' :finalized n}))))

(defn soft-delete-row [sys user-id id]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)
        row (when gid (db/find-gantt-row (:ds sys) user-id gid))]
    (cond
      (not (:ok gate)) gate
      (or (nil? gid) (nil? row) (not (active-row? row)))
      (do
        (log/warn "消すガント行がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})
      :else
      (let [wt (db/count-gantt-work-times (:ds sys) gid)
            ci (db/count-gantt-checklist (:ds sys) gid)
            ci-total (:total ci)]
        (db/soft-delete-gantt-work-times-for-gantt! (:ds sys) gid)
        (db/soft-delete-gantt-checklist-items-for-gantt! (:ds sys) gid)
        (db/soft-delete-gantt-row! (:ds sys) gid)
        (log/info "ガント行をソフト削除しました"
                  :user-id user-id :gantt-id gid
                  :work-times wt :checklist-items ci-total)
        {:ok true}))))

(defn row-progress [sys user-id id]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)
        row (when gid (db/find-gantt-row (:ds sys) user-id gid))]
    (cond
      (not (:ok gate)) gate
      (or (nil? row) (not (active-row? row)))
      (do
        (log/warn "進捗を見るガント行がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})
      :else
      (let [p (compute-progress sys user-id row)]
        (if-not (:applicable p)
          (do
            (log/info "ガント行は％対象外です" :user-id user-id :gantt-id gid)
            (merge {:ok true} p))
          (do
            (log/info "ガント進捗を計算しました"
                      :user-id user-id :gantt-id gid :percent (:percent p)
                      :numerator-m2 (:numerator_m2 p) :denominator-m2 (:denominator_m2 p))
            (merge {:ok true} p)))))))

(defn list-progress-days [sys user-id id]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)
        row (when gid (db/find-gantt-row (:ds sys) user-id gid))]
    (cond
      (not (:ok gate)) gate
      (or (nil? row) (not (active-row? row)))
      (do
        (log/warn "振り返りのガント行がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})
      :else
      (let [days (mapv (fn [d]
                         {:day (:day d)
                          :percent (:percent d)
                          :applicable (= 1 (:applicable d))
                          :finalized_at (:finalized_at d)
                          :finalized_by (:finalized_by d)})
                       (db/list-gantt-progress-days (:ds sys) gid))]
        (log/info "ガント日次進捗を一覧しました" :user-id user-id :gantt-id gid :count (count days))
        {:ok true :days days}))))

(defn work-name-candidates [sys user-id]
  (log/info "作業名候補を一覧しました" :user-id user-id)
  {:ok true :work_names (db/list-work-name-candidates (:ds sys) user-id)})

(defn remove-field-targets!
  "圃場削除時に、どのガント行の対象からも外す。行自体は残す。"
  [sys field-id]
  (db/delete-gantt-targets-for-field! (:ds sys) field-id)
  (log/info "ガント対象から圃場を外しました" :field-id field-id)
  {:ok true})

(defn default-new-row []
  {:title "新しい作業"
   :start_at (time/gantt-default-start)
   :end_at (time/gantt-default-end)
   :work_name nil
   :execution_status "not_started"
   :field_ids []})

(defn- present-work-time [row]
  {:id (:id row)
   :gantt_id (:gantt_id row)
   :start_at (:start_at row)
   :end_at (:end_at row)})

(defn- present-checklist-item [row]
  {:id (:id row)
   :gantt_id (:gantt_id row)
   :label (:label row)
   :status (:status row)})

(defn- require-active-parent [sys user-id id]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)
        row (when gid (db/find-gantt-row (:ds sys) user-id gid))]
    (cond
      (not (:ok gate))
      gate

      (nil? gid)
      (do
        (log/warn "親ガント作業がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})

      (nil? row)
      (do
        (log/warn "親ガント作業がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})

      (not (active-row? row))
      (do
        (log/warn "親ガント作業がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})

      :else
      {:ok true :gantt-id gid :row row})))

(defn- work-time-missing? [tid existing parent-gid]
  (cond
    (nil? tid) true
    (nil? existing) true
    (some? (:deleted_at existing)) true
    (not= (:gantt_id existing) parent-gid) true
    :else false))

(defn- checklist-item-missing? [cid existing parent-gid]
  (cond
    (nil? cid) true
    (nil? existing) true
    (some? (:deleted_at existing)) true
    (not= (:gantt_id existing) parent-gid) true
    :else false))

(defn- normalize-checklist-label [s]
  (let [t (str/trim (str (or s "")))]
    (cond
      (str/blank? t)
      (do
        (log/warn "チェック項目名が空です")
        {:ok false :code "label_required"})
      (> (count t) 200)
      (do
        (log/warn "チェック項目名が長すぎます" :length (count t))
        {:ok false :code "label_too_long"})
      :else
      {:ok true :label t})))

(defn- normalize-checklist-status
  ([s] (normalize-checklist-status s nil))
  ([s default]
   (if (and (nil? s) (some? default))
     {:ok true :status default}
     (let [t (str/trim (str (or s "")))]
       (if (#{"pending" "done"} t)
         {:ok true :status t}
         (do
           (log/warn "チェック状態が不正です" :value s)
           {:ok false :code "checklist_status_invalid"}))))))

(defn list-work-times [sys user-id gantt-id]
  (let [parent (require-active-parent sys user-id gantt-id)]
    (if-not (:ok parent)
      parent
      (let [items (mapv present-work-time
                        (db/list-gantt-work-times (:ds sys) (:gantt-id parent)))]
        (log/info "作業時間を一覧しました"
                  :user-id user-id :gantt-id (:gantt-id parent) :count (count items))
        {:ok true :work_times items}))))

(defn create-work-time [sys user-id gantt-id body]
  (let [parent (require-active-parent sys user-id gantt-id)
        times (normalize-times (:start_at body) (:end_at body))]
    (cond
      (not (:ok parent)) parent
      (not (:ok times))
      (do
        (log/warn "作業時間の時刻が不正です"
                  :user-id user-id :gantt-id gantt-id :code (:code times))
        times)
      :else
      (let [row (db/insert-gantt-work-time! (:ds sys)
                                            {:gantt-id (:gantt-id parent)
                                             :start-at (:start-at times)
                                             :end-at (:end-at times)})]
        (log/info "作業時間を足しました"
                  :user-id user-id :gantt-id (:gantt-id parent) :work-time-id (:id row)
                  :start-at (:start-at times) :end-at (:end-at times))
        {:ok true :work_time (present-work-time row)}))))

(defn update-work-time [sys user-id gantt-id work-time-id body]
  (let [parent (require-active-parent sys user-id gantt-id)
        tid (geo/as-int work-time-id)
        times (normalize-times (:start_at body) (:end_at body))
        existing (when tid (db/find-gantt-work-time (:ds sys) tid))]
    (cond
      (not (:ok parent)) parent
      (not (:ok times))
      (do
        (log/warn "作業時間の更新時刻が不正です"
                  :user-id user-id :gantt-id gantt-id :work-time-id work-time-id
                  :code (:code times))
        times)
      (work-time-missing? tid existing (:gantt-id parent))
      (do
        (log/warn "作業時間が見つかりません"
                  :user-id user-id :gantt-id gantt-id :work-time-id work-time-id)
        {:ok false :code "work_time_not_found"})
      :else
      (let [row (db/update-gantt-work-time! (:ds sys) tid
                                            {:start-at (:start-at times)
                                             :end-at (:end-at times)})]
        (log/info "作業時間を直しました"
                  :user-id user-id :gantt-id (:gantt-id parent) :work-time-id tid
                  :start-at (:start-at times) :end-at (:end-at times))
        {:ok true :work_time (present-work-time row)}))))

(defn soft-delete-work-time [sys user-id gantt-id work-time-id]
  (let [parent (require-active-parent sys user-id gantt-id)
        tid (geo/as-int work-time-id)
        existing (when tid (db/find-gantt-work-time (:ds sys) tid))]
    (cond
      (not (:ok parent)) parent
      (work-time-missing? tid existing (:gantt-id parent))
      (do
        (log/warn "消す作業時間がありません"
                  :user-id user-id :gantt-id gantt-id :work-time-id work-time-id)
        {:ok false :code "work_time_not_found"})
      :else
      (do
        (db/soft-delete-gantt-work-time! (:ds sys) tid)
        (log/info "作業時間をソフト削除しました"
                  :user-id user-id :gantt-id (:gantt-id parent) :work-time-id tid)
        {:ok true}))))

(defn list-checklist-items [sys user-id gantt-id]
  (let [parent (require-active-parent sys user-id gantt-id)]
    (if-not (:ok parent)
      parent
      (let [items (mapv present-checklist-item
                        (db/list-gantt-checklist-items (:ds sys) (:gantt-id parent)))]
        (log/info "チェック項目を一覧しました"
                  :user-id user-id :gantt-id (:gantt-id parent) :count (count items))
        {:ok true :checklist_items items}))))

(defn create-checklist-item [sys user-id gantt-id body]
  (let [parent (require-active-parent sys user-id gantt-id)
        label (normalize-checklist-label (:label body))
        status (normalize-checklist-status (:status body) "pending")]
    (cond
      (not (:ok parent)) parent
      (not (:ok label)) label
      (not (:ok status)) status
      :else
      (let [row (db/insert-gantt-checklist-item! (:ds sys)
                                                 {:gantt-id (:gantt-id parent)
                                                  :label (:label label)
                                                  :status (:status status)})]
        (log/info "チェック項目を足しました"
                  :user-id user-id :gantt-id (:gantt-id parent)
                  :checklist-item-id (:id row) :label (:label label) :status (:status status))
        {:ok true :checklist_item (present-checklist-item row)}))))

(defn update-checklist-item [sys user-id gantt-id item-id body]
  (let [parent (require-active-parent sys user-id gantt-id)
        cid (geo/as-int item-id)
        existing (when cid (db/find-gantt-checklist-item (:ds sys) cid))]
    (cond
      (not (:ok parent)) parent
      (checklist-item-missing? cid existing (:gantt-id parent))
      (do
        (log/warn "チェック項目が見つかりません"
                  :user-id user-id :gantt-id gantt-id :checklist-item-id item-id)
        {:ok false :code "checklist_item_not_found"})
      :else
      (let [has-label? (contains? body :label)
            has-status? (contains? body :status)
            label (if has-label?
                    (normalize-checklist-label (:label body))
                    {:ok true :label (:label existing)})
            status (if has-status?
                     (normalize-checklist-status (:status body))
                     {:ok true :status (:status existing)})]
        (cond
          (not (:ok label)) label
          (not (:ok status)) status
          :else
          (let [row (db/update-gantt-checklist-item! (:ds sys) cid
                                                     {:label (:label label)
                                                      :status (:status status)})]
            (log/info "チェック項目を直しました"
                      :user-id user-id :gantt-id (:gantt-id parent)
                      :checklist-item-id cid :label (:label label) :status (:status status))
            {:ok true :checklist_item (present-checklist-item row)}))))))

(defn soft-delete-checklist-item [sys user-id gantt-id item-id]
  (let [parent (require-active-parent sys user-id gantt-id)
        cid (geo/as-int item-id)
        existing (when cid (db/find-gantt-checklist-item (:ds sys) cid))]
    (cond
      (not (:ok parent)) parent
      (checklist-item-missing? cid existing (:gantt-id parent))
      (do
        (log/warn "消すチェック項目がありません"
                  :user-id user-id :gantt-id gantt-id :checklist-item-id item-id)
        {:ok false :code "checklist_item_not_found"})
      :else
      (do
        (db/soft-delete-gantt-checklist-item! (:ds sys) cid)
        (log/info "チェック項目をソフト削除しました"
                  :user-id user-id :gantt-id (:gantt-id parent) :checklist-item-id cid)
        {:ok true}))))
