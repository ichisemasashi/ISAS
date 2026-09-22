(ns isas.v2-p5-test
  "詳細試験仕様書_第2版_工程5 の項番に対応する自動試験。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.http :as http]
            [isas.memos :as memos]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [ring.mock.request :as mock])
  (:import [java.time Instant]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"} :ui-lang "ja"} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "v2p5@example.com")
        usid (tu/user-sid app "v2p5@example.com" pw)
        uid (:id (db/find-user-by-email (:ds sys) "v2p5@example.com"))]
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

(defn- pub-memo [app kind sid body]
  (:memo (tu/parse (tu/post-json app "/api/memos"
                                 {:body body :status "published"} kind sid))))

(deftest v2p5-screens
  (testing "V2P5-2.1-01 / 07 未紐づけ・作成者・候補0"
    (let [h (html {:page :memos
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :parent_id nil :can_link_gantt true
                                       :author_email "a@example.com" :gantt nil}
                   :memo-gantt-candidates []})]
      (is (re-find #"id=\"memo-gantt-link\"" h))
      (is (re-find #"付けられる作業がありません" h))
      (is (nil? (re-find #"紐づけを外す" h))))
    (let [h-with (html {:page :memos
                        :memo-selected 1
                        :memo-selected-row {:id 1 :body "親" :status "published"
                                            :can_link_gantt true :gantt nil
                                            :author_email "a@example.com"}
                        :memo-gantt-candidates [{:id 9 :title "候補A"}]})]
      (is (re-find #"作業を紐づける" h-with))
      (is (re-find #"候補A" h-with)))
    (let [h-tl (html {:page :memos
                      :memos [{:id 1 :body "親" :status "published"
                               :author_email "a@example.com" :gantt {:id 9 :title "東" :deleted false}}]})]
      (is (nil? (re-find #"id=\"memo-gantt-link\"" h-tl)))
      (is (nil? (re-find #"作業を紐づける" h-tl)))))
  (testing "V2P5-2.1-02 紐づけあり"
    (let [h (html {:page :memos
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :can_link_gantt true :author_email "a@example.com"
                                       :gantt {:id 9 :title "東の防除" :deleted false}}
                   :memo-gantt-candidates [{:id 9 :title "東の防除"}
                                           {:id 10 :title "西の草刈"}]})]
      (is (re-find #"東の防除" h))
      (is (re-find #"別の作業に付け替える" h))
      (is (re-find #"紐づけを外す" h))
      (is (re-find #"id=\"memo-gantt-select\"" h))))
  (testing "V2P5-2.1-03 削除済み"
    (let [h (html {:page :memos
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :can_link_gantt true :author_email "a@example.com"
                                       :gantt {:id 9 :title "旧作業" :deleted true}}
                   :memo-gantt-candidates [{:id 10 :title "新"}]})]
      (is (re-find #"削除済み" h))
      (is (re-find #"旧作業" h))
      (is (re-find #"紐づけを外す" h))))
  (testing "V2P5-2.1-04 閲覧者・未紐づけは欄なし"
    (let [h (html {:page :memos
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :can_link_gantt false :author_email "other@example.com"
                                       :gantt nil}})]
      (is (nil? (re-find #"id=\"memo-gantt-link\"" h)))
      (is (nil? (re-find #"作業を紐づける" h)))))
  (testing "V2P5-2.1-05 閲覧者・紐づけありは要約のみ"
    (let [h (html {:page :memos
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :can_link_gantt false :author_email "other@example.com"
                                       :gantt {:id 9 :title "見える作業" :deleted false}}})]
      (is (re-find #"見える作業" h))
      (is (nil? (re-find #"作業を紐づける|紐づけを外す|memo-gantt-select" h)))))
  (testing "V2P5-2.1-06 下書き画面に紐づけ無し"
    (let [h (html {:page :memos-drafts
                   :memo-drafts [{:id 1 :body "下" :status "draft" :can_edit true}]})]
      (is (nil? (re-find #"id=\"memo-gantt-link\"|作業を紐づける" h)))))
  (testing "V2P5-2.1-08 狭幅でも同じ"
    (let [h (html {:page :memos :narrow? true
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :can_link_gantt true :gantt nil
                                       :author_email "a@example.com"}
                   :memo-gantt-candidates [{:id 1 :title "狭幅候補"}]})]
      (is (re-find #"id=\"bottom-nav\"" h))
      (is (re-find #"作業を紐づける" h))
      (is (re-find #"狭幅候補" h))))
  (testing "V2P5-2.1-09 管理者候補ラベル"
    (let [h (html {:page :memos :kind "admin" :session {:email "admin@example.com"}
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published"
                                       :can_link_gantt true :gantt nil
                                       :author_email "a@example.com"}
                   :memo-gantt-candidates [{:id 3 :title "東" :user_email "u@example.com"}]})]
      (is (re-find #"u@example\.com — 東" h))))
  (testing "V2P5-5-01 文言"
    (is (= "作業を紐づける" (get ui/messages :memos-gantt-link)))
    (is (= "紐づけを外す" (get ui/messages :memos-gantt-unlink)))
    (is (= "別の作業に付け替える" (get ui/messages :memos-gantt-retarget)))
    (is (= "削除済み" (get ui/messages :memos-gantt-deleted)))
    (is (= "付けられる作業がありません" (get ui/messages :memos-gantt-empty)))
    (is (= "Link work" (get ui/messages-en :memos-gantt-link))))
  (testing "V2P5-6-01 daily/gantt/works に付け外し必須無し"
    (doseq [p [:daily :gantt :works]]
      (let [h (html {:page p :fields [{:id 1}] :daily-statuses ["not_started"]
                     :daily-rows [] :gantt-rows [] :gantt-titles []})]
        (is (nil? (re-find #"memo-gantt-link|作業を紐づける" h)))))))

(deftest v2p5-api-and-rules
  (tu/with-sys
    (fn [sys]
      (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-23T01:00:00Z"))]
        (let [{:keys [app asid usid uid]} (farm sys)
              _ (add-field app usid)
              tid (:id (make-title app usid "題A"))
              g1 (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                 (row-body tid "東の防除"
                                                           "2026-09-23T08:00"
                                                           "2026-09-23T17:00")
                                                 "user" usid))
                         [:row :id])
              g2 (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                 (row-body tid "西の草刈"
                                                           "2026-09-24T08:00"
                                                           "2026-09-24T12:00")
                                                 "user" usid))
                         [:row :id])
              pw-o (tu/invite-pw app asid "v2p5-other@example.com")
              usid-o (tu/user-sid app "v2p5-other@example.com" pw-o)
              _ (add-field app usid-o)
              tid-o (:id (make-title app usid-o "他人題"))
              g-o (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                  (row-body tid-o "他人作業"
                                                            "2026-09-23T09:00"
                                                            "2026-09-23T10:00")
                                                  "user" usid-o))
                          [:row :id])
              memo (pub-memo app "user" usid "紐づけ親")
              mid (:id memo)]
          (testing "経路"
            (is (= [:memo-gantt-put "12"] (http/match-api :put "/api/memos/12/gantt")))
            (is (= [:memo-gantt-delete "12"] (http/match-api :delete "/api/memos/12/gantt")))
            (is (= [:admin-gantt-rows-get] (http/match-api :get "/api/admin/gantt/rows"))))
          (testing "V2P5-2.2-08 初期 gantt null / can_link"
            (is (nil? (:gantt memo)))
            (is (true? (:can_link_gantt memo)))
            (let [got (tu/parse (tu/get-path app (str "/api/memos/" mid) "user" usid))]
              (is (nil? (get-in got [:memo :gantt])))))
          (testing "V2P5-2.2-01 / 3.3-01 PUT 紐づけ・置換"
            (let [r1 (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                            {:gantt_id g1} "user" usid))
                  r2 (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                            {:gantt_id g2} "user" usid))]
              (is (:ok r1))
              (is (= g1 (get-in r1 [:memo :gantt :id])))
              (is (= "東の防除" (get-in r1 [:memo :gantt :title])))
              (is (false? (get-in r1 [:memo :gantt :deleted])))
              (is (:ok r2))
              (is (= g2 (get-in r2 [:memo :gantt :id])))
              (is (nil? (get-in r2 [:memo :gantt :execution_status])))
              (is (nil? (get-in r2 [:memo :gantt :work_time_count])))))
          (testing "V2P5-3.3-01 1作業に複数メモ"
            (let [m2 (pub-memo app "user" usid "もう1件")
                  r (tu/parse (tu/put-json app (str "/api/memos/" (:id m2) "/gantt")
                                           {:gantt_id g2} "user" usid))]
              (is (:ok r))
              (is (= g2 (get-in r [:memo :gantt :id])))))
          (testing "V2P5-3.4-01 content_saved_at 不変"
            (let [before (get-in (tu/parse (tu/get-path app (str "/api/memos/" mid) "user" usid))
                                 [:memo :content_saved_at])
                  _ (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                           {:gantt_id g1} "user" usid))
                  after (get-in (tu/parse (tu/get-path app (str "/api/memos/" mid) "user" usid))
                                [:memo :content_saved_at])]
              (is (= before after))))
          (testing "V2P5-3.5-01 非連動（実行状態）"
            (let [before (get-in (tu/parse (tu/get-path app (str "/api/user/gantt") "user" usid))
                                 [:rows])
                  st1 (:execution_status (first (filter #(= g1 (:id %)) before)))
                  _ (tu/parse (tu/delete-path app (str "/api/memos/" mid "/gantt") "user" usid))
                  _ (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                           {:gantt_id g1} "user" usid))
                  after (get-in (tu/parse (tu/get-path app (str "/api/user/gantt") "user" usid))
                                [:rows])
                  st2 (:execution_status (first (filter #(= g1 (:id %)) after)))]
              (is (= st1 st2))))
          (testing "V2P5-2.2-02 DELETE 冪等"
            (let [d1 (tu/parse (tu/delete-path app (str "/api/memos/" mid "/gantt") "user" usid))
                  d2 (tu/parse (tu/delete-path app (str "/api/memos/" mid "/gantt") "user" usid))]
              (is (:ok d1))
              (is (nil? (get-in d1 [:memo :gantt])))
              (is (:ok d2))
              (is (nil? (get-in d2 [:memo :gantt])))))
          (testing "V2P5-2.2-03 / 3.7-01 / 2.2-04 圃場0"
            (let [pw0 (tu/invite-pw app asid "v2p5-zero@example.com")
                  usid0 (tu/user-sid app "v2p5-zero@example.com" pw0)
                  listed (tu/parse (tu/get-path app "/api/user/gantt" "user" usid0))
                  created (tu/parse (tu/post-json app "/api/user/gantt"
                                                  (row-body nil "x" "2026-09-23T08:00" "2026-09-23T09:00")
                                                  "user" usid0))]
              (is (:ok listed))
              (is (= [] (:rows listed)))
              (is (= "no_fields" (:code created)))))
          (testing "V2P5-2.2-05 / 06 / 07 管理者候補"
            (let [rows (tu/parse (tu/get-path app "/api/admin/gantt/rows" "admin" asid))
                  mine (filter #(= g1 (:id %)) (:rows rows))]
              (is (:ok rows))
              (is (= 1 (count mine)))
              (is (= "v2p5@example.com" (:user_email (first mine))))
              (is (= uid (:user_id (first mine))))
              (is (every? #(and (contains? % :start_at) (contains? % :end_at) (contains? % :title))
                          (:rows rows)))
              (is (= "forbidden"
                     (:code (tu/parse (tu/get-path app "/api/admin/gantt/rows" "user" usid)))))
              (is (= "unauthorized"
                     (:code (tu/parse (tu/get-path app "/api/admin/gantt/rows")))))))
          (testing "V2P5-2.2-09 / 3.1-01 / 3.2-01 失敗コード"
            (is (= "gantt_id_required"
                   (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                 {} "user" usid)))))
            (is (= "gantt_id_required"
                   (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                 {:gantt_id "x"} "user" usid)))))
            (is (= "gantt_not_found"
                   (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                 {:gantt_id 999999} "user" usid)))))
            (is (= "gantt_not_found"
                   (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                 {:gantt_id g-o} "user" usid)))))
            (let [draft (:memo (tu/parse (tu/post-json app "/api/memos"
                                                       {:body "下" :status "draft"} "user" usid)))
                  parent mid
                  reply (:memo (tu/parse (tu/post-json app "/api/memos"
                                                       {:body "返" :status "published"
                                                        :parent_id parent}
                                                       "user" usid)))]
              (is (= "link_not_allowed"
                     (:code (tu/parse (tu/put-json app (str "/api/memos/" (:id draft) "/gantt")
                                                   {:gantt_id g1} "user" usid)))))
              (is (= "link_not_allowed"
                     (:code (tu/parse (tu/put-json app (str "/api/memos/" (:id reply) "/gantt")
                                                   {:gantt_id g1} "user" usid)))))
              (is (= "link_not_allowed"
                     (:code (tu/parse (tu/delete-path app (str "/api/memos/" (:id reply) "/gantt")
                                                      "user" usid))))))
            (let [_ (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                           {:gantt_id g1} "user" usid))]
              (is (= "forbidden_memo"
                     (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                   {:gantt_id g1} "user" usid-o)))))
              (is (= "forbidden_memo"
                     (:code (tu/parse (tu/delete-path app (str "/api/memos/" mid "/gantt")
                                                      "user" usid-o))))))
            (is (= "memo_not_found"
                   (:code (tu/parse (tu/put-json app "/api/memos/999999/gantt"
                                                 {:gantt_id g1} "user" usid))))))
          (testing "V2P5-2.2-10 編集窓外でも付け外し可"
            (let [m (pub-memo app "user" usid "窓外")
                  cs (get-in (tu/parse (tu/get-path app (str "/api/memos/" (:id m)) "user" usid))
                             [:memo :content_saved_at])]
              (binding [time/*now-fn* (fn [] (.plusSeconds (Instant/parse "2026-09-23T01:00:00Z") (* 40 60)))]
                (let [r (tu/parse (tu/put-json app (str "/api/memos/" (:id m) "/gantt")
                                               {:gantt_id g1} "user" usid))
                      got (tu/parse (tu/get-path app (str "/api/memos/" (:id m)) "user" usid))]
                  (is (:ok r))
                  (is (not= "edit_window_closed" (:code r)))
                  (is (= cs (get-in got [:memo :content_saved_at])))
                  (is (false? (get-in got [:memo :can_edit])))))))
          (testing "V2P5-3.6-01 作業ソフト削除で deleted"
            (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                   {:gantt_id g1} "user" usid))
            (is (:ok (tu/parse (tu/delete-path app (str "/api/user/gantt/" g1) "user" usid))))
            (let [got (tu/parse (tu/get-path app (str "/api/memos/" mid) "user" usid))]
              (is (= g1 (get-in got [:memo :gantt :id])))
              (is (true? (get-in got [:memo :gantt :deleted])))
              (is (= "東の防除" (get-in got [:memo :gantt :title]))))
            (is (= "gantt_not_found"
                   (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                 {:gantt_id g1} "user" usid)))))
            (is (:ok (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                            {:gantt_id g2} "user" usid)))))
          (testing "管理者は他人のメモに紐づけ可"
            (let [r (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                           {:gantt_id g2} "admin" asid))]
              (is (:ok r))
              (is (= g2 (get-in r [:memo :gantt :id])))))
          (testing "V2P5-3.6-02 メモソフト削除"
            (is (:ok (tu/parse (tu/delete-path app (str "/api/memos/" mid) "user" usid))))
            (is (= "memo_not_found"
                   (:code (tu/parse (tu/get-path app (str "/api/memos/" mid) "user" usid)))))
            (is (= "memo_not_found"
                   (:code (tu/parse (tu/put-json app (str "/api/memos/" mid "/gantt")
                                                 {:gantt_id g2} "user" usid)))))
            (let [tl (tu/parse (tu/get-path app "/api/memos" "user" usid))]
              (is (not-any? #(= mid (:id %)) (:memos tl))))))))))

(deftest v2p5-ui-flows
  (testing "V2P5-7-01 / 02 スレッド・付け外し handle"
    (let [base (assoc (ui/init-state) :page :memos :kind "user"
                      :session {:email "a@example.com"}
                      :memos [{:id 1 :body "親" :status "published"
                               :can_link_gantt true :gantt nil}]
                      :memo-gantt-candidates [{:id 9 :title "東"} {:id 10 :title "西"}])
          open (ui/handle base [:submit {:act "memo-select" :form {:id "1"}}])
          link (ui/handle (assoc (:state open)
                                 :memo-selected "1"
                                 :memo-selected-row {:id 1 :body "親" :status "published"
                                                     :can_link_gantt true :gantt nil}
                                 :memo-gantt-candidates [{:id 9 :title "東"}])
                          [:submit {:act "memo-gantt-link" :form {:id "1" :gantt_id "9"}}])
          unlink (ui/handle (assoc (:state link)
                                   :memo-selected "1"
                                   :memo-selected-row {:id 1 :gantt {:id 9 :title "東" :deleted false}
                                                       :can_link_gantt true})
                            [:submit {:act "memo-gantt-unlink" :form {:id "1"}}])
          retarget (ui/handle (assoc (:state unlink)
                                     :memo-selected "1"
                                     :memo-selected-row {:id 1 :gantt {:id 9 :title "東" :deleted false}
                                                         :can_link_gantt true}
                                     :memo-gantt-candidates [{:id 9 :title "東"} {:id 10 :title "西"}])
                              [:submit {:act "memo-gantt-link" :form {:id "1" :gantt_id "10"}}])]
      (is (some #(= :api (first %)) (:fx open)))
      (is (= "PUT" (second (first (:fx link)))))
      (is (str/includes? (nth (first (:fx link)) 2) "/gantt"))
      (is (= {:gantt_id "9"} (nth (first (:fx link)) 3)))
      (is (= "DELETE" (second (first (:fx unlink)))))
      (is (= {:gantt_id "10"} (nth (first (:fx retarget)) 3)))))
  (testing "V2P5-7-02 結果ハンドラ"
    (let [s0 (assoc (ui/init-state) :page :memos :kind "user"
                    :session {:email "a@example.com"}
                    :memo-selected "1"
                    :memo-selected-row {:id 1 :body "親" :can_link_gantt true :gantt nil}
                    :pending-flash-near "memo-thread-section")
          ok (ui/handle s0 [:memo-gantt-result
                            {:ok true
                             :memo {:id 1 :body "親" :can_link_gantt true
                                    :gantt {:id 9 :title "東" :deleted false}}}])
          fail (ui/handle s0 [:memo-gantt-result {:ok false :code "gantt_not_found"}])
          cands (ui/handle (assoc s0 :kind "admin")
                           [:memo-gantt-candidates-loaded
                            {:ok true :rows [{:id 1 :title "a" :user_email "u@x.com"}]}])
          loaded (ui/handle (assoc s0 :kind "user")
                            [:memo-loaded {:ok true
                                           :memo {:id 1 :body "親" :can_link_gantt true
                                                  :gantt nil}}])]
      (is (false? (boolean (get-in ok [:state :flash :error?]))))
      (is (= "作業を紐づけました" (get-in ok [:state :flash :text])))
      (is (= 9 (get-in ok [:state :memo-selected-row :gantt :id])))
      (is (true? (get-in fail [:state :flash :error?])))
      (is (= 1 (count (get-in cands [:state :memo-gantt-candidates]))))
      (is (some #(and (= :api (first %))
                      (str/includes? (str (nth % 2)) "/api/user/gantt"))
                (:fx loaded)))))
  (testing "V2P5-7-03 狭幅メモ遷移"
    (let [r (ui/handle (assoc (ui/init-state) :page :home :kind "user" :narrow? true
                              :session {:email "a@example.com"})
                       [:path {:path "/memos"}])]
      (is (= :memos (get-in r [:state :page])))
      (is (true? (get-in r [:state :narrow?])))))
  (testing "cloverage: コードメッセージ・空選択・管理者候補読込"
    (is (= "下書きや返信には作業を付けられません" (ui/code-message "link_not_allowed")))
    (is (= "紐づける作業を選んでください" (ui/code-message "gantt_id_required")))
    (let [empty-link (ui/handle (assoc (ui/init-state) :page :memos
                                       :session {:email "a"} :memo-selected "1")
                                [:submit {:act "memo-gantt-link" :form {:id "1" :gantt_id ""}}])
          empty-id (ui/handle (assoc (ui/init-state) :page :memos :session {:email "a"})
                              [:submit {:act "memo-gantt-unlink" :form {}}])
          adm-loaded (ui/handle (assoc (ui/init-state) :page :memos :kind "admin"
                                       :session {:email "a"} :memo-selected "1")
                                [:memo-loaded {:ok true
                                               :memo {:id 1 :can_link_gantt true :gantt nil}}])
          unlink-ok (ui/handle (assoc (ui/init-state) :page :memos :kind "user"
                                      :session {:email "a"} :memo-selected "1"
                                      :memo-gantt-unlinking true
                                      :memo-selected-row {:id 1 :can_link_gantt true
                                                          :gantt {:id 1 :title "x" :deleted false}})
                               [:memo-gantt-result
                                {:ok true :memo {:id 1 :can_link_gantt true :gantt nil}}])
          admin-ok (ui/handle (assoc (ui/init-state) :page :memos :kind "admin"
                                     :session {:email "a"} :memo-selected "1"
                                     :memo-selected-row {:id 1 :can_link_gantt true :gantt nil})
                              [:memo-gantt-result
                               {:ok true :memo {:id 1 :can_link_gantt true
                                                :gantt {:id 2 :title "y" :deleted false}}}])
          cand-fail (ui/handle (assoc (ui/init-state) :page :memos :session {:email "a"})
                               [:memo-gantt-candidates-loaded {:ok false :code "forbidden"}])
          memo-fail (ui/handle (assoc (ui/init-state) :page :memos :session {:email "a"}
                                      :memo-selected "1")
                               [:memo-loaded {:ok false :code "memo_not_found"}])]
      (is (true? (get-in empty-link [:state :flash :error?])))
      (is (true? (get-in empty-id [:state :flash :error?])))
      (is (some #(and (= :api (first %))
                      (str/includes? (str (nth % 2)) "/api/admin/gantt/rows"))
                (:fx adm-loaded)))
      (is (= "紐づけを外しました" (get-in unlink-ok [:state :flash :text])))
      (is (some #(and (= :api (first %))
                      (str/includes? (str (nth % 2)) "/api/admin/gantt/rows"))
                (:fx admin-ok)))
      (is (true? (get-in cand-fail [:state :flash :error?])))
      (is (nil? (get-in memo-fail [:state :memo-selected]))))
    (is (= "" (#'ui/memo-gantt-option-label {:title nil} false)))
    (is (= "t" (#'ui/memo-gantt-option-label {:title "t" :user_email "  "} true)))
    (is (= "t" (#'ui/memo-gantt-option-label {:title "t"} false)))
    (is (nil? (#'ui/memo-gantt-summary-html nil)))
    (is (re-find #"紐づいた作業" (#'ui/memo-gantt-summary-html {:title nil :deleted false})))
    (is (re-find #"削除済み" (#'ui/memo-gantt-summary-html {:title "x" :deleted true})))
    (let [h (html {:page :memos :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :can_link_gantt true
                                       :gantt {:id nil :title "x" :deleted false}
                                       :author_email "a@example.com"}
                   :memo-gantt-candidates [{:id 1 :title "a"}]
                   :memo-gantt-pick nil})]
      (is (re-find #"memo-gantt-select" h)))
    (let [h2 (html {:page :memos :memo-selected 1
                    :memo-selected-row {:id 1 :body "親" :can_link_gantt true
                                        :gantt {:id 9 :title "x" :deleted false}
                                        :author_email "a@example.com"}
                    :memo-gantt-candidates [{:id 9 :title "a"}]
                    :memo-gantt-pick nil})]
      (is (re-find #"value=\"9\" selected" h2)))
    (let [h3 (html {:page :memos :memo-selected 1
                    :memo-selected-row {:id 1 :body "親" :can_link_gantt true
                                        :gantt nil :author_email "a@example.com"}
                    :memo-gantt-candidates [{:id 1 :title "a"}]
                    :memo-gantt-pick nil})]
      (is (re-find #"memo-gantt-select" h3)))
    (let [sec (#'ui/memo-gantt-link-section
               {:kind "user"
                :memo-gantt-pick "5"
                :memo-gantt-candidates [{:id 5 :title "選"}]}
               {:id 1 :can_link_gantt true :gantt {:id 9 :title "x"} :author_email "a"})
          sec2 (#'ui/memo-gantt-link-section
                {:kind "user"
                 :memo-gantt-pick nil
                 :memo-gantt-candidates [{:id 9 :title "選"}]}
                {:id 1 :can_link_gantt true :gantt {:id 9 :title "x"} :author_email "a"})
          sec3 (#'ui/memo-gantt-link-section
                {:kind "user"
                 :memo-gantt-pick nil
                 :memo-gantt-candidates [{:id 1 :title "選"}]}
                {:id 1 :can_link_gantt true :gantt nil :author_email "a"})]
      (is (re-find #"value=\"5\" selected" sec))
      (is (re-find #"value=\"9\" selected" sec2))
      (is (re-find #"memo-gantt-select" sec3)))
    (let [blank-id (ui/handle (assoc (ui/init-state) :page :memos :session {:email "a"})
                              [:submit {:act "memo-gantt-link" :form {:gantt_id "9"}}])]
      (is (true? (get-in blank-id [:state :flash :error?])))
      (is (= "そのメモはありません" (get-in blank-id [:state :flash :text]))))
    (let [nil-rows (ui/handle (assoc (ui/init-state) :page :memos :session {:email "a"})
                              [:memo-gantt-candidates-loaded {:ok true}])
          home-ok (ui/handle (assoc (ui/init-state) :page :home :kind "user"
                                    :session {:email "a"} :memo-selected "1")
                             [:memo-gantt-result
                              {:ok true :memo {:id 1 :can_link_gantt true
                                               :gantt {:id 1 :title "z" :deleted false}}}])]
      (is (= [] (get-in nil-rows [:state :memo-gantt-candidates])))
      (is (= "作業を紐づけました" (get-in home-ok [:state :flash :text])))))

(deftest v2p5-coverage-edges
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app usid]} (farm sys)
            _ (add-field app usid)
            tid (:id (make-title app usid "題C"))
            _gid (get-in (tu/parse (tu/post-json app "/api/user/gantt"
                                                 (row-body tid "残参照"
                                                           "2026-09-23T08:00"
                                                           "2026-09-23T09:00")
                                                 "user" usid))
                         [:row :id])
            memo (pub-memo app "user" usid "孤児参照")
            mid (:id memo)
            actor {:kind "user" :id (:id (db/find-user-by-email (:ds sys) "v2p5@example.com"))
                   :email "v2p5@example.com"}]
        (testing "孤児 gantt_id 要約"
          (db/set-memo-gantt! (:ds sys) mid 999888)
          (let [got (tu/parse (tu/get-path app (str "/api/memos/" mid) "user" usid))]
            (is (= 999888 (get-in got [:memo :gantt :id])))
            (is (nil? (get-in got [:memo :gantt :title])))
            (is (true? (get-in got [:memo :gantt :deleted])))))
        (testing "DELETE memo_not_found"
          (is (= "memo_not_found"
                 (:code (tu/parse (tu/delete-path app "/api/memos/999777/gantt" "user" usid)))))
          (is (= "memo_not_found"
                 (:code (memos/unlink-gantt! sys actor 999777)))))
        (testing "PUT 本文読取失敗"
          (is (= "gantt_id_required"
                 (:code (tu/parse
                         (app (-> (mock/request :put (str "/api/memos/" mid "/gantt"))
                                  (mock/content-type "application/json")
                                  (mock/body "not-json")
                                  (tu/as-user "user" usid)))))))
        (testing "残りの枝"
          (is (nil? (http/match-api :get "/api/memos/1/gantt")))
          (is (nil? (http/match-api :post "/api/memos/1/gantt")))
          (with-redefs [isas.gantt/list-admin-rows (fn [_] {:ok false :code "statuses_invalid"})]
            (is (= "statuses_invalid"
                   (:code (tu/parse (tu/get-path app "/api/admin/gantt/rows" "admin"
                                                 (tu/admin-sid app)))))))
          (let [m2 (pub-memo app "user" usid "削除予定")
                id2 (:id m2)]
            (tu/parse (tu/delete-path app (str "/api/memos/" id2) "user" usid))
            (is (= "memo_not_found"
                   (:code (memos/unlink-gantt! sys actor id2))))
            (is (= "memo_not_found"
                   (:code (memos/link-gantt! sys actor id2 {:gantt_id 1})))))
          (is (nil? (#'memos/resolve-linkable-gantt
                     sys {:kind "admin" :id 1} nil)))
          (is (nil? (#'memos/present-gantt-summary
                     sys {:gantt_id nil})))
          (is (false? (#'memos/linkable-parent? nil)))
          (is (false? (#'memos/linkable-parent?
                       {:status "published" :parent_id nil
                        :deleted_at "2026-09-23T00:00:00Z"})))
          (is (nil? (#'memos/resolve-linkable-gantt
                     sys {:kind "other" :id 1} _gid)))
          (with-redefs [isas.gantt/list-rows (fn [_ _] {:ok false :code "no_fields"})]
            (is (= "no_fields"
                   (:code (tu/parse (tu/get-path app "/api/user/gantt" "user" usid)))))))))))
))
