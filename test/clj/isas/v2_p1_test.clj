(ns isas.v2-p1-test
  "詳細試験仕様書_第2版_工程1 の項番に対応する自動試験。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [next.jdbc :as jdbc])
  (:import [java.time Instant]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "v2p1@example.com")
        usid (tu/user-sid app "v2p1@example.com" pw)
        uid (:id (db/find-user-by-email (:ds sys) "v2p1@example.com"))]
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

(deftest v2p1-screens-and-routes
  (testing "V2P1-2.1-01 / V2P1-2.2-01 / V2P1-5-01 広い画面の日次"
    (let [h (html {:page :daily
                   :fields [{:id 1 :name "北"}]
                   :daily-range "today"
                   :daily-statuses ["not_started" "in_progress"]
                   :daily-rows [{:id 1 :title "予定A" :start_at "2026-09-21T08:00"
                                 :end_at "2026-09-21T17:00" :execution_status "not_started"
                                 :field_ids []}]})]
      (is (re-find #">日次一覧<" h))
      (is (re-find #"daily-range" h))
      (is (re-find #"今日" h))
      (is (re-find #"直近7日" h))
      (is (re-find #"すべて" h))
      (is (re-find #"未着手" h))
      (is (re-find #"着手中" h))
      (is (re-find #"完了" h))
      (is (re-find #"実行状態" h))
      (is (re-find #"daily-list" h))
      (is (re-find #"予定A" h))
      (is (re-find #"set-daily-row-status" h))
      (is (= "日次一覧" (:daily-title ui/messages)))
      (is (= "状態フィルタを1つ以上オンにしてください" (:daily-filter-empty ui/messages)))))
  (testing "V2P1-2.1-02 未ログインは / へ"
    (let [fx (:fx (ui/handle (ui/init-state) [:path {:path "/daily" :search ""}]))]
      (is (= :restore-guest-lang (ffirst fx)))
      (is (= :session (ffirst (rest fx))))))
  (testing "V2P1-2.1-03 / V2P1-2.1-04 ホームの出口"
    (is (re-find #"<p><a data-nav href=\"/daily\">" (html {:page :home :fields [{:id 1}]})))
    (is (not (re-find #"<p><a data-nav href=\"/daily\">" (html {:page :home :fields []})))))
  (testing "V2P1-2.1-05 管理者ホームに日次は無い"
    (is (not (re-find #"/daily" (html {:page :home :kind "admin" :session {:email "a"}})))))
  (testing "V2P1-2.1-06 狭い画面は第2版工程4で閲覧専用"
    (let [h (html {:page :daily :narrow? true :fields [{:id 1}]
                   :daily-statuses ["not_started"] :daily-rows []})]
      (is (re-find #"スマホでは状態を変えられません" h))
      (is (re-find #"daily-list|daily-filter" h))
      (is (not (re-find #"set-daily-row-status" h)))))
  (testing "V2P1-2.1-07 /works 実行状態"
    (let [h (html {:page :works
                   :fields [{:id 1 :name "北"}]
                   :gantt-titles [{:id 10 :name "題A"}]
                   :gantt-rows [{:id 1 :title_id 10 :title "予定A" :start_at "2026-09-21T08:00"
                                 :end_at "2026-09-21T17:00" :execution_status "in_progress"
                                 :work_name nil :field_ids []}]
                   :gantt-selected 1})]
      (is (re-find #"着手中" h))
      (is (re-find #"works-execution-status" h))
      (is (re-find #"実行状態" h))
      (is (re-find #"未着手" h))))
  (testing "V2P1-2.1-08 /gantt 実行状態・サイクル無し"
    (let [h (html {:page :gantt
                   :place {:west 139.0 :south 35.0 :east 141.0 :north 37.0}
                   :fields [{:id 1 :name "北"}]
                   :gantt-titles [{:id 10 :name "題A"}]
                   :gantt-title-selected 10
                   :gantt-rows [{:id 1 :title_id 10 :title "予定A" :start_at "2026-09-21T08:00"
                                 :end_at "2026-09-21T17:00" :execution_status "done"
                                 :work_name "田植え" :field_ids [1]}]
                   :gantt-selected 1
                   :gantt-progress {:ok true :applicable true :percent 40}
                   :work-names ["田植え"]})]
      (is (re-find #"gantt-execution-status" h))
      (is (not (re-find #"cycle|クリックで状態" h)))))
  (testing "V2P1-2.2-03 フィルタ全オフ"
    (is (re-find #"状態フィルタを1つ以上オンにしてください"
                 (html {:page :daily :fields [{:id 1}] :daily-statuses [] :daily-rows []}))))
  (testing "V2P1-2.2-05 圃場0"
    (is (re-find #"圃場が1枚以上あるときだけ、日次一覧を使えます"
                 (html {:page :daily :fields []}))))
  (testing "経路と文言キー"
    (is (= :daily (:page (ui/route-for "/daily"))))
    (is (ui/needs-auth? :daily))
    (is (= "Daily list" (:daily-title ui/messages-en)))
    (is (= "Open the daily list on a computer" (:phone-daily ui/messages-en)))))

(deftest v2p1-api-and-rules
  (tu/with-sys
    (fn [sys]
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-21T01:00:00Z"))]
        (let [{:keys [app asid usid uid]} (farm sys)
              _ (add-field app usid)
              tid (:id (make-title app usid "題A"))]
          (testing "V2P1-2.5-02 POST は常に not_started"
            (let [r (tu/parse (tu/post-json app "/api/user/gantt"
                                            (assoc (row-body tid "新規" "2026-09-21T08:00" "2026-09-21T17:00")
                                                   :execution_status "done")
                                            "user" usid))]
              (is (:ok r))
              (is (= "not_started" (get-in r [:row :execution_status])))))
          (testing "V2P1-2.5-01 / V2P1-2.5-03 / V2P1-2.5-04 / V2P1-3.2-02 PUT"
            (let [gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                      (row-body tid "更新" "2026-09-21T09:00" "2026-09-21T12:00")
                                                      "user" usid))
                              [:row :id])
                  listed (tu/parse (tu/get-path app "/api/user/gantt" "user" usid))
                  row (first (filter #(= gid (:id %)) (:rows listed)))
                  put1 (tu/parse (tu/put-json app (str "/api/user/gantt/" gid)
                                              (assoc (row-body tid "更新" "2026-09-21T09:00" "2026-09-21T12:00")
                                                     :execution_status "in_progress")
                                              "user" usid))
                  put2 (tu/parse (tu/put-json app (str "/api/user/gantt/" gid)
                                              (row-body tid "更新" "2026-09-21T09:00" "2026-09-21T12:00")
                                              "user" usid))
                  put3 (tu/parse (tu/put-json app (str "/api/user/gantt/" gid)
                                              (assoc (row-body tid "更新" "2026-09-21T09:00" "2026-09-21T12:00")
                                                     :execution_status "done")
                                              "user" usid))
                  put4 (tu/parse (tu/put-json app (str "/api/user/gantt/" gid)
                                              (assoc (row-body tid "更新" "2026-09-21T09:00" "2026-09-21T12:00")
                                                     :execution_status "not_started")
                                              "user" usid))
                  bad (tu/parse (tu/put-json app (str "/api/user/gantt/" gid)
                                             (assoc (row-body tid "更新" "2026-09-21T09:00" "2026-09-21T12:00")
                                                    :execution_status "weird")
                                             "user" usid))]
              (is (= "not_started" (:execution_status row)))
              (is (= "in_progress" (get-in put1 [:row :execution_status])))
              (is (= "in_progress" (get-in put2 [:row :execution_status])))
              (is (= "done" (get-in put3 [:row :execution_status])))
              (is (= "not_started" (get-in put4 [:row :execution_status])))
              (is (= "execution_status_invalid" (:code bad)))))
          (testing "V2P1-2.5-05〜11 daily と重なり"
            (let [a (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                    (row-body tid "今日内" "2026-09-21T10:00" "2026-09-21T11:00")
                                                    "user" usid))
                            [:row :id])
                  b (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                    (row-body tid "境界終端" "2026-09-22T00:00" "2026-09-22T01:00")
                                                    "user" usid))
                            [:row :id])
                  c (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                    (row-body tid "境界開始" "2026-09-20T23:00" "2026-09-21T00:00")
                                                    "user" usid))
                            [:row :id])
                  d (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                    (row-body tid "7日内" "2026-09-18T08:00" "2026-09-18T09:00")
                                                    "user" usid))
                            [:row :id])
                  _ (tu/put-json app (str "/api/user/gantt/" a)
                                 (assoc (row-body tid "今日内" "2026-09-21T10:00" "2026-09-21T11:00")
                                        :execution_status "in_progress")
                                 "user" usid)
                  _ (tu/put-json app (str "/api/user/gantt/" d)
                                 (assoc (row-body tid "7日内" "2026-09-18T08:00" "2026-09-18T09:00")
                                        :execution_status "done")
                                 "user" usid)
                  today (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                                {:range "today"} "user" usid))
                  days7 (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                                {:range "days7" :statuses "not_started,in_progress,done"}
                                                "user" usid))
                  all (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                              {:range "all" :statuses "not_started,in_progress,done"}
                                              "user" usid))
                  empty (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                                {:range "today" :statuses ""} "user" usid))
                  bad-r (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                                {:range "month"} "user" usid))
                  bad-s (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                                {:range "today" :statuses "x"} "user" usid))
                  _ (tu/delete-path app (str "/api/user/gantt/" a) "user" usid)
                  after-del (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                                    {:range "today" :statuses "in_progress"}
                                                    "user" usid))]
              (is (:ok today))
              (is (some #(= "今日内" (:title %)) (:rows today)))
              (is (not (some #(= "境界終端" (:title %)) (:rows today))))
              (is (not (some #(= "境界開始" (:title %)) (:rows today))))
              (is (some #(= "7日内" (:title %)) (:rows days7)))
              (is (not (some #(= "境界終端" (:title %)) (:rows days7))))
              (is (:ok all))
              (is (some #(= "7日内" (:title %)) (:rows all)))
              (is (some #(= "境界終端" (:title %)) (:rows all)))
              (is (:ok empty))
              (is (= [] (:rows empty)))
              (is (= "range_invalid" (:code bad-r)))
              (is (= "statuses_invalid" (:code bad-s)))
              (is (not (some #(= a (:id %)) (:rows after-del))))
              (is (some? b))
              (is (some? c))))
          (testing "V2P1-2.5-12 / V2P1-2.5-13 圃場0と管理者"
            (let [pw0 (tu/invite-pw app asid "v2p1-nofield@example.com")
                  usid0 (tu/user-sid app "v2p1-nofield@example.com" pw0)
                  r0 (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                             {:range "today"} "user" usid0))
                  ra (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                             {:range "today"} "admin" asid))
                  post0 (tu/parse (tu/post-json app "/api/user/gantt"
                                                {:title "x" :start_at "2026-09-21T08:00"
                                                 :end_at "2026-09-21T09:00" :field_ids []}
                                                "user" usid0))]
              (is (:ok r0))
              (is (= [] (:rows r0)))
              (is (= "forbidden" (:code ra)))
              (is (= "no_fields" (:code post0)))))
          (testing "V2P1-3.2-03 ％非連動（progress は従来どおり）"
            (let [f (:field (tu/parse (tu/post-json app "/api/user/fields"
                                                    {:name "南" :geojson tu/square-east} "user" usid)))
                  gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                      (row-body tid "％" "2026-09-21T13:00" "2026-09-21T14:00"
                                                                "田植え" [(:id f)])
                                                      "user" usid))
                              [:row :id])
                  _ (tu/put-json app (str "/api/user/gantt/" gid)
                                 (assoc (row-body tid "％" "2026-09-21T13:00" "2026-09-21T14:00"
                                                  "田植え" [(:id f)])
                                        :execution_status "done")
                                 "user" usid)
                  p (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/progress") "user" usid))]
              (is (:ok p))
              (is (true? (:applicable p)))
              (is (contains? p :percent))))
          (testing "V2P1-4.1-01 マイグレーションで未知値を直す"
            (jdbc/execute! (:ds sys)
                           ["UPDATE gantt_rows SET execution_status = 'legacy' WHERE user_id = ?" uid])
            (db/migrate! (:ds sys))
            (let [rows (db/list-gantt-rows (:ds sys) uid)]
              (is (every? #(= "not_started" (:execution_status %)) rows)))))))))

(deftest v2p1-ui-handlers
  (testing "日次の読み込み・フィルタ・状態保存"
    (let [s0 (assoc (ui/init-state) :page :daily :kind "user" :session {:email "a"}
                     :fields [{:id 1}]
                     :daily-range "today"
                     :daily-statuses ["not_started" "in_progress"])
          loaded (ui/daily-loaded s0 {:ok true :rows [{:id 1 :title "A" :start_at "2026-09-21T08:00"
                                                      :end_at "2026-09-21T09:00"
                                                      :execution_status "not_started"
                                                      :field_ids []}]})
          fail (ui/daily-loaded s0 {:ok false :code "no_fields"})
          empty-st (ui/handle s0 [:submit {:act "set-daily-statuses" :form {}}])
          set-days7 (ui/handle (assoc (:state loaded) :daily-rows [{:id 1}])
                               [:submit {:act "set-daily-range" :form {:range "days7"}}])
          set-all (ui/handle (assoc (:state loaded) :daily-rows [{:id 1}])
                             [:submit {:act "set-daily-range" :form {:range "all"}}])
          set-st (ui/handle (:state loaded)
                            [:submit {:act "set-daily-statuses"
                                      :form {:status ["not_started" "done"]}}])
          save (ui/handle (assoc (:state loaded)
                                 :daily-rows [{:id 1 :title "A" :title_id 10
                                               :start_at "2026-09-21T08:00"
                                               :end_at "2026-09-21T09:00"
                                               :work_name nil :field_ids []
                                               :execution_status "not_started"}])
                          [:submit {:act "set-daily-row-status"
                                    :form {:id "1" :execution_status "done"}}])
          save-bad (ui/handle (:state loaded)
                              [:submit {:act "set-daily-row-status"
                                        :form {:id "99" :execution_status "done"}}])
          save-bad-st (ui/handle (assoc (:state loaded)
                                        :daily-rows [{:id 1 :title "A" :start_at "x" :end_at "y"
                                                      :field_ids []}])
                                 [:submit {:act "set-daily-row-status"
                                           :form {:id "1" :execution_status "nope"}}])
          saved-ok (ui/daily-status-save-result
                    (assoc (:state loaded) :daily-statuses ["done"])
                    {:ok true :row {:id 1}})
          saved-fail (ui/daily-status-save-result s0 {:ok false :code "gantt_not_found"})
          fields-empty (ui/fields-loaded s0 {:ok true :fields []})
          fields-ok (ui/fields-loaded s0 {:ok true :fields [{:id 1}]})
          fields-off (ui/fields-loaded (assoc s0 :daily-statuses []) {:ok true :fields [{:id 1}]})
          sess (ui/session-loaded (assoc (ui/init-state) :page :daily :kind "user" :narrow? false)
                                  {:ok true :email "a"})
          path (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user")
                          [:path {:path "/daily" :search ""}])]
      (is (= :html (ffirst (:fx loaded))))
      (is (= 1 (count (:daily-rows (:state loaded)))))
      (is (true? (get-in fail [:state :flash :error?])))
      (is (empty? (get-in empty-st [:state :daily-statuses])))
      (is (empty? (get-in empty-st [:state :daily-rows])))
      (is (= :html (ffirst (:fx empty-st))))
      (is (= "days7" (get-in set-days7 [:state :daily-range])))
      (is (= :api (ffirst (:fx set-days7))))
      (is (= "all" (get-in set-all [:state :daily-range])))
      (is (= :api (ffirst (:fx set-all))))
      (is (= ["not_started" "done"] (get-in set-st [:state :daily-statuses])))
      (is (= :api (ffirst (:fx save))))
      (is (re-find #"/api/user/gantt/1" (nth (first (:fx save)) 2)))
      (is (true? (get-in save-bad [:state :flash :error?])))
      (is (true? (get-in save-bad-st [:state :flash :error?])))
      (is (= :api (ffirst (:fx saved-ok))))
      (is (true? (get-in saved-fail [:state :flash :error?])))
      (is (empty? (get-in fields-empty [:state :daily-rows])))
      (is (= :api (ffirst (:fx fields-ok))))
      (is (= :html (ffirst (:fx fields-off))))
      (is (= :api (ffirst (:fx sess))))
      (is (= :daily (get-in path [:state :page])))
      (is (= "days7" (get-in path [:state :daily-range])))))
  (testing "code-message と gantt body"
    (is (= (:execution-status-invalid ui/messages)
           (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message "execution_status_invalid"))))
    (is (= (:range-invalid ui/messages)
           (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message "range_invalid"))))
    (is (= (:statuses-invalid ui/messages)
           (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message "statuses_invalid"))))
    (is (= "done" (:execution_status (#'ui/gantt-body-from-form
                                      {:title "t" :start_at "a" :end_at "b"
                                       :execution_status "done"} nil))))
    (is (= "未着手" (ui/with-ui-lang {:ui-lang "ja"} #(#'ui/execution-status-label "not_started"))))
    (is (= "着手中" (ui/with-ui-lang {:ui-lang "ja"} #(#'ui/execution-status-label "in_progress"))))
    (is (= "完了" (ui/with-ui-lang {:ui-lang "ja"} #(#'ui/execution-status-label "done"))))
    (is (re-find #"execution_status"
                 (ui/with-ui-lang {:ui-lang "ja"}
                   #(#'ui/execution-status-select-html "in_progress" "x"))))
    (is (= [] (#'ui/form-status-list {})))
    (is (= ["a"] (#'ui/form-status-list {:status "a"})))
    (is (= ["a" "b"] (#'ui/form-status-list {:status ["a" "b"]}))))
  (testing "V2P1-6-01 / V2P1-6-02 禁止機能が日次に無い"
    ;; 第2版工程3でナビにメモの出口が付いたので、日次の本体だけを見る。
    (let [h (str/replace (html {:page :daily :fields [{:id 1}] :daily-statuses ["not_started"]
                                :daily-rows []})
                         #"(?s)<nav>.*?</nav>" "")]
      (is (not (re-find #"メモ|担当|繰り返し|通知" h)))
      (is (nil? (http/match-api :get "/api/user/memos")))))
  (testing "time windows"
    (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-21T01:00:00Z"))]
      (is (= ["2026-09-21T00:00" "2026-09-22T00:00"] (time/tokyo-today-window)))
      (is (= ["2026-09-15T00:00" "2026-09-22T00:00"] (time/tokyo-days7-window)))
      (is (= ["2026-09-21T00:00" "2026-09-28T00:00"] (time/tokyo-week-window))))
    (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-19T15:00:00Z"))]
      (is (= ["2026-09-14T00:00" "2026-09-21T00:00"] (time/tokyo-week-window)))
      (is (= ["2026-09-14T00:00" "2026-09-21T00:00"] (time/tokyo-days7-window)))))
  (testing "cloverage 分岐"
    (is (= [] (#'ui/form-status-list {:status ""})))
    (is (= ["a" "b"] (#'ui/form-status-list {:status (list "a" "b")})))
    (is (= [] (:field_ids (#'ui/daily-row-put-body {:title "t" :start_at "a" :end_at "b"
                                                    :field_ids nil} "done"))))
    (is (re-find #"not_started"
                 (ui/with-ui-lang {:ui-lang "ja"}
                   #(#'ui/execution-status-select-html "weird" nil))))
    (is (false? (#'ui/daily-status-on? {:daily-statuses nil} "done")))
    (is (re-find #"range=days7" (#'ui/daily-query-path {:daily-range "nope" :daily-statuses nil})))
    (is (re-find #"該当する作業はありません"
                 (html {:page :daily :fields [{:id 1}]
                        :daily-range "nope" :daily-statuses ["not_started"] :daily-rows nil})))
    (is (re-find #"選んだ期間・状態に重なる作業がありません"
                 (html {:page :daily :fields [{:id 1}]
                        :daily-range "days7" :daily-statuses ["not_started"]
                        :daily-rows [] :daily-total 3})))
    (is (re-find #"作業タイトル"
                 (html {:page :works :fields [{:id 1}] :gantt-rows [] :gantt-titles []})))
    (is (re-find #"新しい作業を登録"
                 (html {:page :works :fields [{:id 1}] :gantt-rows [] :gantt-titles []})))
    (is (re-find #"手順: ①題名を選ぶ"
                 (html {:page :gantt :fields [{:id 1}] :gantt-titles [] :gantt-rows []})))
    (is (re-find #"状態フィルタを1つ以上"
                 (html {:page :daily :fields [{:id 1}]
                        :daily-range nil :daily-statuses nil :daily-rows nil})))
    (is (re-find #"range=days7" (#'ui/daily-query-path {})))
    (is (re-find #"range=days7" (#'ui/daily-query-path {:daily-range nil})))
    (is (re-find #"range=days7" (#'ui/daily-query-path {:daily-range "days7" :daily-statuses ["done"]})))
    (is (re-find #"range=all" (#'ui/daily-query-path {:daily-range "all"})))
    (is (re-find #"直近7日"
                 (html {:page :daily :fields [{:id 1}]})))
    (let [          ctx-ok (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                   :kind "user" :fields [{:id 1}]
                                   :daily-rows [])
                            [:daily-context-loaded {:ok true :rows [{:id 1} {:id 2}]}])
          ctx-nil-rows (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                         :kind "user" :fields [{:id 1}]
                                         :daily-rows [])
                                  [:daily-context-loaded {:ok true}])
          ctx-fail (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                     :kind "user" :fields [{:id 1}]
                                     :daily-total 9 :daily-rows [])
                              [:daily-context-loaded {:ok false :code "no_fields"}])
          ctx-fail-nil (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                         :kind "user" :fields [{:id 1}]
                                         :daily-rows [])
                                  [:daily-context-loaded {:ok false}])
          orphan (html {:page :gantt :fields [{:id 1}]
                        :gantt-titles [{:id 10 :name "題A"}]
                        :gantt-rows [{:id 1 :title_id nil :title "孤児"
                                      :start_at "2026-09-21T08:00" :end_at "2026-09-21T09:00"
                                      :execution_status "not_started" :field_ids []}]})
          orphan-nil-rows (html {:page :gantt :fields [{:id 1}]
                                 :gantt-titles [{:id 10 :name "題A"}]
                                 :gantt-title-selected 10
                                 :gantt-rows nil})]
      (is (= 2 (get-in ctx-ok [:state :daily-total])))
      (is (= :html (ffirst (:fx ctx-ok))))
      (is (zero? (get-in ctx-nil-rows [:state :daily-total])))
      (is (= 9 (get-in ctx-fail [:state :daily-total])))
      (is (zero? (get-in ctx-fail-nil [:state :daily-total])))
      (is (re-find #"題名のない作業はガントに出ません" orphan))
      (is (re-find #"手順: ①題名を選ぶ" orphan-nil-rows)))
    (let [no-sess (ui/session-loaded (assoc (ui/init-state) :page :daily :kind "user"
                                            :narrow? false)
                                     {:ok false})
          narrow (ui/session-loaded (assoc (ui/init-state) :page :daily :kind "user"
                                           :narrow? true :session {:email "a"})
                                    {:ok true :email "a"})
          wide (ui/session-loaded (assoc (ui/init-state) :page :daily :kind "user"
                                         :narrow? false)
                                  {:ok true :email "a"})
          empty-save (ui/daily-status-save-result
                      (assoc (ui/init-state) :page :daily :kind "user"
                             :session {:email "a"} :daily-statuses [])
                      {:ok true})
          via-handle (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                       :kind "user" :fields [{:id 1}]
                                       :daily-statuses ["not_started"])
                                [:daily-loaded {:ok true :rows nil}])
          via-save (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                     :kind "user" :daily-statuses ["done"])
                              [:daily-status-save-result {:ok true}])
          bad-range (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                      :kind "user" :fields [{:id 1}]
                                      :daily-statuses [])
                               [:submit {:act "set-daily-range" :form {:range "nope"}}])
          blank-id (ui/handle (assoc (ui/init-state) :page :daily :session {:email "a"}
                                     :kind "user"
                                     :daily-rows [{:id 1 :title "A" :start_at "a" :end_at "b"
                                                   :field_ids []}])
                              [:submit {:act "set-daily-row-status"
                                        :form {:id "" :execution_status "done"}}])
          fields-miss (ui/fields-loaded (dissoc (assoc (ui/init-state) :page :daily
                                                                   :session {:email "a"}
                                                                   :kind "user")
                                                :daily-statuses)
                                        {:ok true :fields [{:id 1}]})]
      (is (= :nav (ffirst (:fx no-sess))))
      (is (= :api (ffirst (:fx narrow))))
      (is (= :api (ffirst (:fx wide))))
      (is (= :html (ffirst (:fx empty-save))))
      (is (= :html (ffirst (:fx via-handle))))
      (is (= :api (ffirst (:fx via-save))))
      (is (= "today" (get-in bad-range [:state :daily-range])))
      (is (= :html (ffirst (:fx bad-range))))
      (is (true? (get-in blank-id [:state :flash :error?])))
      (is (= :html (ffirst (:fx fields-miss)))))
    (is (= "execution_status_invalid" (:code (#'gantt/normalize-execution-status nil))))
    (is (= {:ok true :statuses []} (#'gantt/parse-daily-statuses "")))
    (is (= {:ok true :statuses ["not_started" "in_progress"]} (#'gantt/parse-daily-statuses nil)))
    (tu/with-sys
      (fn [sys]
        (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-21T01:00:00Z"))]
          (let [{:keys [app usid uid]} (farm sys)
                _ (add-field app usid)
                tid (:id (make-title app usid "題B"))
                gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                    (row-body tid "枝" "2026-09-21T08:00" "2026-09-21T09:00")
                                                    "user" usid))
                            [:row :id])
                upd (gantt/update-row sys uid gid
                                      (row-body tid "枝" "2026-09-21T08:00" "2026-09-21T09:00"))]
            (is (:ok upd))
            (is (= "not_started" (get-in upd [:row :execution_status])))
            (is (:ok (gantt/list-daily sys uid {:range "today" :statuses ""})))))))))
