(ns isas.gantt
  (:require [clojure.string :as str]
            [isas.db :as db]
            [isas.geo :as geo]
            [isas.log :as log]
            [isas.paints :as paints]
            [isas.time :as time]))

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
      (str/blank? t) {:ok false :code "title_required"}
      (> (count t) 200) {:ok false :code "title_too_long"}
      :else {:ok true :title t})))

(defn normalize-times [start end]
  (cond
    (not (and (time/local-minute-ok? start) (time/local-minute-ok? end)))
    {:ok false :code "time_invalid"}

    (not (.isBefore (time/parse-local-minute start) (time/parse-local-minute end)))
    {:ok false :code "time_order"}

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
      {:ok false :code "field_not_found"}
      (let [uniq (vec (distinct ints))]
        (if (every? #(db/find-field (:ds sys) user-id %) uniq)
          {:ok true :field-ids uniq}
          {:ok false :code "field_not_found"})))))

(defn- present-row [ds row]
  {:id (:id row)
   :title (:title row)
   :start_at (:start_at row)
   :end_at (:end_at row)
   :work_name (:work_name row)
   :field_ids (db/list-gantt-targets ds (:id row))})

(defn list-rows [sys user-id]
  (let [gate (require-fields sys user-id)]
    (if-not (:ok gate)
      gate
      (do
        (log/info "ガント行を一覧しました" :user-id user-id)
        {:ok true
         :rows (mapv #(present-row (:ds sys) %) (db/list-gantt-rows (:ds sys) user-id))}))))

(defn- validate-body [sys user-id body]
  (let [title (normalize-title (:title body))
        times (normalize-times (:start_at body) (:end_at body))
        wn (normalize-optional-work-name (:work_name body))
        fields (normalize-field-ids sys user-id (:field_ids body))]
    (cond
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
                                                     :title (:title v)
                                                     :start-at (:start-at v)
                                                     :end-at (:end-at v)
                                                     :work-name (:work-name v)})]
            (db/replace-gantt-targets! (:ds sys) (:id row) (:field-ids v))
            (log/info "ガント行を足しました"
                      :user-id user-id :gantt-id (:id row) :title (:title v)
                      :fields (count (:field-ids v)))
            {:ok true :row (present-row (:ds sys) row)}))))))

(defn update-row [sys user-id id body]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)]
    (cond
      (not (:ok gate)) gate
      (nil? gid) {:ok false :code "gantt_not_found"}
      (nil? (db/find-gantt-row (:ds sys) user-id gid))
      (do
        (log/warn "ガント行が見つかりません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})
      :else
      (let [v (validate-body sys user-id body)]
        (if-not (:ok v)
          v
          (do
            (db/update-gantt-row! (:ds sys) gid {:title (:title v)
                                                 :start-at (:start-at v)
                                                 :end-at (:end-at v)
                                                 :work-name (:work-name v)})
            (db/replace-gantt-targets! (:ds sys) gid (:field-ids v))
            (log/info "ガント行を直しました"
                      :user-id user-id :gantt-id gid :title (:title v)
                      :fields (count (:field-ids v)))
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

(defn row-progress [sys user-id id]
  (let [gate (require-fields sys user-id)
        gid (geo/as-int id)
        row (when gid (db/find-gantt-row (:ds sys) user-id gid))]
    (cond
      (not (:ok gate)) gate
      (nil? row)
      (do
        (log/warn "進捗を見るガント行がありません" :user-id user-id :gantt-id id)
        {:ok false :code "gantt_not_found"})
      :else
      (let [fids (db/list-gantt-targets (:ds sys) gid)
            wn (:work_name row)]
        (if (or (empty? fids) (str/blank? (str wn)))
          (do
            (log/info "ガント行は％対象外です" :user-id user-id :gantt-id gid)
            {:ok true :applicable false :percent nil :numerator_m2 0 :denominator_m2 0 :fields []})
          (let [summaries (mapv (fn [fid]
                                  (let [field (db/find-field (:ds sys) user-id fid)
                                        paints (db/list-paints-for-field-name (:ds sys) fid wn)]
                                    (paints/field-paint-summary field paints)))
                                fids)
                den (reduce + 0 (map :field_area_m2 summaries))
                num (reduce + 0 (map (fn [s] (min (:area_m2 s) (:field_area_m2 s))) summaries))
                all-done? (every? #(= "done" (:status %)) summaries)
                pct (progress-percent num den all-done?)]
            (log/info "ガント進捗を計算しました"
                      :user-id user-id :gantt-id gid :percent pct
                      :numerator-m2 num :denominator-m2 den)
            {:ok true
             :applicable true
             :percent pct
             :numerator_m2 (long num)
             :denominator_m2 (long den)
             :fields (mapv #(select-keys % [:id :status :area_m2 :field_area_m2]) summaries)}))))))

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
  {:title "新しい予定"
   :start_at (time/gantt-default-start)
   :end_at (time/gantt-default-end)
   :work_name nil
   :field_ids []})
