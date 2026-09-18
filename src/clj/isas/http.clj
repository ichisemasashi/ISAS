(ns isas.http
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.accounts :as accounts]
            [isas.emaff :as emaff]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.log :as log]
            [isas.orders :as orders]
            [isas.paints :as paints]
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
        (let [body (read-body req)
              _ (log/info "作業場所の保存要求を受けました"
                          :user-id uid
                          :west (:west body) :south (:south body)
                          :east (:east body) :north (:north body))
              r (fields/put-place sys uid body)]
          (if (:ok r)
            (do
              (log/info "作業場所の保存 API が成功しました" :user-id uid)
              (ok {}))
            (do
              (log/warn "作業場所の保存 API が失敗しました" :user-id uid :code (:code r))
              (fail (:code r)))))
        (catch Exception e
          (log/warn "作業場所を読めませんでした" :user-id uid :error (.getMessage e))
          (fail "place_invalid"))))))

(defn place-image-put [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (fields/put-image-extent sys uid (read-body req))]
          (if (:ok r) (ok {}) (fail (:code r))))
        (catch Exception e
          (log/warn "下地の位置を読めませんでした" :user-id uid :error (.getMessage e))
          (fail "place_invalid"))))))

(defn place-preview [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [body (read-body req)
              _ (log/info "作業場所の最終確認要求を受けました"
                          :user-id uid
                          :west (:west body) :south (:south body)
                          :east (:east body) :north (:north body))
              r (emaff/preview-aerial sys uid body)]
          (if (:ok r)
            (do
              (log/info "作業場所の最終確認 API が成功しました"
                        :user-id uid :source (:source r) :kind (:kind r)
                        :west (get-in r [:bbox :west]) :south (get-in r [:bbox :south])
                        :east (get-in r [:bbox :east]) :north (get-in r [:bbox :north]))
              (ok (dissoc r :ok)))
            (do
              (log/warn "作業場所の最終確認 API が失敗しました" :user-id uid :code (:code r))
              (fail (:code r)))))
        (catch Exception e
          (log/warn "最終確認を読めませんでした" :user-id uid :error (.getMessage e))
          (fail "place_invalid"))))))

(defn emaff-import [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (emaff/import-for-place sys uid)]
        (if (:ok r)
          (ok (dissoc r :ok))
          (fail (:code r)))))))

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

(defn query-params [req]
  (let [q (or (:query-string req) "")]
    (if (str/blank? q)
      {}
      (->> (str/split q #"&")
           (remove str/blank?)
           (map (fn [part]
                  (let [i (str/index-of part "=")
                        [k v] (if i
                                [(subs part 0 i) (subs part (inc i))]
                                [part ""])]
                    [(keyword k)
                     (try
                       (java.net.URLDecoder/decode (str v) "UTF-8")
                       (catch Exception _ (str v)))])))
           (into {})))))

(defn work-names-get [sys req]
  (with-farm sys req
    (fn [uid]
      (ok (select-keys (paints/list-work-names sys uid) [:work_names])))))

(defn paints-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (paints/list-paints sys uid (:work_name (query-params req)))]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn paints-post [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (paints/create-paint sys uid (read-body req))]
          (if (:ok r) (ok {:paint (:paint r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "塗り確定を読めませんでした" :error (.getMessage e))
          (fail "paint_empty"))))))

(defn field-complete [sys req id]
  (with-farm sys req
    (fn [uid]
      (try
        (let [body (read-body req)
              r (paints/complete-field sys uid id (:work_name body))]
          (if (:ok r) (ok {}) (fail (:code r))))
        (catch Exception e
          (log/warn "全面完了を読めませんでした" :error (.getMessage e))
          (fail "work_name_required"))))))

(defn paint-delete [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (paints/delete-paint sys uid id)]
        (if (:ok r) (ok {}) (fail (:code r)))))))

(defn field-paints-delete [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (paints/delete-field-paints sys uid id (:work_name (query-params req)))]
        (if (:ok r) (ok {}) (fail (:code r)))))))

(defn gantt-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (gantt/list-rows sys uid)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn gantt-post [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (gantt/create-row sys uid (read-body req))]
          (if (:ok r) (ok {:row (:row r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "ガント行の追加を読めませんでした" :error (.getMessage e))
          (fail "title_required"))))))

(defn gantt-put [sys req id]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (gantt/update-row sys uid id (read-body req))]
          (if (:ok r) (ok {:row (:row r)}) (fail (:code r))))
        (catch Exception e
          (log/warn "ガント行の更新を読めませんでした" :error (.getMessage e))
          (fail "title_required"))))))

(defn gantt-progress [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (gantt/row-progress sys uid id)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn work-name-candidates-get [sys req]
  (with-farm sys req
    (fn [uid]
      (ok (select-keys (gantt/work-name-candidates sys uid) [:work_names])))))

(defn orders-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/list-orders sys uid)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn orders-post [sys req]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (orders/create-order sys uid (read-body req))]
          (if (:ok r) (ok (dissoc r :ok)) (fail (:code r))))
        (catch Exception e
          (log/warn "指示の作成を読めませんでした" :error (.getMessage e))
          (fail "time_invalid"))))))

(defn order-get [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/get-order sys uid id)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn order-put [sys req id]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (orders/update-order sys uid id (read-body req))]
          (if (:ok r) (ok (dissoc r :ok)) (fail (:code r))))
        (catch Exception e
          (log/warn "指示の更新を読めませんでした" :error (.getMessage e))
          (fail "time_invalid"))))))

