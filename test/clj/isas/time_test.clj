(ns isas.time-test
  (:require [clojure.test :refer [deftest is]]
            [isas.time :as time])
  (:import [java.time Instant]))

(deftest now-and-format-test
  (let [fixed (Instant/parse "2026-09-12T00:00:00Z")]
    (binding [time/*now-fn* (fn [] fixed)]
      (is (= "2026-09-12T00:00:00Z" (time/now-utc)))
      (is (= "2026-09-12T01:00:00Z" (time/plus-hours 1)))
      (is (= "2026-09-26T00:00:00Z" (time/plus-days 14)))
      (is (true? (time/before? (time/now-utc) (time/plus-hours 1))))
      (is (false? (time/before? (time/plus-hours 1) (time/now-utc))))))
  (is (re-matches #"\d{4}-\d{2}-\d{2}T.*" (time/now-utc)))
  (is (instance? Instant (time/now-instant)))
  (is (instance? Instant (time/parse-instant "2026-01-01T00:00:00Z"))))
