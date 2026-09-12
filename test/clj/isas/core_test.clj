(ns isas.core-test
  (:require [clojure.test :refer [deftest is]]
            [isas.core :as core]
            [isas.test-util :as tu]))

(deftest open-and-start-stop-test
  (let [conf (tu/temp-file "conf" ".conf" tu/sample-conf)
        db (tu/temp-db-path)
        sys (core/open-system {:conf-path conf :db-path db})
        app (core/make-app sys)
        started (core/start-server app {:port 0 :join? false})]
    (is (some? app))
    (is (some? started))
    (.stop started)
    (let [full (core/start! {:conf-path conf :db-path (tu/temp-db-path) :port 0 :join? false})]
      (is (some? (:server full)))
      (core/stop! full)
      (core/stop! {}))))

(deftest halt-and-main-test
  (let [codes (atom [])]
    (binding [core/*exit-fn* #(swap! codes conj %)]
      (core/halt 7)
      (is (= [7] @codes))))
  (is (= 1 (:isas/exit-code (ex-data (try (core/halt 1) (catch Exception e e))))))
  (let [seen (atom nil)]
    (with-redefs [core/start! (fn [opts] (reset! seen opts) {:ok true})]
      (core/-main)
      (is (= 8080 (:port @seen)))
      (core/-main "9090")
      (is (= 9090 (:port @seen)))))
  (let [seen (atom nil)]
    (with-redefs [core/open-system (fn [m] (assoc m :opened true))
                  core/make-app (fn [_] (fn [_req] {:status 200}))
                  core/start-server (fn [_app opts] (reset! seen opts) :srv)]
      (let [r (core/start!)]
        (is (true? (:join? @seen)))
        (is (= :srv (:server r))))))
  (let [code (atom nil)]
    (binding [core/*exit-fn* #(reset! code %)]
      (core/-main "nope")
      (is (= 1 @code))))
  (let [code (atom nil)]
    (binding [core/*exit-fn* #(reset! code %)]
      (with-redefs [core/start! (fn [_] (throw (Exception. "fail start")))]
        (core/-main "8081")
        (is (= 1 @code))))))
