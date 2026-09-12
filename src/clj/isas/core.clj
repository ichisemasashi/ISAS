(ns isas.core
  (:require [isas.accounts :as accounts]
            [isas.config :as config]
            [isas.db :as db]
            [isas.http :as http]
            [isas.log :as log]
            [ring.adapter.jetty :as jetty]
            [clojure.java.io :as io])
  (:gen-class))

(def ^:dynamic *exit-fn* nil)

(defn halt [code]
  (if-let [f *exit-fn*]
    (f code)
    (throw (ex-info "起動できませんでした"
                    {:isas/exit-code code}))))

(defn open-system [{:keys [conf-path db-path basemap-dir]
                    :or {basemap-dir "data/basemaps"}}]
  (let [conf (config/load-conf conf-path)
        ds (db/migrate! (db/datasource (db/sqlite-url db-path)))
        dir (io/file basemap-dir)]
    (.mkdirs dir)
    (accounts/bootstrap-admin! ds conf)
    (log/info "ISAS を用意しました" :db db-path :log-file "data/isas.log" :basemaps (.getPath dir))
    {:conf conf :ds ds :conf-path conf-path :db-path db-path :basemap-dir (.getPath dir)}))

(defn make-app [sys]
  (http/make-app sys))

(defn start-server [app {:keys [port join?]}]
  (log/info "HTTP を開始します" :port port)
  (jetty/run-jetty app {:port port :join? (boolean join?)}))

(defn start!
  ([] (start! {}))
  ([{:keys [conf-path db-path port join? basemap-dir]
     :or {conf-path "data/isas.conf"
          db-path "data/isas.sqlite"
          port 8080
          join? true
          basemap-dir "data/basemaps"}}]
   (let [sys (open-system {:conf-path conf-path :db-path db-path :basemap-dir basemap-dir})
         app (make-app sys)
         server (start-server app {:port port :join? join?})]
     (assoc sys :app app :server server))))

(defn stop! [sys]
  (when-let [server (:server sys)]
    (.stop server)
    (log/info "HTTP を止めました"))
  sys)

(defn -main [& args]
  (try
    (let [port (if (seq args)
                 (Integer/parseInt (first args))
                 8080)]
      (start! {:port port :join? true}))
    (catch Exception e
      (log/error "起動できませんでした" :error (.getMessage e))
      (halt 1))))
