(ns isas.gantt
  (:require [clojure.string :as str]
            [isas.browser :as browser]
            [isas.ui :as ui]))

(defonce installed? (atom false))
(defonce validation-wired? (atom false))

(defn- pad2 [n]
  (let [s (str n)]
    (if (= 1 (count s)) (str "0" s) s)))

(defn- tokyo-ymd []
  (let [parts (.formatToParts
               (js/Intl.DateTimeFormat. "en-US"
                                        #js {:timeZone "Asia/Tokyo"
                                             :year "numeric"
                                             :month "2-digit"
                                             :day "2-digit"})
               (js/Date.))
        get (fn [t]
              (some (fn [p]
                      (when (= t (.-type p)) (.-value p)))
                    (array-seq parts)))]
    [(js/parseInt (get "year") 10)
     (js/parseInt (get "month") 10)
     (js/parseInt (get "day") 10)]))

(defn- ymd-minute [y m d h mi]
  (str y "-" (pad2 m) "-" (pad2 d) "T" (pad2 h) ":" (pad2 mi)))

(defn- local-ms [s]
  (when (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" (str s))
    (.getTime (js/Date. (str s ":00+09:00")))))

(defn- minute-label [ms]
  (let [d (js/Date. ms)
        parts (.formatToParts
               (js/Intl.DateTimeFormat. "en-US"
                                        #js {:timeZone "Asia/Tokyo"
                                             :month "2-digit"
                                             :day "2-digit"
                                             :hour "2-digit"
                                             :minute "2-digit"
                                             :hour12 false})
               d)
        get (fn [t]
              (some (fn [p]
                      (when (= t (.-type p)) (.-value p)))
                    (array-seq parts)))]
    (str (get "month") "/" (get "day") " " (get "hour") ":" (get "minute"))))

(defn- tick-step-ms [range-key span]
  (case (str range-key)
    "week" (* 24 60 60 1000)
    "weeks8" (* 7 24 60 60 1000)
    "month" (* 2 24 60 60 1000)
    "months3" (* 7 24 60 60 1000)
    "months6" (* 14 24 60 60 1000)
    (cond
      (> span (* 48 60 60 1000)) (* 12 60 60 1000)
      (> span (* 12 60 60 1000)) (* 6 60 60 1000)
      :else (* 3 60 60 1000))))

(defn- time-vertical? [^js axis]
  (= "time-v" (str (.getAttribute axis "data-orient"))))

(defn- fill-new-defaults! []
  (let [[y m d] (tokyo-ymd)
        default-title "新しい作業"
        default-start (ymd-minute y m d 8 0)
        default-end (ymd-minute y m d 17 0)
        fill! (fn [id title? start? end?]
                (when-let [el (.getElementById js/document id)]
                  (when (str/blank? (.-value el))
                    (set! (.-value el)
                          (cond title? default-title
                                start? default-start
                                end? default-end
                                :else "")))))]
    (fill! "gantt-new-title" true false false)
    (fill! "gantt-new-start" false true false)
    (fill! "gantt-new-end" false false true)
    (fill! "works-new-title" true false false)
    (fill! "works-new-start" false true false)
    (fill! "works-new-end" false false true)))

(defn- append-axis-mark! [^js host t t0 span time-v? class-name label?]
  (let [pct (* 100 (/ (- t t0) span))
        el (.createElement js/document (if label? "span" "div"))
        st (.-style el)]
    (set! (.-className el) class-name)
    (when label?
      (set! (.-textContent el) (minute-label t)))
    (if time-v?
      (do (set! (.-top st) (str pct "%"))
          (when label? (set! (.-left st) "0")))
      (set! (.-left st) (str pct "%")))
    (.appendChild host el)))

(defn- render-ticks! []
  (when-let [^js axis (.getElementById js/document "gantt-axis")]
    (when-let [^js ticks (.getElementById js/document "gantt-ticks")]
      (let [t0 (local-ms (.getAttribute axis "data-start"))
            t1 (local-ms (.getAttribute axis "data-end"))
            span (when (and t0 t1 (> t1 t0)) (- t1 t0))
            range-key (.getAttribute axis "data-range")
            time-v? (time-vertical? axis)]
        (set! (.-innerHTML ticks) "")
        (when span
          (let [step (tick-step-ms range-key span)]
            (loop [t t0]
              (when (<= t t1)
                (append-axis-mark! ticks t t0 span time-v? "gantt-tick" true)
                (recur (+ t step))))))))))

(defn- render-grid! []
  (when-let [^js axis (.getElementById js/document "gantt-axis")]
    (when-let [^js grid (.getElementById js/document "gantt-grid")]
      (let [t0 (local-ms (.getAttribute axis "data-start"))
            t1 (local-ms (.getAttribute axis "data-end"))
            span (when (and t0 t1 (> t1 t0)) (- t1 t0))
            range-key (.getAttribute axis "data-range")
            time-v? (time-vertical? axis)]
        (set! (.-innerHTML grid) "")
        (when span
          (let [step (tick-step-ms range-key span)
                line-class (if time-v? "gantt-grid-line time-v" "gantt-grid-line time-h")]
            (loop [t t0]
              (when (<= t t1)
                (append-axis-mark! grid t t0 span time-v? line-class false)
                (recur (+ t step))))))))))
