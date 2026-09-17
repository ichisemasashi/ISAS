(ns isas.time-test
  (:require [clojure.test :refer [deftest is]]
            [isas.time :as time])
  (:import [java.time Instant LocalDateTime]))

(deftest now-and-format-test
  (let [fixed (Instant/parse "2026-09-12T00:00:00Z")]
    (binding [time/*now-fn* (fn [] fixed)]
      (is (= "2026-09-12T00:00:00Z" (time/now-utc)))
      (is (= "2026-09-12T01:00:00Z" (time/plus-hours 1)))
      (is (= "2026-09-26T00:00:00Z" (time/plus-days 14)))
      (is (true? (time/before? (time/now-utc) (time/plus-hours 1))))
      (is (false? (time/before? (time/plus-hours 1) (time/now-utc))))
      (is (= (java.time.LocalDate/of 2026 9 12) (time/today-tokyo)))
      (is (= "2026-09-12T08:00" (time/gantt-default-start)))
      (is (= "2026-09-12T17:00" (time/gantt-default-end)))
      (is (= "2026-09-12T00:00" (time/axis-start-day)))
      (is (= "2026-09-15T00:00" (time/axis-end-hours 72)))
      (is (= "2026-09-19T00:00" (time/axis-week-end)))
      (is (= "2026-10-01T00:00" (time/axis-month-end)))
      (is (= "2026-09-12T08:30" (time/today-at 8 30)))
      (is (true? (time/local-minute-ok? "2026-09-12T08:00")))
      (is (false? (time/local-minute-ok? "bad")))
      (is (nil? (time/parse-local-minute "2026-09-12T08:00:00")))
      (is (instance? LocalDateTime (time/parse-local-minute "2026-09-12T08:00")))
      (is (= "2026-09-12T08:00"
             (time/format-local-minute (LocalDateTime/of 2026 9 12 8 0))))))
  (let [dec (Instant/parse "2026-12-15T00:00:00Z")]
    (binding [time/*now-fn* (fn [] dec)]
      (is (= "2027-01-01T00:00" (time/axis-month-end)))))
  (is (re-matches #"\d{4}-\d{2}-\d{2}T.*" (time/now-utc)))
  (is (instance? Instant (time/now-instant)))
  (is (instance? Instant (time/parse-instant "2026-01-01T00:00:00Z"))))
