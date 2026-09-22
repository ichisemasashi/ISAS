(ns isas.v2-p3-coverage-test
  "工程3メモの cloverage 分岐を埋める。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.http :as http]
            [isas.memos :as memos]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock])
  (:import [java.io File]
           [java.time Instant Duration]))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "v2p3c@example.com")
        usid (tu/user-sid app "v2p3c@example.com" pw)
        uid (:id (db/find-user-by-email (:ds sys) "v2p3c@example.com"))]
    {:app app :asid asid :usid usid :uid uid
     :actor {:kind "user" :id uid :email "v2p3c@example.com"}}))

(defn- tmp-file [contents]
  (doto (File/createTempFile "memo-cov" ".txt")
    (.deleteOnExit)
    (spit (str contents) :encoding "UTF-8")))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"} :ui-lang "ja"} opts)))

(deftest v2p3-memos-domain-coverage
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid uid actor]} (farm sys)
            admin-actor {:kind "admin" :id (:id (db/find-admin-by-email (:ds sys) "admin@example.com"))
                         :email "admin@example.com"}]
        (testing "memo-root default and custom"
          (is (= "data/memo-files" (memos/memo-root {})))
          (is (= "/tmp/m" (memos/memo-root {:memo-dir "/tmp/m"}))))
        (testing "present / normalize edge forms"
          (let [mid (get-in (memos/create-memo sys actor {:body "表示" :status "published"}) [:memo :id])
                as-admin (memos/get-memo sys admin-actor mid)
                draft (memos/create-memo sys actor {:body nil :status "draft"})]
            (is (true? (get-in as-admin [:memo :can_delete])))
            (is (nil? (get-in as-admin [:memo :editable_until])))
            (is (:ok draft)))
          (is (= "tag_invalid"
                 (:code (memos/create-memo sys actor {:body "x" :status "published" :tags [nil]}))))
          (let [long-name (str (apply str (repeat 80 "a")) ".txt")
                mid (get-in (memos/create-memo sys actor {:body "長名" :status "draft"}) [:memo :id])
                r (memos/add-attachment sys actor mid {:tempfile (tmp-file "z")
                                                       :filename (str "dir\\\\" long-name)
                                                       :content-type "  "})]
            (is (:ok r)))
          (let [mid (get-in (memos/create-memo sys actor {:body "空名" :status "draft"}) [:memo :id])
                r (memos/add-attachment sys actor mid {:tempfile (tmp-file "z")
                                                       :filename nil
                                                       :content-type nil})]
            (is (:ok r)))
          (is (= "link_invalid"
                 (:code (memos/create-memo sys actor {:body "x" :status "published" :links [nil]}))))
          (is (:ok (memos/update-memo sys actor
                                      (get-in (memos/create-memo sys actor {:body "下直" :status "draft"})
                                              [:memo :id])
                                      {:body "下直2"})))
          (let [did (get-in (memos/create-memo sys actor {:body "下誤" :status "draft" :tags ["a"]})
                            [:memo :id])]
            (is (:ok (memos/update-memo sys actor did {})))
            (is (= "body_too_long"
                   (:code (memos/update-memo sys actor did {:body (apply str (repeat 2001 "あ"))}))))
            (is (= "tag_limit"
                   (:code (memos/update-memo sys actor did {:tags (mapv str (range 21))}))))
            (is (= "link_invalid"
                   (:code (memos/update-memo sys actor did {:links ["nope"]})))))
          (is (true? (get-in (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"
                                         :memo-before-id "9" :memos [{:id 1}]}
                                        [:memos-loaded {:ok false :code "unauthorized"}])
                             [:state :flash :error?])))
          (is (vector? (get-in (ui/handle {:page :memos-drafts :kind "user" :session {:email "a"} :ui-lang "ja"}
                                          [:memo-drafts-loaded {:ok true :memos nil}])
                               [:state :memo-drafts])))
          (is (vector? (get-in (ui/handle {:page :memos-bookmarks :kind "user" :session {:email "a"} :ui-lang "ja"}
                                          [:memo-bookmarks-loaded {:ok true}])
                               [:state :memo-bookmarks])))
          (is (vector? (get-in (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"}
                                          [:memo-replies-loaded {:ok true}])
                               [:state :memo-replies])))
          (is (= :api (first (first (:fx (ui/handle {:page :memos :kind "user" :session {:email "a"}
                                                     :ui-lang "ja"}
                                                    [:submit {:act "memo-search"
                                                              :form {:q "x"}}]))))))
          (is (:ok (memos/search sys actor {:author "admin@example.com"})))
          (let [mid (get-in (memos/create-memo sys actor {:body "壊れた時刻" :status "published"})
                            [:memo :id])]
            (jdbc/execute-one! (:ds sys)
                               ["UPDATE memos SET content_saved_at = ? WHERE id = ?" "not-an-instant" mid])
            (let [g (memos/get-memo sys actor mid)]
              (is (false? (get-in g [:memo :can_edit])))
              (is (nil? (get-in g [:memo :editable_until]))))
            (jdbc/execute-one! (:ds sys)
                               ["UPDATE memos SET content_saved_at = NULL WHERE id = ?" mid])
            (is (false? (get-in (memos/get-memo sys actor mid) [:memo :can_edit])))
            (is (= "edit_window_closed"
                   (:code (memos/update-memo sys actor mid {:body "だめ"})))))
          (let [mid (get-in (memos/create-memo sys actor {:body "窓閉" :status "published"}) [:memo :id])]
            (db/update-memo-body! (:ds sys) mid "窓閉"
                                  (time/format-instant (.minus (Instant/parse (time/now-utc))
                                                               (Duration/ofMinutes 31))))
            (is (= "edit_window_closed"
                   (:code (memos/update-memo sys actor mid {:body "遅"})))))
          (is (some? (db/insert-memo! (:ds sys) {:parent-id nil :author-kind "user" :author-id uid
                                                 :status "draft" :body nil
                                                 :published-at nil :content-saved-at nil})))
          (db/replace-memo-tags! (:ds sys) 1 [])
          (db/replace-memo-links! (:ds sys) 1 [])
          ;; 既に削除済みの id を再度消す（AND deleted_at IS NULL の片側）
          (let [mid (get-in (memos/create-memo sys actor {:body "二重消" :status "published"}) [:memo :id])]
            (is (:ok (memos/soft-delete-memo sys actor mid)))
            (is (= 0 (:descendants (db/soft-delete-memo-tree! (:ds sys) mid))))))
        (testing "tags as scalar / tag_invalid / link_limit"
          (is (= "tag_invalid"
                 (:code (memos/create-memo sys actor {:body "x" :status "published" :tags [""]}))))
          (is (= "tag_invalid"
                 (:code (memos/create-memo sys actor {:body "x" :status "published"
                                                      :tags [(apply str (repeat 101 "a"))]}))))
          (is (= "link_limit"
                 (:code (memos/create-memo sys actor {:body "x" :status "published"
                                                      :links (mapv #(str "https://e.com/" %) (range 21))}))))
          (let [r (memos/create-memo sys actor {:body "単タグ" :status "published" :tags "東"})]
            (is (:ok r))
            (is (= ["東"] (get-in r [:memo :tags])))))
        (testing "get-memo / replies not found / parent_not_found"
          (is (= "memo_not_found" (:code (memos/get-memo sys actor 999999))))
          (is (= "memo_not_found" (:code (memos/list-replies sys actor 999999))))
          (is (= "parent_not_found"
                 (:code (memos/create-memo sys actor {:body "返" :status "published" :parent_id 999999}))))
          (let [ok (memos/get-memo sys actor (get-in (memos/create-memo sys actor {:body "見える" :status "published"})
                                                     [:memo :id]))]
            (is (:ok ok))))
        (testing "update body/tags/links omit and errors"
          (let [mid (get-in (memos/create-memo sys actor {:body "直す" :status "published"
                                                          :tags ["a"] :links ["https://a.example"]})
                            [:memo :id])]
            (is (:ok (memos/update-memo sys actor mid {})))
            (is (= "body_too_long"
                   (:code (memos/update-memo sys actor mid {:body (apply str (repeat 2001 "あ"))}))))
            (is (= "tag_limit"
                   (:code (memos/update-memo sys actor mid {:tags (mapv str (range 21))}))))
            (is (= "link_invalid"
                   (:code (memos/update-memo sys actor mid {:links ["ftp://x"]}))))
            (is (= "body_required"
                   (:code (memos/update-memo sys actor mid {:body "  "}))))
            (is (= "memo_not_found" (:code (memos/update-memo sys actor 999999 {:body "x"}))))
            (is (= "forbidden_memo"
                   (:code (memos/update-memo sys admin-actor mid {:body "盗む"}))))))
        (testing "publish branches"
          (is (= "memo_not_found" (:code (memos/publish-memo sys actor 999999))))
          (let [pub (memos/create-memo sys actor {:body "既公開" :status "published"})]
            (is (= "not_draft" (:code (memos/publish-memo sys actor (get-in pub [:memo :id]))))))
          (let [d (memos/create-memo sys actor {:body "下書き公開" :status "draft"})]
            (is (:ok (memos/publish-memo sys actor (get-in d [:memo :id])))))
          (let [parent (get-in (memos/create-memo sys actor {:body "親公開" :status "published"}) [:memo :id])
                child (get-in (memos/create-memo sys actor {:body "子下" :status "draft" :parent_id parent})
                              [:memo :id])]
            ;; 連鎖削除だと子も消えるので、親だけ消して publish の parent_not_found を踏む。
            (jdbc/execute-one! (:ds sys)
                               ["UPDATE memos SET deleted_at = ?, updated_at = ? WHERE id = ?"
                                (time/now-utc) (time/now-utc) parent])
            (is (= "parent_not_found" (:code (memos/publish-memo sys actor child))))))
        (testing "delete forbidden / not found"
          (is (= "memo_not_found" (:code (memos/soft-delete-memo sys actor 999999))))
          (let [mid (get-in (memos/create-memo sys actor {:body "他人消す" :status "published"}) [:memo :id])
                other-pw (tu/invite-pw app asid "v2p3c2@example.com")
                _other-sid (tu/user-sid app "v2p3c2@example.com" other-pw)
                other {:kind "user" :id (:id (db/find-user-by-email (:ds sys) "v2p3c2@example.com"))}]
            (is (= "forbidden_memo" (:code (memos/soft-delete-memo sys other mid))))
            (is (:ok (memos/soft-delete-memo sys actor mid)))))
        (testing "search advanced branches"
          (let [_ (memos/create-memo sys actor {:body "検索期間" :status "published" :tags ["期間"]})
                hit (memos/search sys actor {:q "期間" :from "2020-01-01" :to "2099-12-31"
                                             :author "v2p3c@example.com" :scope "parents" :drafts "1"})
                bad (memos/search sys actor {:from "bad-day" :to "also-bad" :author "nobody@x.invalid"})]
            (is (pos? (count (:memos hit))))
            (is (:ok bad))))
        (testing "attachments edges"
          (let [draft-id (get-in (memos/create-memo sys actor {:body "添付下" :status "draft"}) [:memo :id])
                mid (get-in (memos/create-memo sys actor {:body "添付公" :status "published"}) [:memo :id])
                f (tmp-file "x")]
            (is (= "memo_not_found" (:code (memos/add-attachment sys actor 999999 {:tempfile f :filename "a.txt"}))))
            (is (= "forbidden_memo"
                   (:code (memos/add-attachment sys admin-actor mid {:tempfile f :filename "a.txt"}))))
            (is (= "attachment_invalid"
                   (:code (memos/add-attachment sys actor draft-id {:filename "a.txt"}))))
            (is (= "attachment_invalid"
                   (:code (memos/add-attachment sys actor draft-id {:tempfile (io/file "/no/such") :filename "a.txt"}))))
            (dotimes [i 20]
              (is (:ok (memos/add-attachment sys actor draft-id {:tempfile (tmp-file i)
                                                                :filename (str "f" i ".txt")
                                                                :content-type ""}))))
            (is (= "attachment_limit"
                   (:code (memos/add-attachment sys actor draft-id {:tempfile (tmp-file "over")
                                                                   :filename "over.txt"}))))
            (is (= "memo_not_found" (:code (memos/get-attachment sys actor 999999 1))))
            (is (= "attachment_not_found" (:code (memos/get-attachment sys actor draft-id 999999))))
            (let [aid (:id (first (db/list-memo-attachments (:ds sys) draft-id)))
                  att (db/find-memo-attachment (:ds sys) draft-id aid)
                  _ (io/delete-file (io/file (:memo-dir sys) (:body_ref att)) true)]
              (is (= "attachment_not_found" (:code (memos/get-attachment sys actor draft-id aid)))))
            (is (= "memo_not_found" (:code (memos/delete-attachment sys actor 999999 1))))
            (is (= "forbidden_memo" (:code (memos/delete-attachment sys admin-actor mid 1))))
            (is (= "attachment_not_found" (:code (memos/delete-attachment sys actor draft-id 999999))))
            (db/update-memo-body! (:ds sys) mid "添付公"
                                  (time/format-instant (.minus (Instant/parse (time/now-utc))
                                                               (Duration/ofMinutes 31))))
            (is (= "edit_window_closed"
                   (:code (memos/add-attachment sys actor mid {:tempfile (tmp-file "late")
                                                              :filename "late.txt"}))))
            (let [aid2 (get-in (memos/add-attachment sys actor draft-id
                                                     {:tempfile (tmp-file "delme")
                                                      :filename "delme.txt"})
                               [:attachment :id])]
              (db/update-memo-body! (:ds sys) draft-id "添付下"
                                    (time/format-instant (.minus (Instant/parse (time/now-utc))
                                                                 (Duration/ofMinutes 31))))
              (db/publish-memo! (:ds sys) draft-id (time/now-utc)
                                (time/format-instant (.minus (Instant/parse (time/now-utc))
                                                             (Duration/ofMinutes 31))))
              (is (= "edit_window_closed"
                     (:code (memos/delete-attachment sys actor draft-id aid2)))))))
        (testing "bookmark draft / missing"
          (is (= "memo_not_found" (:code (memos/add-bookmark sys actor 999999))))
          (let [d (get-in (memos/create-memo sys actor {:body "下書印" :status "draft"}) [:memo :id])]
            (is (= "forbidden_memo" (:code (memos/add-bookmark sys actor d)))))
          (is (pos? (db/count-memo-bookmarks (:ds sys)
                                             (get-in (let [m (memos/create-memo sys actor {:body "印数" :status "published"})
                                                           mid (get-in m [:memo :id])]
                                                       (memos/add-bookmark sys actor mid)
                                                       m)
                                                     [:memo :id])))))
        (testing "db search-memos branches via API"
          (let [r (tu/parse (tu/get-query app "/api/memos/search"
                                          {:q "検索期間" :drafts "true" :scope "parents"
                                           :from "2020-01-01" :to "2099-01-01"
                                           :author "v2p3c@example.com"}
                                          "user" usid))]
            (is (:ok r))))
        (testing "http catch + attachments HTTP"
          (is (= "body_required"
                 (:code (tu/parse (app (tu/as-user (-> (mock/request :post "/api/memos")
                                                       (mock/content-type "application/json")
                                                       (mock/body "{"))
                                                     "user" usid))))))
          (let [mid (get-in (tu/parse (tu/post-json app "/api/memos"
                                                    {:body "HTTP添付" :status "draft"} "user" usid))
                            [:memo :id])
                f (tmp-file "http-att")
                up (app (tu/as-user (assoc (mock/request :post (str "/api/memos/" mid "/attachments"))
                                           :multipart-params {"file" {:filename "日本語 note.txt"
                                                                      :content-type "text/plain"
                                                                      :tempfile f}})
                                    "user" usid))
                body (tu/parse up)
                aid (get-in body [:attachment :id])
                got (app (tu/as-user (mock/request :get (str "/api/memos/" mid "/attachments/" aid))
                                     "user" usid))
                miss (tu/parse (app (tu/as-user (mock/request :get (str "/api/memos/" mid "/attachments/99999"))
                                                "user" usid)))
                del (tu/parse (app (tu/as-user (mock/request :delete (str "/api/memos/" mid "/attachments/" aid))
                                               "user" usid)))]
            (is (:ok body))
            (is (= 200 (:status got)))
            (is (re-find #"attachment" (str (get-in got [:headers "Content-Disposition"]
                                                    (get-in got [:headers "content-disposition"])))))
            (is (= "attachment_not_found" (:code miss)))
            (is (:ok del))
            (is (= "body_required"
                   (:code (tu/parse (app (tu/as-user (-> (mock/request :put (str "/api/memos/" mid))
                                                         (mock/content-type "application/json")
                                                         (mock/body "{"))
                                                       "user" usid)))))))
          (is (= [:memo-attachment-delete "1" "2"]
                 (http/match-api :delete "/api/memos/1/attachments/2"))))))))

