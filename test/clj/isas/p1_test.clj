(ns isas.p1-test
  "詳細試験仕様書_工程1 の項番に対応する自動試験。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.config :as config]
            [isas.core :as core]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.http :as http]
            [isas.mail :as mail]
            [isas.test-util :as tu]
            [isas.time :as time]
            [isas.ui :as ui]
            [ring.mock.request :as mock])
  (:import [java.time Instant]))

(defn- html [opts]
  (tu/page-html (merge {:session {:email "a@example.com"}} opts)))

(deftest p1-1-conf-and-bootstrap
  (testing "P1-1.1-01 設定ファイルが無いと起動しない"
    (is (thrown-with-msg? Exception #"ありません"
                          (core/open-system {:conf-path "/no/such/isas.conf"
                                             :db-path (tu/temp-db-path)}))))
  (testing "P1-1.1-02 空行と # は無視する"
    (let [c (config/parse-text (str "# comment\n\n" tu/sample-conf))]
      (is (= "admin@example.com" (:admin-email c)))))
  (testing "P1-1.1-03 管理者が0人なら設定から1人"
    (tu/with-sys
      (fn [sys]
        (is (= 1 (db/count-admins (:ds sys))))
        (is (nil? (re-find #"初期登録|/signup" (html {:page :login :kind "admin" :session nil})))))))
  (testing "P1-1.1-04 管理者がいると設定のパスワードでは変えない"
    (tu/with-sys
      (fn [sys]
        (is (= :exists (:status (accounts/bootstrap-admin!
                                 (:ds sys)
                                 (assoc (:conf sys) :admin-password "OtherPass99"))))))))
  (testing "P1-1.1-05 利用者と同じメールでは管理者を作らない"
    (let [ds (db/migrate! (db/datasource (db/sqlite-url (tu/temp-db-path))))]
      (binding [crypto/*cost* 4]
        (db/insert-user! ds {:email "admin@example.com" :password-hash "h"
                             :invited-by-kind "admin" :invited-by-id 1})
        (is (thrown-with-msg? Exception #"利用者"
                              (accounts/bootstrap-admin! ds {:admin-email "admin@example.com"
                                                             :admin-password "x"}))))))
  (testing "P1-1.1-06 0人でメールまたはパスワードが無いと起動しない"
    (let [ds (db/migrate! (db/datasource (db/sqlite-url (tu/temp-db-path))))]
      (is (thrown-with-msg? Exception #"admin_email"
                            (accounts/bootstrap-admin! ds {:admin-email "" :admin-password ""})))))
  (testing "P1-1.1-07 smtp_starttls 省略時は STARTTLS"
    (let [text (str/replace tu/sample-conf #"smtp_starttls=1\n" "")
          c (config/parse-text text)]
      (is (true? (:smtp-starttls? c)))))
  (testing "P1-1.1-08 環境変数だけでは起動しない"
    (is (thrown? Exception (config/load-conf "/no/such/isas.conf"))))
  (testing "P1-1.1-09 bcrypt コスト12。平文は残らない"
    (is (= 12 crypto/bcrypt-cost))
    (tu/with-sys
      (fn [sys]
        (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")]
          (is (str/starts-with? (:password_hash admin) "bcrypt+sha512$"))
          (is (not (str/includes? (str admin) "ChangeMeAdmin1"))))))))

(deftest p1-2-routes-and-api
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "u@example.com")
            usid (tu/user-sid app "u@example.com" pw)]
        (testing "P1-2.1-01 未ログインの / は利用者ログイン"
          (is (= 200 (:status (tu/get-path app "/"))))
          (is (= {:page :login :kind "user"} (select-keys (ui/route-for "/") [:page :kind])))
          (is (re-find #"利用者ログイン" (tu/page-html {:page :login :kind "user"}))))
        (testing "P1-2.1-02 利用者で / は /home へ"
          (is (= "/home" (second (first (:fx (ui/handle (assoc (ui/init-state) :page :login :kind "user"
                                                              :session {:email "u@example.com"})
                                                       [:session-loaded {:ok true :email "u@example.com"}])))))))
        (testing "P1-2.1-03〜16 経路の見出し"
          (is (re-find #"パスワード再設定の依頼" (tu/page-html {:page :reset-request :kind "user"})))
          (is (re-find #"新しいパスワード" (tu/page-html {:page :reset :kind "user"})))
          (is (= :home (:page (ui/route-for "/home"))))
          (is (re-find #"招待" (html {:page :invite :kind "user"})))
          (is (re-find #"パスワード変更" (html {:page :password :kind "user"})))
          (is (re-find #"管理者ログイン" (tu/page-html {:page :login :kind "admin"})))
          (is (= "/admin/home" (second (first (:fx (ui/guarded (assoc (ui/init-state) :page :login :kind "admin"
                                                                    :session {:email "a"})))))))
          (is (re-find #"パスワード再設定の依頼" (tu/page-html {:page :reset-request :kind "admin"})))
          (is (re-find #"新しいパスワード" (tu/page-html {:page :reset :kind "admin"})))
          (is (re-find #"管理者として入っています" (html {:page :home :kind "admin"})))
          (is (not (re-find #"href=\"/fields\"" (html {:page :home :kind "admin"}))))
          (is (re-find #"利用者を招待" (html {:page :invite :kind "admin"})))
          (is (re-find #"取り消す" (html {:page :users :kind "admin" :users [{:id 1 :email "u@example.com"}]})))
          (is (re-find #"パスワード変更" (html {:page :password :kind "admin"}))))
        (testing "P1-2.1-05 未ログインの保護画面は入口へ"
          (is (= "/" (second (first (:fx (ui/guarded (assoc (ui/init-state) :page :home :kind "user"))))))))
        (testing "P1-2.1-06 利用者ホームは入ったことが分かる"
          (is (re-find #"u@example.com" (html {:page :home :kind "user" :session {:email "u@example.com"}})))
          (is (re-find #"href=\"/invite\"" (html {:page :home :kind "user"})))
          (is (re-find #"href=\"/password\"" (html {:page :home :kind "user"}))))
        (testing "P1-2.1-17 種別違いでは入れない"
          (is (= 401 (:status (tu/get-path app "/api/admin/session" "user" usid))))
          (is (= 401 (:status (tu/get-path app "/api/user/session" "admin" asid))))
          (is (= "/admin" (ui/login-path "admin")))
          (is (= "/" (ui/login-path "user"))))
        (testing "P1-2.1-18 再設定リンクは public_url + 画面経路"
          (tu/post-json app "/api/user/password/reset/request" {:email "u@example.com"})
          (let [body (:body (last @(:sent sys)))]
            (is (re-find #"http://localhost:8080/reset\?token=" body))
            (is (not (re-find #"/api/" body)))))
        (testing "P1-2.1-19 /signup は無い"
          (is (nil? (http/match-api :post "/api/signup")))
          (is (= :unknown (:page (ui/route-for "/signup")))))
        (testing "P1-2.2-01〜05 利用者 session / login / logout"
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/user/session")))))
          (is (true? (:ok (tu/parse (tu/get-path app "/api/user/session" "user" usid)))))
          (is (seq usid))
          (is (true? (:ok (tu/parse (tu/post-json app "/api/user/logout" {} "user" usid)))))
          (is (= "unauthorized" (:code (tu/parse (tu/post-json app "/api/user/logout" {} "user" usid))))))
        (let [usid2 (tu/user-sid app "u@example.com" pw)]
          (testing "P1-2.2-06 再設定依頼は常に ok"
            (is (true? (:ok (tu/parse (tu/post-json app "/api/user/password/reset/request" {:email "u@example.com"})))))
            (is (true? (:ok (tu/parse (tu/post-json app "/api/user/password/reset/request" {:email "missing@x.x"}))))))
          (testing "P1-2.2-07〜09 password / invite"
            (let [token (second (re-find #"token=([0-9a-f]+)" (:body (last @(:sent sys)))))]
              (is (true? (:ok (tu/parse (tu/post-json app "/api/user/password/reset"
                                                     {:token token :password "newuser12" :password_confirm "newuser12"})))))
              (let [sid (tu/user-sid app "u@example.com" "newuser12")]
                (is (true? (:ok (tu/parse (tu/post-json app "/api/user/password"
                                                       {:current_password "newuser12"
                                                        :password "userpass1"
                                                        :password_confirm "userpass1"}
                                                       "user" sid)))))
                (is (true? (:ok (tu/parse (tu/get-path app "/api/user/session" "user" sid)))))
                (let [inv (tu/parse (tu/post-json app "/api/user/invite" {:email "v@example.com"} "user" sid))]
                  (is (seq (:initial_password inv)))
                  (is (nil? (:initial_password (tu/parse (tu/post-json app "/api/user/invite"
                                                                     {:email "v@example.com"} "user" sid))))))))))
        (testing "P1-2.2-10〜18 管理者 API"
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/admin/session")))))
          (is (true? (:ok (tu/parse (tu/get-path app "/api/admin/session" "admin" asid)))))
          (is (seq asid))
          (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/password/reset/request" {:email "missing@x.x"})))))
          (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/password/reset/request" {:email "admin@example.com"})))))
          (let [users (tu/parse (tu/get-path app "/api/admin/users" "admin" asid))]
            (is (every? #(and (:id %) (:email %)) (:users users)))
            (is (not-any? #(= "admin@example.com" (:email %)) (:users users)))))
        (testing "P1-2.2-19 利用者 Cookie で管理者 API"
          (let [sid (tu/user-sid app "v@example.com" (tu/invite-pw app asid "w2@example.com"))]
            (is (= 401 (:status (tu/post-json app "/api/admin/invite" {:email "z@z.z"} "user" sid))))))
        (testing "P1-2.2-20 管理者 Cookie で利用者保護 API"
          (is (= 401 (:status (tu/post-json app "/api/user/invite" {:email "z@z.z"} "admin" asid)))))
        (testing "P1-2.2-21 GET では password / revoke を受けない"
          (is (nil? (http/match-api :get "/api/user/password")))
          (is (nil? (http/match-api :get "/api/admin/users/revoke")))
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/user/password" "user" usid))))))))))

(deftest p1-2-codes-and-screens
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "c@example.com")]
        (testing "P1-2.2-c01 unauthorized"
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/user/session"))))))
        (testing "P1-2.2-c02 login_failed は1通り"
          (is (= "login_failed" (:code (tu/parse (tu/post-json app "/api/user/login" {:email "no@x.x" :password "x"})))))
          (is (= "login_failed" (:code (tu/parse (tu/post-json app "/api/user/login" {:email "c@example.com" :password "no"})))))
          (is (= "メールアドレスまたはパスワードが違います" (ui/code-message "login_failed"))))
        (testing "P1-2.2-c03 reset_invalid"
          (is (= "reset_invalid" (:code (tu/parse (tu/post-json app "/api/user/password/reset"
                                                              {:token "deadbeef" :password "abcdefgh" :password_confirm "abcdefgh"})))))
          (is (= "この案内は使えません。もう一度やり直してください" (ui/code-message "reset_invalid"))))
        (testing "P1-2.2-c04〜c06 招待 code"
          (is (= "invite_invalid_email" (:code (tu/parse (tu/post-json app "/api/admin/invite" {:email "bad"} "admin" asid)))))
          (is (= "invite_duplicate_user" (:code (tu/parse (tu/post-json app "/api/admin/invite" {:email "c@example.com"} "admin" asid)))))
          (is (= "invite_duplicate_admin" (:code (tu/parse (tu/post-json app "/api/admin/invite" {:email "admin@example.com"} "admin" asid)))))
          (is (= "メールアドレスの形式ではありません" (ui/code-message "invite_invalid_email")))
          (is (= "このメールアドレスは、すでに利用者として招待されています" (ui/code-message "invite_duplicate_user")))
          (is (= "このメールアドレスは管理者のため、利用者として招待できません" (ui/code-message "invite_duplicate_admin"))))
        (testing "P1-2.2-c07〜c09 パスワード code"
          (is (= "password_mismatch" (:code (tu/parse (tu/post-json app "/api/admin/password"
                                                                  {:current_password "ChangeMeAdmin1"
                                                                   :password "abcdefgh"
                                                                   :password_confirm "xxxxxxxx"}
                                                                  "admin" asid)))))
          (is (= "password_too_short" (:code (tu/parse (tu/post-json app "/api/admin/password"
                                                                   {:current_password "ChangeMeAdmin1"
                                                                    :password "short"
                                                                    :password_confirm "short"}
                                                                   "admin" asid)))))
          (is (= "password_wrong" (:code (tu/parse (tu/post-json app "/api/admin/password"
                                                               {:current_password "no"
                                                                :password "abcdefgh"
                                                                :password_confirm "abcdefgh"}
                                                               "admin" asid))))))
        (testing "P1-2.3-01〜04 ログイン画面"
          (let [h (tu/page-html {:page :login :kind "user"})]
            (is (re-find #"required" h))
            (is (re-find #"type=\"password\"" h))
            (is (re-find #"パスワードを忘れた" h))
            (is (re-find #"href=\"/reset/request\"" h)))
          (is (= "/home" (second (first (:fx (ui/handle (ui/init-state) [:login-result {:ok true :email "a"}])))))))
        (testing "P1-2.4-01〜04 再設定依頼"
          (is (re-find #"required" (tu/page-html {:page :reset-request :kind "user"})))
          (is (= "案内を送りました。届かないときは、招待されていないか、入口が違う可能性があります"
                 (get-in (ui/handle (ui/init-state) [:reset-request-result]) [:state :flash :text])))
          (let [n (count @(:sent sys))]
            (accounts/revoke-user sys (:id (db/find-user-by-email (:ds sys) "c@example.com")))
            (is (true? (:ok (tu/parse (tu/post-json app "/api/user/password/reset/request" {:email "c@example.com"})))))
            (is (= n (count @(:sent sys)))))
          (tu/post-json app "/api/admin/password/reset/request" {:email "c@example.com"})
          (is (not (some #(str/includes? (str (:to %)) "c@example.com")
                         (filter #(= "ISAS 管理者パスワードの再設定" (:subject %)) @(:sent sys))))))
        (testing "P1-2.4-05 SMTP 失敗でも画面の返事は同じ"
          (tu/with-sys {:send-fn (fn [_ _] (throw (Exception. "smtp down")))}
            (fn [sys2]
              (let [app2 (tu/app sys2)]
                (is (true? (:ok (tu/parse (tu/post-json app2 "/api/admin/password/reset/request"
                                                       {:email "admin@example.com"})))))
                (is (= (:reset-requested ui/messages)
                       (get-in (ui/handle (ui/init-state) [:reset-request-result]) [:state :flash :text])))))))
        (testing "P1-2.5 再設定画面"
          (is (re-find #"required" (tu/page-html {:page :reset :kind "user"})))
          (is (= "/home" (ui/home-path "user")))
          (is (= "/" (second (first (:fx (ui/handle (assoc (ui/init-state) :kind "user")
                                                   [:reset-complete-result {:ok true}])))))))
        (testing "P1-2.6 ホーム"
          (is (re-find #"a@example.com" (html {:page :home :kind "user"})))
          (is (re-find #"利用者として入っています" (html {:page :home :kind "user"})))
          (is (re-find #"管理者として入っています。圃場は持ちません" (html {:page :home :kind "admin"})))
          (is (not (re-find #"href=\"/gantt\"|href=\"/orders\"|塗[りる]" (html {:page :home :kind "admin"}))))
          (is (re-find #"招待" (html {:page :home :kind "admin"})))
          (is (re-find #"取消し" (html {:page :home :kind "admin"})))
          (is (not (re-find #"取消し" (html {:page :home :kind "user"})))))
        (testing "P1-2.7 招待画面"
          (is (re-find #"required" (html {:page :invite :kind "user"})))
          (is (re-find #"初期パスワードを相手に伝えてください" (html {:page :invite :kind "admin" :initial-password "AbCdEfGh2345"})))
          (is (not (re-find #"<ul>|<li>" (html {:page :invite :kind "user"})))))
        (testing "P1-2.8 取消し"
          (let [uid (:id (db/find-user-by-email (:ds sys) "c@example.com"))]
            (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/users/revoke" {:user_id uid} "admin" asid)))))
            (is (= "login_failed" (:code (tu/parse (tu/post-json app "/api/user/login" {:email "c@example.com" :password pw})))))
            (let [inv2 (tu/parse (tu/post-json app "/api/admin/invite" {:email "c@example.com"} "admin" asid))]
              (is (seq (:initial_password inv2)))
              (is (not= pw (:initial_password inv2)))
              (is (true? (:ok (tu/parse (tu/post-json app "/api/user/login"
                                                     {:email "c@example.com" :password (:initial_password inv2)}))))))
            (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/users/revoke" {:user_id uid} "admin" asid)))))
            (is (true? (:ok (tu/parse (tu/post-json app "/api/admin/users/revoke" {:user_id uid} "admin" asid))))))
          (is (= "/" (second (first (:fx (ui/guarded (assoc (ui/init-state) :page :users :kind "user"))))))))
        (testing "P1-2.9 パスワード変更画面は3欄"
          (let [h (html {:page :password :kind "user"})]
            (is (re-find #"今のパスワード" h))
            (is (re-find #"新しいパスワード（確認）" h))))))))

(deftest p1-3-to-8-rules
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)]
        (testing "P1-3.1-01 メールは空白と大文字を同一"
          (let [inv (accounts/invite sys "admin" 1 "  Helper@Example.COM  ")]
            (is (true? (:ok inv)))
            (is (some? (db/find-user-by-email (:ds sys) "helper@example.com")))
            (is (true? (:ok (accounts/login sys "user" "  HELPER@example.com  " (:initial_password inv)))))))
        (testing "P1-3.2-02 初期パスワードは英数字12文字で紛らわしい字を使わない"
          (dotimes [_ 8]
            (let [pw (crypto/initial-password)]
              (is (= 12 (count pw)))
              (is (re-matches #"[23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{12}" pw)))))
        (testing "P1-3.2-03 8文字可・7文字不可"
          (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")]
            (is (= "password_too_short" (:code (accounts/change-password sys "admin" admin "1234567" "ChangeMeAdmin1" "1234567"))))
            (is (true? (:ok (accounts/change-password sys "admin" admin "12345678" "ChangeMeAdmin1" "12345678"))))))
        (testing "P1-3.3-01 Cookie 名と属性"
          (let [login (tu/post-json app "/api/admin/login" {:email "admin@example.com" :password "12345678"})
                built (http/with-session-cookie {:status 200} {:scheme :http} "admin" "sid")]
            (is (seq (tu/cookie-value login "isas_admin")))
            (is (= "isas_admin" (http/cookie-name "admin")))
            (is (= "isas_user" (http/cookie-name "user")))
            (is (true? (get-in built [:cookies "isas_admin" :http-only])))
            (is (= :lax (get-in built [:cookies "isas_admin" :same-site])))))
        (testing "P1-3.3-03 同時ログイン"
          (let [pw (tu/invite-pw app asid "two@example.com")
                a (tu/user-sid app "two@example.com" pw)
                b (tu/user-sid app "two@example.com" pw)]
            (is (true? (:ok (tu/parse (tu/get-path app "/api/user/session" "user" a)))))
            (is (true? (:ok (tu/parse (tu/get-path app "/api/user/session" "user" b)))))))
        (testing "P1-3.3-04 期限切れは使えない"
          (db/insert-session! (:ds sys) {:id "expired" :kind "admin" :account-id 1 :expires-at "2000-01-01T00:00:00Z"})
          (is (nil? (accounts/current-session sys "admin" "expired"))))
        (testing "P1-3.4-01 再招待は同じ行"
          (let [u (db/find-user-by-email (:ds sys) "helper@example.com")
                id (:id u)]
            (accounts/revoke-user sys id)
            (accounts/invite sys "admin" 1 "helper@example.com")
            (is (= id (:id (db/find-user-by-email (:ds sys) "helper@example.com"))))))
        (testing "P1-3.4-02 / P1-3.7-01 招待で管理者は増えない"
          (is (= 1 (db/count-admins (:ds sys))))
          (is (not (re-find #"管理者を招待|増やす" (html {:page :home :kind "admin"})))))
        (testing "P1-3.5-01 利用者は revoke できない"
          (let [pw (tu/invite-pw app asid "rev@example.com")
                sid (tu/user-sid app "rev@example.com" pw)]
            (is (= 401 (:status (tu/post-json app "/api/admin/users/revoke" {:user_id 1} "user" sid))))))
        (testing "P1-3.6-01〜02 トークンは24時間・一度限り・再依頼で前を無効"
          (tu/post-json app "/api/admin/password/reset/request" {:email "admin@example.com"})
          (let [t1 (second (re-find #"token=([0-9a-f]+)" (:body (last @(:sent sys)))))]
            (tu/post-json app "/api/admin/password/reset/request" {:email "admin@example.com"})
            (is (= "reset_invalid" (:code (tu/parse (tu/post-json app "/api/admin/password/reset"
                                                                {:token t1 :password "abcdefgh" :password_confirm "abcdefgh"}))))))
          (let [fixed (Instant/parse "2026-09-12T00:00:00Z")]
            (binding [time/*now-fn* (fn [] fixed)]
              (db/insert-reset-token! (:ds sys) {:kind "admin" :account-id 1
                                                 :token-hash (crypto/hash-secret "old-token-xxxx")
                                                 :expires-at "2026-09-11T00:00:00Z"})
              (is (= "reset_invalid" (:code (accounts/complete-reset sys "admin" "old-token-xxxx" "abcdefgh" "abcdefgh")))))))
        (testing "P1-4-01 SQLite は Git に入れない"
          (is (re-find #"data/\*\.sqlite" (slurp (io/file ".gitignore")))))
        (testing "P1-4-03〜06 列"
          (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")
                user (db/find-user-by-email (:ds sys) "two@example.com")
                names (tu/table-names (:ds sys))]
            (is (contains? names "admins"))
            (is (every? #(contains? admin %) [:id :email :password_hash :created_at]))
            (is (nil? (:ui_lang admin)))
            (is (every? #(contains? user %) [:id :email :password_hash :revoked_at :invited_by_kind :invited_by_id :created_at]))
            (is (contains? names "reset_tokens"))
            (is (contains? names "sessions"))
            (is (not (contains? names "gantt_rows")))
            (is (not (contains? names "orders")))))
        (testing "P1-4-07 圃場ゼロでも入れる"
          (let [pw (tu/invite-pw app asid "zero-field@example.com")]
            (is (true? (:ok (accounts/login sys "user" "zero-field@example.com" pw))))))
        (testing "P1-5 文言"
          (is (= "利用者ログイン" (:user-login-title ui/messages)))
          (is (= "管理者ログイン" (:admin-login-title ui/messages)))
          (is (not (re-find #"English|言語" (html {:page :login :kind "user" :session nil})))))
        (testing "P1-6 メール"
          (is (= "ISAS パスワードの再設定" (mail/reset-subject "user")))
          (is (= "ISAS 管理者パスワードの再設定" (mail/reset-subject "admin")))
          (let [body (mail/reset-body "user" "http://localhost:8080" "abc")]
            (is (re-find #"http://localhost:8080/reset\?token=abc" body))
            (is (re-find #"24時間" body))
            (is (re-find #"無視" body)))
          (let [n (count @(:sent sys))]
            (accounts/invite sys "admin" 1 "nomail@example.com")
            (is (= n (count @(:sent sys))))))
        (testing "P1-7 / P1-8 作らないもの・完了"
          (is (nil? (http/match-api :post "/api/oauth")))
          (is (re-find #"js/main" (http/index-html)))
          (is (re-find #"data/isas.sqlite" (slurp "src/clj/isas/core.clj")))
          (is (re-find #"data/isas.conf" (slurp "src/clj/isas/core.clj")))
          (is (not (re-find #"oauth|IdP|WebAuthn|twitter" (html {:page :login :kind "user" :session nil}))))
          (is (not (re-find #"言語切替|English" (html {:page :home :kind "user"})))))))))
