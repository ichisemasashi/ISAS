(ns isas.gantt-test
  (:require [cljs.test :refer [deftest is]]
            [isas.browser :as b]
            [isas.gantt :as g]
            [isas.ui :as ui]))

(deftest gantt-sync-test
  (let [title #js {:value ""}
        start #js {:value ""}
        end #js {:value ""}
        circle #js {:innerHTML "x"}
        bar-style #js {}
        bar #js {:style bar-style}
        row #js {:getAttribute (fn [a]
                                 (case a
                                   "data-start" "2026-09-18T08:00"
                                   "data-end" "2026-09-18T17:00"
                                   nil))
                 :querySelector (fn [_] bar)}
        rows #js [row]
        axis #js {:getAttribute (fn [a]
                                  (case a
                                    "data-start" "2026-09-18T00:00"
                                    "data-end" "2026-09-21T00:00"
                                    nil))
                  :querySelectorAll (fn [_] rows)}]
    (set! (.-forEach rows) (fn [f] (.call f nil row)))
    (set! js/document
          #js {:getElementById
               (fn [id]
                 (case id
                   "gantt-new-title" title
                   "gantt-new-start" start
                   "gantt-new-end" end
                   "gantt-axis" axis
                   "gantt-circle" circle
                   nil))})
    (reset! g/installed? false)
    (reset! b/app-state (assoc (ui/init-state)
                               :page :gantt
                               :gantt-progress {:ok true :applicable true :percent 40}))
    (g/install!)
    (g/install!)
    (is (true? @g/installed?))
    (is (= "新しい予定" (.-value title)))
    (is (re-find #"T08:00$" (.-value start)))
    (is (re-find #"T17:00$" (.-value end)))
    (is (re-find #"<svg" (.-innerHTML circle)))
    (is (re-find #"40" (.-innerHTML circle)))
    (is (string? (.-left bar-style)))
    (reset! b/app-state (assoc @b/app-state :gantt-progress nil))
    (g/sync! @b/app-state b/dispatch!)
    (is (= "" (.-innerHTML circle)))
    (reset! b/app-state (assoc @b/app-state :page :home))
    (g/sync! @b/app-state b/dispatch!)
    (set! js/document #js {:getElementById (fn [_] nil)})
    (g/sync! (assoc (ui/init-state) :page :gantt) b/dispatch!)))
