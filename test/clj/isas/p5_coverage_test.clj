(ns isas.p5-coverage-test
  "工程5の残カバレッジを埋める試験。"
  (:require [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.http :as http]
            [isas.orders :as orders]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock]))

(deftest orders-edge-branches
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "cov-a@example.com")
            sid (tu/user-sid app "cov-a@example.com" pw)
            pw2 (tu/invite-pw app asid "cov-b@example.com")
            sid2 (tu/user-sid app "cov-b@example.com" pw2)
            uid (:id (db/find-user-by-email (:ds sys) "cov-a@example.com"))
            uid2 (:id (db/find-user-by-email (:ds sys) "cov-b@example.com"))
            f (:field (tu/parse (tu/post-json app "/api/user/fields"
                                              {:name "北" :geojson tu/square} "user" sid)))
            fid (:id f)]
        (testing "単一値の受け手・対象"
          (let [r (orders/create-order sys uid {:work_date "2026-09-12"
                                                :start_time "08:00" :end_time "17:00"
                                                :work_name "単"
                                                :body ""
                                                :recipient_emails "cov-b@example.com"
                                                :field_ids fid})]
            (is (true? (:ok r)))
            (orders/close-order sys uid (:id r))))
        (testing "不正 field id"
          (is (= "field_not_found"
                 (:code (orders/create-order sys uid {:work_date "2026-09-12"
                                                      :start_time "08:00" :end_time "17:00"
                                                      :work_name "x" :body ""
                                                      :recipient_emails ["cov-b@example.com"]
                                                      :field_ids ["nope"]})))))
        (testing "更新・閉じ・日誌・地図の not_found / 時刻 / 本文"
          (is (= "order_not_found" (:code (orders/update-order sys uid 99999
                                                              {:work_date "2026-09-12"
                                                               :start_time "08:00" :end_time "17:00"
                                                               :body ""}))))
          (is (= "order_not_found" (:code (orders/close-order sys uid 99999))))
          (is (= "order_not_found" (:code (orders/post-journal sys uid 99999 {:body "x"}))))
          (is (= "order_not_found" (:code (orders/order-map sys uid 99999))))
          (let [c (orders/create-order sys uid {:work_date "2026-09-12"
                                                :start_time "08:00" :end_time "17:00"
                                                :work_name "枝" :body ""
                                                :recipient_emails ["cov-b@example.com"]
                                                :field_ids [fid]})]
            (is (= "time_invalid"
                   (:code (orders/update-order sys uid (:id c)
                                               {:work_date "bad" :start_time "08:00"
                                                :end_time "17:00" :body ""}))))
            (is (= "body_too_long"
                   (:code (orders/update-order sys uid (:id c)
                                               {:work_date "2026-09-12" :start_time "08:00"
                                                :end_time "17:00"
                                                :body (apply str (repeat 2001 "あ"))}))))
            (is (true? (:ok (orders/order-map sys uid (:id c)))))
            ;; 対象行を残したまま圃場だけ消して visible:false を出す
            (jdbc/execute! (:ds sys) ["PRAGMA foreign_keys = OFF"])
            (db/delete-field! (:ds sys) fid)
            (jdbc/execute! (:ds sys) ["PRAGMA foreign_keys = ON"])
            (let [got (orders/get-order sys uid (:id c))]
              (is (some #(false? (:visible %)) (:fields got))))
            (orders/close-order sys uid (:id c))))
        (testing "他人 paints の作業名必須と切断の無い利用者"
          (let [f2 (:field (tu/parse (tu/post-json app "/api/user/fields"
                                                   {:name "東" :geojson tu/square-east} "user" sid)))
                fid2 (:id f2)]
            (is (= "work_name_required" (:code (orders/others-paints sys uid2 ""))))
            (is (= "relation_not_found"
                   (:code (orders/cut-relation sys "cov-a@example.com" "missing@example.com"))))
            (is (false? (#'orders/field-visible-for-role?
                         (:ds sys) "other" uid {:issuer_id uid} fid2)))
            ;; 発行者でも受け手でもない → role-for の :else
            (let [c2 (orders/create-order sys uid {:work_date "2026-09-12"
                                                   :start_time "08:00" :end_time "17:00"
                                                   :work_name "役割" :body ""
                                                   :recipient_emails ["cov-b@example.com"]
                                                   :field_ids [fid2]})
                  order (db/find-order (:ds sys) (:id c2))
                  pw3 (tu/invite-pw app asid "cov-c@example.com")
                  _ (tu/user-sid app "cov-c@example.com" pw3)
                  uid3 (:id (db/find-user-by-email (:ds sys) "cov-c@example.com"))]
              (is (nil? (#'orders/role-for (:ds sys) order uid3)))
              (is (= "order_not_found" (:code (orders/get-order sys uid3 (:id c2)))))
              (orders/close-order sys uid (:id c2)))
            (is (= "time_invalid" (:code (time/normalize-order-times nil nil nil))))
            (is (= "time_invalid" (:code (time/normalize-order-times "2026-09-12" "bad" "17:00"))))
            (is (= "time_invalid" (:code (time/normalize-order-times "2026-09-12" "08:00" "bad"))))
            (is (= "recipients_required"
                   (:code (orders/create-order sys uid {:work_date "2026-09-12"
                                                        :start_time "08:00" :end_time "17:00"
                                                        :work_name "x" :body nil
                                                        :recipient_emails nil
                                                        :field_ids nil}))))
            (is (= "fields_required"
                   (:code (orders/create-order sys uid {:work_date "2026-09-12"
                                                        :start_time "08:00" :end_time "17:00"
                                                        :work_name "x" :body ""
                                                        :recipient_emails ["cov-b@example.com"]
                                                        :field_ids nil}))))
            (is (= "relation_not_found" (:code (orders/cut-relation sys "bad" "also-bad"))))
            (is (= "relation_not_found"
                   (:code (orders/cut-relation sys "cov-a@example.com" "cov-a@example.com"))))
            (is (= "relation_not_found"
                   (:code (orders/cut-relation sys "cov-a@example.com" "not-an-email"))))
            (is (= "relation_not_found"
                   (:code (orders/cut-relation sys "admin@example.com" "cov-b@example.com"))))
            (is (= "relation_not_found"
                   (:code (orders/cut-relation sys "missing@example.com" "cov-a@example.com"))))
            (is (false? (db/field-visible-to-viewer? (:ds sys) uid fid2)))
            (is (false? (db/field-visible-to-viewer? (:ds sys) uid2 99999)))
            ;; 名前ヘルパーの or / when-not を踏む
            (is (nil? (#'fields/temp-embedded-base "仮-1（）")))
            (is (nil? (#'fields/temp-embedded-base "仮-1（  ）")))
            (is (nil? (#'fields/name-stem-for-split nil)))
            (is (nil? (#'fields/name-stem-for-split "仮-1")))
            (is (= "本" (#'fields/name-stem-for-split "本")))
            (is (= "元" (#'fields/name-stem-for-split "仮-2（元）")))
            (let [rows [{:id 1 :name "仮-1"} {:id 2 :name "実名"}]
                  keep (first rows)]
              (is (= "実名" (#'fields/preferred-merge-name keep rows))))
            (let [rows [{:id 1 :name "本命"} {:id 2 :name "仮-1"}]
                  keep (first rows)]
              (is (= "本命" (#'fields/preferred-merge-name keep rows))))
            (let [rows [{:id 1 :name "仮-1（埋）"} {:id 2 :name "仮-2"}]
                  keep (first rows)]
              (is (= "埋" (#'fields/preferred-merge-name keep rows))))
            (let [rows [{:id 1 :name "仮-1"} {:id 2 :name "仮-2（他）"}]
                  keep (first rows)]
              (is (= "他" (#'fields/preferred-merge-name keep rows))))
            (let [rows [{:id 1 :name "仮-9"} {:id 2 :name "仮-8"}]
                  keep (first rows)]
              (is (= "仮-9" (#'fields/preferred-merge-name keep rows))))
            (with-redefs [orders/list-orders (fn [_ _] {:ok false :code "unauthorized"})
                          orders/order-map (fn [_ _ _] {:ok false :code "order_not_found"})
                          orders/others-fields (fn [_ _] {:ok false :code "unauthorized"})
                          orders/others-work-names (fn [_ _] {:ok false :code "unauthorized"})]
              (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/user/orders" "user" sid)))))
              (is (= "order_not_found"
                     (:code (tu/parse (tu/get-path app "/api/user/orders/1/map" "user" sid)))))
              (is (= "unauthorized"
                     (:code (tu/parse (tu/get-path app "/api/user/others/fields" "user" sid)))))
              (is (= "unauthorized"
                     (:code (tu/parse (tu/get-path app "/api/user/others/work-names" "user" sid))))))))
        (testing "HTTP catch と unauthorized"
          (let [bad (fn [method path]
                      (app (tu/as-user (-> (mock/request method path)
                                           (mock/content-type "application/json")
                                           (mock/body "{"))
                                       "user" sid)))]
            (is (= "time_invalid" (:code (tu/parse (bad :post "/api/user/orders")))))
            (is (= "time_invalid" (:code (tu/parse (bad :put "/api/user/orders/1")))))
            (is (= "journal_required" (:code (tu/parse (bad :post "/api/user/orders/1/journal")))))
            (is (= "relation_not_found"
                   (:code (tu/parse (app (tu/as-user (-> (mock/request :post "/api/admin/relations/cut")
                                                         (mock/content-type "application/json")
                                                         (mock/body "{"))
                                                     "admin" asid))))))
            (is (= 401 (:status (tu/post-json app "/api/admin/relations/cut"
                                              {:email_a "a" :email_b "b"}))))))))))

(deftest ui-phase5-coverage
  (binding [time/*now-fn* (fn [] (java.time.Instant/parse "2026-09-12T00:00:00Z"))]
    (doseq [code ["order_not_found" "order_closed" "order_not_issuer" "order_not_recipient"
                  "journal_exists" "journal_required" "journal_too_long" "body_too_long"
                  "recipients_required" "recipient_self" "recipient_not_user" "fields_required"
                  "field_in_open_order" "work_name_unrelated" "relation_busy"
                  "relation_not_found" "relation_idle"]]
      (is (string? (ui/code-message code))))
    (is (re-find #"閉じた" (ui/render {:page :order :kind "user" :session {:email "a"}
                                       :order {:id 1 :role "issuer" :status "closed"
                                               :work_date "2026-09-12" :start_time "08:00"
                                               :end_time "17:00" :work_name "x" :body ""
                                               :recipient_emails [] :fields [] :journals []}})))
    (is (re-find #"その指示はありません"
                 (ui/render {:page :order :kind "user" :session {:email "a"} :order nil})))
    (is (re-find #"指示を出す"
                 (ui/render {:page :orders-new :kind "user" :session {:email "a"}
                             :fields [{:id 1 :name "北"}]})))
    ;; 一覧に「指示を出す」リンク（非狭幅かつ圃場あり）
    (is (re-find #"/orders/new"
                 (ui/render {:page :orders :kind "user" :session {:email "a"} :narrow? false
                             :fields [{:id 1 :name "北"}]
                             :orders-sent [] :orders-received []})))
    (is (re-find #"日付"
                 (ui/render {:page :order :kind "user" :session {:email "a"}
                             :order {:id 1 :role "issuer" :status "open"
                                     :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                     :work_name "x" :body "" :recipient_emails nil
                                     :fields [{:id 1 :name "北" :visible true}]
                                     :journals nil}})))
    (is (re-find #"他人"
                 (ui/render {:page :others :kind "user" :session {:email "a"}})))
    (let [h (ui/render {:page :orders-new :kind "user" :session {:email "a"}
                        :fields [{:id 1 :name "北"}]
                        :work-names ["田植え"]
                        :form {:work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                               :work_name "" :body "" :recipient_emails "" :field_ids ["1"]}})]
      (is (re-find #"checked" h)))
    (let [h (ui/render {:page :order :kind "user" :session {:email "b@example.com"}
                        :order {:id 1 :role "recipient" :status "open"
                                :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                :work_name "田植え" :body "" :recipient_emails ["b@example.com"]
                                :fields [{:id 1 :name "北" :visible true}] :journals []}
                        :order-map {:work_name "田植え"
                                    :fields [{:id 1 :name "北" :status "done" :geojson {}}]}})]
      (is (re-find #"日誌を書く" h))
      (is (re-find #"data-order-mode" h)))
    ;; 受け手だが閉じ済み → can-journal? の open? が false
    (is (not (re-find #"日誌を書く"
                      (ui/render {:page :order :kind "user" :session {:email "b@example.com"}
                                  :order {:id 1 :role "recipient" :status "closed"
                                          :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                          :work_name "田植え" :body "" :recipient_emails ["b@example.com"]
                                          :fields [] :journals nil}}))))
    ;; journals が nil の受け手（or の [] 枝と can-journal?）
    (is (re-find #"日誌を書く"
                 (ui/render {:page :order :kind "user" :session {:email "b@example.com"}
                             :order {:id 1 :role "recipient" :status "open"
                                     :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                     :work_name "田植え" :body "" :recipient_emails ["b@example.com"]
                                     :fields [{:id 1 :name "北" :visible true}]
                                     :journals nil}})))
    (let [h (ui/render {:page :others :kind "user" :session {:email "a"}
                        :others-fields [{:id 1 :name "北" :owner_email "o@x.com"}]
                        :others-work-names ["田植え"]
                        :others-paint-data {:work_name "田植え"
                                            :fields [{:id 1 :status "partial"}]}
                        :form {:work_name "田植え"}})]
      (is (re-find #"一部" h)))
    (let [h (ui/render {:page :order :kind "user" :session {:email "b@example.com"}
                        :order {:id 1 :role "recipient" :status "open"
                                :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                :work_name "田植え" :body "" :recipient_emails ["b@example.com"]
                                :fields [{:id 1 :name "北" :visible true}]
                                :journals [{:author_email "b@example.com" :body "済" :created_at "t"}]}
                        :order-map nil})]
      (is (re-find #"済" h))
      (is (not (re-find #"data-act=\"post-journal\"" h))))
    ;; 他人の日誌だけある受け手（some 述語が false の枝）
    (is (re-find #"日誌を書く"
                 (ui/render {:page :order :kind "user" :session {:email "b@example.com"}
                             :order {:id 1 :role "recipient" :status "open"
                                     :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                     :work_name "田植え" :body "" :recipient_emails ["b@example.com"]
                                     :fields []
                                     :journals [{:author_email "other@example.com" :body "他"
                                                 :created_at "t"}]}})))
    (testing "handlers"
      (let [s0 (assoc (ui/init-state) :session {:email "a"} :kind "user")]
        (is (= [] (#'ui/parse-field-ids-form {})))
        (is (= [] (#'ui/parse-field-ids-form {:field_ids nil})))
        (is (= [] (#'ui/parse-field-ids-form {:field_ids ""})))
        (is (= ["9"] (#'ui/parse-field-ids-form {:field_ids "9"})))
        (is (= ["a@x.com"] (#'ui/parse-recipient-emails "a@x.com")))
        (is (= :html (ffirst (:fx (ui/handle (assoc s0 :narrow? true)
                                             [:path {:path "/orders/new" :search ""}])))))
        (is (= :html (ffirst (:fx (ui/handle (assoc s0 :narrow? true :page :orders-new :session {:email "a"})
                                             [:session-loaded {:ok true :email "a"}])))))
        (is (= :html (ffirst (:fx (ui/handle (assoc s0 :narrow? true :page :others :session {:email "a"})
                                             [:session-loaded {:ok true :email "a"}])))))
        (is (= :api (ffirst (:fx (ui/handle s0 [:path {:path "/orders/new" :search ""}])))))
        (is (= :api (ffirst (:fx (ui/handle s0 [:path {:path "/orders/9" :search ""}])))))
        (is (= :api (ffirst (:fx (ui/handle s0 [:path {:path "/others" :search ""}])))))
        (let [r (ui/handle (assoc s0 :page :orders-new)
                           [:fields-loaded {:fields [{:id 1 :name "北"}]}])]
          (is (= "2026-09-12" (get-in r [:state :form :work_date])))
          (is (= :api (ffirst (:fx r)))))
        (is (map? (:state (ui/handle (assoc s0 :page :orders)
                                     [:orders-loaded {:ok true :sent nil :received nil}]))))
        (is (map? (:state (ui/handle (assoc s0 :page :orders)
                                     [:orders-loaded {:ok true
                                                      :sent [{:id 1 :work_date "2026-09-12"
                                                              :work_name "a" :status "open"}]
                                                      :received [{:id 2 :work_date "2026-09-12"
                                                                  :work_name "b" :status "closed"}]}]))))
        (is (map? (:state (ui/handle (assoc s0 :page :others)
                                     [:others-fields-loaded {:ok true :fields nil}]))))
        (is (map? (:state (ui/handle s0 [:others-work-names-loaded {:work_names nil}]))))
        (is (= [] (#'ui/parse-recipient-emails nil)))
        (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user"
                                  :page :orders-new :form nil :fields [{:id 1}])
                           [:submit {:act "create-order"
                                     :form {:work_date "2026-09-12" :start_time "08:00"
                                            :end_time "17:00" :work_name "x" :body ""
                                            :recipient_emails "b@x.com" :field_ids "1"}}])]
          (is (= :api (ffirst (:fx r)))))
        (let [r (ui/handle (assoc s0 :page :order :order-id "8")
                           [:submit {:act "update-order"
                                     :form {:work_date "2026-09-12" :start_time "08:00"
                                            :end_time "17:00" :body "x"}}])]
          (is (= :api (ffirst (:fx r)))))
        (let [r (ui/handle (assoc s0 :page :order :order-id "8")
                           [:submit {:act "close-order" :form {}}])]
          (is (= :api (ffirst (:fx r)))))
        (let [r (ui/handle (assoc s0 :page :order :order-id "8")
                           [:submit {:act "post-journal" :form {:body "j"}}])]
          (is (= :api (ffirst (:fx r)))))
        ;; 未ログインは login へ
        (is (= :nav (ffirst (:fx (ui/handle (assoc s0 :page :orders-new :session nil)
                                            [:session-loaded {:ok false}])))))
        (is (= :nav (ffirst (:fx (ui/handle (assoc s0 :page :others :session nil)
                                            [:session-loaded {:ok false}])))))
        (is (= :api (ffirst (:fx (ui/handle (assoc s0 :page :orders-new :session {:email "a"} :narrow? false)
                                            [:session-loaded {:ok true :email "a"}])))))
        (is (= :api (ffirst (:fx (ui/handle (assoc s0 :page :others :session {:email "a"} :narrow? false)
                                            [:session-loaded {:ok true :email "a"}])))))
        (let [r (ui/handle (assoc s0 :page :orders-new)
                           [:fields-loaded {:fields [{:id 1 :name "北"}]}])]
          (is (= "2026-09-12" (get-in r [:state :form :work_date])))
          (is (= :api (ffirst (:fx r)))))
        ;; form が nil のとき defaults と {} を merge
        (let [r (ui/handle (assoc s0 :page :orders-new :form nil)
                           [:fields-loaded {:fields []}])]
          (is (= "08:00" (get-in r [:state :form :start_time]))))
        ;; existing form merge when fields-loaded on orders-new
        (let [r (ui/handle (assoc s0 :page :orders-new :form {:work_name "残す"})
                           [:fields-loaded {:fields []}])]
          (is (= "残す" (get-in r [:state :form :work_name]))))
        (is (re-find #"error"
                     (pr-str (ui/handle (assoc s0 :page :orders)
                                        [:orders-loaded {:ok false :code "unauthorized"}]))))
        (let [r (ui/handle (assoc s0 :page :order :order-id "1")
                           [:order-loaded {:ok true :id 1 :role "issuer" :status "open"
                                           :work_date "2026-09-12" :start_time "08:00"
                                           :end_time "17:00" :work_name "x" :body ""
                                           :recipient_emails [] :fields [] :journals []}])]
          (is (= :api (ffirst (:fx r)))))
        (is (map? (:state (ui/handle s0 [:order-loaded {:ok false :code "order_not_found"}]))))
        (is (map? (:state (ui/handle s0 [:order-map-loaded {:ok true :fields []}]))))
        (is (map? (:state (ui/handle s0 [:order-map-loaded {:ok false}]))))
        (is (= :nav (ffirst (:fx (ui/handle s0 [:order-save-result {:ok true :id 3}])))))
        (doseq [code ["time_invalid" "time_order" "recipient_self"]]
          (is (true? (get-in (ui/handle s0 [:order-save-result {:ok false :code code}])
                             [:state :flash :error?]))))
        (is (= :api (ffirst (:fx (ui/handle s0 [:journal-save-result {:ok true :id 1}])))))
        (is (true? (get-in (ui/handle s0 [:journal-save-result {:ok false :code "journal_exists"}])
                           [:state :flash :error?])))
        (let [r (ui/handle (assoc s0 :page :others)
                           [:others-fields-loaded {:ok true :fields [{:id 1}]}])]
          (is (= :api (ffirst (:fx r)))))
        (is (map? (:state (ui/handle s0 [:others-fields-loaded {:ok false :code "unauthorized"}]))))
        (is (map? (:state (ui/handle s0 [:others-work-names-loaded {:work_names ["a"]}]))))
        (is (map? (:state (ui/handle s0 [:others-paints-loaded {:ok true :fields []}]))))
        (is (true? (get-in (ui/handle s0 [:others-paints-loaded {:ok false :code "work_name_unrelated"}])
                           [:state :flash :error?])))
        (is (true? (get-in (ui/handle s0 [:relation-cut-result {:ok false :code "relation_busy"}])
                           [:state :flash :error?])))
        (let [r (ui/handle (assoc s0 :page :order :order {:id 5} :order-id "5")
                           [:submit {:act "update-order"
                                     :form {:id "5" :work_date "2026-09-12"
                                            :start_time "08:00" :end_time "17:00" :body "x"}}])]
          (is (= :api (ffirst (:fx r)))))
        (is (re-find #"その指示"
                     (get-in (ui/handle s0 [:submit {:act "update-order" :form {}}])
                             [:state :flash :text])))
        (is (re-find #"その指示"
                     (get-in (ui/handle s0 [:submit {:act "close-order" :form {}}])
                             [:state :flash :text])))
        (is (re-find #"その指示"
                     (get-in (ui/handle s0 [:submit {:act "post-journal" :form {}}])
                             [:state :flash :text])))
        (let [r (ui/handle (assoc s0 :page :order :order {:id 5})
                           [:submit {:act "post-journal" :form {:id "5" :body "日誌"}}])]
          (is (= :api (ffirst (:fx r)))))
        (let [r (ui/handle (assoc s0 :page :others)
                           [:submit {:act "select-others-work-name" :form {:work_name "田植え"}}])]
          (is (= :api (ffirst (:fx r)))))
        (is (re-find #"作業名"
                     (get-in (ui/handle (assoc s0 :page :others)
                                        [:submit {:act "select-others-work-name" :form {:work_name ""}}])
                             [:state :flash :text])))
        (is (= :html (ffirst (:fx (ui/handle (assoc s0 :kind "admin" :session {:email "admin@example.com"} :page :relations)
                                             [:path {:path "/admin/relations" :search ""}])))))
        (is (map? (:state (ui/handle (assoc s0 :kind "admin" :page :relations)
                                     [:session-loaded {:ok true :email "admin@example.com"}]))))))))