(defn order-close [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/close-order sys uid id)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn order-journal [sys req id]
  (with-farm sys req
    (fn [uid]
      (try
        (let [r (orders/post-journal sys uid id (read-body req))]
          (if (:ok r) (ok (dissoc r :ok)) (fail (:code r))))
        (catch Exception e
          (log/warn "日誌を読めませんでした" :error (.getMessage e))
          (fail "journal_required"))))))

(defn order-map-get [sys req id]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/order-map sys uid id)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn others-fields-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/others-fields sys uid)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn others-work-names-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/others-work-names sys uid)]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn others-paints-get [sys req]
  (with-farm sys req
    (fn [uid]
      (let [r (orders/others-paints sys uid (:work_name (query-params req)))]
        (if (:ok r) (ok (dissoc r :ok)) (fail (:code r)))))))

(defn relations-cut [sys req]
  (if (require-session sys req "admin")
    (try
      (let [body (read-body req)
            r (orders/cut-relation sys (:email_a body) (:email_b body))]
        (if (:ok r) (ok {}) (fail (:code r))))
      (catch Exception e
        (log/warn "関係切断を読めませんでした" :error (.getMessage e))
        (fail "relation_not_found")))
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
   [:post "/api/admin/users/revoke"] [:revoke]
   [:post "/api/admin/relations/cut"] [:relations-cut]
   [:get "/api/user/place"] [:place-get]
   [:put "/api/user/place"] [:place-put]
   [:put "/api/user/place/image"] [:place-image-put]
   [:post "/api/user/place/preview"] [:place-preview]
   [:post "/api/user/emaff/import"] [:emaff-import]
   [:get "/api/user/basemaps"] [:basemaps-get]
   [:get "/api/user/fields"] [:fields-get]
   [:post "/api/user/fields"] [:fields-post]
   [:post "/api/user/fields/merge"] [:fields-merge]
   [:post "/api/user/fields/import"] [:fields-import]
   [:get "/api/user/work-names"] [:work-names-get]
   [:get "/api/user/work-name-candidates"] [:work-name-candidates-get]
   [:get "/api/user/paints"] [:paints-get]
   [:post "/api/user/paints"] [:paints-post]
   [:get "/api/user/gantt"] [:gantt-get]
   [:post "/api/user/gantt"] [:gantt-post]
   [:get "/api/user/orders"] [:orders-get]
   [:post "/api/user/orders"] [:orders-post]
   [:get "/api/user/others/fields"] [:others-fields-get]
   [:get "/api/user/others/work-names"] [:others-work-names-get]
   [:get "/api/user/others/paints"] [:others-paints-get]})

(defn match-api [method uri]
  (or (get api-routes [method uri])
      (when-let [[_ kind] (re-matches #"/api/user/basemaps/([^/]+)" (str uri))]
        (cond
          (= method :put) [:basemap-put kind]
          (= method :get) [:basemap-get kind]
          :else nil))
      (when-let [[_ id] (re-matches #"/api/user/fields/(\d+)/complete" (str uri))]
        (when (= method :post) [:field-complete id]))
      (when-let [[_ id] (re-matches #"/api/user/fields/(\d+)/paints" (str uri))]
        (when (= method :delete) [:field-paints-delete id]))
      (when-let [[_ id] (re-matches #"/api/user/fields/(\d+)/split" (str uri))]
        (when (= method :post) [:field-split id]))
      (when-let [[_ id] (re-matches #"/api/user/paints/(\d+)" (str uri))]
        (when (= method :delete) [:paint-delete id]))
      (when-let [[_ id] (re-matches #"/api/user/gantt/(\d+)/progress" (str uri))]
        (when (= method :get) [:gantt-progress id]))
      (when-let [[_ id] (re-matches #"/api/user/gantt/(\d+)" (str uri))]
        (when (= method :put) [:gantt-put id]))
      (when-let [[_ id] (re-matches #"/api/user/orders/(\d+)/close" (str uri))]
        (when (= method :post) [:order-close id]))
      (when-let [[_ id] (re-matches #"/api/user/orders/(\d+)/journal" (str uri))]
        (when (= method :post) [:order-journal id]))
      (when-let [[_ id] (re-matches #"/api/user/orders/(\d+)/map" (str uri))]
        (when (= method :get) [:order-map id]))
      (when-let [[_ id] (re-matches #"/api/user/orders/(\d+)" (str uri))]
        (cond
          (= method :get) [:order-get id]
          (= method :put) [:order-put id]
          :else nil))
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
        :relations-cut (relations-cut sys req)
        :place-get (place-get sys req)
        :place-put (place-put sys req)
        :place-image-put (place-image-put sys req)
        :place-preview (place-preview sys req)
        :emaff-import (emaff-import sys req)
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
        :work-names-get (work-names-get sys req)
        :work-name-candidates-get (work-name-candidates-get sys req)
        :paints-get (paints-get sys req)
        :paints-post (paints-post sys req)
        :field-complete (field-complete sys req (second spec))
        :field-paints-delete (field-paints-delete sys req (second spec))
        :paint-delete (paint-delete sys req (second spec))
        :gantt-get (gantt-get sys req)
        :gantt-post (gantt-post sys req)
        :gantt-put (gantt-put sys req (second spec))
        :gantt-progress (gantt-progress sys req (second spec))
        :orders-get (orders-get sys req)
        :orders-post (orders-post sys req)
        :order-get (order-get sys req (second spec))
        :order-put (order-put sys req (second spec))
        :order-close (order-close sys req (second spec))
        :order-journal (order-journal sys req (second spec))
        :order-map (order-map-get sys req (second spec))
        :others-fields-get (others-fields-get sys req)
        :others-work-names-get (others-work-names-get sys req)
        :others-paints-get (others-paints-get sys req)
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
