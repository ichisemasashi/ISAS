(ns isas.orders
  (:require [clojure.string :as str]
            [isas.crypto :as crypto]
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
      (log/warn "圃場が無いので指示を出せません" :user-id user-id)
      {:ok false :code "no_fields"})))

(defn- order-open? [row]
  (nil? (:closed_at row)))

(defn- order-status [row]
  (if (order-open? row) "open" "closed"))

(defn- normalize-body [s]
  (str/trim (str (or s ""))))

(defn- normalize-journal-body [s]
  (let [t (normalize-body s)]
    (cond
      (str/blank? t) {:ok false :code "journal_required"}
      (> (count t) 2000) {:ok false :code "journal_too_long"}
      :else {:ok true :body t})))

(defn- normalize-order-body [s]
  (let [t (normalize-body s)]
    (if (> (count t) 2000)
      {:ok false :code "body_too_long"}
      {:ok true :body t})))

(defn- active-user-by-email [ds email]
  (let [e (crypto/normalize-email email)]
    (when (crypto/email-ok? e)
      (when-let [u (db/find-user-by-email ds e)]
        (when (nil? (:revoked_at u))
          u)))))

(defn- normalize-recipients [sys issuer-id emails]
  (let [raw (cond
              (nil? emails) []
              (sequential? emails) emails
              :else [emails])
        norms (->> raw
                   (map crypto/normalize-email)
                   (remove str/blank?)
                   distinct
                   vec)]
    (cond
      (empty? norms)
      (do
        (log/warn "受け手がいません" :user-id issuer-id)
        {:ok false :code "recipients_required"})

      :else
      (loop [es norms ids []]
        (if (empty? es)
          {:ok true :user-ids ids :emails (mapv #(:email (db/find-user-by-id (:ds sys) %)) ids)}
          (let [e (first es)
                self? (= e (:email (db/find-user-by-id (:ds sys) issuer-id)))
                u (active-user-by-email (:ds sys) e)
                admin? (some? (db/find-admin-by-email (:ds sys) e))]
            (cond
              self?
              (do
                (log/warn "自分を受け手に含めようとしました" :user-id issuer-id :email e)
                {:ok false :code "recipient_self"})

              (or (nil? u) admin?)
              (do
                (log/warn "受け手にできないメールです" :user-id issuer-id :email e)
                {:ok false :code "recipient_not_user"})

              :else
              (recur (rest es) (conj ids (:id u))))))))))

(defn- normalize-field-ids [sys user-id ids]
  (let [raw (cond
              (nil? ids) []
              (sequential? ids) ids
              :else [ids])
        ints (mapv geo/as-int raw)]
    (cond
      (some nil? ints)
      (do
        (log/warn "対象圃場の ID が不正です" :user-id user-id)
        {:ok false :code "field_not_found"})

      (empty? (distinct ints))
      (do
        (log/warn "対象圃場がありません" :user-id user-id)
        {:ok false :code "fields_required"})

      :else
      (let [uniq (vec (distinct ints))]
        (if (every? #(db/find-field (:ds sys) user-id %) uniq)
          {:ok true :field-ids uniq}
          (do
            (log/warn "対象に他人の圃場または無い ID があります" :user-id user-id)
            {:ok false :code "field_not_found"}))))))

(defn- recipient-emails [ds order-id]
  (mapv (fn [uid] (:email (db/find-user-by-id ds uid)))
        (db/list-order-recipient-ids ds order-id)))

(defn- present-journals [ds order-id]
  (mapv (fn [j]
          {:author_email (:email (db/find-user-by-id ds (:user_id j)))
           :body (:body j)
           :created_at (:created_at j)})
        (db/list-journals-for-order ds order-id)))

(defn- field-visible-for-role? [ds role viewer-id order field-id]
  (cond
    (= role "issuer")
    (some? (db/find-field ds (:issuer_id order) field-id))

    (= role "recipient")
    (db/field-visible-to-viewer? ds viewer-id field-id)

    :else false))

(defn- present-fields [ds role viewer-id order]
  (let [fids (db/list-order-target-ids ds (:id order))]
    (if (= role "recipient")
      (let [visible (->> fids
                         (filter #(field-visible-for-role? ds role viewer-id order %))
                         (keep (fn [fid]
                                 (when-let [f (db/find-field-any ds fid)]
                                   {:id (:id f) :name (:name f) :visible true})))
                         vec)]
        ;; 切ったあと等、見えてよい枚が無ければ空にする
        visible)
      (mapv (fn [fid]
              (if-let [f (db/find-field ds (:issuer_id order) fid)]
                {:id (:id f) :name (:name f) :visible true}
                {:visible false}))
            fids))))

(defn- role-for [ds order user-id]
  (cond
    (= (long (:issuer_id order)) (long user-id)) "issuer"
    (db/order-recipient? ds (:id order) user-id) "recipient"
    :else nil))

(defn- present-order [ds order user-id]
  (let [role (role-for ds order user-id)
        issuer (db/find-user-by-id ds (:issuer_id order))]
    {:id (:id order)
     :role role
     :status (order-status order)
     :work_date (:work_date order)
     :start_time (:start_time order)
     :end_time (:end_time order)
     :work_name (:work_name order)
     :body (:body order)
     :issuer_email (:email issuer)
     :recipient_emails (recipient-emails ds (:id order))
     :fields (present-fields ds role user-id order)
     :journals (present-journals ds (:id order))}))

(defn- present-order-summary [ds order user-id]
  (let [full (present-order ds order user-id)
        mine? (some? (db/find-journal ds (:id order) user-id))]
    (-> (select-keys full [:id :role :status :work_date :start_time :end_time
                           :work_name :body :issuer_email :recipient_emails])
        (assoc :my_journal mine?))))

(defn- find-accessible-order [sys user-id id]
  (let [oid (geo/as-int id)
        order (when oid (db/find-order (:ds sys) oid))]
    (when (and order (role-for (:ds sys) order user-id))
      order)))

(defn field-in-open-order?
  "圃場が進行中の指示の対象か。fields から呼ぶ。"
  [sys field-id]
  (db/field-in-open-order? (:ds sys) field-id))

(defn any-field-in-open-order? [sys field-ids]
  (db/any-field-in-open-order? (:ds sys) field-ids))

(defn remove-field-targets!
  "圃場削除時に指示対象から外す（閉じた指示の対象も含む）。"
  [sys field-id]
  (db/delete-order-targets-for-field! (:ds sys) field-id)
  (log/info "指示対象から圃場を外しました" :field-id field-id)
  {:ok true})

(defn list-orders [sys user-id]
  (log/info "指示を一覧しました" :user-id user-id)
  {:ok true
   :sent (mapv #(present-order-summary (:ds sys) % user-id)
               (db/list-orders-issued (:ds sys) user-id))
   :received (mapv #(present-order-summary (:ds sys) % user-id)
                   (db/list-orders-received (:ds sys) user-id))})

(defn get-order [sys user-id id]
  (if-let [order (find-accessible-order sys user-id id)]
    (do
      (log/info "指示を取得しました" :user-id user-id :order-id (:id order))
      (merge {:ok true} (present-order (:ds sys) order user-id)))
    (do
      (log/warn "指示が見つかりません" :user-id user-id :order-id id)
      {:ok false :code "order_not_found"})))

(defn create-order [sys user-id body]
  (let [gate (require-fields sys user-id)]
    (if-not (:ok gate)
      gate
      (let [times (time/normalize-order-times (:work_date body) (:start_time body) (:end_time body))
            wn (paints/normalize-work-name (:work_name body))
            ob (normalize-order-body (:body body))
            recs (when (and (:ok times) (:ok wn) (:ok ob))
                   (normalize-recipients sys user-id (:recipient_emails body)))
            fields (when (and recs (:ok recs))
                     (normalize-field-ids sys user-id (:field_ids body)))]
        (cond
          (not (:ok times))
          (do (log/warn "指示の時刻が不正です" :user-id user-id :code (:code times)) times)

          (not (:ok wn))
          (do (log/warn "指示の作業名が使えません" :user-id user-id :code (:code wn)) wn)

          (not (:ok ob))
          (do (log/warn "依頼文が長すぎます" :user-id user-id) ob)

          (not (:ok recs)) recs
          (not (:ok fields)) fields

          :else
          (let [row (db/insert-order! (:ds sys) {:issuer-id user-id
                                                 :work-date (:work-date times)
                                                 :start-time (:start-time times)
                                                 :end-time (:end-time times)
                                                 :work-name (:work-name wn)
                                                 :body (:body ob)})]
            (db/set-order-recipients! (:ds sys) (:id row) (:user-ids recs))
            (db/set-order-targets! (:ds sys) (:id row) (:field-ids fields))
            (log/info "指示を出しました"
                      :user-id user-id :order-id (:id row)
                      :work-name (:work-name wn)
                      :recipients (count (:user-ids recs))
                      :fields (count (:field-ids fields)))
            (merge {:ok true} (present-order (:ds sys) (db/find-order (:ds sys) (:id row)) user-id))))))))

(defn update-order [sys user-id id body]
  (if-let [order (find-accessible-order sys user-id id)]
    (cond
      (not (order-open? order))
      (do
        (log/warn "閉じた指示は直せません" :user-id user-id :order-id (:id order))
        {:ok false :code "order_closed"})

      (not= (long (:issuer_id order)) (long user-id))
      (do
        (log/warn "出した人以外が指示を直そうとしました" :user-id user-id :order-id (:id order))
        {:ok false :code "order_not_issuer"})

      :else
      (let [times (time/normalize-order-times (:work_date body) (:start_time body) (:end_time body))
            ob (normalize-order-body (:body body))]
        (cond
          (not (:ok times))
          (do (log/warn "指示の時刻が不正です" :user-id user-id :code (:code times)) times)

          (not (:ok ob))
          (do (log/warn "依頼文が長すぎます" :user-id user-id) ob)

          :else
          (do
            (db/update-order-times-body! (:ds sys) (:id order)
                                         {:work-date (:work-date times)
                                          :start-time (:start-time times)
                                          :end-time (:end-time times)
                                          :body (:body ob)})
            (log/info "指示を直しました" :user-id user-id :order-id (:id order))
            (merge {:ok true} (present-order (:ds sys) (db/find-order (:ds sys) (:id order)) user-id))))))
    (do
      (log/warn "指示が見つかりません" :user-id user-id :order-id id)
      {:ok false :code "order_not_found"})))

(defn close-order [sys user-id id]
  (if-let [order (find-accessible-order sys user-id id)]
    (cond
      (not (order-open? order))
      (do
        (log/warn "すでに閉じた指示です" :user-id user-id :order-id (:id order))
        {:ok false :code "order_closed"})

      (not= (long (:issuer_id order)) (long user-id))
      (do
        (log/warn "出した人以外が指示を閉じようとしました" :user-id user-id :order-id (:id order))
        {:ok false :code "order_not_issuer"})

      :else
      (do
        (db/close-order! (:ds sys) (:id order))
        (log/info "指示を閉じました" :user-id user-id :order-id (:id order))
        (merge {:ok true} (present-order (:ds sys) (db/find-order (:ds sys) (:id order)) user-id))))
    (do
      (log/warn "指示が見つかりません" :user-id user-id :order-id id)
      {:ok false :code "order_not_found"})))

(defn post-journal [sys user-id id body]
  (if-let [order (find-accessible-order sys user-id id)]
    (cond
      (not (order-open? order))
      (do
        (log/warn "閉じた指示に日誌は書けません" :user-id user-id :order-id (:id order))
        {:ok false :code "order_closed"})

      (not (db/order-recipient? (:ds sys) (:id order) user-id))
      (do
        (log/warn "受け手以外が日誌を書こうとしました" :user-id user-id :order-id (:id order))
        {:ok false :code "order_not_recipient"})

      (some? (db/find-journal (:ds sys) (:id order) user-id))
      (do
        (log/warn "すでに日誌があります" :user-id user-id :order-id (:id order))
        {:ok false :code "journal_exists"})

      :else
      (let [jb (normalize-journal-body (:body body))]
        (if-not (:ok jb)
          (do
            (log/warn "日誌本文が使えません" :user-id user-id :code (:code jb))
            jb)
          (do
            (db/insert-journal! (:ds sys) {:order-id (:id order)
                                           :user-id user-id
                                           :body (:body jb)})
            (log/info "日誌を書きました" :user-id user-id :order-id (:id order))
            (merge {:ok true} (present-order (:ds sys) order user-id))))))
    (do
      (log/warn "指示が見つかりません" :user-id user-id :order-id id)
      {:ok false :code "order_not_found"})))

(defn order-map [sys user-id id]
  (if-let [order (find-accessible-order sys user-id id)]
    (let [role (role-for (:ds sys) order user-id)
          fids (db/list-order-target-ids (:ds sys) (:id order))
          wn (:work_name order)
          fields (->> fids
                      (filter #(field-visible-for-role? (:ds sys) role user-id order %))
                      (keep (fn [fid]
                              (when-let [f (db/find-field-any (:ds sys) fid)]
                                (let [summary (paints/field-paint-summary
                                               f
                                               (db/list-paints-for-field-name (:ds sys) fid wn))
                                      gj (geo/parse-json (:geojson f))]
                                  {:id (:id f)
                                   :name (:name f)
                                   :status (:status summary)
                                   :geojson gj}))))
                      vec)]
      (log/info "指示地図を返しました" :user-id user-id :order-id (:id order) :fields (count fields))
      {:ok true :work_name wn :fields fields})
    (do
      (log/warn "指示地図の指示がありません" :user-id user-id :order-id id)
      {:ok false :code "order_not_found"})))

(defn others-fields [sys user-id]
  (let [rows (db/list-visible-other-fields (:ds sys) user-id)]
    (log/info "他人の対象圃場を一覧しました" :user-id user-id :count (count rows))
    {:ok true
     :fields (mapv (fn [f]
                     {:id (:id f)
                      :name (:name f)
                      :owner_email (:owner_email f)
                      :geojson (geo/parse-json (:geojson f))})
                   rows)}))

(defn others-work-names [sys user-id]
  (let [names (db/list-related-other-work-names (:ds sys) user-id)]
    (log/info "他人の関係した作業名を一覧しました" :user-id user-id :count (count names))
    {:ok true :work_names names}))

(defn others-paints [sys user-id work-name]
  (let [nw (paints/normalize-work-name work-name)]
    (if-not (:ok nw)
      (do
        (log/warn "他人地図の作業名がありません" :user-id user-id :code (:code nw))
        nw)
      (let [related (set (db/list-related-other-work-names (:ds sys) user-id))
            wn (:work-name nw)]
        (if-not (contains? related wn)
          (do
            (log/warn "関係していない作業名です" :user-id user-id :work-name wn)
            {:ok false :code "work_name_unrelated"})
          (let [fields (db/list-visible-other-fields (:ds sys) user-id)]
            (log/info "他人の進捗色を返しました" :user-id user-id :work-name wn :fields (count fields))
            {:ok true
             :work_name wn
             :fields (mapv (fn [f]
                             (let [summary (paints/field-paint-summary
                                            f
                                            (db/list-paints-for-field-name (:ds sys) (:id f) wn))]
                               {:id (:id f)
                                :name (:name f)
                                :owner_email (:owner_email f)
                                :status (:status summary)}))
                           fields)}))))))

(defn- resolve-relation-users [sys email-a email-b]
  (let [ea (crypto/normalize-email email-a)
        eb (crypto/normalize-email email-b)]
    (cond
      (or (not (crypto/email-ok? ea)) (not (crypto/email-ok? eb)) (= ea eb))
      {:ok false :code "relation_not_found"}

      (or (db/find-admin-by-email (:ds sys) ea) (db/find-admin-by-email (:ds sys) eb))
      {:ok false :code "relation_not_found"}

      :else
      (let [ua (active-user-by-email (:ds sys) ea)
            ub (active-user-by-email (:ds sys) eb)]
        (if (and ua ub)
          {:ok true :user-a ua :user-b ub}
          {:ok false :code "relation_not_found"})))))

(defn- any-visible-between? [ds user-a-id user-b-id]
  (or (some #(db/field-visible-to-viewer? ds user-b-id (:id %))
            (db/list-fields ds user-a-id))
      (some #(db/field-visible-to-viewer? ds user-a-id (:id %))
            (db/list-fields ds user-b-id))))

(defn cut-relation [sys email-a email-b]
  (let [users (resolve-relation-users sys email-a email-b)]
    (if-not (:ok users)
      (do
        (log/warn "関係を切る相手が見つかりません" :code (:code users))
        users)
      (let [a (get-in users [:user-a :id])
            b (get-in users [:user-b :id])]
        (cond
          (db/open-order-between? (:ds sys) a b)
          (do
            (log/warn "進行中の指示があるので切れません" :user-a a :user-b b)
            {:ok false :code "relation_busy"})

          (not (any-visible-between? (:ds sys) a b))
          (do
            (log/warn "見えている相手の圃場が無いので切りません" :user-a a :user-b b)
            {:ok false :code "relation_idle"})

          :else
          (do
            (db/upsert-relation-cut! (:ds sys) a b)
            (log/info "関係を切りました" :user-a a :user-b b)
            {:ok true}))))))

(defn default-new-order []
  {:work_date (time/today-work-date)
   :start_time (time/order-default-start)
   :end_time (time/order-default-end)
   :work_name ""
   :body ""
   :recipient_emails []
   :field_ids []})
