(ns isas.ui-test
  (:require [clojure.test :refer [deftest is]]
            [isas.ui :as ui]))

(deftest helpers-test
  (is (= "&amp;&lt;&gt;&quot;" (ui/esc "&<>\"")))
  (is (= "" (ui/esc nil)))
  (is (= {} (ui/parse-query nil)))
  (is (= {} (ui/parse-query "")))
  (is (= {} (ui/parse-query "?")))
  (is (= {:a "1" :b "2"} (ui/parse-query "?a=1&b=2")))
  (is (= {:flag ""} (ui/parse-query "flag")))
  (doseq [[path page kind] [["/" :login "user"]
                            ["/reset/request" :reset-request "user"]
                            ["/reset" :reset "user"]
                            ["/home" :home "user"]
                            ["/invite" :invite "user"]
                            ["/password" :password "user"]
                            ["/admin" :login "admin"]
                            ["/admin/reset/request" :reset-request "admin"]
                            ["/admin/reset" :reset "admin"]
                            ["/admin/home" :home "admin"]
                            ["/admin/invite" :invite "admin"]
                            ["/admin/users" :users "admin"]
                            ["/admin/password" :password "admin"]
                            ["/nope" :unknown "user"]]]
    (let [r (ui/route-for path)]
      (is (= page (:page r)))
      (is (= kind (:kind r)))))
  (is (= "/" (ui/login-path "user")))
  (is (= "/admin" (ui/login-path "admin")))
  (is (= "/home" (ui/home-path "user")))
  (is (= "/admin/home" (ui/home-path "admin")))
  (is (true? (ui/needs-auth? :home)))
  (is (false? (ui/needs-auth? :login)))
  (is (= "メールアドレスまたはパスワードが違います" (ui/code-message "login_failed")))
  (is (= "この案内は使えません。もう一度やり直してください" (ui/code-message "reset_invalid")))
  (is (= "メールアドレスの形式ではありません" (ui/code-message "invite_invalid_email")))
  (is (re-find #"すでに" (ui/code-message "invite_duplicate_user")))
  (is (re-find #"管理者" (ui/code-message "invite_duplicate_admin")))
  (is (re-find #"確認用" (ui/code-message "password_mismatch")))
  (is (re-find #"8文字" (ui/code-message "password_too_short")))
  (is (re-find #"今のパスワード" (ui/code-message "password_wrong")))
  (is (re-find #"入っていません" (ui/code-message "unauthorized")))
  (is (re-find #"通信" (ui/code-message "other"))))

(deftest render-all-pages-test
  (let [base (ui/init-state)]
    (doseq [page [:login :reset-request :reset :home :invite :password :users :unknown]]
      (let [st (assoc base :page page :kind "user" :session {:email "a@b.c"}
                      :flash {:error? true :text "e"} :initial-password "pw"
                      :users [{:id 1 :email "x@y.z"}])]
        (is (string? (ui/render st)))))
    (let [admin (assoc base :page :login :kind "admin")]
      (is (re-find #"管理者ログイン" (ui/render admin))))
    (is (re-find #"管理者として" (ui/render (assoc base :page :home :kind "admin" :session {:email "a"}))))
    (is (re-find #"取り消す" (ui/render (assoc base :page :users :kind "admin" :users [{:id 2 :email "z@z.z"}]))))
    (is (re-find #"招待" (ui/render (assoc base :page :invite :kind "admin" :session {:email "a"}))))
    (is (re-find #"今のパスワード" (ui/render (assoc base :page :password :kind "admin" :session {:email "a"}))))
    (is (re-find #"ホーム" (ui/render (assoc base :page :invite :kind "user" :session {:email "a"} :initial-password nil))))))

(deftest handle-flow-test
  (let [s (ui/init-state)
        boot (ui/handle s [:boot {:path "/" :search ""}])]
    (is (= :session (ffirst (:fx boot))))
    (let [loaded (ui/handle (:state boot) [:session-loaded {:ok false :code "unauthorized"}])]
      (is (some? (ui/handle (:state loaded) [:path {:path "/home" :search ""}])))
      (is (= :nav (ffirst (:fx (ui/handle (assoc (:state loaded) :page :home) [:session-loaded {:ok false}])))))
      (let [ok (ui/handle s [:session-loaded {:ok true :email "a@b.c"}])]
        (is (= :nav (ffirst (:fx (ui/handle (assoc s :page :login :session {:email "a"}) [:session-loaded {:ok true :email "a"}])))))
        (let [users-boot (ui/handle (assoc s :page :users :kind "admin") [:session-loaded {:ok true :email "ad"}])]
          (is (= :api (ffirst (:fx users-boot)))))
        (is (map? (ui/handle s [:users-loaded {:ok true :users [{:id 1 :email "e"}]}])))
        (is (map? (ui/handle s [:users-loaded {}])))
        (is (= :nav (ffirst (:fx (ui/handle s [:login-result {:ok true :email "a"}])))))
        (is (re-find #"違います" (get-in (ui/handle s [:login-result {:ok false :code "login_failed"}]) [:state :flash :text])))
        (is (= :nav (ffirst (:fx (ui/handle s [:logout-result])))))
        (is (re-find #"案内" (get-in (ui/handle s [:reset-request-result]) [:state :flash :text])))
        (is (= :nav (ffirst (:fx (ui/handle s [:reset-complete-result {:ok true}])))))
        (is (true? (get-in (ui/handle s [:reset-complete-result {:ok false :code "reset_invalid"}]) [:state :flash :error?])))
        (is (seq (get-in (ui/handle s [:invite-result {:ok true :initial_password "x"}]) [:state :initial-password])))
        (is (true? (get-in (ui/handle s [:invite-result {:ok false :code "invite_duplicate_user"}]) [:state :flash :error?])))
        (is (false? (get-in (ui/handle s [:password-result {:ok true}]) [:state :flash :error?])))
        (is (true? (get-in (ui/handle s [:password-result {:ok false :code "password_wrong"}]) [:state :flash :error?])))
        (is (= :api (ffirst (:fx (ui/handle s [:revoke-result])))))
        (is (re-find #"通信" (get-in (ui/handle s [:api-error]) [:state :flash :text])))
        (is (= :html (ffirst (:fx (ui/handle s [:nope])))))
        (is (= :html (ffirst (:fx (ui/handle s :nope)))))
        (is (map? (ui/handle s [:path {:path "/invite" :search nil}])))
        (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "unknown" :form {}}])))))
        (doseq [act ["login" "logout" "reset-request" "reset-complete" "invite" "password" "revoke"]]
          (is (= :api (ffirst (:fx (ui/handle (assoc s :kind "user") [:submit {:act act :form {:email "a" :token "t"}}])))))
          (is (= :api (ffirst (:fx (ui/handle (assoc s :kind "admin") [:submit {:act act :form {:email "a"}}]))))))
        (is (= :api (ffirst (:fx (ui/handle (assoc s :kind "user" :search "?token=fromq")
                                           [:submit {:act "reset-complete" :form {}}])))))
        (let [b (ui/handle s [:boot {:path "/reset" :search "?token=abc"}])]
          (is (= "abc" (get-in b [:state :form :token]))))
        (is (= :html (ffirst (:fx (ui/guarded (assoc s :page :login :session nil))))))
        (is (= :nav (ffirst (:fx (ui/guarded (assoc s :page :login :session {:email "a"}))))))
        (is (= :html (ffirst (:fx (ui/guarded (assoc s :page :reset :session nil))))))))))
