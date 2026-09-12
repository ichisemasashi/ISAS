(ns isas.http
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.accounts :as accounts]
            [isas.log :as log]
            [ring.middleware.cookies :as cookies]
            [ring.util.response :as response]))

(def cookie-max-age (* 14 24 60 60))

(defn json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json; charset=utf-8"}
    :body (json/write-str body)}))

(defn ok [m]
  (json-response (assoc m :ok true)))

(defn fail
  ([code] (fail code (if (= code "unauthorized") 401 200)))
  ([code status]
   (json-response status {:ok false :code code})))

(defn https? [req]
  (let [fwd (get-in req [:headers "x-forwarded-proto"])
        scheme (name (or (:scheme req) "http"))]
    (or (= "https" fwd) (= "https" scheme))))

(defn cookie-name [kind]
  (if (= kind "admin") "isas_admin" "isas_user"))

(defn read-body [req]
  (let [b (:body req)]
    (cond
      (map? b) b
      (nil? b) {}
      (string? b) (if (str/blank? b) {} (json/read-str b :key-fn keyword))
      :else (let [s (slurp b)]
              (if (str/blank? s)
                {}
                (json/read-str s :key-fn keyword))))))

(defn cookie-id [req kind]
  (get-in req [:cookies (cookie-name kind) :value]))

(defn with-session-cookie [resp req kind session-id]
  (let [cookie {:value session-id
                :http-only true
                :same-site :lax
                :path "/"
                :max-age cookie-max-age
                :secure (https? req)}]
    (assoc-in resp [:cookies (cookie-name kind)] cookie)))

(defn clear-session-cookie [resp req kind]
  (assoc-in resp [:cookies (cookie-name kind)]
            {:value ""
             :http-only true
             :same-site :lax
             :path "/"
             :max-age 0
             :secure (https? req)}))

(defn require-session [sys req kind]
  (accounts/current-session sys kind (cookie-id req kind)))

(defn session-get [sys req kind]
  (if-let [ctx (require-session sys req kind)]
    (ok {:email (get-in ctx [:account :email])})
    (fail "unauthorized")))

(defn login-post [sys req kind]
  (try
    (let [body (read-body req)
          result (accounts/login sys kind (:email body) (:password body))]
      (if (:ok result)
        (-> (ok {:email (:email result)})
            (with-session-cookie req kind (:session-id result)))
        (fail (:code result))))
    (catch Exception e
      (log/warn "ログイン要求を読めませんでした" :error (.getMessage e))
      (fail "login_failed"))))

(defn logout-post [sys req kind]
  (let [result (accounts/logout sys kind (cookie-id req kind))]
    (if (:ok result)
      (clear-session-cookie (ok {}) req kind)
      (fail "unauthorized"))))

(defn reset-request-post [sys req kind]
  (try
    (let [body (read-body req)]
      (accounts/request-reset sys kind (:email body))
      (ok {}))
    (catch Exception e
      (log/warn "再設定依頼を読めませんでした" :error (.getMessage e))
      (ok {}))))

(defn reset-complete-post [sys req kind]
  (try
    (let [body (read-body req)
          result (accounts/complete-reset sys kind (:token body) (:password body) (:password_confirm body))]
      (if (:ok result) (ok {}) (fail (:code result))))
    (catch Exception e
      (log/warn "再設定を読めませんでした" :error (.getMessage e))
      (fail "reset_invalid"))))

(defn password-post [sys req kind]
  (if-let [ctx (require-session sys req kind)]
    (try
      (let [body (read-body req)
            result (accounts/change-password sys kind (:account ctx)
                                             (:password body)
                                             (:current_password body)
                                             (:password_confirm body))]
        (if (:ok result) (ok {}) (fail (:code result))))
      (catch Exception e
        (log/warn "パスワード変更を読めませんでした" :error (.getMessage e))
        (fail "password_wrong")))
    (fail "unauthorized")))

