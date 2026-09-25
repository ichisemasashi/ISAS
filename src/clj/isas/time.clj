(ns isas.time
  (:import [java.time Instant Duration LocalDate LocalDateTime LocalTime ZoneId]
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

(defn tokyo-today-window
  "Asia/Tokyo の今日 [0:00, 翌日0:00)。終端は含まない。local-minute 文字列の組。"
  []
  (let [d (today-tokyo)
        start (.atStartOfDay d)
        end (.atStartOfDay (.plusDays d 1))]
    [(format-local-minute start) (format-local-minute end)]))

(defn tokyo-now-local-minute
  "Asia/Tokyo の現在時刻（分まで）の local-minute 文字列。"
  []
  (format-local-minute (LocalDateTime/ofInstant (now-instant) tokyo)))

(defn- this-monday []
  (let [d (today-tokyo)
        dow (.getValue (.getDayOfWeek d))]
    (.minusDays d (long (dec dow)))))

(defn tokyo-week-window
  "Asia/Tokyo の今週（月曜始まり）[月曜0:00, 翌月曜0:00)。終端は含まない。"
  []
  (let [monday (this-monday)]
    [(format-local-minute (.atStartOfDay monday))
     (format-local-minute (.atStartOfDay (.plusDays monday 7)))]))

(defn tokyo-last-week-window
  "Asia/Tokyo の先週（月曜始まり）[先週月曜0:00, 今週月曜0:00)。終端は含まない。"
  []
  (let [monday (this-monday)]
    [(format-local-minute (.atStartOfDay (.minusDays monday 7)))
     (format-local-minute (.atStartOfDay monday))]))

(defn tokyo-around7-window
  "Asia/Tokyo の前後7日（今日含む15暦日）[(今日-7日)0:00, (今日+8日)0:00)。終端は含まない。"
  []
  (let [d (today-tokyo)]
    [(format-local-minute (.atStartOfDay (.minusDays d 7)))
     (format-local-minute (.atStartOfDay (.plusDays d 8)))]))

(def work-date-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(def clock-time-fmt (DateTimeFormatter/ofPattern "HH:mm"))

(defn parse-work-date [s]
  (try
    (LocalDate/parse (str s) work-date-fmt)
    (catch Exception _
      nil)))

(defn work-date-ok? [s]
  (boolean (parse-work-date s)))

(defn parse-clock-time [s]
  (try
    (LocalTime/parse (str s) clock-time-fmt)
    (catch Exception _
      nil)))

(defn clock-time-ok? [s]
  (boolean (parse-clock-time s)))

(defn today-work-date
  "日本時間の今日の YYYY-MM-DD。"
  []
  (.format (today-tokyo) work-date-fmt))

(defn yesterday-work-date
  "日本時間の昨日の YYYY-MM-DD。"
  []
  (.format (.minusDays (today-tokyo) 1) work-date-fmt))

(defn format-work-date [^LocalDate d]
  (.format d work-date-fmt))

(defn days-inclusive
  "from-day から to-day までの暦日（YYYY-MM-DD）を昇順で返す。不正や順序逆は空。"
  [from-day to-day]
  (let [a (parse-work-date from-day)
        b (parse-work-date to-day)]
    (if (or (nil? a) (nil? b) (.isAfter a b))
      []
      (loop [cur a
             acc []]
        (if (.isAfter cur b)
          acc
          (recur (.plusDays cur 1) (conj acc (format-work-date cur))))))))

(defn order-default-start []
  "08:00")

(defn order-default-end []
  "17:00")

(defn normalize-order-times
  "指示の日付・開始・終了を検証する。終了は同じ日で開始より後。"
  [work-date start-time end-time]
  (let [wd (str (or work-date ""))
        st (str (or start-time ""))
        et (str (or end-time ""))]
    (cond
      (not (and (work-date-ok? wd) (clock-time-ok? st) (clock-time-ok? et)))
      {:ok false :code "time_invalid"}

      (not (.isBefore (parse-clock-time st) (parse-clock-time et)))
      {:ok false :code "time_order"}

      :else
      {:ok true :work-date wd :start-time st :end-time et})))
