(ns isas.ui
  (:require [clojure.string :as str]))

(def messages
  {:user-login-title "利用者ログイン"
   :admin-login-title "管理者ログイン"
   :login-failed "メールアドレスまたはパスワードが違います"
   :reset-requested "案内を送りました。届かないときは、招待されていないか、入口が違う可能性があります"
   :reset-invalid "この案内は使えません。もう一度やり直してください"
   :invite-ok "初期パスワードを相手に伝えてください。この画面を離れると同じ文字列は出せません"
   :invite-duplicate-user "このメールアドレスは、すでに利用者として招待されています"
   :invite-duplicate-admin "このメールアドレスは管理者のため、利用者として招待できません"
   :invite-invalid "メールアドレスの形式ではありません"
   :user-home "利用者として入っています"
   :admin-home "管理者として入っています。圃場は持ちません"
   :password-mismatch "確認用パスワードが一致しません"
   :password-too-short "パスワードは8文字以上にしてください"
   :password-wrong "今のパスワードが違います"
   :password-ok "パスワードを変更しました"
   :unauthorized "入っていません"
   :api-error "通信できませんでした"})

(defn code-message [code]
  (case code
    "login_failed" (:login-failed messages)
    "reset_invalid" (:reset-invalid messages)
    "invite_invalid_email" (:invite-invalid messages)
    "invite_duplicate_user" (:invite-duplicate-user messages)
    "invite_duplicate_admin" (:invite-duplicate-admin messages)
    "password_mismatch" (:password-mismatch messages)
    "password_too_short" (:password-too-short messages)
    "password_wrong" (:password-wrong messages)
    "unauthorized" (:unauthorized messages)
    (:api-error messages)))

