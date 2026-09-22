(ns isas.v2-p3-test
  "第2版工程3 メモ（パソコン）の詳細試験。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.http :as http]
            [isas.memos :as memos]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui])
  (:import [java.io File]
           [java.time Instant Duration]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "v2p3@example.com")
        usid (tu/user-sid app "v2p3@example.com" pw)
        uid (:id (db/find-user-by-email (:ds sys) "v2p3@example.com"))]
    {:app app :asid asid :usid usid :uid uid}))

(defn- long-body [n]
  (apply str (repeat n "あ")))

(deftest v2p3-screens-and-routes
  (testing "V2P3-2.1-01 /memos タイムライン・投稿・検索"
    (let [h (html {:page :memos})]
      (is (re-find #"memo-compose-section" h))
      (is (re-find #"memo-search-section|高度な条件|この条件で検索|検索" h))
      (is (re-find #"タイムライン|memos-timeline" h))
      (is (nil? (re-find #"ガント紐づけ|gantt.?link" h)))))
  (testing "V2P3-2.1-02 /memos/drafts"
    (let [h (html {:page :memos-drafts
                   :memo-drafts [{:id 1 :body "下書きA" :status "draft" :can_edit true}]})]
      (is (re-find #"下書きA" h))
      (is (re-find #"公開する|data-act=\"memo-publish-draft\"" h))))
  (testing "V2P3-2.1-03 /memos/bookmarks"
    (let [h (html {:page :memos-bookmarks
                   :memo-bookmarks [{:id 2 :body "印付き" :status "published" :bookmarked true}]})]
      (is (re-find #"印付き" h))
      (is (re-find #"★|ブックマークを外す|memo-unbookmark" h))))
  (testing "V2P3-2.1-04 ホーム出口"
    (is (re-find #"href=\"/memos\"" (html {:page :home})))
    (is (re-find #"href=\"/memos\"" (html {:page :home :kind "admin"}))))
  (testing "V2P3-2.1-05 狭い画面は案内のみ"
    (doseq [p [:memos :memos-drafts :memos-bookmarks]]
      (let [h (html {:page p :narrow? true})]
        (is (re-find #"メモはパソコンで開いてください" h))
        (is (nil? (re-find #"memo-compose-section" h))))))
  (testing "V2P3-2.1-06 狭い画面ナビにメモ無し"
    (is (nil? (re-find #"href=\"/memos\"" (html {:page :home :narrow? true}))))
    (is (nil? (re-find #"href=\"/memos\"" (html {:page :home :kind "admin" :narrow? true})))))
  (testing "V2P3-2.1-08 画面遷移ナビ"
    (let [h (html {:page :memos})]
      (is (re-find #"href=\"/memos\"" h))
      (is (re-find #"href=\"/memos/drafts\"" h))
      (is (re-find #"href=\"/memos/bookmarks\"" h))))
  (testing "経路"
    (is (= :memos (:page (ui/route-for "/memos"))))
    (is (= :memos-drafts (:page (ui/route-for "/memos/drafts"))))
    (is (= :memos-bookmarks (:page (ui/route-for "/memos/bookmarks"))))
    (is (nil? (:kind (ui/route-for "/memos"))))
    (is (= "admin" (:kind (ui/apply-route {:kind "admin"} "/memos" ""))))
    (is (= [:memos-get] (http/match-api :get "/api/memos")))
    (is (= [:memos-post] (http/match-api :post "/api/memos")))
    (is (= [:memos-search-get] (http/match-api :get "/api/memos/search")))
    (is (= [:memos-drafts-get] (http/match-api :get "/api/memos/drafts")))
    (is (= [:memos-bookmarks-get] (http/match-api :get "/api/memos/bookmarks")))
    (is (= [:memo-get "3"] (http/match-api :get "/api/memos/3")))
    (is (= [:memo-put "3"] (http/match-api :put "/api/memos/3")))
    (is (= [:memo-delete "3"] (http/match-api :delete "/api/memos/3")))
    (is (= [:memo-publish "3"] (http/match-api :post "/api/memos/3/publish")))
    (is (= [:memo-replies-get "3"] (http/match-api :get "/api/memos/3/replies")))
    (is (= [:memo-attachment-post "3"] (http/match-api :post "/api/memos/3/attachments")))
    (is (= [:memo-attachment-get "3" "9"] (http/match-api :get "/api/memos/3/attachments/9")))
    (is (= [:memo-bookmark-post "3"] (http/match-api :post "/api/memos/3/bookmark")))
    (is (= [:memo-bookmark-delete "3"] (http/match-api :delete "/api/memos/3/bookmark")))
    (is (nil? (http/match-api :get "/api/user/memos"))))
  (testing "V2P3-5-01 文言キー"
    (ui/with-ui-lang {:ui-lang "ja"}
      (fn []
        (is (= "メモ" (get ui/messages :memos-title)))
        (is (= "メモはパソコンで開いてください" (get ui/messages :phone-memos)))))
    (is (= "Memos" (get ui/messages-en :memos-title)))
    (is (= "Open memos on a computer" (get ui/messages-en :phone-memos))))
  (testing "V2P3-2.2-06 空公開はクライアントで止まる"
    (let [r (ui/handle {:page :memos :kind "user" :session {:email "a@example.com"}
                        :ui-lang "ja" :memos [] :memo-compose {}}
                       [:submit {:act "memo-publish-new" :form {:body "  " :status "published"}}])]
      (is (nil? (some #(= :api (first %)) (:fx r))))
      (is (re-find #"本文を入力" (get-in r [:state :flash :text])))))
  (testing "V2P3-2.2-07 編集期限表示"
    (let [h (html {:page :memos
                   :memos [{:id 1 :body "本文" :status "published" :can_edit true
                            :editable_until "2026-09-22T04:00:00Z"
                            :author_email "a@example.com"}]})]
      (is (re-find #"編集期限|2026-09-22T04:00:00Z" h))))
  (testing "スレッド選択と星"
    (let [h (html {:page :memos
                   :memos [{:id 5 :body "親" :status "published" :bookmarked false
                            :author_email "a@example.com"}]
                   :memo-selected 5
                   :memo-selected-row {:id 5 :body "親" :status "published"
                                       :author_email "a@example.com"}
                   :memo-replies [{:id 6 :body "子" :status "published"
                                   :author_email "b@example.com"}]})]
      (is (re-find #"memo-thread-section" h))
      (is (re-find #"子" h))
      (is (re-find #"☆|memo-bookmark" h)))))

(deftest v2p3-api-core
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid]} (farm sys)
            actor {:kind "user" :id (:id (db/find-user-by-email (:ds sys) "v2p3@example.com"))}]
        (testing "V2P3-2.5-14 未ログイン"
          (is (= 401 (:status (tu/get-path app "/api/memos"))))
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/memos"))))))
        (testing "V2P3-2.5-01/02 タイムラインと公開"
          (let [a (tu/parse (tu/post-json app "/api/memos"
                                          {:body "親A" :status "published" :tags ["東"]
                                           :links ["https://example.com/a"]}
                                          "user" usid))
                b (tu/parse (tu/post-json app "/api/memos"
                                          {:body "親B" :status "published"}
                                          "user" usid))
                tl (tu/parse (tu/get-path app "/api/memos" "user" usid))]
            (is (:ok a))
            (is (:ok b))
            (is (= "親B" (get-in tl [:memos 0 :body])))
            (is (= ["東"] (get-in a [:memo :tags])))
            (is (= ["https://example.com/a"] (get-in a [:memo :links])))
            (is (some? (get-in a [:memo :editable_until])))
            (is (true? (get-in a [:memo :can_edit])))
            (let [more (tu/parse (tu/get-query app "/api/memos"
                                               {:limit 1 :before_id (get-in tl [:memos 0 :id])}
                                               "user" usid))]
              (is (= 1 (count (:memos more))))
              (is (= "親A" (get-in more [:memos 0 :body]))))))
        (testing "V2P3-2.5-03 返信"
          (let [parent-id (get-in (tu/parse (tu/get-path app "/api/memos" "user" usid))
                                  [:memos 0 :id])
                r (tu/parse (tu/post-json app "/api/memos"
                                          {:body "返信1" :status "published" :parent_id parent-id}
                                          "user" usid))
                reps (tu/parse (tu/get-path app (str "/api/memos/" parent-id "/replies")
                                            "user" usid))
                tl (tu/parse (tu/get-path app "/api/memos" "user" usid))]
            (is (:ok r))
            (is (= parent-id (get-in r [:memo :parent_id])))
            (is (some #(= "返信1" (:body %)) (:replies reps)))
            (is (not-any? #(= "返信1" (:body %)) (:memos tl)))))
        (testing "V2P3-2.5-10 下書き"
          (let [d (tu/parse (tu/post-json app "/api/memos"
                                          {:body "下書きX" :status "draft"}
                                          "user" usid))
                drafts (tu/parse (tu/get-path app "/api/memos/drafts" "user" usid))
                tl (tu/parse (tu/get-path app "/api/memos" "user" usid))]
            (is (:ok d))
            (is (some #(= "下書きX" (:body %)) (:memos drafts)))
            (is (not-any? #(= "下書きX" (:body %)) (:memos tl)))
            (testing "V2P3-2.5-07 公開"
              (let [pub (tu/parse (tu/post-json app (str "/api/memos/" (get-in d [:memo :id]) "/publish")
                                                {} "user" usid))]
                (is (:ok pub))
                (is (= "published" (get-in pub [:memo :status])))))))
        (testing "空公開は body_required"
          (let [d (tu/parse (tu/post-json app "/api/memos" {:body "" :status "draft"} "user" usid))
                pub (tu/parse (tu/post-json app (str "/api/memos/" (get-in d [:memo :id]) "/publish")
                                            {} "user" usid))]
            (is (not (:ok pub)))
            (is (= "body_required" (:code pub)))))
        (testing "V2P3-2.5-05/3.4 編集窓"
          (let [m (tu/parse (tu/post-json app "/api/memos"
                                          {:body "窓内" :status "published"} "user" usid))
                mid (get-in m [:memo :id])
                u1 (tu/parse (tu/put-json app (str "/api/memos/" mid)
                                          {:body "窓内2" :tags ["t1"]} "user" usid))]
            (is (:ok u1))
            (is (= "窓内2" (get-in u1 [:memo :body])))
            (is (not= (get-in m [:memo :content_saved_at])
                      (get-in u1 [:memo :content_saved_at])))
            (db/update-memo-body! (:ds sys) mid "窓内2"
                                  (time/format-instant (.minus (Instant/parse (time/now-utc))
                                                               (Duration/ofMinutes 31))))
            (let [u2 (tu/parse (tu/put-json app (str "/api/memos/" mid)
                                            {:body "遅すぎ"} "user" usid))]
              (is (not (:ok u2)))
              (is (= "edit_window_closed" (:code u2))))
            (testing "V2P3-3.4-03 親の窓外でも返信可"
              (let [rep (tu/parse (tu/post-json app "/api/memos"
                                                {:body "窓外返信" :status "published" :parent_id mid}
                                                "user" usid))]
                (is (:ok rep))))))
        (testing "V2P3-2.5-06/3.6 ソフト削除連鎖"
          (let [p (tu/parse (tu/post-json app "/api/memos"
                                          {:body "削除親" :status "published"} "user" usid))
                pid (get-in p [:memo :id])
                _ (tu/post-json app "/api/memos"
                                {:body "削除子" :status "published" :parent_id pid}
                                "user" usid)
                del (tu/parse (tu/delete-path app (str "/api/memos/" pid) "user" usid))
                g (tu/parse (tu/get-path app (str "/api/memos/" pid) "user" usid))]
            (is (:ok del))
            (is (not (:ok g)))
            (is (= "memo_not_found" (:code g)))))
        (testing "V2P3-2.5-09 検索"
          (let [_ (tu/post-json app "/api/memos"
                                {:body "検索ヒット防除" :status "published" :tags ["防除"]}
                                "user" usid)
                hit (tu/parse (tu/get-query app "/api/memos/search" {:q "防除"} "user" usid))
                miss (tu/parse (tu/get-query app "/api/memos/search"
                                             {:q "防除" :exclude "ヒット"} "user" usid))]
            (is (some #(str/includes? (:body %) "検索ヒット") (:memos hit)))
            (is (not-any? #(str/includes? (:body %) "検索ヒット") (:memos miss)))))
        (testing "V2P3-2.5-12/13 ブックマーク"
          (let [m (tu/parse (tu/post-json app "/api/memos"
                                          {:body "星メモ" :status "published"} "user" usid))
                mid (get-in m [:memo :id])
                b1 (tu/parse (tu/post-json app (str "/api/memos/" mid "/bookmark") {} "user" usid))
                b2 (tu/parse (tu/post-json app (str "/api/memos/" mid "/bookmark") {} "user" usid))
                list1 (tu/parse (tu/get-path app "/api/memos/bookmarks" "user" usid))
                rm (tu/parse (tu/delete-path app (str "/api/memos/" mid "/bookmark") "user" usid))
                miss (tu/parse (tu/delete-path app (str "/api/memos/" mid "/bookmark") "user" usid))]
            (is (:ok b1))
            (is (:ok b2))
            (is (some #(= mid (:id %)) (:memos list1)))
            (is (:ok rm))
            (is (= "bookmark_not_found" (:code miss)))))
        (testing "V2P3-2.5-15 管理者は投稿・削除可、他人編集不可"
          (let [m (tu/parse (tu/post-json app "/api/memos"
                                          {:body "利用者メモ" :status "published"} "user" usid))
                mid (get-in m [:memo :id])
                admin-post (tu/parse (tu/post-json app "/api/memos"
                                                   {:body "管理者メモ" :status "published"}
                                                   "admin" asid))
                forbidden (tu/parse (tu/put-json app (str "/api/memos/" mid)
                                                 {:body "改ざん"} "admin" asid))
                del (tu/parse (tu/delete-path app (str "/api/memos/" mid) "admin" asid))]
            (is (:ok admin-post))
            (is (= "forbidden_memo" (:code forbidden)))
            (is (:ok del))))
        (testing "V2P3-2.5-16 検証失敗"
          (is (= "body_too_long"
                 (:code (tu/parse (tu/post-json app "/api/memos"
                                                {:body (long-body 2001) :status "published"}
                                                "user" usid)))))
          (is (= "tag_limit"
                 (:code (tu/parse (tu/post-json app "/api/memos"
                                                {:body "x" :status "published"
                                                 :tags (mapv str (range 21))}
                                                "user" usid)))))
          (is (= "link_invalid"
                 (:code (tu/parse (tu/post-json app "/api/memos"
                                                {:body "x" :status "published"
                                                 :links ["not-a-url"]}
                                                "user" usid)))))
          (is (= "body_required"
                 (:code (tu/parse (tu/post-json app "/api/memos"
                                                {:body "  " :status "published"}
                                                "user" usid))))))
        (testing "V2P3-2.5-11 添付"
          (let [m (tu/parse (tu/post-json app "/api/memos"
                                          {:body "添付先" :status "draft"} "user" usid))
                mid (get-in m [:memo :id])
                tmp (doto (File/createTempFile "memo-att" ".txt")
                      (.deleteOnExit)
                      (spit "hello-memo" :encoding "UTF-8"))
                upload {:tempfile tmp :filename "note.txt" :content-type "text/plain"}
                r (memos/add-attachment sys actor mid upload)
                aid (get-in r [:attachment :id])
                got (memos/get-attachment sys actor mid aid)]
            (is (:ok r))
            (is (:ok got))
            (is (.isFile (:file got)))
            (is (:ok (memos/delete-attachment sys actor mid aid)))))
        (testing "テーブルがある"
          (is (contains? (tu/table-names (:ds sys)) "memos"))
          (is (contains? (tu/table-names (:ds sys)) "memo_bookmarks")))))))

(deftest v2p3-ui-handlers-coverage
  (testing "検索トグルと読み込み"
    (let [tog (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"
                          :memo-search-advanced? false}
                         [:submit {:act "memo-search-advanced-toggle" :form {}}])]
      (is (true? (get-in tog [:state :memo-search-advanced?]))))
    (let [sel (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"
                          :memos [{:id 9 :body "親"}]}
                         [:submit {:act "memo-select" :form {:id "9"}}])]
      (is (= "9" (str (get-in sel [:state :memo-selected]))))
      (is (some #(= :api (first %)) (:fx sel))))
    (let [loaded (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"
                             :memos []}
                            [:memos-loaded {:ok true :memos [{:id 1 :body "a"}]}])]
      (is (= 1 (count (get-in loaded [:state :memos])))))
    (let [err (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"}
                         [:memo-save-result {:ok false :code "tag_limit"}])]
      (is (true? (get-in err [:state :flash :error?]))))))