(deftest v2p3-ui-coverage
  (testing "code-message memo codes"
    (doseq [c ["memo_not_found" "body_required" "tag_invalid" "link_limit" "link_invalid"
               "attachment_limit" "edit_window_closed" "forbidden_memo" "parent_not_found"
               "not_draft" "bookmark_not_found"]]
      (is (string? (ui/with-ui-lang {:ui-lang "ja"} #(ui/code-message c))))
      (is (string? (ui/with-ui-lang {:ui-lang "en"} #(ui/code-message c))))))
  (testing "rich memo card / empty drafts bookmarks / phone admin"
    (let [h (html {:page :memos
                   :memos [{:id 1 :body "親" :status "published" :bookmarked true :can_delete true
                            :published_at "2026-09-22T01:00:00Z"
                            :tags ["東"] :links ["https://example.com"]
                            :attachments [{:id 9 :filename "a.pdf"}]
                            :reply_count 2 :author_email "a@example.com"}]})]
      (is (re-find #"#東" h))
      (is (re-find #"https://example.com" h))
      (is (re-find #"a\.pdf" h))
      (is (re-find #"（2）" h))
      ;; UTC 01:00 → 東京 10:00
      (is (re-find #"10:00" h)))
    (let [h (html {:page :memos
                   :memos [{:id 1 :body "編集可" :status "published" :can_edit true
                            :editable_until "2026-09-22T03:40:00Z"
                            :author_email "a@example.com" :tags [] :links []
                            :attachments [{:id 9 :filename "a.pdf"}]}]})]
      (is (re-find #"編集する|memo-edit|data-act=\"memo-update\"" h))
      (is (re-find #"memo-attach|data-auto-upload|ファイルを選ぶ" h))
      (is (re-find #"添付を外す|memo-detach" h)))
    (let [h (html {:page :memos
                   :memo-last-saved {:id 7 :body "直前投稿" :status "published" :can_edit true
                                     :author_email "a@example.com"
                                     :attachments [{:id 1 :filename "x.txt"}]}})]
      (is (re-find #"memo-compose-saved" h))
      (is (re-find #"直前投稿" h))
      (is (re-find #"memo-edit|data-act=\"memo-update\"" h)))
    (is (re-find #"10:00" (ui/format-display-instant "2026-09-22T01:00:00Z")))
    (is (re-find #"2026" (ui/format-display-instant "2026-09-22T10:30")))
    (is (= "nope" (ui/format-display-instant "nope")))
    (is (nil? (ui/format-display-instant "")))
    (is (nil? (ui/format-display-instant nil)))
    (is (= "bogusZ" (ui/format-display-instant "bogusZ")))
    (let [h (html {:page :memos
                   :memo-search-active? true
                   :memo-search-q "防除"
                   :memo-search-results [{:id 3 :body "ヒット" :status "published"
                                          :author_email "a@example.com"}]
                   :memos [{:id 1 :body "タイムライン用" :status "published"
                            :author_email "a@example.com"}]})]
      (is (re-find #"検索文字列は「防除」" h))
      (is (re-find #"検索結果" h))
      (is (re-find #"ヒット" h))
      (is (re-find #"タイムライン用" h))
      (is (re-find #"タイムライン" h)))
    (let [h (html {:page :memos
                   :memo-selected 1
                   :memo-selected-row {:id 1 :body "親" :status "published" :can_edit true
                                       :author_email "a@example.com"}
                   :memo-replies [{:id 2 :body "子返信" :status "published"
                                   :author_email "a@example.com"}]})]
      (is (re-find #"スレッドを閉じる|memo-close-thread" h))
      (is (re-find #"子返信" h))
      (is (re-find #">スレッド<" h)))
    (is (re-find #"下書きはありません" (html {:page :memos-drafts :memo-drafts []})))
    (is (re-find #"ブックマークはありません" (html {:page :memos-bookmarks :memo-bookmarks []})))
    (is (re-find #"memo-compose|タイムライン|メモ" (html {:page :memos :kind "admin" :narrow? true}))))
  (testing "session-loaded memo paths"
    (let [r (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja" :narrow? false}
                       [:session-loaded {:ok true :email "a"}])]
      (is (= "/api/memos" (nth (first (:fx r)) 2))))
    (let [r (ui/handle {:page :memos-drafts :kind "user" :session {:email "a"} :ui-lang "ja"}
                       [:session-loaded {:ok true :email "a"}])]
      (is (str/includes? (nth (first (:fx r)) 2) "drafts")))
    (let [r (ui/handle {:page :memos-bookmarks :kind "user" :session {:email "a"} :ui-lang "ja"}
                       [:session-loaded {:ok true :email "a"}])]
      (is (str/includes? (nth (first (:fx r)) 2) "bookmarks")))
    (let [r (ui/handle {:page :memos :kind "user" :session nil :ui-lang "ja" :memo-admin-tried? false}
                       [:session-loaded {:ok false}])]
      (is (= "admin" (get-in r [:state :kind]))))
    (let [r (ui/handle {:page :memos :kind "user" :session nil :ui-lang "ja" :memo-admin-tried? true}
                       [:session-loaded {:ok false}])]
      (is (= "user" (get-in r [:state :kind]))))
    (let [r (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja" :narrow? true}
                       [:session-loaded {:ok true :email "a"}])]
      (is (= "/api/memos" (nth (first (:fx r)) 2)))))
  (testing "loaders and result handlers"
    (let [base {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"
                :memos [] :memo-selected 1}]
      (is (= 2 (count (get-in (ui/handle (assoc base :memo-before-id "3")
                                         [:memos-loaded {:ok true :memos [{:id 1} {:id 2}]}])
                              [:state :memos]))))
      (is (true? (get-in (ui/handle base [:memos-loaded {:ok false :code "unauthorized"}])
                         [:state :flash :error?])))
      (is (vector? (get-in (ui/handle (assoc base :page :memos-drafts)
                                      [:memo-drafts-loaded {:ok true :memos [{:id 1}]}])
                           [:state :memo-drafts])))
      (is (true? (get-in (ui/handle (assoc base :page :memos-drafts)
                                    [:memo-drafts-loaded {:ok false :code "unauthorized"}])
                         [:state :flash :error?])))
      (is (vector? (get-in (ui/handle (assoc base :page :memos-bookmarks)
                                      [:memo-bookmarks-loaded {:ok true :memos []}])
                           [:state :memo-bookmarks])))
      (is (true? (get-in (ui/handle (assoc base :page :memos-bookmarks)
                                    [:memo-bookmarks-loaded {:ok false :code "unauthorized"}])
                         [:state :flash :error?])))
      (is (vector? (get-in (ui/handle base [:memo-replies-loaded {:ok true :replies [{:id 2}]}])
                           [:state :memo-replies])))
      (is (true? (get-in (ui/handle base [:memo-replies-loaded {:ok false :code "memo_not_found"}])
                         [:state :flash :error?])))
      (is (map? (get-in (ui/handle base [:memo-loaded {:ok true :memo {:id 1 :body "x"}}])
                        [:state :memo-selected-row])))
      (is (nil? (get-in (ui/handle base [:memo-loaded {:ok false :code "memo_not_found"}])
                        [:state :memo-selected])))
      (doseq [[op body] [[:memo-save-result {:ok true :memo {:id 1 :status "published" :body "a"}}]
                         [:memo-save-result {:ok true :memo {:id 2 :status "draft" :body "d"}}]
                         [:memo-save-result {:ok false :code "tag_limit"}]
                         [:memo-publish-result {:ok true :memo {:id 1}}]
                         [:memo-publish-result {:ok false :code "body_required"}]
                         [:memo-delete-result {:ok true}]
                         [:memo-delete-result {:ok false :code "memo_not_found"}]
                         [:memo-bookmark-result {:ok true :memo {:id 1}}]
                         [:memo-bookmark-result {:ok true}]
                         [:memo-bookmark-result {:ok false :code "bookmark_not_found"}]
                         [:memo-attach-result {:ok true}]
                         [:memo-attach-result {:ok false :code "attachment_limit"}]
                         [:memo-search-result {:ok true :memos [{:id 1}]}]
                         [:memo-search-result {:ok false :code "unauthorized"}]]]
        (is (map? (:state (ui/handle (assoc base :pending-flash-near "memo-compose-section") [op body])))))))
  (testing "submit acts"
    (let [base {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"
                :memos [{:id 1 :body "親"}] :memo-selected 1}]
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-publish-new"
                                                               :form {:body "本文" :status "published"}}]))))))
      (is (nil? (some #(= :api (first %))
                      (:fx (ui/handle base [:submit {:act "memo-publish-new"
                                                     :form {:body "  " :status "published"}}])))))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-draft-new"
                                                               :form {:body "" :status "draft"}}]))))))
      (is (nil? (some #(= :api (first %))
                      (:fx (ui/handle base [:submit {:act "memo-reply"
                                                     :form {:parent_id "" :body "x"}}])))))
      (is (nil? (some #(= :api (first %))
                      (:fx (ui/handle base [:submit {:act "memo-reply"
                                                     :form {:parent_id "1" :body "  " :status "published"}}])))))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-reply"
                                                               :form {:parent_id "1" :body "返"
                                                                      :status "published"}}]))))))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-reply"
                                                               :form {:parent_id "1" :body ""
                                                                      :status "draft"}}]))))))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-search"
                                                               :form {:q "防除" :exclude "x"
                                                                      :from "2020-01-01" :to "2020-01-02"
                                                                      :author "a@b.c"}}]))))))
      (is (vector? (get-in (ui/handle base [:memo-search-result {:ok true}])
                           [:state :memo-search-results])))
      (is (true? (get-in (ui/handle base [:memo-search-result {:ok true :memos [{:id 9}]}])
                         [:state :memo-search-active?])))
      (is (nil? (get-in (ui/handle (assoc base :memo-search-active? true
                                          :memo-search-results [{:id 1}])
                                    [:submit {:act "memo-search-clear" :form {}}])
                        [:state :memo-search-results])))
      (is (nil? (get-in (ui/handle (assoc base :memo-selected 1 :memo-replies [{:id 2}])
                                    [:submit {:act "memo-close-thread" :form {}}])
                        [:state :memo-selected])))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-update"
                                                               :form {:id "1" :body "直"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-update" :form {}}])
                  [:state :flash :error?]))
      (is (= "memo-thread-section"
             (get-in (ui/handle (assoc base :memo-selected 1)
                                [:submit {:act "memo-update" :form {:id "1" :body "直"}}])
                     [:state :pending-flash-near])))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-detach"
                                                               :form {:id "1" :attachment_id "2"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-detach" :form {:id "1"}}])
                  [:state :flash :error?]))
      (is (get-in (ui/handle base [:submit {:act "memo-detach" :form {:attachment_id "2"}}])
                  [:state :flash :error?]))
      (is (= "memo-compose-section"
             (get-in (ui/handle (dissoc base :memo-selected)
                                [:submit {:act "memo-attach" :form {:id "1" :file "x"}}])
                     [:state :pending-flash-near])))
      (is (= "memo-thread-section"
             (get-in (ui/handle (assoc base :memo-selected 1)
                                [:submit {:act "memo-attach" :form {:id "1" :file "x"}}])
                     [:state :pending-flash-near])))
      (is (= "memo-drafts-list"
             (get-in (ui/handle (assoc base :page :memos-drafts)
                                [:submit {:act "memo-attach" :form {:id "1" :file "x"}}])
                     [:state :pending-flash-near])))
      (is (= "memo-timeline"
             (get-in (ui/handle (dissoc base :memo-selected)
                                [:submit {:act "memo-update" :form {:id "1" :body "直"}}])
                     [:state :pending-flash-near])))
      (is (= "memo-thread-section"
             (get-in (ui/handle (assoc base :memo-selected 1)
                                [:submit {:act "memo-update" :form {:id "1" :body "直"}}])
                     [:state :pending-flash-near])))
      (let [r (ui/handle (assoc base :memo-search-results [{:id 88 :body "検索経由"}]
                                :memos [])
                         [:submit {:act "memo-select" :form {:id "88"}}])]
        (is (= "88" (str (get-in r [:state :memo-selected])))))
      (let [r (ui/handle (assoc base :memo-last-saved {:id 77 :body "保存済" :can_edit true}
                                :memos [])
                         [:submit {:act "memo-select" :form {:id "77"}}])]
        (is (= "77" (str (get-in r [:state :memo-selected])))))
      (is (map? (:state (ui/handle base [:memo-attach-result
                                         {:ok true :memo {:id 1 :body "x" :can_edit true}}]))))
      (let [r (ui/handle {:page :memos :kind "admin" :session nil :ui-lang "ja"}
                         [:session-loaded {:ok false}])]
        (is (= "user" (get-in r [:state :kind]))))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-delete" :form {:id "1"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-delete" :form {}}]) [:state :flash :error?]))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-publish-draft" :form {:id "1"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-publish-draft" :form {}}]) [:state :flash :error?]))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-update-draft"
                                                               :form {:id "1" :body "d"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-update-draft" :form {}}]) [:state :flash :error?]))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-bookmark" :form {:id "1"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-bookmark" :form {}}]) [:state :flash :error?]))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-unbookmark" :form {:id "1"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-unbookmark" :form {}}]) [:state :flash :error?]))
      (is (= :upload-status (first (first (:fx (ui/handle base [:submit {:act "memo-attach"
                                                                         :form {:id "1" :file "x"}}]))))))
      (is (= :upload (first (second (:fx (ui/handle base [:submit {:act "memo-attach"
                                                                   :form {:id "1" :file "x"}}]))))))
      (is (get-in (ui/handle base [:submit {:act "memo-attach" :form {}}]) [:state :flash :error?]))
      (is (= :api (first (first (:fx (ui/handle base [:submit {:act "memo-more"
                                                               :form {:before_id "9"}}]))))))
      (is (some? (ui/handle base [:submit {:act "memo-more" :form {:before_id ""}}])))
      (let [r (ui/handle base [:path {:path "/memos" :search ""}])]
        (is (or (nil? (get-in r [:state :memo-selected]))
                (= :memos (get-in r [:state :page])))))
      ;; 残りの partial / not-covered
      (is (get-in (ui/handle base [:submit {:act "memo-select" :form {}}])
                  [:state :flash :error?]))
      (is (= :upload-status (first (first (:fx (ui/handle (assoc base :page :memos-drafts)
                                                          [:submit {:act "memo-attach"
                                                                    :form {:id "1" :file "x"}}]))))))
      (is (re-find #"アップロード完了"
                   (get-in (ui/handle base [:memo-attach-result
                                            {:ok true :memo {:id 1 :body "x" :can_edit true}}])
                           [:state :flash :text])))
      (is (= "x" (get-in (ui/handle (assoc base :memos [{:id 1 :body "old"}])
                                    [:memo-attach-result
                                     {:ok true :memo {:id 1 :body "x" :can_edit true}}])
                         [:state :memos 0 :body])))
      (let [r (ui/handle (assoc base
                                :memos nil :memo-replies nil :memo-drafts nil
                                :memo-bookmarks nil :memo-search-results nil
                                :memo-selected 1
                                :memo-selected-row {:id 1 :body "old"})
                         [:memo-attach-result
                          {:ok true :memo {:id 1 :body "差し替え" :can_edit true}}])]
        (is (= "差し替え" (get-in r [:state :memo-selected-row :body])))
        (is (= "差し替え" (get-in r [:state :memo-last-saved :body]))))
      (is (true? (get-in (ui/handle base [:memo-attach-result
                                          {:ok false :code "attachment_limit"}])
                         [:state :flash :error?])))
      (is (re-find #"本文は2000"
                   (get-in (ui/handle (assoc base :pending-flash-near "memo-compose-section")
                                      [:memo-save-result {:ok false :code "body_too_long"}])
                           [:state :flash :text])))
      (let [h (html {:page :memos
                     :memo-selected 99
                     :memos [{:id 1 :body "別"}]
                     :memo-selected-row nil})]
        (is (string? h)))
      (let [h (html {:page :memos
                     :memos [{:id 1 :body "下" :status "draft" :can_edit true
                              :author_email "a@example.com"}]})]
        (is (re-find #"下書き" h)))
      (let [h (html {:page :memos
                     :memo-selected 1
                     :memo-selected-row {:id 1 :body "親" :status "published" :can_edit true
                                         :author_email "a@example.com"}
                     :memo-replies []})]
        (is (re-find #"memo-attach|添付" h)))
      (let [r (ui/handle {:page :memos :kind "admin" :session nil :ui-lang "ja"}
                         [:boot {:path "/memos" :search "" :narrow? false}])]
        (is (= :memos (get-in r [:state :page]))))
      (is (nil? (http/match-api :patch "/api/memos/1/attachments/2")))
      (is (nil? (http/match-api :patch "/api/memos/1/bookmark")))
      (is (nil? (http/match-api :patch "/api/memos/1")))
      (is (= "user" (:kind (ui/apply-route {} "/memos" ""))))
      (is (true? (get-in (ui/handle {:page :memos-drafts :kind "user" :session {:email "a"} :ui-lang "ja"}
                                    [:memo-delete-result {:ok false :code "memo_not_found"}])
                         [:state :flash :error?])))
      (let [r (ui/handle {:page :memos-drafts :kind "user" :session {:email "a"} :ui-lang "ja"
                          :memos [{:id 1}]}
                         [:submit {:act "memo-select" :form {:id "1"}}])]
        (is (some? r)))
      (is (vector? (get-in (ui/handle {:page :memos :kind "user" :session {:email "a"} :ui-lang "ja"}
                                      [:memos-loaded {:ok true}])
                           [:state :memos])))
      (doseq [op [:memo-publish-result :memo-delete-result :memo-bookmark-result :memo-attach-result]]
        (is (map? (:state (ui/handle {:page :memos-bookmarks :kind "user" :session {:email "a"}
                                      :ui-lang "ja"}
                                     [op {:ok true :memo {:id 1}}])))))
      (is (re-find #"nav" (html {:page :memos :kind "admin"})))
      (is (string? (html {:page :memos-drafts})))
      (is (string? (html {:page :memos-bookmarks}))))))
