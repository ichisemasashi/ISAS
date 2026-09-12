(ns isas.http
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.accounts :as accounts]
            [isas.fields :as fields]
            [isas.log :as log]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.multipart-params :as mp]
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
  ([code] (fail code (cond
                       (= code "unauthorized") 401
                       (= code "forbidden") 403
                       :else 200)))
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

(defn farm-user [sys req]
  (if (require-session sys req "admin")
    :forbidden
    (if-let [ctx (require-session sys req "user")]
      ctx
      :unauthorized)))

(defn with-farm [sys req f]
  (let [u (farm-user sys req)]
    (cond
      (= u :forbidden) (fail "forbidden")
      (= u :unauthorized) (fail "unauthorized")
      :else (f (get-in u [:account :id])))))

(defn upload-of [req]
  (or (get-in req [:params "file"])
      (get-in req [:params :file])
      (get-in req [:multipart-params "file"])
      (get-in req [:multipart-params :file])))

(defn place-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (fields/get-place sys uid)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn place-put [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (fields/put-place sys uid (read-body req))]
          (if (:ok r) (ok {}) (fail (:code r))))
        (catch Exception e
          (log/warn "作業場所を読めませんでした" :error (.getMessage e))
          (fail "place_invalid"))))))

(defn basemaps-get [sys req]
  (with-farm sys req
    (fn [uid]
      (ok (select-keys (fields/list-basemap-status sys uid) [:basemaps])))))

(defn basemap-put [sys req kind]
  (with-farm sys req
    (fn [uid]
      (let [r (fields/put-basemap sys uid kind (upload-of req))]
        (if (:ok r) (ok {}) (fail (:code r)))))))

(defn basemap-file [sys req kind]
  (with-farm sys req
    (fn [uid]
      (let [r (fields/get-basemap sys uid kind)]
        (if (:ok r)
          {:status 200
           :headers {"Content-Type" (:content-type r)}
           :body (:file r)}
          (fail (:code r)))))))

(defn fields-get [sys req]
  (with-farm sys req
    (fn [uid]
      (ok (select-keys (fields/list-fields sys uid) [:fields])))))

(defn fields-post [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (fields/create-field sys uid (read-body req))]
          (if (:ok r) (ok {:field (:field r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "圃場の追加を読めませんでした" :error (.getMessage e))
          (fail "shape_not_area"))))))

(defn field-put [sys req id]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (fields/update-field sys uid id (read-body req))]
          (if (:ok r) (ok {:field (:field r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "圃場の更新を読めませんでした" :error (.getMessage e))
          (fail "shape_not_area"))))))

(defn field-delete [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (fields/delete-field sys uid id)]
        (if (:ok r) (ok {}) (fail (:code r)))))))

(defn field-split [sys req id]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (fields/split-field sys uid id (read-body req))]
          (if (:ok r) (ok {:fields (:fields r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "分割要求を読めませんでした" :error (.getMessage e))
          (fail "split_too_few"))))))

(defn fields-merge [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (fields/merge-fields sys uid (read-body req))]
          (if (:ok r) (ok {:field (:field r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "合筆要求を読めませんでした" :error (.getMessage e))
          (fail "merge_too_few"))))))

(defn fields-import [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (fields/import-geojson sys uid (upload-of req))]
        (if (:ok r) (ok {:fields (:fields r)}) (fail (:code r)))))))

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
   [:post "/api/admin/users/revoke"] [:revoke]
   [:get "/api/user/place"] [:place-get]
   [:put "/api/user/place"] [:place-put]
   [:get "/api/user/basemaps"] [:basemaps-get]
   [:get "/api/user/fields"] [:fields-get]
   [:post "/api/user/fields"] [:fields-post]
   [:post "/api/user/fields/merge"] [:fields-merge]
   [:post "/api/user/fields/import"] [:fields-import]})

(defn match-api [method uri]
  (or (get api-routes [method uri])
      (when-let [[_ kind] (re-matches #"/api/user/basemaps/([^/]+)" (str uri))]
        (cond
          (= method :put) [:basemap-put kind]
          (= method :get) [:basemap-get kind]
          :else nil))
      (when-let [[_ id] (re-matches #"/api/user/fields/(\d+)/split" (str uri))]
        (when (= method :post) [:field-split id]))
      (when-let [[_ id] (re-matches #"/api/user/fields/(\d+)" (str uri))]
        (cond
          (= method :put) [:field-put id]
          (= method :delete) [:field-delete id]
          :else nil))))

(defn dispatch-api [sys req]
  (let [method (:request-method req)
        uri (:uri req)
        spec (match-api method uri)]
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
        :place-get (place-get sys req)
        :place-put (place-put sys req)
        :basemaps-get (basemaps-get sys req)
        :basemap-put (basemap-put sys req (second spec))
        :basemap-get (basemap-file sys req (second spec))
        :fields-get (fields-get sys req)
        :fields-post (fields-post sys req)
        :field-put (field-put sys req (second spec))
        :field-delete (field-delete sys req (second spec))
        :field-split (field-split sys req (second spec))
        :fields-merge (fields-merge sys req)
        :fields-import (fields-import sys req)
        (fail "unauthorized")))))

(defn index-html []
  (slurp (io/resource "public/index.html") :encoding "UTF-8"))

(defn static-content-type [uri]
  (cond
    (str/ends-with? uri ".js") "application/javascript; charset=utf-8"
    (str/ends-with? uri ".css") "text/css; charset=utf-8"
    :else "application/octet-stream"))

(defn static-response [req]
  (let [uri (:uri req)
        path (str "public" uri)]
    (if-let [res (io/resource path)]
      (-> (response/response (slurp res))
          (response/content-type (static-content-type uri)))
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
      (str/starts-with? uri "/css/") (if (= :get (:request-method req))
                                       (static-response req)
                                       {:status 405 :headers {} :body ""})
      :else (if (= :get (:request-method req))
              (spa-response)
              {:status 405 :headers {} :body ""}))))

(defn make-app [sys]
  (-> (fn [req] (handler sys req))
      mp/wrap-multipart-params
      cookies/wrap-cookies))
