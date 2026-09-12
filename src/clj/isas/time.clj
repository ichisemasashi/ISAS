(ns isas.time
  (:import [java.time Instant Duration]
           [java.time.format DateTimeFormatter]))

(def ^:dynamic *now-fn* nil)

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
