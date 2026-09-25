(ns isas.review-rv001-test
  "レビュー記録票 RV-V2-001 の指摘 R-01〜R-04・R-08〜R-12 の回帰試験。"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [isas.db :as db]
            [isas.gantt :as gantt]
            [next.jdbc :as jdbc]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [ring.mock.request :as mock])
  (:import [java.time Instant]))

(defn- farm [sys email]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid email)
        usid (tu/user-sid app email pw)
        fid (:id (:field (tu/parse (tu/post-json app "/api/user/fields"
                                                 {:name "北" :geojson tu/square} "user" usid))))
        tid (:id (:title (tu/parse (tu/post-json app "/api/user/gantt/titles"
                                                 {:name "稲作"} "user" usid))))]
    {:app app :asid asid :usid usid :fid fid :tid tid}))

(defn- body [tid fid title s e]
  {:title_id tid :title title :start_at s :end_at e :work_name "草刈り" :field_ids [fid]})

(defn- daily [app kind sid range statuses]
  (:rows (tu/parse (tu/get-query app (if (= "admin" kind) "/api/admin/gantt/daily" "/api/user/gantt/daily")
                                 {:range range :statuses statuses} kind sid))))

(defn- titles [rows] (set (map :title rows)))

(defn- by-title [rows t] (first (filter #(= t (:title %)) rows)))

(deftest rv001-r01-r02-periods-and-overdue
  (tu/with-sys
    (fn [sys]
      ;; 2026-09-25（金）09:00 JST
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-25T00:00:00Z"))]
        (let [{:keys [app asid usid fid tid]} (farm sys "rv1@example.com")
              mk (fn [t s e & [st]]
                   (let [id (get-in (tu/parse (tu/post-json app "/api/user/gantt" (body tid fid t s e) "user" usid))
                                    [:row :id])]
                     (when st
                       (tu/put-json app (str "/api/user/gantt/" id "/status") {:execution_status st} "user" usid))
                     id))
              _ (mk "今日" "2026-09-25T08:00" "2026-09-25T17:00")
              _ (mk "日曜" "2026-09-27T08:00" "2026-09-27T17:00")
              _ (mk "5日前" "2026-09-20T08:00" "2026-09-20T17:00" "in_progress")
              _ (mk "8日前" "2026-09-17T08:00" "2026-09-17T17:00")
              _ (mk "先月" "2026-08-20T08:00" "2026-08-20T17:00")
              _ (mk "済" "2026-09-10T08:00" "2026-09-10T17:00" "done")
              _ (mk "来月" "2026-10-20T08:00" "2026-10-20T17:00")
              open "not_started,in_progress"
              every "not_started,in_progress,done"]
          (testing "R-01 今週（月曜始まり）に今週これからの作業が出る"
            (let [rows (daily app "user" usid "week" every)]
              (is (contains? (titles rows) "日曜"))
              (is (contains? (titles rows) "今日"))
              (is (not (contains? (titles rows) "来月")))
              (is (not (contains? (titles rows) "済")))))
          (testing "R-01 前後7日は過去7日と先7日を含む"
            (let [rows (daily app "user" usid "around7" every)]
              (is (contains? (titles rows) "日曜"))
              (is (contains? (titles rows) "5日前"))
              (is (not (contains? (titles rows) "来月")))))
          (testing "R-01 先週は先週の作業だけ（遅れは足さない）"
            (let [rows (daily app "user" usid "last_week" every)]
              (is (= #{"5日前" "8日前"} (titles rows)))
              (is (true? (:overdue (by-title rows "8日前"))))))
          (testing "R-02 今日に期限切れの未完了が遅れとして出る"
            (let [rows (daily app "user" usid "today" open)]
              (is (= #{"今日" "5日前" "8日前" "先月"} (titles rows)))
              (is (true? (:overdue (by-title rows "8日前"))))
              (is (true? (:overdue (by-title rows "先月"))))
              (is (true? (:overdue (by-title rows "5日前"))))
              (is (false? (:overdue (by-title rows "今日"))))))
          (testing "R-02 完了済みの過去作業は遅れにならず、今日にも出ない"
            (let [rows (daily app "user" usid "today" every)]
              (is (not (contains? (titles rows) "済")))))
          (testing "R-02 状態フィルタは遅れにも効く"
            (is (= #{"5日前"} (titles (filter :overdue (daily app "user" usid "week" "in_progress"))))))
          (testing "R-02 すべてでも遅れ印が付く"
            (let [rows (daily app "user" usid "all" every)]
              (is (true? (:overdue (by-title rows "先月"))))
              (is (false? (:overdue (by-title rows "済"))))
              (is (false? (:overdue (by-title rows "来月"))))))
          (testing "R-02 管理者の横断一覧も同じ規則"
            (let [rows (daily app "admin" asid "week" open)]
              (is (contains? (titles rows) "先月"))
              (is (true? (:overdue (by-title rows "先月"))))
              (is (= "rv1@example.com" (:user_email (by-title rows "先月"))))))
          (testing "R-02 終了ちょうどの時刻で遅れになる"
            (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-25T08:00:00Z"))]
              (is (true? (:overdue (by-title (daily app "user" usid "today" open) "今日")))))))))))

(deftest rv001-r03-status-api-and-version
  (tu/with-sys
    (fn [sys]
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-25T00:00:00Z"))]
        (let [{:keys [app asid usid fid tid]} (farm sys "rv3@example.com")
              row (:row (tu/parse (tu/post-json app "/api/user/gantt"
                                                (body tid fid "今日の作業" "2026-09-25T08:00" "2026-09-25T17:00")
                                                "user" usid)))
              gid (:id row)
              path (str "/api/user/gantt/" gid)]
          (testing "作成直後の版は 1"
            (is (= 1 (:version row))))
          (testing "R-03 日次の古い一覧から状態を保存しても、作業画面の変更は戻らない"
            (let [snap (by-title (daily app "user" usid "today" "not_started,in_progress") "今日の作業")
                  _ (tu/put-json app path (assoc (body tid fid "今日の作業(時刻変更)" "2026-09-25T13:00" "2026-09-25T18:00")
                                                 :version (:version snap))
                                 "user" usid)
                  r (tu/parse (tu/put-json app (str path "/status") {:execution_status "in_progress"} "user" usid))
                  after (db/find-gantt-row-by-id (:ds sys) gid)]
              (is (:ok r))
              (is (= "今日の作業(時刻変更)" (:title after)))
              (is (= "2026-09-25T13:00" (:start_at after)))
              (is (= "2026-09-25T18:00" (:end_at after)))
              (is (= "in_progress" (:execution_status after)))
              (is (= 3 (:version after)))
              (is (= 3 (get-in r [:row :version])))
              (is (= [fid] (get-in r [:row :field_ids])))))
          (testing "R-03 古い版での全体保存は gantt_conflict で、内容は変わらない"
            (let [r (tu/parse (tu/put-json app path (assoc (body tid fid "古い画面" "2026-09-25T08:00" "2026-09-25T17:00")
                                                           :version 2
                                                           :execution_status "not_started")
                                           "user" usid))
                  after (db/find-gantt-row-by-id (:ds sys) gid)]
              (is (= "gantt_conflict" (:code r)))
              (is (= "今日の作業(時刻変更)" (:title after)))
              (is (= "in_progress" (:execution_status after)))
              (is (= 3 (:version after)))))
          (testing "R-03 最新の版なら保存でき、版が進む"
            (let [r (tu/parse (tu/put-json app path (assoc (body tid fid "直した" "2026-09-25T08:00" "2026-09-25T17:00")
                                                           :version 3)
                                           "user" usid))]
              (is (:ok r))
              (is (= 4 (get-in r [:row :version])))
              (is (= "in_progress" (get-in r [:row :execution_status])))))
          (testing "R-03 版が数値でなければ gantt_conflict"
            (is (= "gantt_conflict"
                   (:code (tu/parse (tu/put-json app path (assoc (body tid fid "x" "2026-09-25T08:00" "2026-09-25T17:00")
                                                                 :version "abc")
                                                 "user" usid))))))
          (testing "R-03 版を省略した全体保存は従来どおり照合しない"
            (let [r (tu/parse (tu/put-json app path (body tid fid "版なし" "2026-09-25T08:00" "2026-09-25T17:00")
                                           "user" usid))]
              (is (:ok r))
              (is (= 5 (get-in r [:row :version])))))
          (testing "状態 API の入力エラー"
            (is (= "execution_status_invalid"
                   (:code (tu/parse (tu/put-json app (str path "/status") {:execution_status "weird"} "user" usid)))))
            (is (= "execution_status_invalid"
                   (:code (tu/parse (tu/put-json app (str path "/status") {} "user" usid)))))
            (is (= "gantt_not_found"
                   (:code (tu/parse (tu/put-json app "/api/user/gantt/99999/status"
                                                 {:execution_status "done"} "user" usid)))))
            (is (= "gantt_not_found"
                   (:code (gantt/update-row-status sys (:id (db/find-user-by-email (:ds sys) "rv3@example.com"))
                                                   "abc" {:execution_status "done"}))))
            (is (= "execution_status_invalid"
                   (:code (tu/parse (app (tu/as-user (-> (mock/request :put (str path "/status"))
                                                         (mock/content-type "application/json")
                                                         (mock/body "{"))
                                                     "user" usid)))))))
          (testing "状態 API は他人・削除済み・圃場0・未ログインを拒む"
            (let [pw2 (tu/invite-pw app asid "rv3-other@example.com")
                  usid2 (tu/user-sid app "rv3-other@example.com" pw2)
                  _ (tu/post-json app "/api/user/fields" {:name "南" :geojson tu/square} "user" usid2)
                  pw0 (tu/invite-pw app asid "rv3-zero@example.com")
                  usid0 (tu/user-sid app "rv3-zero@example.com" pw0)]
              (is (= "gantt_not_found"
                     (:code (tu/parse (tu/put-json app (str path "/status") {:execution_status "done"} "user" usid2)))))
              (is (= "no_fields"
                     (:code (tu/parse (tu/put-json app (str path "/status") {:execution_status "done"} "user" usid0)))))
              (is (= "unauthorized"
                     (:code (tu/parse (tu/put-json app (str path "/status") {:execution_status "done"})))))
              (tu/delete-path app path "user" usid)
              (is (= "gantt_not_found"
                     (:code (tu/parse (tu/put-json app (str path "/status") {:execution_status "done"} "user" usid))))))))))))

(deftest rv001-r03-ui
  (testing "日次の状態保存は状態 API に状態だけを送る"
    (let [s (assoc (ui/init-state) :page :daily :kind "user" :session {:email "a"}
                   :fields [{:id 1}]
                   :daily-rows [{:id 7 :title "A" :title_id 1 :start_at "a" :end_at "b"
                                 :field_ids [1] :execution_status "not_started" :version 3}])
          [op method path payload] (first (:fx (ui/handle s [:submit {:act "set-daily-row-status"
                                                                     :form {:id "7" :execution_status "done"}}])))]
      (is (= [:api "PUT" "/api/user/gantt/7/status"] [op method path]))
      (is (= {:execution_status "done"} payload))))
  (testing "作業画面の保存は読み込んだ版を送る"
    (let [s (assoc (ui/init-state) :page :works :kind "user" :session {:email "a"}
                   :fields [{:id 1}] :gantt-selected 7
                   :gantt-rows [{:id 7 :title "A" :title_id 1 :start_at "2026-09-25T08:00"
                                 :end_at "2026-09-25T17:00" :field_ids [] :execution_status "not_started"
                                 :version 3}])
          form {:id "7" :title "A" :start_at "2026-09-25T08:00" :end_at "2026-09-25T17:00"}
          with-v (nth (first (:fx (ui/handle s [:submit {:act "save-gantt-row" :form form}]))) 3)
          without-v (nth (first (:fx (ui/handle (assoc s :gantt-rows [])
                                                [:submit {:act "save-gantt-row" :form form}]))) 3)]
      (is (= 3 (:version with-v)))
      (is (not (contains? without-v :version)))))
  (testing "衝突したら案内を出して最新を読み込み直す"
    (let [s (assoc (ui/init-state) :page :works :kind "user" :session {:email "a"} :ui-lang "ja")
          r (ui/gantt-save-result s {:ok false :code "gantt_conflict"})
          other (ui/gantt-save-result s {:ok false :code "time_order"})]
      (is (= [:api "GET" "/api/user/gantt" nil :gantt-loaded] (first (:fx r))))
      (is (true? (get-in r [:state :flash :error?])))
      (is (re-find #"ほかの画面" (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message "gantt_conflict"))))
      (is (re-find #"another screen" (ui/with-ui-lang {:ui-lang "en"} #(ui/code-message "gantt_conflict"))))
      (is (= :html (ffirst (:fx other))))))
  (testing "日次一覧に遅れ印と5つの期間が出る"
    (let [h (tu/page-html {:page :daily :kind "user" :session {:email "a"} :fields [{:id 1}]
                           :daily-statuses ["not_started"]
                           :daily-rows [{:id 1 :title "遅れA" :start_at "2026-09-20T08:00"
                                         :end_at "2026-09-20T17:00" :execution_status "not_started"
                                         :field_ids [] :overdue true}
                                        {:id 2 :title "普通B" :start_at "2026-09-25T08:00"
                                         :end_at "2026-09-25T17:00" :execution_status "not_started"
                                         :field_ids [] :overdue false}]})]
      (is (= 1 (count (re-seq #"daily-item-overdue" h))))
      (is (re-find #"遅れ</span> <span class=\"daily-item-title\">遅れA" h))
      (doseq [v ["today" "week" "last_week" "around7" "all"]]
        (is (re-find (re-pattern (str "<option value=\"" v "\"")) h))))))

(deftest rv001-r04-no-fields-daily
  (testing "R-04 圃場0枚はスマホでも日次一覧を出さず案内だけ"
    (let [h (tu/page-html {:page :daily :kind "user" :narrow? true :session {:email "a"} :fields []
                           :daily-statuses ["not_started"] :daily-rows []})]
      (is (re-find #"圃場が1枚以上あるときだけ、日次一覧を使えます" h))
      (is (nil? (re-find #"daily-filter|daily-list" h)))))
  (testing "R-04 圃場0枚は日次 API を呼ばない"
    (let [r (ui/fields-loaded (assoc (ui/init-state) :page :daily :kind "user" :narrow? true
                                     :session {:email "a"} :daily-statuses ["not_started"])
                              {:ok true :fields []})]
      (is (= :html (ffirst (:fx r))))
      (is (= 1 (count (:fx r))))))
  (testing "R-04 圃場があれば日次一覧と作業一覧を読む"
    (let [r (ui/fields-loaded (assoc (ui/init-state) :page :daily :kind "user" :narrow? true
                                     :session {:email "a"} :daily-statuses ["not_started"])
                              {:ok true :fields [{:id 1}]})]
      (is (= [:daily-loaded :daily-context-loaded] (map #(nth % 4) (:fx r))))))
  (testing "R-04 管理者は圃場がなくても横断一覧を出す"
    (let [h (tu/page-html {:page :daily :kind "admin" :narrow? true :session {:email "a"} :fields []
                           :daily-statuses ["not_started"] :daily-rows []})]
      (is (nil? (re-find #"圃場が1枚以上" h))))))

(defn- daily-state [row]
  (assoc (ui/init-state) :page :daily :kind "user" :session {:email "a"} :ui-lang "ja"
         :fields [{:id 1}] :daily-statuses ["not_started" "in_progress" "done"]
         :daily-rows [(merge {:id 7 :title "A" :title_id 1 :start_at "2026-09-25T08:00"
                              :end_at "2026-09-25T17:00" :field_ids [1]
                              :execution_status "not_started" :version 1}
                             row)]))

(defn- flash-text [r] (get-in r [:state :flash :text]))

(deftest rv001-r08-checklist-left
  (testing "R-08 日次で完了にしたときチェックが残っていれば注意を出す"
    (let [s (:state (ui/handle (daily-state {:checklist_done 1 :checklist_total 3})
                               [:submit {:act "set-daily-row-status" :form {:id "7" :execution_status "done"}}]))
          r (ui/daily-status-save-result s {:ok true})]
      (is (= 2 (:checklist-left s)))
      (is (= "実行状態を保存しました。完了にしましたが、まだのチェックが 2 件あります。作業を開いて確認してください"
             (flash-text r)))
      (is (not (contains? (:state r) :checklist-left)))))
  (testing "R-08 英語の注意"
    (let [s (:state (ui/handle (assoc (daily-state {:checklist_done 0 :checklist_total 1}) :ui-lang "en")
                               [:submit {:act "set-daily-row-status" :form {:id "7" :execution_status "done"}}]))
          r (ui/with-ui-lang {:ui-lang "en"} #(ui/daily-status-save-result s {:ok true}))]
      (is (= "Execution status saved. Marked done, but 1 checklist item(s) are still pending. Open the work to check them"
             (flash-text r)))))
  (testing "R-08 チェックが残っていない・完了以外・チェックなしは注意しない"
    (doseq [[row st] [[{:checklist_done 2 :checklist_total 2} "done"]
                      [{:checklist_done 0 :checklist_total 2} "in_progress"]
                      [{} "done"]]]
      (let [s (:state (ui/handle (daily-state row)
                                 [:submit {:act "set-daily-row-status" :form {:id "7" :execution_status st}}]))]
        (is (= "実行状態を保存しました" (flash-text (ui/daily-status-save-result s {:ok true})))))))
  (testing "R-08 保存に失敗したら注意を持ち越さない"
    (let [s (:state (ui/handle (daily-state {:checklist_done 0 :checklist_total 2})
                               [:submit {:act "set-daily-row-status" :form {:id "7" :execution_status "done"}}]))
          r (ui/daily-status-save-result s {:ok false :code "gantt_not_found"})]
      (is (true? (get-in r [:state :flash :error?])))
      (is (not (contains? (:state r) :checklist-left)))))
  (testing "R-08 日次一覧は完了でチェック残りの行にだけ印を出す"
    (let [h (tu/page-html {:page :daily :kind "user" :session {:email "a"} :fields [{:id 1}]
                           :daily-statuses ["done" "in_progress"]
                           :daily-rows [{:id 1 :title "残りあり" :execution_status "done" :field_ids []
                                         :start_at "2026-09-25T08:00" :end_at "2026-09-25T17:00"
                                         :checklist_done 1 :checklist_total 2}
                                        {:id 2 :title "全部済" :execution_status "done" :field_ids []
                                         :start_at "2026-09-25T08:00" :end_at "2026-09-25T17:00"
                                         :checklist_done 2 :checklist_total 2}
                                        {:id 3 :title "作業中" :execution_status "in_progress" :field_ids []
                                         :start_at "2026-09-25T08:00" :end_at "2026-09-25T17:00"
                                         :checklist_done 0 :checklist_total 2}]})]
      (is (= 1 (count (re-seq #"daily-item-checklist-left" h))))
      (is (re-find #"チェック残り</span> <span class=\"daily-item-title\">残りあり" h))))
  (testing "R-08 作業画面で完了を保存したときも、選択中の作業のチェック残りを注意する"
    (let [base (assoc (ui/init-state) :page :works :kind "user" :session {:email "a"} :ui-lang "ja"
                      :fields [{:id 1}] :gantt-selected 7
                      :gantt-rows [{:id 7 :title "A" :title_id 1 :start_at "2026-09-25T08:00"
                                    :end_at "2026-09-25T17:00" :field_ids [] :execution_status "in_progress"
                                    :version 2}]
                      :gantt-checklist-items [{:id 1 :label "x" :status "done"}
                                              {:id 2 :label "y" :status "pending"}])
          form {:id "7" :title "A" :title_id "1" :start_at "2026-09-25T08:00" :end_at "2026-09-25T17:00"
                :execution_status "done"}
          s (:state (ui/handle base [:submit {:act "save-gantt-row" :form form}]))
          ok (ui/gantt-save-result s {:ok true :row {:id 7 :title_id 1}})
          ng (ui/gantt-save-result s {:ok false :code "gantt_conflict"})
          not-done (:state (ui/handle base [:submit {:act "save-gantt-row"
                                                     :form (assoc form :execution_status "in_progress")}]))]
      (is (= 1 (:checklist-left s)))
      (is (= "作業を保存しました。完了にしましたが、まだのチェックが 1 件あります。作業を開いて確認してください"
             (flash-text ok)))
      (is (not (contains? (:state ok) :checklist-left)))
      (is (not (contains? (:state ng) :checklist-left)))
      (is (nil? (:checklist-left not-done)))
      (is (= "作業を保存しました" (flash-text (ui/gantt-save-result not-done {:ok true :row {:id 7 :title_id 1}}))))))
  (testing "R-08 状態は連動させない（チェック残りがあっても完了で保存できる）"
    (tu/with-sys
      (fn [sys]
        (let [{:keys [app usid fid tid]} (farm sys "rv8@example.com")
              gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                  (body tid fid "チェック付き" "2026-09-25T08:00" "2026-09-25T17:00")
                                                  "user" usid))
                          [:row :id])
              c (tu/parse (tu/post-json app (str "/api/user/gantt/" gid "/checklist-items") {:label "片付け"} "user" usid))
              r (tu/parse (tu/put-json app (str "/api/user/gantt/" gid "/status") {:execution_status "done"} "user" usid))]
          (is (= "pending" (get-in c [:checklist_item :status])))
          (is (:ok r))
          (is (= "done" (:execution_status (db/find-gantt-row-by-id (:ds sys) gid)))))))))

(deftest rv001-r09-work-time-today
  (tu/with-sys
    (fn [sys]
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-25T00:00:00Z"))]
        (let [{:keys [app asid usid fid tid]} (farm sys "rv9@example.com")
              mk (fn [t]
                   (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                   (body tid fid t "2026-09-24T08:00" "2026-09-26T17:00")
                                                   "user" usid))
                           [:row :id]))
              wt (fn [gid s e]
                   (tu/post-json app (str "/api/user/gantt/" gid "/work-times") {:start_at s :end_at e} "user" usid))
              g-today (mk "稲刈り今日やった")
              g-yday (mk "稲刈り昨日だけ")
              g-none (mk "稲刈り未記録")
              _ (wt g-today "2026-09-24T08:00" "2026-09-24T12:00")
              _ (wt g-today "2026-09-25T06:00" "2026-09-25T08:00")
              _ (wt g-yday "2026-09-24T13:00" "2026-09-25T00:00")
              del-id (get-in (tu/parse (wt g-yday "2026-09-25T06:00" "2026-09-25T07:00")) [:work_time :id])
              _ (tu/delete-path app (str "/api/user/gantt/" g-yday "/work-times/" del-id) "user" usid)
              rows (daily app "user" usid "week" "not_started,in_progress")]
          (testing "R-09 今日と重なる作業時間の件数を返す"
            (is (= 1 (:work_time_today (by-title rows "稲刈り今日やった"))))
            (is (= 2 (:work_time_count (by-title rows "稲刈り今日やった"))))
            (is (some? del-id))
            (is (= 0 (:work_time_today (by-title rows "稲刈り昨日だけ"))))
            (is (= 1 (:work_time_count (by-title rows "稲刈り昨日だけ"))))
            (is (= 0 (:work_time_today (by-title rows "稲刈り未記録"))))
            (is (some? g-none)))
          (testing "R-09 管理者の横断一覧も同じ"
            (is (= 1 (:work_time_today (by-title (daily app "admin" asid "week" "not_started") "稲刈り今日やった")))))
          (testing "R-09 日次一覧は今日の作業時間がある行にだけ印を出す"
            (let [h (tu/page-html {:page :daily :kind "user" :session {:email "a"} :fields [{:id 1}]
                                   :daily-statuses ["not_started"] :daily-rows rows})]
              (is (= 1 (count (re-seq #"daily-item-today-time" h))))
              (is (re-find #"今日の作業時間あり" h))
              (is (re-find #"Work time logged today"
                           (ui/with-ui-lang {:ui-lang "en"}
                             #(tu/page-html {:page :daily :kind "user" :session {:email "a"} :ui-lang "en"
                                             :fields [{:id 1}] :daily-statuses ["not_started"]
                                             :daily-rows rows})))))))))))

(def ^:private win
  {:now "2026-09-25T09:00"
   :today ["2026-09-25T00:00" "2026-09-26T00:00"]
   :week ["2026-09-21T00:00" "2026-09-28T00:00"]
   :last_week ["2026-09-14T00:00" "2026-09-21T00:00"]
   :around7 ["2026-09-18T00:00" "2026-10-03T00:00"]})

(defn- wrow [id title s e st]
  {:id id :title title :title_id nil :start_at s :end_at e :execution_status st :field_ids [] :version 1})

(def ^:private works-rows
  [(wrow 1 "先月の遅れ" "2026-08-20T08:00" "2026-08-20T17:00" "not_started")
   (wrow 2 "先週済" "2026-09-16T08:00" "2026-09-16T17:00" "done")
   (wrow 3 "今日" "2026-09-25T08:00" "2026-09-25T17:00" "in_progress")
   (wrow 4 "日曜" "2026-09-27T08:00" "2026-09-27T17:00" "not_started")
   (wrow 5 "来月" "2026-10-20T08:00" "2026-10-20T17:00" "not_started")])

(defn- works-state [& kvs]
  (apply assoc (ui/init-state) :page :works :kind "user" :session {:email "a"} :ui-lang "ja"
         :fields [{:id 1}] :gantt-rows works-rows :gantt-windows win kvs))

(defn- listed [h]
  (set (map second (re-seq #"class=\"work-open-btn\" id=\"work-btn-\d+\">編集する: ([^<（]+)" h))))

(deftest rv001-r10-open-work-and-picker
  (testing "R-10 日次の「作業を開く」はその作業を指す"
    (is (re-find #"href=\"/works\?id=7\""
                 (tu/page-html {:page :daily :kind "user" :session {:email "a"} :fields [{:id 1}]
                                :daily-statuses ["not_started"]
                                :daily-rows [(wrow 7 "A" "2026-09-25T08:00" "2026-09-25T17:00" "not_started")]}))))
  (testing "R-10 画面内リンクの ? 以降を検索部として扱い、作業を選んだ状態で開く"
    (let [s (:state (ui/handle (assoc (ui/init-state) :page :daily :kind "user")
                               [:path {:path "/works?id=7" :search ""}]))]
      (is (= "/works" (:path s)))
      (is (= "?id=7" (:search s)))
      (is (= :works (:page s)))
      (is (= "7" (:gantt-selected s))))
    (is (nil? (:gantt-selected (:state (ui/handle (ui/init-state) [:path {:path "/works?id=x" :search ""}])))))
    (is (nil? (:gantt-selected (:state (ui/handle (ui/init-state) [:path {:path "/works" :search ""}])))))
    (is (= "?token=t" (:search (ui/apply-route (ui/init-state) "/reset" "?token=t"))))
    (is (= [nil ""] ((juxt :path :search) (ui/apply-route (ui/init-state) nil nil)))))
  (testing "R-10 直接開いた /works?id= でも選ぶ"
    (is (= "5" (get-in (ui/boot (ui/init-state) {:path "/works" :search "?id=5"}) [:state :gantt-selected])))
    (is (nil? (get-in (ui/boot (ui/init-state) {:path "/daily" :search "?id=5"}) [:state :gantt-selected]))))
  (testing "R-10 読み込み後、指した作業の明細を読む"
    (let [r (ui/gantt-loaded (works-state :gantt-selected "3") {:ok true :rows works-rows :titles [] :windows win})]
      (is (= "3" (get-in r [:state :gantt-selected])))
      (is (some #(str/includes? (str (nth % 2)) "/api/user/gantt/3/") (:fx r)))))
  (testing "R-10 開始・終了は日時の選択部品"
    (let [h (tu/page-html (works-state :gantt-selected 3 :gantt-titles []))
          hg (tu/page-html (assoc (works-state :gantt-selected 3) :page :gantt
                                  :gantt-titles [{:id 10 :name "題"}] :gantt-title-selected 10
                                  :gantt-rows [(assoc (wrow 3 "今日" "2026-09-25T08:00" "2026-09-25T17:00" "not_started")
                                                      :title_id 10)]))]
      (is (= 6 (count (re-seq #"type=\"datetime-local\" (id=\"works-new-(start|end)\" )?name=\"(start|end)_at\"" h))))
      (is (nil? (re-find #"<input (id=\"[^\"]+\" )?name=\"(start|end)_at\"" h)))
      (is (re-find #"type=\"datetime-local\" name=\"start_at\" placeholder" h))
      (is (re-find #"type=\"datetime-local\" id=\"gantt-new-start\"" hg))
      (is (re-find #"type=\"datetime-local\" name=\"start_at\" value=\"2026-09-25T08:00\"" hg)))))

(deftest rv001-r11-works-filter
  (testing "R-11 作業一覧 API は期間と現在時刻を返す"
    (tu/with-sys
      (fn [sys]
        (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-25T00:00:00Z"))]
          (let [{:keys [app usid]} (farm sys "rv11@example.com")
                w (:windows (tu/parse (tu/get-path app "/api/user/gantt" "user" usid)))]
            (is (= win w)))))))
  (testing "R-11 初期値はすべての期間・すべての状態"
    (let [h (tu/page-html (works-state))]
      (is (= #{"先月の遅れ" "先週済" "今日" "日曜" "来月"} (listed h)))
      (is (re-find #"<option value=\"all\" selected>" h))
      (is (= 3 (count (re-seq #"name=\"status\" value=\"[a-z_]+\" checked" h))))))
  (testing "R-11 期間は日次一覧と同じ規則（現在を含む期間は遅れも出す）"
    (is (= #{"先月の遅れ" "今日"} (listed (tu/page-html (works-state :works-range "today")))))
    (is (= #{"先月の遅れ" "今日" "日曜"} (listed (tu/page-html (works-state :works-range "week")))))
    (is (= #{"先週済"} (listed (tu/page-html (works-state :works-range "last_week")))))
    (is (= #{"先月の遅れ" "今日" "日曜"} (listed (tu/page-html (works-state :works-range "around7"))))))
  (testing "R-11 遅れの印"
    (let [h (tu/page-html (works-state))]
      (is (= 1 (count (re-seq #"daily-item-overdue" h))))
      (is (nil? (re-find #"daily-item-overdue" (tu/page-html (works-state :gantt-windows nil)))))))
  (testing "R-11 状態で絞る・何も出ないときは案内"
    (is (= #{"先週済"} (listed (tu/page-html (works-state :works-statuses ["done"])))))
    (let [h (tu/page-html (works-state :works-range "today" :works-statuses ["done"]))]
      (is (empty? (listed h)))
      (is (re-find #"選んだ期間・状態に重なる作業がありません" h))))
  (testing "R-11 期間が届いていなければ期間では絞らない"
    (is (= 5 (count (listed (tu/page-html (works-state :works-range "today" :gantt-windows nil)))))))
  (testing "R-11 作業が無いときは絞り込みを出さない"
    (is (nil? (re-find #"works-filter" (tu/page-html (works-state :gantt-rows []))))))
  (testing "R-11 絞り込みの操作"
    (let [s (:state (ui/handle (works-state) [:submit {:act "set-works-filter"
                                                       :form {:range "week" :status ["done" "weird"]}}]))
          bad (:state (ui/handle (works-state :works-range "today")
                                 [:submit {:act "set-works-filter" :form {:range "month"}}]))]
      (is (= "week" (:works-range s)))
      (is (= ["done"] (:works-statuses s)))
      (is (= "all" (:works-range bad)))
      (is (= [] (:works-statuses bad)))
      (is (= #{} (listed (tu/page-html bad))))))
  (testing "R-11 作業画面を開き直すと初期値に戻る"
    (let [s (:state (ui/handle (works-state :works-range "today" :works-statuses ["done"])
                               [:path {:path "/works" :search ""}]))]
      (is (= "all" (:works-range s)))
      (is (= ["not_started" "in_progress" "done"] (:works-statuses s)))))
  (testing "R-11 範囲外の値は既定に戻す"
    (is (= 5 (count (listed (tu/page-html (works-state :works-range "month"))))))))

(deftest rv001-r12-busy-timeout
  (testing "R-12 全接続に busy_timeout が効く"
    (is (str/ends-with? (db/sqlite-url "data/x.sqlite") "?busy_timeout=5000"))
    (tu/with-sys
      (fn [sys]
        (is (= 5000 (:timeout (jdbc/execute-one! (:ds sys) ["PRAGMA busy_timeout"]))))))))
