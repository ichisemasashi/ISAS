(ns isas.gantt
  (:require [clojure.string :as str]
            [isas.browser :as browser]
            [isas.ui :as ui]))

(defonce installed? (atom false))

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

(defn- fill-new-defaults! []
  (let [[y m d] (tokyo-ymd)
        title (.getElementById js/document "gantt-new-title")
        start (.getElementById js/document "gantt-new-start")
        end (.getElementById js/document "gantt-new-end")]
    (when (and title (str/blank? (.-value title)))
      (set! (.-value title) "新しい予定"))
    (when (and start (str/blank? (.-value start)))
      (set! (.-value start) (ymd-minute y m d 8 0)))
    (when (and end (str/blank? (.-value end)))
      (set! (.-value end) (ymd-minute y m d 17 0)))))

(defn- render-bars! []
  (when-let [^js axis (.getElementById js/document "gantt-axis")]
    (let [t0 (local-ms (.getAttribute axis "data-start"))
          t1 (local-ms (.getAttribute axis "data-end"))
          span (when (and t0 t1 (> t1 t0)) (- t1 t0))
          rows (.querySelectorAll axis ".gantt-row")]
      (when span
        (.forEach rows
                  (fn [^js row]
                    (let [a (local-ms (.getAttribute row "data-start"))
                          b (local-ms (.getAttribute row "data-end"))
                          ^js bar (.querySelector row ".gantt-bar")]
                      (when (and bar a b)
                        (let [left (max 0 (* 100 (/ (- a t0) span)))
                              right (min 100 (* 100 (/ (- b t0) span)))
                              width (max 0.5 (- right left))]
                          (set! (.-position (.-style bar)) "absolute")
                          (set! (.-left (.-style bar)) (str left "%"))
                          (set! (.-width (.-style bar)) (str width "%"))
                          (set! (.-top (.-style bar)) "0.35rem")
                          (set! (.-height (.-style bar)) "0.7rem"))))))))))

(defn- render-circle! [state]
  (when-let [^js el (.getElementById js/document "gantt-circle")]
    (let [progress (:gantt-progress state)
          pct (when (and progress (:applicable progress)) (:percent progress))
          unit (:gantt-percent-unit ui/messages)]
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

(defn sync! [state _dispatch]
  (when (= :gantt (:page state))
    (fill-new-defaults!)
    (render-bars!)
    (render-circle! state)))

(defn install! []
  (browser/register-gantt-sync! sync!)
  (when-not @installed?
    (reset! installed? true)
    (sync! @browser/app-state browser/dispatch!)))
