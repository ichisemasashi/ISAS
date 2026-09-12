(ns isas.test-util
  (:require [isas.accounts :as accounts]
            [isas.config :as config]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.http :as http]
            [isas.mail :as mail]
            [isas.time :as time])
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
                       {:error :SUCCESS}))]
       (accounts/bootstrap-admin! ds conf)
       {:conf conf
        :ds ds
        :db-path db-path
        :sent sent
        :send-fn send!
        :now now}))))

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
