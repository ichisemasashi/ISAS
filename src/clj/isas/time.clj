(ns isas.time
  (:import [java.time Instant Duration LocalDate LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(def ^:dynamic *now-fn* nil)

(def tokyo (ZoneId/of "Asia/Tokyo"))

(def local-minute-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm"))

(defn now-instant []
  (if-let [f *now-fn*]
    (f)
    (Instant/now)))

(defn format-instant [^Instant inst]
  (.format DateTimeFormatter/ISO_INSTANT inst))

(defn now-utc []
  (format-instant (now-instant)))

(defn plus-hours [hours]
  (format-instant (.plus (now-instant) (Duration/ofHours hours))))

(defn plus-days [days]
  (format-instant (.plus (now-instant) (Duration/ofDays days))))

(defn parse-instant [s]
  (Instant/parse s))

(defn before? [a b]
  (.isBefore (parse-instant a) (parse-instant b)))

(defn today-tokyo
  "日本時間の今日の LocalDate。"
  []
  (LocalDate/ofInstant (now-instant) tokyo))

(defn format-local-minute [^LocalDateTime ldt]
  (.format ldt local-minute-fmt))

(defn parse-local-minute [s]
  (try
    (LocalDateTime/parse (str s) local-minute-fmt)
    (catch Exception _
      nil)))

(defn local-minute-ok? [s]
  (boolean (parse-local-minute s)))

(defn today-at [hour minute]
  (format-local-minute (.atTime (today-tokyo) (int hour) (int minute))))

(defn gantt-default-start []
  (today-at 8 0))

(defn gantt-default-end []
  (today-at 17 0))

(defn axis-start-day []
  (format-local-minute (.atStartOfDay (today-tokyo))))

(defn axis-end-hours [hours]
  (format-local-minute (.plusHours (.atStartOfDay (today-tokyo)) (long hours))))

(defn axis-week-end []
  (format-local-minute (.plusDays (.atStartOfDay (today-tokyo)) 7)))

(defn axis-month-end []
  (let [d (today-tokyo)
        end (.plusMonths (.withDayOfMonth d 1) 1)]
    (format-local-minute (.atStartOfDay end))))
