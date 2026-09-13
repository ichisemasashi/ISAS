(ns isas.test-util
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [isas.accounts :as accounts]
            [isas.config :as config]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.http :as http]
            [isas.mail :as mail]
            [isas.time :as time]
            [isas.ui :as ui]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock])
  (:import [java.io File]))

(def sample-conf
  (str "admin_email=admin@example.com\n"
       "admin_password=ChangeMeAdmin1\n"
       "smtp_host=smtp.example.com\n"
       "smtp_port=587\n"
       "smtp_user=isas@example.com\n"
       "smtp_password=ChangeMeSmtp1\n"
       "smtp_from=isas@example.com\n"
       "public_url=http://localhost:8080\n"
       "smtp_starttls=1\n"))

(defn temp-file [prefix suffix contents]
  (let [f (File/createTempFile prefix suffix)]
    (.deleteOnExit f)
    (when contents
      (spit f contents :encoding "UTF-8"))
    (.getPath f)))

(defn temp-db-path []
  (let [f (File/createTempFile "isas" ".sqlite")]
    (.deleteOnExit f)
    (.delete f)
    (.getPath f)))

(defn test-system
  ([] (test-system {}))
  ([{:keys [conf-text send-fn now]
     :or {conf-text sample-conf}}]
   (binding [crypto/*cost* 4]
     (let [conf (config/parse-text conf-text)
           db-path (temp-db-path)
           ds (db/migrate! (db/datasource (db/sqlite-url db-path)))
           sent (atom [])
           send! (or send-fn
                     (fn [_conf message]
                       (swap! sent conj message)
                       {:error :SUCCESS}))
           bdir (let [d (File/createTempFile "basemap" "dir")]
                  (.delete d)
                  (.mkdirs d)
                  (.deleteOnExit d)
                  (.getPath d))]
       (accounts/bootstrap-admin! ds conf)
       {:conf conf
        :ds ds
        :db-path db-path
        :sent sent
        :send-fn send!
        :now now
        :basemap-dir bdir}))))

(defn with-sys
  ([f] (with-sys {} f))
  ([opts f]
   (binding [crypto/*cost* 4]
     (let [sys (test-system opts)]
       (if-let [n (:now opts)]
         (binding [time/*now-fn* (fn [] n)
                   mail/*send-fn* (:send-fn sys)]
           (f sys))
         (binding [mail/*send-fn* (:send-fn sys)]
           (f sys)))))))

(defn app [sys]
  (http/make-app sys))

(defn parse [resp]
  (let [b (:body resp)]
    (cond
      (map? b) b
      (string? b) (json/read-str b :key-fn keyword)
      (nil? b) nil
      :else (json/read-str (slurp b) :key-fn keyword))))

(defn- header-vals [resp name]
  (let [want (str/lower-case name)
        headers (:headers resp)]
    (->> headers
         (filter (fn [[k _]] (= want (str/lower-case (str k)))))
         (map val)
         (mapcat #(if (coll? %) % [%])))))

(defn cookie-value [resp name]
  (or (get-in resp [:cookies name :value])
      (some (fn [s]
              (second (re-find (re-pattern (str name "=([^;]+)")) (str s))))
            (header-vals resp "Set-Cookie"))))

(defn cookie-header [resp]
  (str/join " " (map str (header-vals resp "Set-Cookie"))))

(defn as-user [req kind sid]
  (mock/header req "cookie" (str (http/cookie-name kind) "=" sid)))

(defn post-json
  ([app path body] (post-json app path body nil nil))
  ([app path body kind sid]
   (let [req (-> (mock/request :post path)
                 (mock/content-type "application/json")
                 (mock/body (json/write-str body)))]
     (app (if sid (as-user req kind sid) req)))))

(defn get-path
  ([app path] (get-path app path nil nil))
  ([app path kind sid]
   (let [req (mock/request :get path)]
     (app (if sid (as-user req kind sid) req)))))

(defn put-json
  ([app path body] (put-json app path body nil nil))
  ([app path body kind sid]
   (let [req (-> (mock/request :put path)
                 (mock/content-type "application/json")
                 (mock/body (json/write-str body)))]
     (app (if sid (as-user req kind sid) req)))))

(defn delete-path
  ([app path] (delete-path app path nil nil))
  ([app path kind sid]
   (let [req (mock/request :delete path)]
     (app (if sid (as-user req kind sid) req)))))

(def square
  {:type "Polygon"
   :coordinates [[[140.0 36.0]
                  [140.001 36.0]
                  [140.001 36.001]
                  [140.0 36.001]
                  [140.0 36.0]]]})

(def square-east
  {:type "Polygon"
   :coordinates [[[140.002 36.0]
                  [140.003 36.0]
                  [140.003 36.001]
                  [140.002 36.001]
                  [140.002 36.0]]]})

(defn admin-sid [app]
  (cookie-value (post-json app "/api/admin/login"
                           {:email "admin@example.com" :password "ChangeMeAdmin1"})
                "isas_admin"))

(defn invite-pw [app asid email]
  (:initial_password (parse (post-json app "/api/admin/invite" {:email email} "admin" asid))))

(defn user-sid [app email password]
  (cookie-value (post-json app "/api/user/login" {:email email :password password})
                "isas_user"))

(defn page-html
  ([opts] (page-html (ui/init-state) opts))
  ([state opts]
   (ui/render (merge state opts))))

(defn fx-op [state msg]
  (ffirst (:fx (ui/handle state msg))))

(defn table-names [ds]
  (set (map :name (jdbc/execute! ds ["SELECT name FROM sqlite_master WHERE type='table'"]))))