(defn- render-bars! []
  (when-let [^js axis (.getElementById js/document "gantt-axis")]
    (let [t0 (local-ms (.getAttribute axis "data-start"))
          t1 (local-ms (.getAttribute axis "data-end"))
          span (when (and t0 t1 (> t1 t0)) (- t1 t0))
          time-v? (time-vertical? axis)
          rows (.querySelectorAll axis ".gantt-row")]
      (when span
        (.forEach rows
                  (fn [^js row]
                    (let [a (local-ms (.getAttribute row "data-start"))
                          b (local-ms (.getAttribute row "data-end"))
                          ^js bar (.querySelector row ".gantt-bar")]
                      (when (and bar a b)
                        (let [start-pct (max 0 (* 100 (/ (- a t0) span)))
                              end-pct (min 100 (* 100 (/ (- b t0) span)))
                              size (max 0.5 (- end-pct start-pct))
                              st (.-style bar)]
                          (set! (.-position st) "absolute")
                          (if time-v?
                            (do (set! (.-top st) (str start-pct "%"))
                                (set! (.-height st) (str size "%"))
                                (set! (.-left st) "0.35rem")
                                (set! (.-width st) "0.7rem")
                                (set! (.-right st) "auto"))
                            (do (set! (.-left st) (str start-pct "%"))
                                (set! (.-width st) (str size "%"))
                                (set! (.-top st) "0.35rem")
                                (set! (.-height st) "0.7rem"))))))))))))

(defn- render-circle! [state]
  (when-let [^js el (.getElementById js/document "gantt-circle")]
    (let [progress (:gantt-progress state)
          pct (when (and progress (:applicable progress)) (:percent progress))
          unit (or (when (fn? (.-getAttribute el))
                     (.getAttribute el "data-percent-unit"))
                   (get (ui/messages-for (ui/ui-lang state)) :gantt-percent-unit))]
      (if (nil? pct)
        (set! (.-innerHTML el) "")
        (let [r 42
              c (* 2 js/Math.PI r)
              offset (- c (* c (/ (js/Number pct) 100.0)))]
          (set! (.-innerHTML el)
                (str "<svg viewBox=\"0 0 100 100\" width=\"120\" height=\"120\" aria-hidden=\"true\">"
                     "<circle cx=\"50\" cy=\"50\" r=\"" r "\" fill=\"none\" stroke=\"#ddd\" stroke-width=\"10\"/>"
                     "<circle cx=\"50\" cy=\"50\" r=\"" r "\" fill=\"none\" stroke=\"#2e7d32\" stroke-width=\"10\""
                     " stroke-dasharray=\"" c "\" stroke-dashoffset=\"" offset "\""
                     " transform=\"rotate(-90 50 50)\"/>"
                     "<text x=\"50\" y=\"54\" text-anchor=\"middle\" font-size=\"18\">"
                     (str pct) (ui/esc unit) "</text></svg>")))))))

(defn- form-value [^js form name]
  (when-let [^js el (.querySelector form (str "[name='" name "']"))]
    (str/trim (str (.-value el)))))

(defn- checked-field-ids [^js form]
  (->> (.querySelectorAll form "input[name='field_ids']:checked")
       array-seq
       (map #(.-value %))
       (remove str/blank?)
       vec))

(defn times-ok? [start end]
  (let [a (local-ms start)
        b (local-ms end)]
    (boolean (and a b (< a b)))))

(defn save-form-ok? [^js form]
  (let [title (form-value form "title")
        start (form-value form "start_at")
        end (form-value form "end_at")
        wn (form-value form "work_name")
        fids (checked-field-ids form)]
    (and (not (str/blank? title))
         (times-ok? start end)
         (or (empty? fids) (not (str/blank? wn))))))

(defn add-form-ok? [^js form]
  (let [start (form-value form "start_at")
        end (form-value form "end_at")]
    (times-ok? start end)))

(defn- set-disabled! [^js btn disabled?]
  (when btn
    (set! (.-disabled btn) (boolean disabled?))))

(defn- refresh-save-btn! []
  (when-let [^js form (.getElementById js/document "gantt-save-form")]
    (set-disabled! (.getElementById js/document "gantt-save-btn")
                   (not (save-form-ok? form)))))

(defn- refresh-add-btn! []
  (when-let [^js form (.getElementById js/document "gantt-add-form")]
    (set-disabled! (.getElementById js/document "gantt-add-btn")
                   (not (add-form-ok? form)))))

(defn- wire-validation! []
  (refresh-save-btn!)
  (refresh-add-btn!)
  (when-not @validation-wired?
    (reset! validation-wired? true)
    (let [on-edit (fn [ev]
                    (when-let [^js t (.-target ev)]
                      (when-let [^js form (.-form t)]
                        (case (.-id form)
                          "gantt-save-form" (refresh-save-btn!)
                          "gantt-add-form" (refresh-add-btn!)
                          nil))))]
      (.addEventListener js/document "input" on-edit true)
      (.addEventListener js/document "change" on-edit true))))

(defn sync! [state _dispatch]
  (when (= :gantt (:page state))
    (fill-new-defaults!)
    (render-ticks!)
    (render-grid!)
    (render-bars!)
    (render-circle! state)
    (wire-validation!)))
(defn install! []
  (browser/register-gantt-sync! sync!)
  (when-not @installed?
    (reset! installed? true)
    (sync! @browser/app-state browser/dispatch!)))
