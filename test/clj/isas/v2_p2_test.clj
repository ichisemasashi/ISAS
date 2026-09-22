(ns isas.v2-p2-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui])
  (:import [java.time Instant]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "v2p2@example.com")
        usid (tu/user-sid app "v2p2@example.com" pw)
        uid (:id (db/find-user-by-email (:ds sys) "v2p2@example.com"))]
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

(deftest v2p2-screens-and-routes
  (testing "V2P2-2.1-01 /works 行編集に作業時間・チェック"
    (let [h (html {:page :works :fields [{:id 1 :name "北"}]
                   :gantt-rows [{:id 10 :title "作業A" :title_id 1
                                 :start_at "2026-09-21T08:00" :end_at "2026-09-21T17:00"
                                 :execution_status "not_started" :field_ids []}]
                   :gantt-titles [{:id 1 :name "題A"}]
                   :gantt-selected 10
                   :gantt-work-times [{:id 1 :gantt_id 10
                                       :start_at "2026-09-21T09:00" :end_at "2026-09-21T10:30"}]
                   :gantt-checklist-items [{:id 2 :gantt_id 10 :label "油量" :status "pending"}]})]
      (is (re-find #"works-work-times|作業時間" h))
      (is (re-find #"works-checklist|チェック項目" h))
      (is (re-find #"この時間を追加" h))
      (is (re-find #"この項目を追加" h))
      (is (re-find #"編集中:" h))
      (is (re-find #"基本情報を保存" h))
      (is (re-find #"1時間30分" h))
      (is (re-find #"data-confirm=.*作業時間を消します" h))
      (is (re-find #"data-confirm=.*チェック項目を消します" h))
      (is (re-find #"data-select=\"checklist-status\"" h))))
  (testing "V2P2-2.1-01b 未選択時は案内だけ"
    (let [h (html {:page :works :fields [{:id 1 :name "北"}]
                   :gantt-rows [{:id 10 :title "作業A" :title_id 1
                                 :start_at "2026-09-21T08:00" :end_at "2026-09-21T17:00"
                                 :execution_status "not_started" :field_ids []}]
                   :gantt-titles [{:id 1 :name "題A"}]})]
      (is (re-find #"編集する:" h))
      (is (re-find #"works-pick-hint" h))
      (is (re-find #"作業名のボタンを押すと" h))
      (is (nil? (re-find #"works-children" h)))))
  (testing "V2P2-2.1-02 /gantt 行編集"
    (let [h (html {:page :gantt :fields [{:id 1 :name "北"}]
                   :gantt-titles [{:id 1 :name "題A"}]
                   :gantt-title-selected 1
                   :gantt-rows [{:id 10 :title "作業A" :title_id 1
                                 :start_at "2026-09-21T08:00" :end_at "2026-09-21T17:00"
                                 :execution_status "not_started" :field_ids []}]
                   :gantt-selected 10
                   :gantt-work-times []
                   :gantt-checklist-items []})]
      (is (re-find #"gantt-work-times" h))
      (is (re-find #"gantt-checklist" h))))
  (testing "V2P2-2.1-03 /daily 要約と作業への出口"
    (let [h (html {:page :daily :fields [{:id 1}]
                   :daily-statuses ["not_started"]
                   :daily-rows [{:id 10 :title "作業A"
                                 :start_at "2026-09-21T08:00" :end_at "2026-09-21T17:00"
                                 :execution_status "not_started"
                                 :work_time_count 2 :checklist_done 1 :checklist_total 3}]})]
      (is (re-find #"作業時間 2 件" h))
      (is (re-find #"チェック 1/3" h))
      (is (re-find #"href=\"/works\"" h))
      (is (re-find #"作業を開く" h))))
  (testing "V2P2-2.3-01〜04 要約文言"
    (let [h0 (html {:page :daily :fields [{:id 1}]
                    :daily-statuses ["not_started"]
                    :daily-rows [{:id 1 :title "a" :start_at "2026-09-21T08:00"
                                  :end_at "2026-09-21T09:00" :execution_status "not_started"
                                  :work_time_count 0 :checklist_done 0 :checklist_total 0}]})]
      (is (re-find #"作業時間なし" h0))
      (is (re-find #"チェックなし" h0)))
    (is (re-find #"作業時間 1 件"
                 (html {:page :daily :fields [{:id 1}]
                        :daily-statuses ["not_started"]
                        :daily-rows [{:id 1 :title "a" :start_at "a" :end_at "b"
                                      :execution_status "not_started"
                                      :work_time_count 1 :checklist_done 0 :checklist_total 0}]}))))
  (testing "V2P2-2.1-04 狭い画面に変更 UI 無し"
    (doseq [page [:works :gantt :daily]]
      (let [h (html {:page page :narrow? true :fields [{:id 1}]})]
        (is (re-find #"パソコンで開いてください" h))
        (is (not (re-find #"works-work-times|gantt-work-times|作業時間を足す" h))))))
  (testing "V2P2-2.1-07 狭い画面ナビに使えない入口無し"
    (let [h (html {:page :home :narrow? true :fields [{:id 1}]})]
      (is (not (re-find #"href=\"/works\"" h)))
      (is (not (re-find #"href=\"/gantt\"" h)))
      (is (not (re-find #"href=\"/daily\"" h)))
      (is (not (re-find #"href=\"/fields\"" h)))
      (is (not (re-find #"href=\"/map\"" h)))
      (is (re-find #"href=\"/orders\"" h))))
  (testing "V2P2-2.1-08 flash near は操作箇所"
    (let [h (html {:page :works :fields [{:id 1}]
                   :gantt-selected 1
                   :gantt-rows [{:id 1 :title "t" :start_at "a" :end_at "b"
                                 :execution_status "not_started" :field_ids []}]
                   :flash {:error? true :text "日時が不正です" :near "works-work-time-add-box"}})]
      (is (re-find #"id=\"flash-works-work-time-add-box\"" h))
      (is (re-find #"日時が不正です" h))
      (is (not (re-find #"<p class=\"flash error\">日時が不正です</p>" h)))))
  (testing "V2P2-2.1-09 flash 成功も操作箇所寄り"
    (let [h (html {:page :works :fields [{:id 1}]
                   :gantt-selected 1
                   :gantt-rows [{:id 1 :title "t" :start_at "a" :end_at "b"
                                 :execution_status "not_started" :field_ids []}]
                   :flash {:error? false :text "作業時間を保存しました" :near "works-work-times"}})]
      (is (re-find #"id=\"flash-works-work-times\"" h))
      (is (re-find #"作業時間を保存しました" h))))
  (testing "V2P2-2.1-05 新規フォームに子 UI 無し"
    (let [h (html {:page :works :fields [{:id 1 :name "北"}]
                   :gantt-rows [] :gantt-titles [{:id 1 :name "題A"}]})]
      (is (re-find #"works-add-section" h))
      (is (not (re-find #"works-work-times|works-checklist" h)))))
  (testing "V2P2-5-01 文言キー"
    (ui/with-ui-lang {:ui-lang "ja"}
                     (fn []
                       (is (= "作業時間" (get ui/messages :work-times)))
                       (is (= "やった" (get ui/messages :checklist-done)))
                       (is (= "まだ" (get ui/messages :checklist-pending)))
                       (is (= "項目名を入れてください" (get ui/messages :label-required)))))
    (is (= "Work times" (get ui/messages-en :work-times))))
  (testing "V2P2-6-01 メモ等混入なし"
    (is (nil? (http/match-api :get "/api/user/memos")))
    (is (nil? (http/match-api :post "/api/user/assignees")))
    (let [h (html {:page :works :fields [{:id 1}] :gantt-selected 1
                   :gantt-rows [{:id 1 :title "t" :start_at "a" :end_at "b"
                                 :execution_status "not_started" :field_ids []}]})]
      (is (not (re-find #"担当|繰り返し|通知|メモ一覧" h))))))

(deftest v2p2-api-and-rules
  (tu/with-sys
    (fn [sys]
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-21T01:00:00Z"))]
        (let [{:keys [app asid usid uid]} (farm sys)
              _ (add-field app usid)
              tid (:id (make-title app usid "題A"))
              row (:row (tu/parse (tu/post-json app "/api/user/gantt"
                                                (row-body tid "作業A"
                                                          "2026-09-21T08:00"
                                                          "2026-09-21T17:00")
                                                "user" usid)))
              gid (:id row)]
          (testing "V2P2-4.1-01 / 4.2-01 表がある"
            (is (contains? (db/table-columns (:ds sys) "gantt_work_times") "start_at"))
            (is (contains? (db/table-columns (:ds sys) "gantt_checklist_items") "status")))
          (testing "V2P2-2.4-02 POST work-times 親外側・重なり可"
            (let [a (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/work-times")
                                            {:start_at "2026-09-20T22:00" :end_at "2026-09-21T01:00"}
                                            "user" usid))
                  b (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/work-times")
                                            {:start_at "2026-09-20T23:00" :end_at "2026-09-21T02:00"}
                                            "user" usid))]
              (is (:ok a))
              (is (:ok b))
              (let [listed (tu/parse (tu/get-path app "/api/user/gantt" "user" usid))
                    mine (first (filter #(= gid (:id %)) (:rows listed)))]
                (is (= "not_started" (:execution_status mine))))))
          (testing "V2P2-2.4-01 GET 並び"
            (let [r (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times") "user" usid))
                  times (:work_times r)]
              (is (:ok r))
              (is (= 2 (count times)))
              (is (= times (sort-by (juxt :start_at :id) times)))))
          (testing "V2P2-2.4-03 PUT 両方必須"
            (let [tid1 (:id (first (:work_times (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times") "user" usid)))))
                  r (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/work-times/" tid1)
                                           {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}
                                           "user" usid))]
              (is (:ok r))
              (is (= "2026-09-21T09:00" (get-in r [:work_time :start_at])))))
          (testing "V2P2-2.4-13 / 14 時刻エラー"
            (is (= "time_invalid"
                   (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/work-times")
                                                  {:start_at "bad" :end_at "2026-09-21T10:00"}
                                                  "user" usid)))))
            (is (= "time_order"
                   (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/work-times")
                                                  {:start_at "2026-09-21T10:00" :end_at "2026-09-21T10:00"}
                                                  "user" usid))))))
          (testing "V2P2-2.4-06 POST checklist status 省略→pending"
            (let [r (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items")
                                            {:label "油量確認"} "user" usid))]
              (is (:ok r))
              (is (= "pending" (get-in r [:checklist_item :status])))))
          (testing "V2P2-3.3-01 重複名可"
            (is (:ok (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items")
                                             {:label "油量確認" :status "done"} "user" usid)))))
          (testing "V2P2-2.4-05 GET checklist id 昇順"
            (let [items (:checklist_items (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/checklist-items") "user" usid)))]
              (is (= 2 (count items)))
              (is (= items (sort-by :id items)))))
          (testing "V2P2-2.4-07 PUT 部分更新"
            (let [cid (:id (first (:checklist_items (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/checklist-items") "user" usid)))))
                  r (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/checklist-items/" cid)
                                           {:status "done"} "user" usid))]
              (is (:ok r))
              (is (= "油量確認" (get-in r [:checklist_item :label])))
              (is (= "done" (get-in r [:checklist_item :status])))))
          (testing "V2P2-2.4-15 / 16 label・status エラー"
            (is (= "label_required"
                   (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items")
                                                  {:label "  "} "user" usid)))))
            (is (= "label_too_long"
                   (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items")
                                                  {:label (apply str (repeat 201 "あ"))}
                                                  "user" usid)))))
            (is (= "checklist_status_invalid"
                   (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items")
                                                  {:label "x" :status "nope"} "user" usid))))))
          (testing "V2P2-2.4-09 daily 要約"
            (let [r (tu/parse (tu/get-query app "/api/user/gantt/daily"
                                            {:range "all" :statuses "not_started,in_progress,done"}
                                            "user" usid))
                  row (first (filter #(= gid (:id %)) (:rows r)))]
              (is (:ok r))
              (is (= 2 (:work_time_count row)))
              (is (= 2 (:checklist_total row)))
              (is (pos? (:checklist_done row)))))
          (testing "V2P2-2.4-10 一覧 GET に要約なし"
            (let [r (tu/parse (tu/get-path app "/api/user/gantt" "user" usid))
                  row (first (filter #(= gid (:id %)) (:rows r)))]
              (is (nil? (:work_time_count row)))
              (is (nil? (:checklist_total row)))))
          (testing "V2P2-3.4-01 状態非連動"
            (is (= "not_started"
                   (:execution_status (first (filter #(= gid (:id %))
                                                     (:rows (tu/parse (tu/get-path app "/api/user/gantt" "user" usid)))))))))
          (testing "V2P2-2.4-04 DELETE work-time"
            (let [tid1 (:id (first (:work_times (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times") "user" usid)))))
                  r (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid "/work-times/" tid1) "user" usid))]
              (is (:ok r))
              (is (= 1 (count (:work_times (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times") "user" usid))))))))
          (testing "V2P2-2.4-08 DELETE checklist"
            (let [cid (:id (first (:checklist_items (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/checklist-items") "user" usid)))))
                  r (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid "/checklist-items/" cid) "user" usid))]
              (is (:ok r))
              (is (= 1 (count (:checklist_items (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/checklist-items") "user" usid))))))))
          (testing "V2P2-2.4-12 子 not found"
            (is (= "work_time_not_found"
                   (:code (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/work-times/99999")
                                                 {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}
                                                 "user" usid)))))
            (is (= "checklist_item_not_found"
                   (:code (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid "/checklist-items/99999")
                                                    "user" usid))))))
          (testing "V2P2-2.4-11 親 not found"
            (is (= "gantt_not_found"
                   (:code (tu/parse (tu/get-path app "/api/user/gantt/99999/work-times" "user" usid))))))
          (testing "V2P2-2.4-18 管理者 forbidden"
            (is (= "forbidden"
                   (:code (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times")
                                                 "admin" asid))))))
          (testing "V2P2-2.2-03 対象圃場無しでも可（すでに field_ids 空）"
            (is (:ok (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/work-times")
                                             {:start_at "2026-09-21T11:00" :end_at "2026-09-21T12:00"}
                                             "user" usid)))))
          (testing "V2P2-3.5-01 親ソフト削除で子も消える"
            (let [before-wt (count (:work_times (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times") "user" usid))))
                  _ (is (pos? before-wt))
                  del (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid) "user" usid))]
              (is (:ok del))
              (is (= "gantt_not_found"
                     (:code (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/work-times") "user" usid)))))
              (is (= "gantt_not_found"
                     (:code (tu/parse (tu/get-path app (str "/api/user/gantt/" gid "/checklist-items") "user" usid)))))))
          (testing "V2P2-3.5-02 題名削除で子も通常操作から消える"
            (let [tid2 (:id (make-title app usid "題B"))
                  row2 (:row (tu/parse (tu/post-json app "/api/user/gantt"
                                                     (row-body tid2 "作業B"
                                                               "2026-09-21T08:00"
                                                               "2026-09-21T17:00")
                                                     "user" usid)))
                  gid2 (:id row2)
                  _ (tu/parse (tu/post-json app (str "/api/user/gantt/" gid2 "/work-times")
                                            {:start_at "2026-09-21T08:00" :end_at "2026-09-21T09:00"}
                                            "user" usid))
                  _ (tu/parse (tu/post-json app (str "/api/user/gantt/" gid2 "/checklist-items")
                                            {:label "x"} "user" usid))
                  del (tu/parse (tu/delete-path app (str "/api/user/gantt/titles/" tid2) "user" usid))]
              (is (:ok del))
              (is (= "gantt_not_found"
                     (:code (tu/parse (tu/get-path app (str "/api/user/gantt/" gid2 "/work-times") "user" usid)))))))
          (testing "V2P2-2.4-17 圃場0"
            (let [pw2 (tu/invite-pw app asid "v2p2-nofield@example.com")
                  usid2 (tu/user-sid app "v2p2-nofield@example.com" pw2)]
              (is (= "no_fields"
                     (:code (tu/parse (tu/get-path app "/api/user/gantt/1/work-times" "user" usid2))))))))))))

(deftest v2p2-ui-handlers
  (testing "select-gantt-row で子 API を読む"
    (let [state (assoc (ui/init-state)
                       :page :works :session {:email "a@example.com"} :kind "user"
                       :fields [{:id 1}]
                       :gantt-rows [{:id 10 :title "t" :title_id 1
                                     :start_at "a" :end_at "b"
                                     :execution_status "not_started" :field_ids []}])
          r (ui/handle state [:submit {:act "select-gantt-row" :form {:id "10"}}])
          fx (:fx r)]
      (is (some #(= [:api "GET" "/api/user/gantt/10/work-times" nil :work-times-loaded] %) fx))
      (is (some #(= [:api "GET" "/api/user/gantt/10/checklist-items" nil :checklist-items-loaded] %) fx))))
  (testing "add/save/delete work-time fx"
    (let [state (assoc (ui/init-state) :gantt-selected 10 :session {:email "a"})]
      (is (= "/api/user/gantt/10/work-times"
             (nth (first (:fx (ui/handle state [:submit {:act "add-work-time"
                                                        :form {:gantt_id "10"
                                                               :start_at "2026-09-21T09:00"
                                                               :end_at "2026-09-21T10:00"}}])))
                  2)))
      (is (= "/api/user/gantt/10/work-times"
             (nth (first (:fx (ui/handle state [:submit {:act "add-work-time"
                                                        :form {:start_at "2026-09-21T09:00"
                                                               :end_at "2026-09-21T10:00"}}])))
                  2)))
      (is (str/includes? (nth (first (:fx (ui/handle state [:submit {:act "save-work-time"
                                                                    :form {:gantt_id "10" :id "3"
                                                                           :start_at "2026-09-21T09:00"
                                                                           :end_at "2026-09-21T10:00"}}])))
                              2)
                         "/work-times/3"))
      (is (str/includes? (nth (first (:fx (ui/handle state [:submit {:act "save-work-time"
                                                                    :form {:id "3"
                                                                           :start_at "2026-09-21T09:00"
                                                                           :end_at "2026-09-21T10:00"}}])))
                              2)
                         "/work-times/3"))
      (is (= :work-time-delete-result
             (last (first (:fx (ui/handle state [:submit {:act "delete-work-time"
                                                         :form {:gantt_id "10" :id "3"}}]))))))
      (is (= :work-time-delete-result
             (last (first (:fx (ui/handle state [:submit {:act "delete-work-time"
                                                         :form {:id "3"}}]))))))))
  (testing "checklist handlers"
    (let [state (assoc (ui/init-state) :gantt-selected 10 :session {:email "a"})]
      (is (= :checklist-item-save-result
             (last (first (:fx (ui/handle state [:submit {:act "add-checklist-item"
                                                         :form {:gantt_id "10" :label "油"}}]))))))
      (is (= :checklist-item-save-result
             (last (first (:fx (ui/handle state [:submit {:act "add-checklist-item"
                                                         :form {:label "油"}}]))))))
      (is (= :checklist-item-save-result
             (last (first (:fx (ui/handle state [:submit {:act "save-checklist-item"
                                                         :form {:id "4" :label "油" :status "done"}}]))))))
      (is (= :checklist-item-delete-result
             (last (first (:fx (ui/handle state [:submit {:act "delete-checklist-item"
                                                         :form {:id "4"}}]))))))
      (is (re-find #"項目名を入れてください"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "add-checklist-item"
                                                :form {:gantt_id "10" :label "  "}}])
                           [:state :flash :text])))))
  (testing "loaded / save-result"
    (let [s0 (assoc (ui/init-state) :page :works :session {:email "a"} :gantt-selected 10)
          s1 (:state (ui/handle s0 [:work-times-loaded {:ok true :work_times [{:id 1}]}]))]
      (is (= 1 (count (:gantt-work-times s1))))
      (is (= 2 (count (:fx (ui/handle (assoc s1 :gantt-selected 10)
                                      [:work-time-save-result {:ok true :work_time {:gantt_id 10}}]))))))
    (let [s0 (assoc (ui/init-state) :page :works :session {:email "a"} :gantt-selected 10)]
      (is (= 1 (count (:gantt-checklist-items
                       (:state (ui/handle s0 [:checklist-items-loaded
                                              {:ok true :checklist_items [{:id 1}]}])))))))
  (testing "code-message"
    (ui/with-ui-lang {:ui-lang "ja"}
      #(do
         (is (= (:label-required ui/messages) (ui/code-message "label_required")))
         (is (= (:work-time-not-found ui/messages) (ui/code-message "work_time_not_found")))
         (is (= (:checklist-item-not-found ui/messages) (ui/code-message "checklist_item_not_found")))
         (is (= (:checklist-status-invalid ui/messages) (ui/code-message "checklist_status_invalid"))))))
  (testing "cloverage 分岐"
    (is (nil? (#'ui/work-duration-minutes "bad" "2026-09-21T10:00")))
    (is (= "2時間30分"
           (ui/with-ui-lang {:ui-lang "ja"}
             #(#'ui/work-duration-label "2026-09-21T08:00" "2026-09-21T10:30"))))
    (is (= "45分"
           (ui/with-ui-lang {:ui-lang "ja"}
             #(#'ui/work-duration-label "2026-09-21T08:00" "2026-09-21T08:45"))))
    (is (= "2h 30m"
           (ui/with-ui-lang {:ui-lang "en"}
             #(#'ui/work-duration-label "2026-09-21T08:00" "2026-09-21T10:30"))))
    (is (= "1h"
           (ui/with-ui-lang {:ui-lang "en"}
             #(#'ui/work-duration-label "2026-09-21T08:00" "2026-09-21T09:00"))))
    (is (= "作業時間なし" (ui/with-ui-lang {:ui-lang "ja"} #(#'ui/daily-work-time-summary 0))))
    (is (= "チェックなし" (ui/with-ui-lang {:ui-lang "ja"} #(#'ui/daily-checklist-summary 0 0))))
    (is (re-find #"pending"
                 (ui/with-ui-lang {:ui-lang "ja"}
                   #(#'ui/checklist-status-select-html "weird" "x"))))
    (is (= "30m"
           (ui/with-ui-lang {:ui-lang "en"}
             #(#'ui/work-duration-label "2026-09-21T08:00" "2026-09-21T08:30"))))
    (is (= "1時間"
           (ui/with-ui-lang {:ui-lang "ja"}
             #(#'ui/work-duration-label "2026-09-21T08:00" "2026-09-21T09:00"))))
    (is (= (:label-too-long ui/messages)
           (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message "label_too_long"))))
    (let [state (assoc (ui/init-state) :session {:email "a"} :gantt-selected 10)]
      (is (get-in (ui/handle state [:work-times-loaded {:ok false :code "gantt_not_found"}])
                  [:state :flash :error?]))
      (is (get-in (ui/handle state [:checklist-items-loaded {:ok false :code "gantt_not_found"}])
                  [:state :flash :error?]))
      (is (get-in (ui/handle state [:work-time-save-result {:ok false :code "time_order"}])
                  [:state :flash :error?]))
      (is (get-in (ui/handle state [:checklist-item-save-result {:ok false :code "label_required"}])
                  [:state :flash :error?]))
      (is (= 2 (count (:fx (ui/handle state [:checklist-item-save-result
                                            {:ok true :checklist_item {:gantt_id 10}}])))))
      (is (re-find #"消しました"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:work-time-delete-result {:ok true}])
                           [:state :flash :text])))
      (is (re-find #"消しました"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:checklist-item-delete-result {:ok true}])
                           [:state :flash :text])))
      (is (get-in (ui/handle state [:work-time-delete-result {:ok false :code "work_time_not_found"}])
                  [:state :flash :error?]))
      (is (get-in (ui/handle state [:checklist-item-delete-result {:ok false :code "checklist_item_not_found"}])
                  [:state :flash :error?]))
      (is (re-find #"作業時間"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "save-work-time"
                                                :form {:gantt_id "" :id "" :start_at "a" :end_at "b"}}])
                           [:state :flash :text])))
      (is (re-find #"チェック"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "save-checklist-item"
                                                :form {:gantt_id "" :id "" :label "x" :status "done"}}])
                           [:state :flash :text])))
      (is (re-find #"チェック"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "delete-checklist-item"
                                                :form {:gantt_id "" :id ""}}])
                           [:state :flash :text])))
      (is (= :api (first (first (:fx (ui/handle state [:submit {:act "save-checklist-item"
                                                               :form {:gantt_id "10" :id "3"
                                                                      :label "油" :status "done"}}]))))))
      (is (re-find #"状態"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "save-checklist-item"
                                                :form {:gantt_id "10" :id "3"
                                                       :label "油" :status "nope"}}])
                           [:state :flash :text])))
      (is (= :api (first (first (:fx (ui/handle state [:submit {:act "delete-checklist-item"
                                                               :form {:gantt_id "10" :id "3"}}]))))))
      (is (re-find #"作業時間"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "delete-work-time"
                                                :form {:gantt_id "" :id ""}}])
                           [:state :flash :text])))
      (is (re-find #"予定"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "add-work-time"
                                                :form {:gantt_id "" :start_at "a" :end_at "b"}}])
                           [:state :flash :text])))
      (is (re-find #"日時"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "add-work-time"
                                                :form {:start_at "" :end_at ""}}])
                           [:state :flash :text])))
      (is (re-find #"日時"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "save-work-time"
                                                :form {:id "3" :start_at "" :end_at "b"}}])
                           [:state :flash :text])))
      (is (re-find #"予定"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "add-checklist-item"
                                                :form {:gantt_id "" :label "x"}}])
                           [:state :flash :text])))
      (is (re-find #"項目名"
                   (get-in (ui/handle (assoc state :ui-lang "ja")
                                      [:submit {:act "save-checklist-item"
                                                :form {:gantt_id "10" :id "3"
                                                       :label "  " :status "done"}}])
                           [:state :flash :text])))
      (is (= 2 (count (:fx (ui/handle state [:work-time-save-result
                                            {:ok true :work_time {}}]))))))
    (is (re-find #"pending"
                 (ui/with-ui-lang {:ui-lang "ja"}
                   #(#'ui/checklist-status-select-html nil nil))))
    (is (re-find #"まだ作業時間がありません"
                 (ui/with-ui-lang {:ui-lang "ja"}
                   #(#'ui/gantt-children-edit-html
                     {:gantt-work-times nil :gantt-checklist-items nil} 1 "works"))))
    (let [s0 (assoc (ui/init-state) :page :works :session {:email "a"} :kind "user"
                    :gantt-selected 10 :gantt-title-selected 1
                    :gantt-titles [{:id 1 :name "題"}]
                    :gantt-rows [{:id 10 :title_id 1 :title "t" :start_at "a" :end_at "b"
                                  :execution_status "not_started" :field_ids []}])
          r (ui/handle s0 [:gantt-loaded {:ok true
                                          :titles [{:id 1 :name "題"}]
                                          :rows [{:id 10 :title_id 1 :title "t" :start_at "a" :end_at "b"
                                                  :execution_status "not_started" :field_ids []}]}])]
      (is (= :api (first (first (:fx r))))))
    (let [s0 (assoc (ui/init-state) :page :works :session {:email "a"} :kind "user"
                    :gantt-selected 10 :gantt-title-selected 99
                    :gantt-work-times [{:id 1}] :gantt-checklist-items [{:id 2}])
          r (ui/handle s0 [:gantt-loaded {:ok true
                                          :titles [{:id 1 :name "題"}]
                                          :rows [{:id 10 :title_id nil :title "orphan" :start_at "a" :end_at "b"
                                                  :execution_status "not_started" :field_ids []}]}])]
      (is (= 10 (:gantt-selected (:state r)))))
    (let [state (assoc (ui/init-state) :session {:email "a"} :gantt-selected 10)]
      (is (= [] (:gantt-work-times (:state (ui/handle state [:work-times-loaded {:ok true}])))))
      (is (= [] (:gantt-checklist-items (:state (ui/handle state [:checklist-items-loaded {:ok true}])))))
      (is (= 2 (count (:fx (ui/handle state [:checklist-item-save-result {:ok true}])))))
      (is (= :api (first (first (:fx (ui/handle (dissoc state :gantt-selected)
                                               [:submit {:act "delete-work-time"
                                                         :form {:gantt_id "10" :id "3"}}]))))))
      (is (= :api (first (first (:fx (ui/handle (dissoc state :gantt-selected)
                                               [:submit {:act "add-checklist-item"
                                                         :form {:gantt_id "10" :label "油"}}]))))))
      (is (= :api (first (first (:fx (ui/handle (dissoc state :gantt-selected)
                                               [:submit {:act "save-checklist-item"
                                                         :form {:gantt_id "10" :id "3"
                                                                :label "油" :status "done"}}]))))))
      (is (= :api (first (first (:fx (ui/handle (dissoc state :gantt-selected)
                                               [:submit {:act "delete-checklist-item"
                                                         :form {:gantt_id "10" :id "3"}}])))))))
    (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :home
                              :gantt-selected 9 :gantt-work-times [1] :gantt-checklist-items [2])
                       [:path {:path "/works" :search ""}])]
      (is (= :works (get-in r [:state :page])))
      (is (nil? (get-in r [:state :gantt-selected])))
      (is (= [] (get-in r [:state :gantt-work-times]))))
    (is (= "label_required" (:code (#'gantt/normalize-checklist-label nil))))
    (is (= "checklist_status_invalid" (:code (#'gantt/normalize-checklist-status nil))))
    (is (some? (http/match-api :get "/api/user/gantt/1/work-times")))
    (is (some? (http/match-api :post "/api/user/gantt/1/checklist-items")))
    (is (some? (http/match-api :put "/api/user/gantt/1/work-times/2")))
    (is (some? (http/match-api :delete "/api/user/gantt/1/checklist-items/2")))
    (tu/with-sys
      (fn [sys]
        (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-21T01:00:00Z"))]
          (let [{:keys [app asid usid uid]} (farm sys)
                _ (add-field app usid)
                tid (:id (make-title app usid "題C"))
                gid (:id (:row (tu/parse (tu/post-json app "/api/user/gantt"
                                                       (row-body tid "x"
                                                                 "2026-09-21T08:00"
                                                                 "2026-09-21T17:00")
                                                       "user" usid))))
                wt (gantt/create-work-time sys uid gid
                                           {:start_at "2026-09-21T08:00" :end_at "2026-09-21T09:00"})
                ci (gantt/create-checklist-item sys uid gid {:label "a"})
                wtid (get-in wt [:work_time :id])
                cid (get-in ci [:checklist_item :id])]
            (is (= "time_invalid"
                   (:code (gantt/update-work-time sys uid gid wtid
                                                  {:start_at "bad" :end_at "2026-09-21T10:00"}))))
            (is (= "work_time_not_found"
                   (:code (gantt/soft-delete-work-time sys uid gid 99999))))
            (is (= "checklist_item_not_found"
                   (:code (gantt/update-checklist-item sys uid gid 99999 {:status "done"}))))
            (is (:ok (gantt/update-checklist-item sys uid gid cid {:label "油量"})))
            (is (= "油量" (get-in (gantt/update-checklist-item sys uid gid cid {:status "done"})
                                [:checklist_item :label])))
            (is (= "work_time_not_found"
                   (:code (gantt/update-work-time sys uid gid "abc"
                                                  {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}))))
            (is (= "checklist_item_not_found"
                   (:code (gantt/soft-delete-checklist-item sys uid gid "abc"))))
            (let [tid2 (:id (make-title app usid "題D"))
                  gid2 (:id (:row (tu/parse (tu/post-json app "/api/user/gantt"
                                                          (row-body tid2 "y"
                                                                    "2026-09-21T08:00"
                                                                    "2026-09-21T17:00")
                                                          "user" usid))))]
              (is (= "work_time_not_found"
                     (:code (gantt/update-work-time sys uid gid2 wtid
                                                    {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}))))
              (is (= "work_time_not_found"
                     (:code (gantt/soft-delete-work-time sys uid gid2 wtid))))
              (is (= "checklist_item_not_found"
                     (:code (gantt/update-checklist-item sys uid gid2 cid {:status "pending"}))))
              (is (= "checklist_item_not_found"
                     (:code (gantt/soft-delete-checklist-item sys uid gid2 cid)))))
            (is (:ok (gantt/soft-delete-work-time sys uid gid wtid)))
            (is (= "work_time_not_found"
                   (:code (gantt/update-work-time sys uid gid wtid
                                                  {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}))))
            (is (= "work_time_not_found"
                   (:code (gantt/soft-delete-work-time sys uid gid wtid))))
            (is (:ok (gantt/soft-delete-checklist-item sys uid gid cid)))
            (is (= "checklist_item_not_found"
                   (:code (gantt/update-checklist-item sys uid gid cid {:status "pending"}))))
            (is (= "checklist_item_not_found"
                   (:code (gantt/soft-delete-checklist-item sys uid gid cid))))
            (is (= "no_fields"
                   (:code (gantt/list-work-times sys 999999 1))))
            (is (= "no_fields"
                   (:code (gantt/create-work-time sys 999999 1
                                                  {:start_at "2026-09-21T08:00" :end_at "2026-09-21T09:00"}))))
            (is (= "no_fields"
                   (:code (gantt/list-checklist-items sys 999999 1))))
            (is (= "no_fields"
                   (:code (gantt/create-checklist-item sys 999999 1 {:label "x"}))))
            (is (= "label_required"
                   (:code (gantt/create-checklist-item sys uid gid {:label "  "}))))
            (is (= "checklist_status_invalid"
                   (:code (gantt/create-checklist-item sys uid gid {:label "z" :status "nope"}))))
            (is (= {:work_time_count 0 :checklist_done 0 :checklist_total 0}
                   (with-redefs [db/count-gantt-work-times (fn [& _] nil)
                                 db/count-gantt-checklist (fn [& _] {:done nil :total nil})]
                     (#'gantt/child-summary (:ds sys) gid))))
            (is (= {:total 0 :done 0}
                   (with-redefs [next.jdbc/execute-one! (fn [& _] {:total nil :done nil})]
                     (db/count-gantt-checklist (:ds sys) gid))))
            (is (nil? (http/match-api :patch "/api/user/gantt/1/work-times")))
            (is (nil? (http/match-api :get "/api/user/gantt/1/work-times/2")))
            (is (nil? (http/match-api :patch "/api/user/gantt/1/checklist-items")))
            (is (nil? (http/match-api :get "/api/user/gantt/1/checklist-items/2")))
            (is (= "work_time_not_found"
                   (:code (tu/parse (tu/delete-path app (str "/api/user/gantt/" gid "/work-times/99999")
                                                    "user" usid)))))
            (let [ci2 (gantt/create-checklist-item sys uid gid {:label "再"})
                  cid2 (get-in ci2 [:checklist_item :id])
                  bad (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/checklist-items/" cid2)
                                             {:label "  "} "user" usid))]
              (is (= "label_required" (:code bad)))
              (is (:ok (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/checklist-items/" cid2)
                                              {:label "再2" :status "done"} "user" usid))))
              (is (= "checklist_status_invalid"
                     (:code (gantt/update-checklist-item sys uid gid cid2 {:status "nope"})))))
            (with-redefs [http/read-body (fn [_] (throw (Exception. "boom")))]
              (is (= "time_invalid"
                     (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/work-times")
                                                    {} "user" usid)))))
              (is (= "time_invalid"
                     (:code (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/work-times/" wtid)
                                                   {} "user" usid)))))
              (is (= "label_required"
                     (:code (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items")
                                                    {} "user" usid)))))
              (is (= "label_required"
                     (:code (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/checklist-items/" cid)
                                                   {} "user" usid))))))
            (is (= "gantt_not_found"
                   (:code (gantt/list-work-times sys uid "abc"))))
            (is (= "gantt_not_found"
                   (:code (gantt/update-work-time sys uid "abc" 1
                                                  {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}))))
            (is (:ok (gantt/soft-delete-row sys uid gid)))
            (is (= "gantt_not_found" (:code (gantt/list-work-times sys uid gid))))
            (is (= "gantt_not_found" (:code (gantt/list-checklist-items sys uid gid))))
            (is (= "gantt_not_found"
                   (:code (gantt/create-work-time sys uid gid
                                                  {:start_at "2026-09-21T08:00" :end_at "2026-09-21T09:00"}))))
            (is (= "gantt_not_found"
                   (:code (gantt/create-checklist-item sys uid gid {:label "x"}))))
            (is (= "gantt_not_found"
                   (:code (gantt/update-work-time sys uid gid 1
                                                  {:start_at "2026-09-21T09:00" :end_at "2026-09-21T10:00"}))))
            (is (= "gantt_not_found"
                   (:code (gantt/soft-delete-work-time sys uid gid 1))))
            (is (= "gantt_not_found"
                   (:code (gantt/update-checklist-item sys uid gid 1 {:status "pending"}))))
            (is (= "gantt_not_found"
                   (:code (gantt/soft-delete-checklist-item sys uid gid 1))))))))))

(deftest v2p2-flash-near-and-gantt-save-branches
  (testing "work-time error near"
    (let [near (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                                 :session {:email "a"} :gantt-selected 1
                                 :gantt-rows [{:id 1 :title "t" :start_at "a" :end_at "b"
                                               :execution_status "not_started" :field_ids []}])
                          [:work-time-save-result {:ok false :code "time_invalid"}])]
      (is (= "works-work-times" (get-in near [:state :flash :near])))))
  (testing "gantt-save-result near on gantt add and works save"
    (let [added-gantt (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                                        :session {:email "a"} :gantt-selected nil)
                                 [:gantt-save-result
                                  {:ok true :row {:id 9 :title_id 1 :title "新"}}])
          added-works (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                                        :session {:email "a"} :gantt-selected nil)
                                 [:gantt-save-result
                                  {:ok true :row {:id 8 :title_id 1 :title "新作"}}])
          saved-works (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                                        :session {:email "a"} :gantt-selected 3)
                                 [:gantt-save-result
                                  {:ok true :row {:id 3 :title_id 1 :title "旧"}}])
          saved-gantt (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                                        :session {:email "a"} :gantt-selected 4)
                                 [:gantt-save-result
                                  {:ok true :row {:id 4 :title_id 1 :title "旧ガ"}}])
          err-gantt (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                                      :session {:email "a"})
                               [:gantt-save-result {:ok false :code "time_order"}])]
      (is (= "gantt-add-form" (get-in added-gantt [:state :flash :near])))
      (is (= "works-add-form" (get-in added-works [:state :flash :near])))
      (is (= "works-save-form" (get-in saved-works [:state :flash :near])))
      (is (= "gantt-save-form" (get-in saved-gantt [:state :flash :near])))
      (is (= "gantt-save-form" (get-in err-gantt [:state :flash :near])))))
  (testing "flash-ok-state blank near"
    (is (nil? (:near (:flash (#'ui/flash-ok-state (ui/init-state) "x" nil)))))
    (is (= "n" (:near (:flash (#'ui/flash-ok-state (ui/init-state) "x" "n"))))))
  (testing "gantt-delete-result near"
    (let [r (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                              :session {:email "a"} :gantt-selected 1)
                       [:gantt-delete-result {:ok true}])
          rw (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                               :session {:email "a"} :gantt-selected 1)
                        [:gantt-delete-result {:ok true}])]
      (is (= "gantt-edit-section" (get-in r [:state :flash :near])))
      (is (= "works-edit-section" (get-in rw [:state :flash :near])))))
  (testing "title save flash near"
    (let [ok (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                               :session {:email "a"})
                        [:gantt-title-save-result {:ok true :title {:id 2}}])
          bad (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                                :session {:email "a"})
                         [:gantt-title-save-result {:ok false :code "title_required"}])]
      (is (= "gantt-titles" (get-in ok [:state :flash :near])))
      (is (= "gantt-titles" (get-in bad [:state :flash :near])))))
  (testing "password/invite flash near"
    (let [pw (ui/handle (assoc (ui/init-state) :page :password :kind "user"
                               :session {:email "a"})
                        [:password-result {:ok false :code "password_wrong"}])
          inv (ui/handle (assoc (ui/init-state) :page :invite :kind "user"
                                :session {:email "a"})
                         [:invite-result {:ok false :code "invite_invalid_email"}])]
      (is (= "password-form-section" (get-in pw [:state :flash :near])))
      (is (= "invite-form-section" (get-in inv [:state :flash :near])))))
  (testing "flash-ok without near keeps page-top flash"
    (let [h (html {:page :home :flash {:error? false :text "ok"}})]
      (is (re-find #"<p class=\"flash ok\">ok</p>" h))
      (is (not (re-find #"id=\"flash-" h)))))
  (testing "children-near / pending-flash-near / delete error / order near"
    (is (= "gantt-work-times" (#'ui/children-near {:page :gantt} "-work-times")))
    (is (= "works-checklist" (#'ui/children-near {:page :works} "-checklist")))
    (let [pending (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                                    :session {:email "a"} :gantt-selected 1
                                    :pending-flash-near "works-work-time-add-box"
                                    :gantt-rows [{:id 1 :title "t" :start_at "a" :end_at "b"
                                                  :execution_status "not_started" :field_ids []}])
                             [:work-time-save-result {:ok false :code "time_invalid"}])
          pend-ok (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                                    :session {:email "a"} :gantt-selected 1
                                    :pending-flash-near "gantt-checklist-add-box")
                             [:checklist-item-save-result
                              {:ok true :checklist_item {:gantt_id 1}}])
          del-bad (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                                    :session {:email "a"})
                             [:gantt-delete-result {:ok false :code "gantt_not_found"}])
          ord-new (ui/handle (assoc (ui/init-state) :page :orders-new :kind "user"
                                    :session {:email "a"})
                             [:order-save-result {:ok false :code "time_order"}])
          ord-edit (ui/handle (assoc (ui/init-state) :page :order :kind "user"
                                     :session {:email "a"})
                              [:order-save-result {:ok false :code "no_fields"}])
          wn (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                               :session {:email "a"})
                        [:gantt-save-result {:ok false :code "work_name_required"}])
          err-works (ui/handle (assoc (ui/init-state) :page :works :kind "user"
                                      :session {:email "a"})
                               [:gantt-save-result {:ok false :code "time_order"}])
          del-gantt-bad (ui/handle (assoc (ui/init-state) :page :gantt :kind "user"
                                          :session {:email "a"})
                                   [:gantt-delete-result {:ok false :code "gantt_not_found"}])]
      (is (= "works-work-time-add-box" (get-in pending [:state :flash :near])))
      (is (= "gantt-checklist-add-box" (get-in pend-ok [:state :flash :near])))
      (is (= "works-edit-section" (get-in del-bad [:state :flash :near])))
      (is (= "gantt-edit-section" (get-in del-gantt-bad [:state :flash :near])))
      (is (= "orders-new-section" (get-in ord-new [:state :flash :near])))
      (is (nil? (get-in ord-edit [:state :flash :near])))
      (is (= "works-save-form" (get-in err-works [:state :flash :near])))
      (is (re-find #"作業名" (get-in wn [:state :flash :text])))))))
