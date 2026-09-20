(ns isas.p5-test
  "詳細試験仕様書_工程5 の項番に対応する自動試験。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.orders :as orders]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui])
  (:import [java.time Instant]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "p5a@example.com")
        usid (tu/user-sid app "p5a@example.com" pw)
        pw2 (tu/invite-pw app asid "p5b@example.com")
        usid2 (tu/user-sid app "p5b@example.com" pw2)
        uid (:id (db/find-user-by-email (:ds sys) "p5a@example.com"))
        uid2 (:id (db/find-user-by-email (:ds sys) "p5b@example.com"))]
    {:app app :asid asid :usid usid :usid2 usid2 :uid uid :uid2 uid2}))

(defn- add-field [app usid name gj]
  (:field (tu/parse (tu/post-json app "/api/user/fields" {:name name :geojson gj} "user" usid))))

(defn- order-body
  ([wn emails fids] (order-body "2026-09-12" "08:00" "17:00" wn "頼む" emails fids))
  ([wd st et wn body emails fids]
   {:work_date wd :start_time st :end_time et :work_name wn :body body
    :recipient_emails emails :field_ids fids}))

(deftest p5-screens-and-routes
  (testing "P5-2.1-01 / P5-5-01 一覧"
    (let [h (html {:page :orders
                   :orders-sent [{:id 1 :work_date "2026-09-12" :work_name "田植え" :status "open"}]
                   :orders-received []})]
      (is (re-find #">指示<" h))
      (is (re-find #"出した指示" h))
      (is (re-find #"受けた指示" h))))
  (testing "P5-2.1-02 / P5-3.2-07 新規"
    (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-12T00:00:00Z"))]
      (let [h (html {:page :orders-new :fields [{:id 1 :name "北"}]
                     :form {:work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                            :work_name "" :body "" :recipient_emails "" :field_ids []}})]
        (is (re-find #"指示を出す|指示を保存" h))
        (is (re-find #"2026-09-12" h))
        (is (re-find #"08:00" h))
        (is (re-find #"17:00" h))))
    (is (re-find #"圃場が1枚以上あるときだけ、指示を出せます"
                 (html {:page :orders-new :fields []}))))
  (testing "P5-2.1-03 / P5-2.2-01〜04 / P5-2.2-02 詳細"
    (let [h (html {:page :order
                   :order {:id 1 :role "issuer" :status "open"
                           :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                           :work_name "田植え" :body "本文"
                           :recipient_emails ["b@example.com"]
                           :fields [{:id 1 :name "北" :visible true}]
                           :journals []}
                   :order-map {:work_name "田植え"
                               :fields [{:id 1 :name "北" :status "partial" :geojson tu/square}]}})]
      (is (re-find #"田植え" h))
      (is (re-find #"b@example.com" h))
      (is (re-find #"北（一部）" h))
      (is (re-find #"進行中" h))
      (is (re-find #"id=\"ol-map\"" h))
      (is (re-find #"data-order-mode=\"1\"" h))
      (is (re-find #"data-target-ids=\"1\"" h))
      (is (re-find #"この指示を閉じる" h))
      (is (re-find #"依頼文と時刻を直す" h))
      (is (not (re-find #"受け手の追加|対象の追加" h)))
      (is (not (re-find #"gantt-circle|パーセント" h)))))
  (testing "P5-2.1-08 狭い画面でも指示地図の色"
    (let [h (html {:page :order :narrow? true
                   :order {:id 1 :role "recipient" :status "open"
                           :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                           :work_name "田植え" :body "" :recipient_emails []
                           :fields [{:id 1 :name "北" :visible true}] :journals []}
                   :order-map {:work_name "田植え"
                               :fields [{:id 1 :name "北" :status "none" :geojson tu/square}]}})]
      (is (re-find #"北（未）" h))
      (is (re-find #"id=\"ol-map\"" h))
      (is (re-find #"data-order-mode=\"1\"" h))))
  (testing "P5-2.1-04 / P5-5-05 他人"
    (is (re-find #"他人の対象圃場" (html {:page :others :others-fields [] :others-work-names []}))))
  (testing "P5-2.1-05 / P5-5-06 関係"
    (is (re-find #"関係を切る" (html {:page :relations :kind "admin"
                                      :session {:email "admin@example.com"}}))))
  (testing "P5-2.1-06 / P5-2.1-07 ホーム"
    (is (re-find #"href=\"/orders\"" (html {:page :home})))
    (is (re-find #"href=\"/orders/new\"" (html {:page :home :fields [{:id 1}]})))
    (is (not (re-find #"href=\"/orders/new\"" (html {:page :home :fields []}))))
    (is (not (re-find #"href=\"/orders\"" (html {:page :home :kind "admin"}))))
    (let [admin-home (html {:page :home :kind "admin"})]
      (is (re-find #"href=\"/admin/relations\"" admin-home))
      (is (= 1 (count (re-seq #"href=\"/admin/relations\"" admin-home)))
          "関係を切るリンクはナビに1つだけ")
      (is (= 1 (count (re-seq #"関係を切る" admin-home)))
          "「関係を切る」文言も1回だけ")))
  (testing "P5-2.1-08 / P5-2.1-09 狭い画面"
    (is (re-find #"出した指示|受けた指示" (html {:page :orders :narrow? true})))
    (is (re-find #"指示の作成・修正・閉じはパソコンで開いてください"
                 (html {:page :orders-new :narrow? true :fields [{:id 1}]})))
    (is (re-find #"他人の対象圃場のまとめはパソコンで開いてください"
                 (html {:page :others :narrow? true})))
    (let [h (html {:page :order :narrow? true
                   :order {:id 1 :role "issuer" :status "open"
                           :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                           :work_name "田植え" :body "" :recipient_emails []
                           :fields [{:id 1 :name "北" :visible true}] :journals []}})]
      (is (re-find #"パソコンで開いてください" h))
      (is (not (re-find #"この指示を閉じる" h)))))
  (testing "P5-2.1-10 /map に他人を混ぜない"
    (is (not (re-find #"他人|orders" (html {:page :map :place {:west 1 :south 2 :east 3 :north 4}
                                            :fields [{:id 1 :name "自分"}]})))))
  (testing "P5-2.1-11 管理者に指示画面は無い"
    (is (= :unknown (:page (ui/route-for "/admin/orders"))))
    (is (not (re-find #"href=\"/orders\"" (html {:page :home :kind "admin"})))))
  (testing "P5-5 文言"
    (is (= "指示" (:orders-title ui/messages)))
    (is (= "指示を出す" (:orders-create ui/messages)))
    (is (= "この指示を閉じる" (:orders-close ui/messages)))
    (is (= "日誌を書く" (:orders-journal ui/messages)))
    (is (= "自分は受け手に含められません" (:recipient-self ui/messages)))
    (is (= "このメールアドレスの利用者には出せません" (:recipient-not-user ui/messages)))
    (is (= "進行中の指示がある二人は関係を切れません" (:relation-busy ui/messages)))
    (is (= "未" (:status-none ui/messages))))
  (testing "P5-6 作らないもの"
    (let [h (html {:page :orders})
          doc (slurp (io/file "docs/詳細試験仕様書_工程5.md"))]
      (is (nil? (http/match-api :delete "/api/user/orders/1")))
      (is (nil? (http/match-api :post "/api/user/orders/1/reopen")))
      (is (nil? (http/match-api :put "/api/user/locale")))
      (is (some? (http/match-api :put "/api/user/language")))
      (is (re-find #"data-select=\"lang\"" h))
      (doseq [id ["P5-6-01" "P5-6-02" "P5-6-03" "P5-6-04" "P5-6-05"
                  "P5-6-06" "P5-6-07" "P5-6-08" "P5-6-09" "P5-6-10" "P5-6-11"]]
        (is (re-find (re-pattern id) doc))))))

(deftest p5-api-and-rules
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid usid2 uid uid2]} (farm sys)
            f1 (add-field app usid "北" tu/square)
            f2 (add-field app usid "南" tu/square-east)
            fo (add-field app usid2 "他人" tu/square)
            id1 (:id f1)
            id2 (:id f2)
            oid (:id fo)]
        (testing "P5-2.3-14 管理者は forbidden"
          (is (= 403 (:status (tu/get-path app "/api/user/orders" "admin" asid))))
          (is (= 403 (:status (tu/post-json app "/api/user/orders"
                                            (order-body "a" ["p5b@example.com"] [id1])
                                            "admin" asid)))))
        (testing "P5-2.3-c01 / P5-3.1-01 圃場0"
          (let [pw0 (tu/invite-pw app asid "p5z@example.com")
                sid0 (tu/user-sid app "p5z@example.com" pw0)]
            (is (= "no_fields" (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                              (order-body "x" ["p5b@example.com"] [1])
                                                              "user" sid0)))))))
        (testing "P5-2.3-01 / P5-2.3-02 / P5-2.3-03 / P5-2.3-18 作成・一覧・取得"
          (let [c (tu/parse (tu/post-json app "/api/user/orders"
                                          (order-body "田植え" ["p5b@example.com"] [id1])
                                          "user" usid))
                listed (tu/parse (tu/get-path app "/api/user/orders" "user" usid))
                got (tu/parse (tu/get-path app (str "/api/user/orders/" (:id c)) "user" usid))
                got-b (tu/parse (tu/get-path app (str "/api/user/orders/" (:id c)) "user" usid2))]
            (is (true? (:ok c)))
            (is (= "open" (:status c)))
            (is (= "issuer" (:role c)))
            (is (= 1 (count (:sent listed))))
            (is (= "recipient" (:role got-b)))
            (is (= [{:id id1 :name "北" :visible true}] (:fields got)))
            (is (= "2026-09-12" (:work_date got)))
            (is (= "08:00" (:start_time got)))
            (is (= "17:00" (:end_time got)))))
        (testing "P5-2.3-04 / P5-7-04 PUT は依頼文と日時だけ"
          (let [oid (:id (first (:sent (tu/parse (tu/get-path app "/api/user/orders" "user" usid)))))
                before (tu/parse (tu/get-path app (str "/api/user/orders/" oid) "user" usid))
                u (tu/parse (tu/put-json app (str "/api/user/orders/" oid)
                                         {:work_date "2026-09-13" :start_time "09:00" :end_time "18:00"
                                          :body "直した"
                                          :recipient_emails ["nope@example.com"]
                                          :field_ids [id2]}
                                         "user" usid))]
            (is (= "直した" (:body u)))
            (is (= "2026-09-13" (:work_date u)))
            (is (= (:recipient_emails before) (:recipient_emails u)))
            (is (= (mapv :id (filter :visible (:fields before)))
                   (mapv :id (filter :visible (:fields u)))))))
        (testing "P5-2.3-06 / P5-2.3-c05〜c08 日誌"
          (let [oid (:id (first (:sent (tu/parse (tu/get-path app "/api/user/orders" "user" usid)))))]
            (is (= "order_not_recipient"
                   (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/journal")
                                                  {:body "出した人"} "user" usid)))))
            (is (= "journal_required"
                   (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/journal")
                                                  {:body "  "} "user" usid2)))))
            (is (= "journal_too_long"
                   (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/journal")
                                                  {:body (apply str (repeat 2001 "あ"))}
                                                  "user" usid2)))))
            (is (true? (:ok (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/journal")
                                                    {:body "やりました"} "user" usid2)))))
            (is (= "journal_exists"
                   (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/journal")
                                                  {:body "二通目"} "user" usid2)))))))
        (testing "P5-2.3-07 地図"
          (let [oid (:id (first (:sent (tu/parse (tu/get-path app "/api/user/orders" "user" usid)))))
                m (tu/parse (tu/get-path app (str "/api/user/orders/" oid "/map") "user" usid2))]
            (is (true? (:ok m)))
            (is (= "田植え" (:work_name m)))
            (is (= 1 (count (:fields m))))
            (is (contains? (first (:fields m)) :status))
            (is (not (contains? (first (:fields m)) :percent)))))
        (testing "P5-2.3-08〜11 / P5-7-05 他人。対象外の圃場は見えない"
          (tu/post-json app "/api/user/paints"
                        {:field_id id1 :work_name "田植え" :geojson tu/square-inner}
                        "user" usid)
          (let [of (tu/parse (tu/get-path app "/api/user/others/fields" "user" usid2))
                wn (tu/parse (tu/get-path app "/api/user/others/work-names" "user" usid2))
                op (tu/parse (tu/get-query app "/api/user/others/paints" {:work_name "田植え"} "user" usid2))
                bad (tu/parse (tu/get-query app "/api/user/others/paints" {:work_name "秘密"} "user" usid2))
                names (set (map :name (:fields of)))]
            (is (= 1 (count (:fields of))))
            (is (contains? names "北"))
            (is (not (contains? names "南")))
            (is (false? (db/field-visible-to-viewer? (:ds sys) uid2 id2)))
            (is (some #{"田植え"} (:work_names wn)))
            (is (= "partial" (:status (first (:fields op)))))
            (is (= "work_name_unrelated" (:code bad)))))
        (testing "P5-2.3-05 / P5-7-03 閉じ"
          (let [oid (:id (first (:sent (tu/parse (tu/get-path app "/api/user/orders" "user" usid)))))
                cl (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/close") {} "user" usid))]
            (is (= "closed" (:status cl)))
            (is (= "order_closed" (:code (tu/parse (tu/put-json app (str "/api/user/orders/" oid)
                                                                {:work_date "2026-09-14" :start_time "08:00"
                                                                 :end_time "17:00" :body "x"}
                                                                "user" usid)))))
            (is (= "order_closed" (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/close")
                                                                 {} "user" usid)))))
            (is (= "order_closed" (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/journal")
                                                                 {:body "late"} "user" usid2)))))))
        (testing "P5-2.3-12 / P5-2.3-15 / P5-2.3-c19〜c21 切断"
          (is (nil? (http/match-api :post "/api/user/relations/cut")))
          ;; 閉じた指示でも見え方が残るので切れる
          (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/relations/cut"
                                                  {:email_a "p5a@example.com" :email_b "p5b@example.com"}
                                                  "admin" asid)))))
          (let [c2 (tu/parse (tu/post-json app "/api/user/orders"
                                           (order-body "追肥" ["p5b@example.com"] [id2])
                                           "user" usid))]
            (is (= "relation_busy"
                   (:code (tu/parse (tu/post-json app "/api/admin/relations/cut"
                                                  {:email_a "p5a@example.com" :email_b "p5b@example.com"}
                                                  "admin" asid)))))
            (tu/post-json app (str "/api/user/orders/" (:id c2) "/close") {} "user" usid)
            (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/relations/cut"
                                                    {:email_a "p5a@example.com" :email_b "p5b@example.com"}
                                                    "admin" asid)))))
            (let [got (tu/parse (tu/get-path app (str "/api/user/orders/" (:id c2)) "user" usid2))]
              (is (= [] (:fields got))))
            (is (= "relation_idle"
                   (:code (tu/parse (tu/post-json app "/api/admin/relations/cut"
                                                  {:email_a "p5a@example.com" :email_b "p5b@example.com"}
                                                  "admin" asid)))))
            (is (= "relation_not_found"
                   (:code (tu/parse (tu/post-json app "/api/admin/relations/cut"
                                                  {:email_a "p5a@example.com" :email_b "p5a@example.com"}
                                                  "admin" asid)))))
            (is (= "relation_not_found"
                   (:code (tu/parse (tu/post-json app "/api/admin/relations/cut"
                                                  {:email_a "p5a@example.com" :email_b "admin@example.com"}
                                                  "admin" asid)))))))
        (testing "P5-2.3-13 DELETE/再開は無い"
          (is (nil? (http/match-api :delete "/api/user/orders/1")))
          (is (nil? (http/match-api :post "/api/user/orders/1/reopen"))))
        (testing "P5-2.3-16 候補"
          (let [c (tu/parse (tu/get-path app "/api/user/work-name-candidates" "user" usid))
                cb (tu/parse (tu/get-path app "/api/user/work-name-candidates" "user" usid2))]
            (is (some #{"田植え"} (:work_names c)))
            (is (some #{"追肥"} (:work_names c)))
            (is (not (some #{"田植え"} (:work_names cb))))))
        (testing "P5-2.3-17 / P5-2.3-c15 field_in_open_order"
          (let [c (tu/parse (tu/post-json app "/api/user/orders"
                                          (order-body "見回り" ["p5b@example.com"] [id1])
                                          "user" usid))]
            (is (= "field_in_open_order"
                   (:code (tu/parse (tu/delete-path app (str "/api/user/fields/" id1) "user" usid)))))
            (is (= "field_in_open_order"
                   (:code (fields/split-field sys uid id1 {:polygons [tu/square-nw tu/square-inner]}))))
            (is (= "field_in_open_order"
                   (:code (fields/merge-fields sys uid {:ids [id1 id2] :keep_id id1}))))
            (tu/post-json app (str "/api/user/orders/" (:id c) "/close") {} "user" usid)))
        (testing "P5-2.3-c02〜c04 / c09〜c14 / c16 / c18 codes"
          (is (= "order_not_found" (:code (tu/parse (tu/get-path app "/api/user/orders/99999" "user" usid)))))
          (let [c (tu/parse (tu/post-json app "/api/user/orders"
                                          (order-body "codes" ["p5b@example.com"] [id2])
                                          "user" usid))
                oid (:id c)]
            (is (= "order_not_issuer"
                   (:code (tu/parse (tu/post-json app (str "/api/user/orders/" oid "/close")
                                                  {} "user" usid2)))))
            (is (= "body_too_long"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "2026-09-12" "08:00" "17:00" "x"
                                                              (apply str (repeat 2001 "あ"))
                                                              ["p5b@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "recipients_required"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "x" [] [id2]) "user" usid)))))
            (is (= "recipient_self"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "x" ["p5a@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "recipient_not_user"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "x" ["nobody@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "recipient_not_user"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "x" ["admin@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "fields_required"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "x" ["p5b@example.com"] [])
                                                  "user" usid)))))
            (is (= "field_not_found"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "x" ["p5b@example.com"] [oid])
                                                  "user" usid)))))
            (is (= "work_name_required"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "" ["p5b@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "work_name_too_long"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body (apply str (repeat 101 "あ"))
                                                              ["p5b@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "time_invalid"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "bad" "08:00" "17:00" "x" ""
                                                              ["p5b@example.com"] [id2])
                                                  "user" usid)))))
            (is (= "time_order"
                   (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                  (order-body "2026-09-12" "17:00" "08:00" "x" ""
                                                              ["p5b@example.com"] [id2])
                                                  "user" usid)))))
            (tu/post-json app (str "/api/user/orders/" oid "/close") {} "user" usid)))
        (testing "P5-3.1-03 受け手の圃場は混ぜられない"
          (is (= "field_not_found"
                 (:code (tu/parse (tu/post-json app "/api/user/orders"
                                                (order-body "x" ["p5b@example.com"] [oid])
                                                "user" usid))))))
        (testing "P5-3.1-04 指示が無くても塗り・ガント"
          (is (true? (:ok (paints/list-work-names sys uid))))
          (is (true? (:ok (gantt/list-rows sys uid)))))
        (testing "P5-3.2-03〜05 空依頼文・重複除去"
          (let [c (tu/parse (tu/post-json app "/api/user/orders"
                                          (order-body "2026-09-12" "08:00" "17:00" "重複"
                                                      "  "
                                                      ["p5b@example.com" "p5b@example.com" " P5B@example.com "]
                                                      [id2 id2])
                                          "user" usid))]
            (is (true? (:ok c)))
            (is (= "" (:body c)))
            (is (= 1 (count (:recipient_emails c))))
            (is (= 1 (count (filter :visible (:fields c)))))
            (tu/post-json app (str "/api/user/orders/" (:id c) "/close") {} "user" usid)))
        (testing "P5-3.4 / P5-3.7 / P5-7 見え方と削除"
          (let [c (tu/parse (tu/post-json app "/api/user/orders"
                                          (order-body "切断後" ["p5b@example.com"] [id2])
                                          "user" usid))]
            (tu/post-json app (str "/api/user/orders/" (:id c) "/close") {} "user" usid)
            (tu/post-json app "/api/admin/relations/cut"
                          {:email_a "p5a@example.com" :email_b "p5b@example.com"}
                          "admin" asid)
            (is (empty? (:fields (tu/parse (tu/get-path app "/api/user/others/fields" "user" usid2)))))
            (let [c2 (tu/parse (tu/post-json app "/api/user/orders"
                                             (order-body "再開見え" ["p5b@example.com"] [id2])
                                             "user" usid))]
              (is (= 1 (count (:fields (tu/parse (tu/get-path app "/api/user/others/fields" "user" usid2))))))
              (tu/post-json app (str "/api/user/orders/" (:id c2) "/close") {} "user" usid)
              (is (true? (:ok (fields/delete-field sys uid id2))))
              (is (empty? (:fields (tu/parse (tu/get-path app "/api/user/others/fields" "user" usid2)))))
              (is (some? (db/find-order (:ds sys) (:id c2)))))))
        (testing "P5-3.7-02 ガント対象だけでは止めない"
          (let [tid (:id (:title (gantt/create-title sys uid {:name "題"})))]
            (gantt/create-row sys uid {:title_id tid :title "g" :start_at "2026-09-18T08:00" :end_at "2026-09-18T09:00"
                                       :work_name "田植え" :field_ids [id1]})
            (is (true? (:ok (fields/delete-field sys uid id1))))))
        (testing "P5-4 表"
          (let [names (tu/table-names (:ds sys))
                cols (db/table-columns (:ds sys) "orders")]
            (is (contains? names "orders"))
            (is (contains? names "order_recipients"))
            (is (contains? names "order_targets"))
            (is (contains? names "journals"))
            (is (contains? names "relation_cuts"))
            (is (contains? names "gantt_rows"))
            (is (every? #(contains? cols %) #{"issuer_id" "work_date" "start_time" "end_time"
                                              "work_name" "body" "closed_at" "created_at"}))
            (is (not (contains? cols "gantt_id")))))))))

(deftest p5-ui-handlers
  (testing "経路と submit"
    (is (= :orders (:page (ui/route-for "/orders"))))
    (is (= :orders-new (:page (ui/route-for "/orders/new"))))
    (is (= :order (:page (ui/route-for "/orders/3"))))
    (is (= "3" (:order-id (ui/route-for "/orders/3"))))
    (is (= :others (:page (ui/route-for "/others"))))
    (is (= :relations (:page (ui/route-for "/admin/relations"))))
    (is (true? (ui/needs-auth? :orders)))
    (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :home)
                       [:path {:path "/orders" :search ""}])]
      (is (= :api (ffirst (:fx r)))))
    (let [r (ui/handle (assoc (ui/init-state) :page :orders-new :fields [{:id 1}])
                       [:submit {:act "create-order"
                                 :form {:work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                                        :work_name "田植え" :body "" :recipient_emails "b@x.com"
                                        :field_ids ["1"]}}])]
      (is (= :api (ffirst (:fx r))))
      (is (= "POST" (second (first (:fx r))))))
    (let [r (ui/handle (assoc (ui/init-state) :page :order :order {:id 9} :order-id "9")
                       [:submit {:act "close-order" :form {:id "9"}}])]
      (is (re-find #"/close" (nth (first (:fx r)) 2))))
    (let [r (ui/handle (assoc (ui/init-state) :page :relations :kind "admin")
                       [:submit {:act "cut-relation" :form {:email_a "a@x.com" :email_b "b@x.com"}}])]
      (is (= "/api/admin/relations/cut" (nth (first (:fx r)) 2))))
    (let [r (ui/handle (ui/init-state) [:order-save-result {:ok false :code "no_fields"}])]
      (is (= (:order-no-fields ui/messages) (get-in r [:state :flash :text]))))
    (let [r (ui/handle (ui/init-state) [:relation-cut-result {:ok true}])]
      (is (false? (get-in r [:state :flash :error?]))))))

(deftest p5-order-enter-flow
  "P5-2.2-02 / P5-7-02 / P5-7-08: 指示詳細の画面遷移（session→place→order→map→basemaps→html）。
   静的 HTML に order-map を注入する試験だけでは、灰色地図の回帰を止められない。"
  (let [s0 (assoc (ui/init-state) :session {:email "a@example.com"} :kind "user"
                  :page :order :order-id "9")
        place-body {:ok true :west 139.0 :south 35.0 :east 141.0 :north 37.0}
        order-body {:ok true :id 9 :role "recipient" :status "open"
                    :work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
                    :work_name "田植え" :body "頼む"
                    :recipient_emails ["a@example.com"]
                    :fields [{:id 1 :name "北" :visible true}]
                    :journals []}
        map-body {:ok true :work_name "田植え"
                  :fields [{:id 1 :name "北" :status "partial"
                            :geojson {:type "Polygon"
                                      :coordinates [[[140 36] [140.1 36]
                                                     [140.1 36.1] [140 36.1] [140 36]]]}}]}
        fx-path (fn [st msg]
                  (let [fx (:fx (ui/handle st msg))
                        api (first (filter #(= :api (first %)) fx))]
                    (when api (nth api 2))))]
    (testing "広い画面: session は place から始める"
      (let [r (ui/handle s0 [:session-loaded {:ok true :email "a@example.com"}])]
        (is (= "/api/user/place" (fx-path s0 [:session-loaded {:ok true :email "a@example.com"}])))
        (is (= :api (ffirst (:fx r))))))
    (testing "狭い画面でも place を読む（スマホで色を見る経路）"
      (is (= "/api/user/place"
             (fx-path (assoc s0 :narrow? true)
                      [:session-loaded {:ok true :email "a@example.com"}]))))
    (testing "place → order → map → basemaps → html（#ol-map と進捗色ラベル）"
      (let [after-place (ui/handle s0 [:place-loaded place-body])
            after-order (ui/handle (:state after-place) [:order-loaded order-body])
            after-map (ui/handle (:state after-order) [:order-map-loaded map-body])
            after-bm (ui/handle (:state after-map) [:basemaps-loaded {:ok true :basemaps [{:kind "aerial" :ready true}]}])
            html (apply str (keep (fn [fx]
                                    (when (= :html (first fx)) (second fx)))
                                  (:fx after-bm)))]
        (is (= "/api/user/orders/9" (nth (first (:fx after-place)) 2)))
        (is (re-find #"/api/user/orders/9/map" (pr-str (:fx after-order))))
        (is (= "/api/user/basemaps" (nth (first (:fx after-map)) 2)))
        (is (= :html (ffirst (:fx after-bm))))
        (is (re-find #"id=\"ol-map\"" html))
        (is (re-find #"data-order-mode=\"1\"" html))
        (is (re-find #"data-target-ids=\"1\"" html))
        (is (re-find #"北（一部）" html))
        (is (some? (get-in after-bm [:state :place :west])))
        (is (= "partial" (get-in after-bm [:state :order-map :fields 0 :status])))))
    (testing "他人地図も place から始める"
      (is (= "/api/user/place"
             (fx-path (assoc s0 :page :others :order-id nil :narrow? false)
                      [:session-loaded {:ok true :email "a@example.com"}])))
      (let [after-place (ui/handle (assoc s0 :page :others :order-id nil)
                                   [:place-loaded place-body])]
        (is (re-find #"/api/user/others/fields" (pr-str (:fx after-place))))))))

(deftest p5-spec-ids-present
  (let [doc (slurp (io/file "docs/詳細試験仕様書_工程5.md"))
        ids ["P5-2.1-01" "P5-2.1-02" "P5-2.1-03" "P5-2.1-04" "P5-2.1-05" "P5-2.1-06"
             "P5-2.1-07" "P5-2.1-08" "P5-2.1-09" "P5-2.1-10" "P5-2.1-11"
             "P5-2.2-01" "P5-2.2-02" "P5-2.2-02b" "P5-2.2-03" "P5-2.2-04" "P5-2.2-05"
             "P5-2.3-01" "P5-2.3-02" "P5-2.3-03" "P5-2.3-04" "P5-2.3-05" "P5-2.3-06"
             "P5-2.3-07" "P5-2.3-08" "P5-2.3-09" "P5-2.3-10" "P5-2.3-11" "P5-2.3-12"
             "P5-2.3-13" "P5-2.3-14" "P5-2.3-15" "P5-2.3-16" "P5-2.3-17" "P5-2.3-18" "P5-2.3-19"
             "P5-2.3-c01" "P5-2.3-c02" "P5-2.3-c03" "P5-2.3-c04" "P5-2.3-c05" "P5-2.3-c06"
             "P5-2.3-c07" "P5-2.3-c08" "P5-2.3-c09" "P5-2.3-c10" "P5-2.3-c11" "P5-2.3-c12"
             "P5-2.3-c13" "P5-2.3-c14" "P5-2.3-c15" "P5-2.3-c16" "P5-2.3-c17" "P5-2.3-c18"
             "P5-2.3-c19" "P5-2.3-c20" "P5-2.3-c21"
             "P5-3.1-01" "P5-3.1-02" "P5-3.1-03" "P5-3.1-04"
             "P5-3.2-01" "P5-3.2-02" "P5-3.2-03" "P5-3.2-04" "P5-3.2-05" "P5-3.2-06" "P5-3.2-07"
             "P5-3.3-01" "P5-3.4-01" "P5-3.4-02" "P5-3.4-03" "P5-3.5-01" "P5-3.5-02"
             "P5-3.6-01" "P5-3.6-02" "P5-3.7-01" "P5-3.7-02"
             "P5-4-01" "P5-4-02" "P5-4-03" "P5-4-04" "P5-4-05" "P5-4-06"
             "P5-5-01" "P5-5-02" "P5-5-03" "P5-5-04" "P5-5-05" "P5-5-06" "P5-5-07"
             "P5-5-08" "P5-5-09" "P5-5-10" "P5-5-11" "P5-5-12" "P5-5-13"
             "P5-6-01" "P5-6-02" "P5-6-03" "P5-6-04" "P5-6-05" "P5-6-06" "P5-6-07"
             "P5-6-08" "P5-6-09" "P5-6-10" "P5-6-11"
             "P5-7-01" "P5-7-02" "P5-7-03" "P5-7-04" "P5-7-05" "P5-7-06" "P5-7-07"
             "P5-7-08" "P5-7-09"]]
    (doseq [id ids]
      (is (re-find (re-pattern id) doc)))))
