(ns isas.e2e-harness
  "ブラウザ自動試験用に一時 DB／設定で HTTP を立ち上げ、利用者と作業場所を用意する。"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.core :as core]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.http :as http]
            [isas.log :as log]
            [isas.test-util :as tu]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn- seed! [sys]
  (let [app (http/make-app sys)
        asid (tu/admin-sid app)
        email "e2e@example.com"
        pw (tu/invite-pw app asid email)]
    (when (str/blank? pw)
      (throw (ex-info "e2e invite failed" {:email email})))
    (let [uid (:id (db/find-user-by-email (:ds sys) email))]
      (fields/put-place sys uid {:west 140.04 :south 37.88 :east 140.06 :north 37.90})
      (fields/create-field sys uid {:name "E2E圃場"
                                    :geojson {:type "Polygon"
                                              :coordinates [[[140.045 37.885]
                                                             [140.055 37.885]
                                                             [140.055 37.895]
                                                             [140.045 37.895]
                                                             [140.045 37.885]]]}})
      {:email email :password pw :user-id uid})))

(defn -main [& _args]
  (binding [crypto/*cost* 4]
    (let [dir (.getAbsolutePath (doto (io/file "target/e2e-run")
                                  (.mkdirs)))
          conf-path (str dir "/isas.conf")
          db-path (str dir "/isas.sqlite")
          basemap-dir (str dir "/basemaps")
          ready-path (str dir "/ready.json")
          port (free-port)
          _ (spit conf-path tu/sample-conf :encoding "UTF-8")
          sys (core/open-system {:conf-path conf-path
                                 :db-path db-path
                                 :basemap-dir basemap-dir})
          seed (seed! sys)
          app (http/make-app sys)
          server (jetty/run-jetty app {:port port :join? false})
          ready {:baseURL (str "http://127.0.0.1:" port)
                 :email (:email seed)
                 :password (:password seed)
                 :port port}]
      (spit ready-path (json/write-str ready) :encoding "UTF-8")
      (log/info "e2e harness ready" :port port :ready ready-path)
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (try (.stop server) (catch Exception _))
                  (log/info "e2e harness stopped"))))
      @(promise))))