(defn invite-post [sys req kind]
  (if-let [ctx (require-session sys req kind)]
    (try
      (let [body (read-body req)
            result (accounts/invite sys kind (get-in ctx [:account :id]) (:email body))]
        (if (:ok result)
          (ok (select-keys result [:initial_password]))
          (fail (:code result))))
      (catch Exception e
        (log/warn "招待要求を読めませんでした" :error (.getMessage e))
        (fail "invite_invalid_email")))
    (fail "unauthorized")))

(defn users-get [sys req]
  (if (require-session sys req "admin")
    (ok (select-keys (accounts/list-users sys) [:users]))
    (fail "unauthorized")))

(defn revoke-post [sys req]
  (if (require-session sys req "admin")
    (try
      (let [body (read-body req)
            id (:user_id body)
            uid (cond
                  (number? id) (long id)
                  (string? id) (Long/parseLong id)
                  :else nil)]
        (accounts/revoke-user sys uid)
        (ok {}))
      (catch Exception e
        (log/warn "取消し要求を読めませんでした" :error (.getMessage e))
        (ok {})))
    (fail "unauthorized")))

(def api-routes
  {[:get "/api/user/session"] [:session "user"]
   [:post "/api/user/login"] [:login "user"]
   [:post "/api/user/logout"] [:logout "user"]
   [:post "/api/user/password/reset/request"] [:reset-request "user"]
   [:post "/api/user/password/reset"] [:reset-complete "user"]
   [:post "/api/user/password"] [:password "user"]
   [:post "/api/user/invite"] [:invite "user"]
   [:get "/api/admin/session"] [:session "admin"]
   [:post "/api/admin/login"] [:login "admin"]
   [:post "/api/admin/logout"] [:logout "admin"]
   [:post "/api/admin/password/reset/request"] [:reset-request "admin"]
   [:post "/api/admin/password/reset"] [:reset-complete "admin"]
   [:post "/api/admin/password"] [:password "admin"]
   [:post "/api/admin/invite"] [:invite "admin"]
   [:get "/api/admin/users"] [:users]
   [:post "/api/admin/users/revoke"] [:revoke]})

(defn dispatch-api [sys req]
  (let [method (:request-method req)
        uri (:uri req)
        spec (get api-routes [method uri])]
    (if-not spec
      (do
        (log/warn "APIの経路がありません" :method method :uri uri)
        (fail "unauthorized"))
      (case (first spec)
        :session (session-get sys req (second spec))
        :login (login-post sys req (second spec))
        :logout (logout-post sys req (second spec))
        :reset-request (reset-request-post sys req (second spec))
        :reset-complete (reset-complete-post sys req (second spec))
        :password (password-post sys req (second spec))
        :invite (invite-post sys req (second spec))
        :users (users-get sys req)
        :revoke (revoke-post sys req)
        (fail "unauthorized")))))

(defn index-html []
  (slurp (io/resource "public/index.html") :encoding "UTF-8"))

(defn static-response [req]
  (let [uri (:uri req)
        path (str "public" uri)]
    (if-let [res (io/resource path)]
      (-> (response/response (slurp res))
          (response/content-type (if (str/ends-with? uri ".js")
                                   "application/javascript; charset=utf-8"
                                   "application/octet-stream")))
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "not found"})))

(defn spa-response []
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (index-html)})

(defn handler [sys req]
  (let [uri (or (:uri req) "/")]
    (cond
      (str/starts-with? uri "/api/") (dispatch-api sys req)
      (str/starts-with? uri "/js/") (if (= :get (:request-method req))
                                      (static-response req)
                                      {:status 405 :headers {} :body ""})
      :else (if (= :get (:request-method req))
              (spa-response)
              {:status 405 :headers {} :body ""}))))

(defn make-app [sys]
  (cookies/wrap-cookies (fn [req] (handler sys req))))
