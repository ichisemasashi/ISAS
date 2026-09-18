(ns isas.b5-test
  "基本試験仕様書_工程5 の項番に対応する自動試験。経路名は見ない。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.orders :as orders]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(deftest b5-blocks
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "b5a@example.com")
            usid (tu/user-sid app "b5a@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "b5a@example.com"))
            pw2 (tu/invite-pw app asid "b5b@example.com")
            usid2 (tu/user-sid app "b5b@example.com" pw2)
            uid2 (:id (db/find-user-by-email (:ds sys) "b5b@example.com"))
            f (fields/create-field sys uid {:name "北" :geojson tu/square})
            fid (get-in f [:field :id])
            wide (html {:page :orders
                        :orders-sent [{:id 1 :work_date "2026-09-12" :work_name "田植え" :status "open"}]
                        :orders-received []})
            detail (html {:page :order
                          :order {:id 1 :role "recipient" :status "open"
                                  :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                  :work_name "田植え" :body "頼む"
                                  :recipient_emails ["b5b@example.com"]
                                  :fields [{:id fid :name "北" :visible true}]
                                  :journals []}
                          :order-map {:work_name "田植え"
                                      :fields [{:id fid :name "北" :status "none" :geojson tu/square}]}})]
        (testing "B5-2-01 / B5-2-03 / B5-5.3-01 / B5-10-01 指示と日誌"
          (is (re-find #"指示" wide))
          (is (re-find #"日誌を書く|日誌" detail))
          (is (re-find #"id=\"ol-map\"" detail))
          (is (re-find #"data-order-mode=\"1\"" detail))
          (is (re-find #"北（未）" detail)))
        (testing "B5-2-02 / B5-3-01 / B5-5.2-01 管理者は切断だけ"
          (is (re-find #"関係を切" (html {:page :home :kind "admin"})))
          (is (= 1 (count (re-seq #"href=\"/admin/relations\"" (html {:page :home :kind "admin"})))))
          (is (not (re-find #"href=\"/orders\"|href=\"/gantt\"|href=\"/map\""
                            (html {:page :home :kind "admin"}))))
          (is (= 403 (:status (tu/get-path app "/api/user/orders" "admin" asid))))
          (is (some? (http/match-api :post "/api/admin/relations/cut")))
          (is (nil? (http/match-api :post "/api/user/relations/cut"))))
        (testing "B5-2-04 / B5-5.4-01〜04 スマホ"
          (is (re-find #"出した指示|受けた指示" (html {:page :orders :narrow? true})))
          (let [phone (html {:page :order :narrow? true
                             :session {:email "b5b@example.com"}
                             :order {:id 1 :role "recipient" :status "open"
                                     :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                     :work_name "田植え" :body "" :recipient_emails []
                                     :fields [{:id 1 :name "北" :visible true}] :journals []}
                             :order-map {:work_name "田植え"
                                         :fields [{:id 1 :name "北" :status "partial"
                                                   :geojson tu/square}]}})]
            (is (re-find #"日誌を書く" phone))
            (is (re-find #"id=\"ol-map\"" phone))
            (is (re-find #"data-order-mode=\"1\"" phone))
            (is (re-find #"北（一部）" phone)))
          (let [issuer-phone (html {:page :order :narrow? true
                                    :order {:id 1 :role "issuer" :status "open"
                                            :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                            :work_name "田植え" :body "" :recipient_emails []
                                            :fields [{:id 1 :name "北" :visible true}] :journals []}
                                    :order-map {:work_name "田植え"
                                                :fields [{:id 1 :name "北" :status "none"
                                                          :geojson tu/square}]}})]
            (is (re-find #"id=\"ol-map\"" issuer-phone))
            (is (re-find #"北（未）" issuer-phone))
            (is (not (re-find #"この指示を閉じる" issuer-phone))))
          (is (re-find #"パソコンで開いてください"
                       (html {:page :orders-new :narrow? true :fields [{:id 1}]})))
          (is (not (re-find #"言語切替|English" (html {:page :orders :narrow? true}))))
          (is (not (re-find #"ブラシ|全面完了|gantt-circle" detail))))
        (testing "B5-3-02 / B5-5.3-03 指示を出す権限"
          (is (true? (:ok (orders/create-order sys uid {:work_date "2026-09-12"
                                                        :start_time "08:00" :end_time "17:00"
                                                        :work_name "田植え" :body ""
                                                        :recipient_emails ["b5b@example.com"]
                                                        :field_ids [fid]}))))
          (let [pw0 (tu/invite-pw app asid "b5z@example.com")
                uid0 (:id (db/find-user-by-email (:ds sys) "b5z@example.com"))]
            (is (= "no_fields" (:code (orders/create-order sys uid0 {:work_date "2026-09-12"
                                                                     :start_time "08:00" :end_time "17:00"
                                                                     :work_name "x" :body ""
                                                                     :recipient_emails ["b5b@example.com"]
                                                                     :field_ids [1]})))))
          (is (re-find #"圃場が1枚以上" (html {:page :orders-new :fields []}))))
        (testing "B5-3-03 / B5-4.7-04 日誌は圃場0でも可"
          (let [oid (:id (first (:sent (orders/list-orders sys uid))))
                pw0 (tu/invite-pw app asid "b5recv0@example.com")
                sid0 (tu/user-sid app "b5recv0@example.com" pw0)
                uid0 (:id (db/find-user-by-email (:ds sys) "b5recv0@example.com"))
                ;; 新しい指示を 0枚利用者へ
                c (orders/create-order sys uid {:work_date "2026-09-13"
                                                :start_time "08:00" :end_time "12:00"
                                                :work_name "草刈り" :body ""
                                                :recipient_emails ["b5recv0@example.com"]
                                                :field_ids [fid]})]
            (is (true? (:ok (orders/post-journal sys uid0 (:id c) {:body "やった"}))))
            (is (= "journal_exists" (:code (orders/post-journal sys uid0 (:id c) {:body "二通"}))))
            (orders/close-order sys uid (:id c))
            (is (= "order_closed" (:code (orders/post-journal sys uid0 (:id c) {:body "遅"}))))))
        (testing "B5-4.4-01 / B5-4.4-02 候補と塗れない"
          (let [names (:work_names (gantt/work-name-candidates sys uid))]
            (is (some #{"田植え"} names))
            (is (some #{"草刈り"} names)))
          (is (nil? (http/match-api :post "/api/user/others/paints")))
          (is (= "field_not_found"
                 (:code (paints/create-paint sys uid2 {:field_id fid :work_name "田植え"
                                                       :geojson tu/square-inner})))))
        (testing "B5-4.5-01〜03 色と％禁止"
          (is (= "#c8c8c8" (:none ui/paint-colors)))
          (is (not (re-find #"gantt-circle|パーセントサークル" detail)))
          (let [c (orders/create-order sys uid {:work_date "2026-09-16"
                                                :start_time "08:00" :end_time "17:00"
                                                :work_name "色確認" :body ""
                                                :recipient_emails ["b5b@example.com"]
                                                :field_ids [fid]})
                oid (:id c)
                before (orders/order-map sys uid2 oid)]
            (orders/post-journal sys uid2 oid {:body "50%"})
            (is (= (:fields before) (:fields (orders/order-map sys uid2 oid))))
            (orders/close-order sys uid oid)))
        (testing "B5-4.6-01 ガントと自動で繋がない"
          (is (not (contains? (db/table-columns (:ds sys) "orders") "gantt_id")))
          (is (not (contains? (db/table-columns (:ds sys) "gantt_rows") "order_id"))))
        (testing "B5-4.7-01〜09 / B5-7.4 / B5-7.5 流れ"
          ;; 前段で残った進行中の指示を閉じてから切断を試す
          (doseq [o (:sent (orders/list-orders sys uid))]
            (when (= "open" (:status o))
              (orders/close-order sys uid (:id o))))
          (let [c (orders/create-order sys uid {:work_date "2026-09-14"
                                                :start_time "08:00" :end_time "17:00"
                                                :work_name "見回り" :body "本文"
                                                :recipient_emails ["b5b@example.com"]
                                                :field_ids [fid]})
                oid (:id c)]
            (is (true? (:ok c)))
            (is (= "order_not_issuer" (:code (orders/update-order sys uid2 oid
                                                                  {:work_date "2026-09-14"
                                                                   :start_time "09:00" :end_time "18:00"
                                                                   :body "x"}))))
            (is (true? (:ok (orders/update-order sys uid oid
                                                 {:work_date "2026-09-14"
                                                  :start_time "09:00" :end_time "18:00"
                                                  :body "直した"
                                                  :recipient_emails ["other@example.com"]
                                                  :field_ids [999]}))))
            (is (= ["b5b@example.com"] (:recipient_emails (orders/get-order sys uid oid))))
            (is (= "relation_busy" (:code (orders/cut-relation sys "b5a@example.com" "b5b@example.com"))))
            (is (true? (:ok (orders/close-order sys uid oid))))
            (is (true? (:ok (orders/cut-relation sys "b5a@example.com" "b5b@example.com"))))
            (is (empty? (:fields (orders/get-order sys uid2 oid))))
            (is (seq (filter :visible (:fields (orders/get-order sys uid oid)))))
            (let [c2 (orders/create-order sys uid {:work_date "2026-09-15"
                                                   :start_time "08:00" :end_time "10:00"
                                                   :work_name "再" :body ""
                                                   :recipient_emails ["b5b@example.com"]
                                                   :field_ids [fid]})]
              (is (= 1 (count (:fields (orders/others-fields sys uid2)))))
              (orders/close-order sys uid (:id c2)))))
        (testing "B5-4.7-06 /map は自分だけ"
          (is (not (re-find #"他人の対象" (html {:page :map
                                                 :place {:west 1 :south 2 :east 3 :north 4}
                                                 :fields [{:id fid :name "北"}]})))))
        (testing "B5-5.2-02 管理者に地図・台帳・ガント・指示は無い"
          (is (= :unknown (:page (ui/route-for "/admin/orders"))))
          (is (= :unknown (:page (ui/route-for "/admin/gantt")))))
        (testing "B5-5.3-02 他人の対象圃場"
          (is (re-find #"他人の対象圃場" (html {:page :others})))
          (is (not (re-find #"ブラシ|全面完了" (html {:page :others})))))
        (testing "B5-6 データ"
          (let [names (tu/table-names (:ds sys))]
            (is (contains? names "orders"))
            (is (contains? names "journals"))
            (is (contains? names "relation_cuts"))
            (is (not (contains? names "work_names")))))
        (testing "B5-8-01 通知は無い"
          (is (nil? (http/match-api :post "/api/user/orders/notify")))
          (is (nil? (http/match-api :post "/api/notify"))))
        (testing "B5-10-02 言語切替は出さない"
          (is (nil? (http/match-api :put "/api/user/locale")))
          (is (not (re-find #"言語切替|English" wide))))))))

(deftest b5-spec-ids-present
  (let [doc (slurp (io/file "docs/基本試験仕様書_工程5.md"))
        ids ["B5-2-01" "B5-2-02" "B5-2-03" "B5-2-04"
             "B5-3-01" "B5-3-02" "B5-3-03" "B5-3-04"
             "B5-4.4-01" "B5-4.4-02"
             "B5-4.5-01" "B5-4.5-02" "B5-4.5-03"
             "B5-4.6-01"
             "B5-4.7-01" "B5-4.7-02" "B5-4.7-03" "B5-4.7-04" "B5-4.7-05"
             "B5-4.7-06" "B5-4.7-07" "B5-4.7-08" "B5-4.7-09"
             "B5-5.2-01" "B5-5.2-02"
             "B5-5.3-01" "B5-5.3-02" "B5-5.3-03"
             "B5-5.4-01" "B5-5.4-02" "B5-5.4-03" "B5-5.4-04"
             "B5-6-01" "B5-6-02" "B5-6-03" "B5-6-04"
             "B5-7.4-01" "B5-7.4-02" "B5-7.4-03" "B5-7.4-04"
             "B5-7.5-01" "B5-7.5-02" "B5-7.5-03"
             "B5-8-01" "B5-10-01" "B5-10-02"]]
    (doseq [id ids]
      (is (re-find (re-pattern id) doc)))))