(defn esc [s]
  (-> (str (or s ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn parse-query [search]
  (let [q (if (str/starts-with? (or search "") "?") (subs search 1) (or search ""))]
    (if (str/blank? q)
      {}
      (->> (str/split q #"&")
           (remove str/blank?)
           (map (fn [part]
                  (let [i (str/index-of part "=")]
                    (if i
                      [(keyword (subs part 0 i)) (subs part (inc i))]
                      [(keyword part) ""]))))
           (into {})))))

(defn route-for [path]
  (case path
    "/" {:page :login :kind "user"}
    "/reset/request" {:page :reset-request :kind "user"}
    "/reset" {:page :reset :kind "user"}
    "/home" {:page :home :kind "user"}
    "/invite" {:page :invite :kind "user"}
    "/password" {:page :password :kind "user"}
    "/admin" {:page :login :kind "admin"}
    "/admin/reset/request" {:page :reset-request :kind "admin"}
    "/admin/reset" {:page :reset :kind "admin"}
    "/admin/home" {:page :home :kind "admin"}
    "/admin/invite" {:page :invite :kind "admin"}
    "/admin/users" {:page :users :kind "admin"}
    "/admin/password" {:page :password :kind "admin"}
    {:page :unknown :kind "user"}))

(defn login-path [kind]
  (if (= kind "admin") "/admin" "/"))

(defn home-path [kind]
  (if (= kind "admin") "/admin/home" "/home"))

(defn needs-auth? [page]
  (contains? #{:home :invite :password :users} page))

(defn init-state []
  {:path "/"
   :search ""
   :page :login
   :kind "user"
   :session nil
   :flash nil
   :busy false
   :initial-password nil
   :users []
   :form {}})

(defn flash-html [state]
  (when-let [f (:flash state)]
    (str "<p class=\"flash " (if (:error? f) "error" "ok") "\">" (esc (:text f)) "</p>")))

(defn layout [title body]
  (str "<main><h1>" (esc title) "</h1>" body "</main>"))

(defn nav-user []
  "<nav><a data-nav href=\"/home\">ホーム</a><a data-nav href=\"/invite\">招待</a><a data-nav href=\"/password\">パスワード</a><form data-act=\"logout\" method=\"post\"><button type=\"submit\">ログアウト</button></form></nav>")

(defn nav-admin []
  "<nav><a data-nav href=\"/admin/home\">ホーム</a><a data-nav href=\"/admin/invite\">招待</a><a data-nav href=\"/admin/users\">取消し</a><a data-nav href=\"/admin/password\">パスワード</a><form data-act=\"logout\" method=\"post\"><button type=\"submit\">ログアウト</button></form></nav>")

(defn login-view [state]
  (let [admin? (= "admin" (:kind state))
        title (if admin? (:admin-login-title messages) (:user-login-title messages))
        reset (if admin? "/admin/reset/request" "/reset/request")
        other (if admin? ["/" "利用者入口"] ["/admin" "管理者入口"])]
    (layout title
            (str (flash-html state)
                 "<form data-act=\"login\" method=\"post\">"
                 "<label>メールアドレス<input name=\"email\" type=\"email\" required></label>"
                 "<label>パスワード<input name=\"password\" type=\"password\" required></label>"
                 "<button type=\"submit\">入る</button></form>"
                 "<p><a data-nav href=\"" reset "\">パスワードを忘れた</a></p>"
                 "<p><a data-nav href=\"" (first other) "\">" (esc (second other)) "</a></p>"))))

(defn reset-request-view [state]
  (layout "パスワード再設定の依頼"
          (str (flash-html state)
               "<form data-act=\"reset-request\" method=\"post\">"
               "<label>メールアドレス<input name=\"email\" type=\"email\" required></label>"
               "<button type=\"submit\">案内を送る</button></form>"
               "<p><a data-nav href=\"" (login-path (:kind state)) "\">ログインへ</a></p>")))

(defn reset-view [state]
  (layout "新しいパスワード"
          (str (flash-html state)
               "<form data-act=\"reset-complete\" method=\"post\">"
               "<label>新しいパスワード<input name=\"password\" type=\"password\" required></label>"
               "<label>新しいパスワード（確認）<input name=\"password_confirm\" type=\"password\" required></label>"
               "<button type=\"submit\">決める</button></form>")))

(defn home-view [state]
  (let [admin? (= "admin" (:kind state))
        title (if admin? (:admin-home messages) (:user-home messages))]
    (layout title
            (str (if admin? (nav-admin) (nav-user))
                 (flash-html state)
                 "<p>" (esc (get-in state [:session :email])) "</p>"))))

(defn invite-view [state]
  (layout "利用者を招待"
          (str (if (= "admin" (:kind state)) (nav-admin) (nav-user))
               (flash-html state)
               (when-let [pw (:initial-password state)]
                 (str "<p>" (esc (:invite-ok messages)) "</p><p>初期パスワード: <code>" (esc pw) "</code></p>"))
               "<form data-act=\"invite\" method=\"post\">"
               "<label>相手のメールアドレス<input name=\"email\" type=\"email\" required></label>"
               "<button type=\"submit\">招待する</button></form>")))

(defn password-view [state]
  (layout "パスワード変更"
          (str (if (= "admin" (:kind state)) (nav-admin) (nav-user))
               (flash-html state)
               "<form data-act=\"password\" method=\"post\">"
               "<label>今のパスワード<input name=\"current_password\" type=\"password\" required></label>"
               "<label>新しいパスワード<input name=\"password\" type=\"password\" required></label>"
               "<label>新しいパスワード（確認）<input name=\"password_confirm\" type=\"password\" required></label>"
               "<button type=\"submit\">変える</button></form>")))

(defn users-view [state]
  (layout "招待の取消し"
          (str (nav-admin)
               (flash-html state)
               "<ul>"
               (apply str
                      (for [u (:users state)]
                        (str "<li>" (esc (:email u))
                             "<form data-act=\"revoke\" method=\"post\">"
                             "<input type=\"hidden\" name=\"user_id\" value=\"" (esc (:id u)) "\">"
                             "<button type=\"submit\">取り消す</button></form></li>")))
               "</ul>")))

(defn unknown-view []
  (layout "ISAS" "<p>このページはありません。</p><p><a data-nav href=\"/\">利用者入口</a></p>"))

(defn render [state]
  (case (:page state)
    :login (login-view state)
    :reset-request (reset-request-view state)
    :reset (reset-view state)
    :home (home-view state)
    :invite (invite-view state)
    :password (password-view state)
    :users (users-view state)
    (unknown-view)))

(defn apply-route [state path search]
  (let [r (route-for path)]
    (assoc state
           :path path
           :search (or search "")
           :page (:page r)
           :kind (:kind r)
           :initial-password nil)))

(defn guarded [state]
  (cond
    (and (needs-auth? (:page state)) (nil? (:session state)))
    {:state (assoc state :flash {:error? true :text (:unauthorized messages)})
     :fx [[:nav (login-path (:kind state))]]}

    (and (= :login (:page state)) (:session state))
    {:state state
     :fx [[:nav (home-path (:kind state))]]}

    :else
    {:state state
     :fx [[:html (render state)]]}))

(defn boot [state {:keys [path search]}]
  (let [s (apply-route state path search)
        token (:token (parse-query search))]
    {:state (assoc s :form (if token {:token token} {}))
     :fx [[:session (:kind s)]]}))

(defn session-loaded [state body]
  (let [s (if (:ok body)
            (assoc state :session {:email (:email body)})
            (assoc state :session nil))]
    (if (and (= :users (:page s)) (:session s))
      {:state s :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]}
      (guarded s))))

(defn users-loaded [state body]
  (guarded (assoc state :users (or (:users body) []))))

(defn after-login [state body]
  (if (:ok body)
    {:state (assoc state :session {:email (:email body)} :flash nil)
     :fx [[:nav (home-path (:kind state))]]}
    {:state (assoc state :flash {:error? true :text (code-message (:code body))})
     :fx [[:html (render (assoc state :flash {:error? true :text (code-message (:code body))}))]]}))

(defn after-logout [state]
  {:state (assoc state :session nil :flash nil)
   :fx [[:nav (login-path (:kind state))]]})

(defn after-reset-request [state]
  (let [s (assoc state :flash {:error? false :text (:reset-requested messages)})]
    {:state s :fx [[:html (render s)]]}))

(defn after-reset-complete [state body]
  (if (:ok body)
    {:state (assoc state :flash nil)
     :fx [[:nav (login-path (:kind state))]]}
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-invite [state body]
  (if (:ok body)
    (let [s (assoc state
                   :initial-password (:initial_password body)
                   :flash {:error? false :text (:invite-ok messages)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-password [state body]
  (if (:ok body)
    (let [s (assoc state :flash {:error? false :text (:password-ok messages)})]
      {:state s :fx [[:html (render s)]]})
    (let [s (assoc state :flash {:error? true :text (code-message (:code body))})]
      {:state s :fx [[:html (render s)]]})))

(defn after-revoke [state]
  {:state state :fx [[:api "GET" "/api/admin/users" nil :users-loaded]]})

(defn after-api-error [state]
  (let [s (assoc state :flash {:error? true :text (:api-error messages)})]
    {:state s :fx [[:html (render s)]]}))

(defn handle [state msg]
  (let [[op arg] (if (vector? msg) msg [msg nil])]
    (case op
      :boot (boot state arg)
      :session-loaded (session-loaded state arg)
      :users-loaded (users-loaded state arg)
      :login-result (after-login state arg)
      :logout-result (after-logout state)
      :reset-request-result (after-reset-request state)
      :reset-complete-result (after-reset-complete state arg)
      :invite-result (after-invite state arg)
      :password-result (after-password state arg)
      :revoke-result (after-revoke state)
      :api-error (after-api-error state)
      :path (guarded (apply-route (assoc state :session (:session state) :flash nil) (:path arg) (:search arg)))
      :submit
      (let [act (:act arg)
            form (:form arg)
            kind (:kind state)]
        (case act
          "login" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/login" "/api/user/login") form :login-result]]}
          "logout" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/logout" "/api/user/logout") {} :logout-result]]}
          "reset-request" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password/reset/request" "/api/user/password/reset/request") form :reset-request-result]]}
          "reset-complete" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password/reset" "/api/user/password/reset")
                                              (assoc form :token (or (:token form) (:token (parse-query (:search state)))))
                                              :reset-complete-result]]}
          "invite" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/invite" "/api/user/invite") form :invite-result]]}
          "password" {:state state :fx [[:api "POST" (if (= kind "admin") "/api/admin/password" "/api/user/password") form :password-result]]}
          "revoke" {:state state :fx [[:api "POST" "/api/admin/users/revoke" form :revoke-result]]}
          {:state state :fx [[:html (render state)]]}))
      {:state state :fx [[:html (render state)]]})))
