(ns isas.b1-test
  "基本試験仕様書_工程1 の項番に対応する自動試験。経路名は見ない。"
  (:require [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:session {:email "a@example.com"}} opts)))

(deftest b1-blocks-and-permissions
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "b1@example.com")
            usid (tu/user-sid app "b1@example.com" pw)]
        (testing "B1-2-01 入口は別"
          (is (not= (tu/page-html {:page :login :kind "user"})
                    (tu/page-html {:page :login :kind "admin"})))
          (is (= "/" (second (first (:fx (ui/guarded (assoc (ui/init-state) :page :home :kind "user")))))))
          (is (= "/admin" (second (first (:fx (ui/guarded (assoc (ui/init-state) :page :home :kind "admin"))))))))
        (testing "B1-2-02 認証・アカウント"
          (let [inv (accounts/invite sys "admin" 1 "auth@example.com")]
            (is (seq (:initial_password inv)))
            (is (true? (:ok (accounts/login sys "user" "auth@example.com" (:initial_password inv))))))
          (is (true? (:ok (tu/parse (tu/post-json app "/api/user/logout" {} "user" usid))))))
        (testing "B1-2-03 取消しは管理者だけ"
          (is (some? (http/match-api :post "/api/admin/users/revoke")))
          (is (nil? (http/match-api :post "/api/user/users/revoke")))
          (is (not (re-find #"取り消す" (html {:page :home :kind "user"})))))
        (testing "B1-2-04 利用者による招待"
          (let [sid (tu/user-sid app "b1@example.com" pw)]
            (is (seq (:initial_password (tu/parse (tu/post-json app "/api/user/invite"
                                                               {:email "from-user@example.com"} "user" sid)))))))
        (testing "B1-2-05 後工程のうち未着手は出さない（塗り・ガント・指示・言語切替）"
          (is (nil? (http/match-api :post "/api/user/paints")))
          (is (nil? (http/match-api :get "/api/user/gantt")))
          (is (nil? (http/match-api :post "/api/user/orders")))
          (is (not (re-find #"言語切替|English" (html {:page :home :kind "user"})))))
        (testing "B1-2-06 端末で入口を分けない"
          (is (= :login (:page (ui/route-for "/"))))
          (is (re-find #"利用者ログイン" (tu/page-html {:page :login :kind "user" :narrow? true}))))
        (testing "B1-2-07 公開登録は無い"
          (is (= :unknown (:page (ui/route-for "/signup")))))
        (testing "B1-3-01〜04 権限"
          (is (true? (:ok (accounts/login sys "admin" "admin@example.com" "ChangeMeAdmin1"))))
          (is (= "login_failed" (:code (accounts/login sys "admin" "b1@example.com" pw))))
          (is (= "login_failed" (:code (accounts/login sys "user" "admin@example.com" "ChangeMeAdmin1"))))
          (is (true? (:ok (accounts/login sys "user" "b1@example.com" pw))))
          (is (true? (:ok (accounts/invite sys "user" (:id (db/find-user-by-email (:ds sys) "b1@example.com"))
                                          "b1b@example.com"))))
          (is (true? (:ok (accounts/revoke-user sys (:id (db/find-user-by-email (:ds sys) "from-user@example.com"))))))
          (is (= 401 (:status (tu/post-json app "/api/admin/users/revoke" {:user_id 1} "user"
                                           (tu/user-sid app "b1@example.com" pw))))))
        (testing "B1-3-05 関係の切断はまだ無い"
          (is (nil? (http/match-api :post "/api/admin/relations/cut")))
          (is (not (re-find #"関係を切" (html {:page :home :kind "admin"})))))
        (testing "B1-3-06 同じメールを両方に置かない"
          (is (= "invite_duplicate_admin" (:code (accounts/invite sys "admin" 1 "admin@example.com")))))
        (testing "B1-3-07〜08 管理者は画面から増やさない"
          (is (= 1 (db/count-admins (:ds sys))))
          (is (not (re-find #"管理者を招待" (html {:page :invite :kind "admin"})))))))))

(deftest b1-flows-and-data
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)]
        (testing "B1-4.1-01 識別子はメール"
          (is (re-find #"メールアドレス" (tu/page-html {:page :login :kind "user"})))
          (is (not (re-find #"ログインID" (tu/page-html {:page :login :kind "user"})))))
        (testing "B1-4.1-02 初期パスワードはその場だけ"
          (let [inv (tu/parse (tu/post-json app "/api/admin/invite" {:email "once@example.com"} "admin" asid))]
            (is (seq (:initial_password inv)))
            (is (nil? (:initial_password (tu/parse (tu/get-path app "/api/admin/users" "admin" asid)))))))
        (testing "B1-4.1-03 再設定メールだけ。招待メールは送らない"
          (let [n (count @(:sent sys))]
            (tu/post-json app "/api/admin/invite" {:email "nomail@example.com"} "admin" asid)
            (is (= n (count @(:sent sys)))))
          (tu/post-json app "/api/admin/password/reset/request" {:email "admin@example.com"})
          (is (seq @(:sent sys))))
        (testing "B1-4.1-04 取消し後は再設定しても入れない"
          (let [pw (tu/invite-pw app asid "rev@example.com")
                uid (:id (db/find-user-by-email (:ds sys) "rev@example.com"))]
            (tu/post-json app "/api/user/password/reset/request" {:email "rev@example.com"})
            (let [token (second (re-find #"token=([0-9a-f]+)" (:body (last @(:sent sys)))))]
              (accounts/revoke-user sys uid)
              (is (= "reset_invalid" (:code (tu/parse (tu/post-json app "/api/user/password/reset"
                                                                   {:token token :password "abcdefgh"
                                                                    :password_confirm "abcdefgh"})))))
              (is (= "login_failed" (:code (accounts/login sys "user" "rev@example.com" pw)))))))
        (testing "B1-4.1-05 失敗理由は分けない"
          (is (= (ui/code-message "login_failed")
                 (get-in (ui/handle (ui/init-state) [:login-result {:ok false :code "login_failed"}])
                         [:state :flash :text]))))
        (testing "B1-5.1 未ログイン"
          (is (re-find #"利用者ログイン" (tu/page-html {:page :login :kind "user"})))
          (is (re-find #"管理者ログイン" (tu/page-html {:page :login :kind "admin"})))
          (is (= :unknown (:page (ui/route-for "/signup")))))
        (testing "B1-5.2 / B1-5.3"
          (is (re-find #"招待" (html {:page :home :kind "admin"})))
          (is (re-find #"取消し" (html {:page :home :kind "admin"})))
          (is (not (re-find #"関係を切" (html {:page :home :kind "admin"}))))
          (is (not (re-find #"href=\"/fields\"" (html {:page :home :kind "admin"}))))
          (is (re-find #"招待" (html {:page :home :kind "user"})))
          (is (not (re-find #"取り消す" (html {:page :invite :kind "user"})))))
        (testing "B1-5.3-04 / B1-5.4-01 圃場0でも狭い画面でも入れる"
          (let [pw (tu/invite-pw app asid "narrow@example.com")]
            (is (true? (:ok (accounts/login sys "user" "narrow@example.com" pw)))))
          (is (true? (:ok (tu/parse (let [pw (tu/invite-pw app asid "phone@example.com")]
                                     (tu/post-json app "/api/user/login" {:email "phone@example.com" :password pw})))))))
        (testing "B1-6 データ"
            (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")
                  user (db/find-user-by-email (:ds sys) "once@example.com")
                  names (tu/table-names (:ds sys))]
            (is (:email admin))
            (is (:password_hash admin))
            (is (nil? (:ui_lang admin)))
            (is (:email user))
            (is (contains? user :revoked_at))
            (is (not (contains? names "paints")))
            (is (not (contains? names "gantt_rows")))
            (is (not (contains? names "orders")))))
        (testing "B1-7.1 招待して入る／取り消す"
          (let [inv (accounts/invite sys "admin" 1 "flow@example.com")]
            (is (true? (:ok (accounts/login sys "user" "flow@example.com" (:initial_password inv)))))
            (accounts/revoke-user sys (:id (db/find-user-by-email (:ds sys) "flow@example.com")))
            (is (= "login_failed" (:code (accounts/login sys "user" "flow@example.com" (:initial_password inv)))))))
        (testing "B1-8 / B1-9 / B1-10"
          (is (nil? (http/match-api :get "/api/oauth")))
          (is (not (re-find #"農機|GAP|在庫|オフライン下書き" (html {:page :home :kind "user"}))))
          (is (not (re-find #"言語切替" (html {:page :login :kind "user" :session nil})))))))))
