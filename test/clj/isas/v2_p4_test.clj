(ns isas.v2-p4-test
  "詳細試験仕様書_第2版_工程4 の項番に対応する自動試験。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui])
  (:import [java.time Instant]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"} :ui-lang "ja"} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "v2p4@example.com")
        usid (tu/user-sid app "v2p4@example.com" pw)
        uid (:id (db/find-user-by-email (:ds sys) "v2p4@example.com"))]
    {:app app :asid asid :usid usid :uid uid}))

(defn- add-field [app usid]
  (:field (tu/parse (tu/post-json app "/api/user/fields"
                                  {:name "北" :geojson tu/square} "user" usid))))

(defn- make-title [app usid name]
  (:title (tu/parse (tu/post-json app "/api/user/gantt/titles" {:name name} "user" usid))))

(defn- row-body
  ([tid title start end] (row-body tid title start end nil []))
  ([tid title start end wn fids]
   (cond-> {:title_id tid :title title :start_at start :end_at end :field_ids fids}
     (some? wn) (assoc :work_name wn))))

(deftest v2p4-screens-and-nav
  (testing "V2P4-2.1-01 / V2P4-2.3-01 狭幅下部ナビ"
    (let [h (html {:page :home :narrow? true})]
      (is (re-find #"id=\"bottom-nav\"" h))
      (is (re-find #"href=\"/home\"" h))
      (is (re-find #"href=\"/daily\"" h))
      (is (re-find #"href=\"/orders\"" h))
      (is (re-find #">メモ<" h))
      (is (re-find #">日次<" h))
      (is (re-find #">指示<" h))))
  (testing "V2P4-2.1-02 パソコン幅に下部ナビ無し"
    (is (nil? (re-find #"id=\"bottom-nav\"" (html {:page :memos :narrow? false})))))
  (testing "V2P4-2.1-03 ログイン画面に下部ナビ無し"
    (is (nil? (re-find #"id=\"bottom-nav\""
                       (html {:page :login :session nil :narrow? true}))))
    (is (nil? (re-find #"id=\"bottom-nav\""
                       (html {:page :reset-request :session nil :narrow? true})))))
  (testing "V2P4-2.2-01 狭幅メモは工程3 UI"
    (let [h (html {:page :memos :narrow? true
                   :memos [{:id 1 :body "本文" :status "published"
                            :author_email "a@example.com"}]})]
      (is (nil? (re-find #"メモはパソコンで開いてください" h)))
      (is (re-find #"memo-compose-section|メモ" h))
      (is (re-find #"id=\"bottom-nav\"" h))))
  (testing "V2P4-2.2-02 / V2P4-2.4-02 狭幅日次は閲覧専用"
    (let [h (html {:page :daily :narrow? true :fields [{:id 1}]
                   :daily-statuses ["not_started" "in_progress"]
                   :daily-rows [{:id 1 :title "予定A" :start_at "2026-09-21T08:00"
                                 :end_at "2026-09-21T09:00" :execution_status "not_started"
                                 :work_time_count 0 :checklist_done 0 :checklist_total 0}]})]
      (is (re-find #"スマホでは状態を変えられません" h))
      (is (re-find #"daily-list" h))
      (is (re-find #"予定A" h))
      (is (nil? (re-find #"set-daily-row-status" h)))
      (is (nil? (re-find #"daily-link-edit-work" h)))))
  (testing "V2P4-2.2-04 狭幅で works/gantt/map は案内のまま"
    (doseq [p [:works :gantt :map]]
      (is (re-find #"パソコンで開いてください" (html {:page p :narrow? true :fields [{:id 1}]})))))
  (testing "V2P4-2.2-05 狭幅ログイン着地はホーム"
    (let [r (ui/handle (assoc (ui/init-state) :page :login :kind "user" :narrow? true)
                       [:login-result {:ok true :email "a" :ui_lang "ja"}])]
      (is (= "/home" (second (first (:fx r))))))
    (let [r (ui/handle (assoc (ui/init-state) :page :login :kind "admin" :narrow? true)
                       [:login-result {:ok true :email "a" :ui_lang "ja"}])]
      (is (= "/admin/home" (second (first (:fx r))))))
    (let [r (ui/handle (assoc (ui/init-state) :page :login :kind "user" :narrow? false)
                       [:login-result {:ok true :email "a" :ui_lang "ja"}])]
      (is (= "/home" (second (first (:fx r)))))))
  (testing "V2P4-2.2-06 /home 狭幅はタイムラインのみ"
    (let [h (html {:page :home :narrow? true
                   :memos [{:id 1 :body "ホーム本文" :author_email "a@example.com"}]})]
      (is (re-find #"id=\"bottom-nav\"" h))
      (is (re-find #"memo-timeline" h))
      (is (re-find #"ホーム本文" h))
      (is (nil? (re-find #"memo-compose-section" h)))
      (is (re-find #"href=\"/memos\"" h))))
  (testing "V2P4-2.3-02 current"
    (is (re-find #"href=\"/home\" class=\"current\"" (html {:page :home :narrow? true})))
    (is (re-find #"href=\"/daily\" class=\"current\"" (html {:page :daily :narrow? true
                                                             :daily-statuses ["not_started"]
                                                             :fields [{:id 1}]})))
    (is (re-find #"href=\"/orders\" class=\"current\"" (html {:page :orders :narrow? true}))))
  (testing "V2P4-2.3-03 管理者日次で kind を落とさない"
    (let [from (assoc (ui/init-state) :page :home :kind "admin" :narrow? true
                      :session {:email "admin@example.com"})
          to (ui/handle from [:path {:path "/daily" :search ""}])]
      (is (= "admin" (get-in to [:state :kind])))
      (is (some? (get-in to [:state :session])))
      (is (= :daily (get-in to [:state :page])))
      (is (= :api (ffirst (:fx to))))
      (is (str/includes? (nth (first (:fx to)) 2) "/api/admin/gantt/daily"))))
  (testing "V2P4-2.4-03 圃場0狭幅は日次閲覧可・パソコンは不可"
    (let [narrow (html {:page :daily :narrow? true :fields []
                        :daily-statuses ["not_started"] :daily-rows []})
          wide (html {:page :daily :narrow? false :fields []})]
      (is (re-find #"daily-list|daily-filter" narrow))
      (is (re-find #"圃場が1枚以上" wide))))
  (testing "V2P4-2.4-04 管理者狭幅にメール・状態ラベル"
    (let [h (html {:page :daily :kind "admin" :narrow? true
                   :daily-statuses ["not_started" "in_progress" "done"]
                   :daily-rows [{:id 1 :title "横断" :start_at "2026-09-21T08:00"
                                 :end_at "2026-09-21T09:00" :execution_status "in_progress"
                                 :user_email "u@example.com" :user_id 3
                                 :work_time_count 0 :checklist_done 0 :checklist_total 0}
                                {:id 2 :title "完了行" :start_at "2026-09-21T10:00"
                                 :end_at "2026-09-21T11:00" :execution_status "done"
                                 :user_email "v@example.com" :user_id 4
                                 :work_time_count 1 :checklist_done 1 :checklist_total 1}]})]
      (is (re-find #"u@example.com" h))
      (is (re-find #"横断" h))
      (is (re-find #"着手中" h))
      (is (re-find #"完了" h))
      (is (nil? (re-find #"set-daily-row-status" h)))))
  (testing "V2P4-2.4-05 管理者パソコン日次案内"
    (is (re-find #"日次一覧はスマホで開いてください"
                 (html {:page :daily :kind "admin" :narrow? false}))))
  (testing "V2P4-2.4-06 フィルタ全オフ"
    (is (re-find #"状態フィルタを1つ以上オンにしてください"
                 (html {:page :daily :narrow? true :fields [{:id 1}] :daily-statuses []}))))
  (testing "V2P4-2.5-01 添付は任意形式（capture 無し）"
    (let [h (html {:page :memos :narrow? true
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published" :can_edit true
                                       :author_email "a@example.com"}})]
      (is (re-find #"type=\"file\"" h))
      (is (nil? (re-find #"capture=" h)))
      (is (nil? (re-find #"accept=\"image" h)))))
  (testing "V2P4-5-01 文言"
    (ui/with-ui-lang {:ui-lang "ja"}
      (fn []
        (is (= "メモ" (get ui/messages :nav-bottom-memos)))
        (is (= "日次" (get ui/messages :nav-bottom-daily)))
        (is (= "指示" (get ui/messages :nav-bottom-orders)))
        (is (= "スマホでは状態を変えられません" (get ui/messages :daily-phone-readonly)))
        (is (= "日次一覧はスマホで開いてください" (get ui/messages :phone-daily-admin-pc)))
        (is (= "指示は利用者ログインで開けます" (get ui/messages :orders-admin-phone)))))
    (is (= "Memos" (get ui/messages-en :nav-bottom-memos)))
    (is (= "Daily" (get ui/messages-en :nav-bottom-daily)))
    (is (= "Orders" (get ui/messages-en :nav-bottom-orders))))
  (testing "V2P4-7-01 / V2P4-7-02 画面遷移"
    (let [login (ui/handle (assoc (ui/init-state) :page :login :kind "user" :narrow? true)
                           [:login-result {:ok true :email "a" :ui_lang "ja"}])
          to-daily (ui/handle (assoc (:state login) :session {:email "a"} :narrow? true)
                              [:path {:path "/daily" :search ""}])
          to-orders (ui/handle (assoc (:state to-daily) :session {:email "a"} :narrow? true
                                      :kind "user")
                               [:path {:path "/orders" :search ""}])]
      (is (= "/home" (second (first (:fx login)))))
      (is (= :daily (get-in to-daily [:state :page])))
      (is (= :api (ffirst (:fx to-daily))))
      (is (= :orders (get-in to-orders [:state :page])))))
  (testing "利用者狭幅の指示は読める"
    (let [h (html {:page :orders :narrow? true
                   :orders-sent [{:id 1 :work_date "2026-09-21" :work_name "田植え" :status "open"}]
                   :orders-received []})]
      (is (nil? (re-find #"この入口では使えません" h)))
      (is (re-find #"田植え" h))
      (is (re-find #"出した指示|受けた指示" h))))
  (testing "管理者の指示は案内のみ（forbidden にしない）"
    (let [r (ui/session-loaded
             (assoc (ui/init-state) :page :orders :kind "admin" :narrow? true
                    :session {:email "admin@example.com"})
             {:ok true :email "admin@example.com"})
          r-order (ui/session-loaded
                   (assoc (ui/init-state) :page :order :kind "admin" :narrow? true
                          :session {:email "admin@example.com"} :order-id "1")
                   {:ok true :email "admin@example.com"})
          h (html {:page :orders :kind "admin" :narrow? true
                   :flash {:error? false :text "指示は利用者ログインで開けます"}})]
      (is (= :html (ffirst (:fx r))))
      (is (re-find #"指示は利用者ログイン" (get-in r [:state :flash :text])))
      (is (= :html (ffirst (:fx r-order))))
      (is (nil? (get-in r-order [:state :order])))
      (is (re-find #"指示は利用者ログイン" h))
      (is (nil? (re-find #"この入口では使えません" h)))))
  (testing "V2P4-6-01 工程5以前の禁止経路・文言は混入しない"
    (is (nil? (http/match-api :post "/api/memos/link-gantt")))
    (let [h (html {:page :memos :narrow? true})]
      (is (not (re-find #"オフライン必須|ネイティブアプリ" h))))))

(deftest v2p4-api-and-handlers
  (tu/with-sys
    (fn [sys]
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-21T01:00:00Z"))]
        (let [{:keys [app asid usid uid]} (farm sys)
              _ (add-field app usid)
              tid (:id (make-title app usid "題A"))
              gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                  (row-body tid "狭幅予定"
                                                            "2026-09-21T08:00"
                                                            "2026-09-21T17:00")
                                                  "user" usid))
                          [:row :id])]
          (testing "V2P4-2.8-01 圃場0の daily GET"
            (let [pw0 (tu/invite-pw app asid "v2p4-nofield@example.com")
                  usid0 (tu/user-sid app "v2p4-nofield@example.com" pw0)
                  r0 (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                             {:range "around7"} "user" usid0))]
              (is (:ok r0))
              (is (= [] (:rows r0)))))
          (testing "V2P4-2.8-02 圃場0の作成は no_fields"
            (let [pw0 (tu/invite-pw app asid "v2p4-nofield2@example.com")
                  usid0 (tu/user-sid app "v2p4-nofield2@example.com" pw0)
                  r (tu/parse (tu/post-json app "/api/user/gantt"
                                            (row-body nil "x" "2026-09-21T08:00" "2026-09-21T09:00")
                                            "user" usid0))]
              (is (= "no_fields" (:code r)))))
          (testing "V2P4-2.8-03 管理者 daily"
            (let [r (tu/parse (tu/get-query app "/api/admin/gantt/daily"
                                            {:range "today" :statuses "not_started,in_progress"}
                                            "admin" asid))
                  row (first (filter #(= gid (:id %)) (:rows r)))
                  rall (tu/parse (tu/get-query app "/api/admin/gantt/daily"
                                               {:range "all" :statuses "not_started,in_progress,done"}
                                               "admin" asid))
                  bad-r (tu/parse (tu/get-query app "/api/admin/gantt/daily"
                                                {:range "month"} "admin" asid))
                  bad-s (tu/parse (tu/get-query app "/api/admin/gantt/daily"
                                                {:range "today" :statuses "weird"}
                                                "admin" asid))]
              (is (:ok r))
              (is (some? row))
              (is (= uid (:user_id row)))
              (is (= "v2p4@example.com" (:user_email row)))
              (is (= "狭幅予定" (:title row)))
              (is (:ok rall))
              (is (some #(= gid (:id %)) (:rows rall)))
              (is (= "range_invalid" (:code bad-r)))
              (is (= "statuses_invalid" (:code bad-s)))))
          (testing "V2P4-2.8-04 / 05 権限"
            (is (= "forbidden"
                   (:code (tu/parse (tu/get-query app "/api/admin/gantt/daily"
                                                  {:range "today"} "user" usid)))))
            (is (= "unauthorized"
                   (:code (tu/parse (tu/get-query app "/api/admin/gantt/daily"
                                                  {:range "today"}))))))
          (testing "V2P4-2.8-06 メモ専用 API 無し"
            (is (= [:memos-get] (http/match-api :get "/api/memos")))
            (is (nil? (http/match-api :get "/api/user/memos")))
            (is (nil? (http/match-api :get "/api/phone/memos"))))
          (testing "経路"
            (is (= [:admin-gantt-daily-get] (http/match-api :get "/api/admin/gantt/daily")))
            (is (= [:gantt-daily-get] (http/match-api :get "/api/user/gantt/daily")))))))))

(deftest v2p4-ui-session-flows
  (testing "session-loaded 狭幅日次・メモ"
    (let [user-daily (ui/session-loaded
                      (assoc (ui/init-state) :page :daily :kind "user" :narrow? true
                             :session {:email "a"}
                             :daily-statuses ["not_started"])
                      {:ok true :email "a"})
          admin-daily (ui/session-loaded
                       (assoc (ui/init-state) :page :daily :kind "admin" :narrow? true
                              :session {:email "a"}
                              :daily-statuses ["not_started"])
                       {:ok true :email "a"})
          admin-empty (ui/session-loaded
                       (assoc (ui/init-state) :page :daily :kind "admin" :narrow? true
                              :session {:email "a"}
                              :daily-statuses [])
                       {:ok true :email "a"})
          admin-pc (ui/session-loaded
                    (assoc (ui/init-state) :page :daily :kind "admin" :narrow? false
                           :session {:email "a"})
                    {:ok true :email "a"})
          memos (ui/session-loaded
                 (assoc (ui/init-state) :page :memos :kind "user" :narrow? true
                        :session {:email "a"})
                 {:ok true :email "a"})]
      (is (= :api (ffirst (:fx user-daily))))
      (is (str/includes? (nth (first (:fx user-daily)) 2) "/api/user/fields"))
      (is (= :api (ffirst (:fx admin-daily))))
      (is (str/includes? (nth (first (:fx admin-daily)) 2) "/api/admin/gantt/daily"))
      (is (= :html (ffirst (:fx admin-empty))))
      (is (empty? (get-in admin-empty [:state :daily-rows])))
      (is (= :html (ffirst (:fx admin-pc))))
      (is (= "/api/memos" (nth (first (:fx memos)) 2)))))
  (testing "fields-loaded 狭幅圃場0でも daily GET"
    (let [r (ui/fields-loaded
             (assoc (ui/init-state) :page :daily :kind "user" :narrow? true
                    :session {:email "a"}
                    :daily-statuses ["not_started" "in_progress"])
             {:ok true :fields []})]
      (is (= :api (ffirst (:fx r))))
      (is (str/includes? (nth (first (:fx r)) 2) "/api/user/gantt/daily"))
      (is (= 1 (count (:fx r)))))
    (let [r (ui/fields-loaded
             (assoc (ui/init-state) :page :daily :kind "user" :narrow? false
                    :session {:email "a"}
                    :daily-statuses ["not_started"])
             {:ok true :fields []})]
      (is (= :html (ffirst (:fx r))))))
  (testing "guarded ログイン済み狭幅はホーム"
    (let [r (ui/guarded (assoc (ui/init-state) :page :login :kind "user" :narrow? true
                               :session {:email "a"}))]
      (is (= "/home" (second (first (:fx r)))))))
  (testing "狭幅ホームは memos を読む"
    (let [r (ui/session-loaded
             (assoc (ui/init-state) :page :home :kind "user" :narrow? true
                    :session {:email "a"})
             {:ok true :email "a"})]
      (is (= "/api/memos" (nth (first (:fx r)) 2)))))
  (testing "cloverage 分岐: 日次空フィルタ・メモ置換"
    (let [h (html {:page :daily :narrow? false :fields [{:id 1}]
                   :daily-statuses ["done"] :daily-rows [] :daily-total 3})]
      (is (re-find #"選んだ期間・状態に重なる作業がありません" h))
      (is (re-find #"href=\"/works\"" h)))
    (let [hn (html {:page :daily :narrow? true :fields [{:id 1}]
                    :daily-statuses ["done"] :daily-rows [] :daily-total 3})]
      (is (re-find #"選んだ期間・状態に重なる作業がありません" hn))
      (is (nil? (re-find #"href=\"/works\"" hn))))
    (is (= [] (#'ui/replace-memo-in-list nil {:id 1 :body "a"})))
    (is (= [{:id 1 :body "b"}]
           (#'ui/replace-memo-in-list [{:id 1 :body "a"}] {:id 1 :body "b"})))
    (is (= [{:id 2 :body "a"}]
           (#'ui/replace-memo-in-list [{:id 2 :body "a"}] {:id 1 :body "b"})))))
