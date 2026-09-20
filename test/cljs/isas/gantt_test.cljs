(ns isas.gantt-test
  (:require [cljs.test :refer [deftest is]]
            [isas.browser :as b]
            [isas.gantt :as g]
            [isas.ui :as ui]))

(deftest times-and-save-ok-test
  (is (true? (g/times-ok? "2026-09-18T08:00" "2026-09-18T17:00")))
  (is (false? (g/times-ok? "2026-09-18T08:00" "2026-09-18T08:00")))
  (is (false? (g/times-ok? "2026-09-18T17:00" "2026-09-18T08:00")))
  (is (false? (g/times-ok? "bad" "2026-09-18T08:00")))
  (let [form (js-obj "querySelector"
                     (fn [sel]
                       (cond
                         (= sel "[name='title']") #js {:value "題"}
                         (= sel "[name='start_at']") #js {:value "2026-09-18T08:00"}
                         (= sel "[name='end_at']") #js {:value "2026-09-18T17:00"}
                         (= sel "[name='work_name']") #js {:value ""}
                         :else nil))
                     "querySelectorAll"
                     (fn [_]
                       (let [arr #js []]
                         (set! (.-forEach arr) (fn [f] nil))
                         arr)))]
    (is (true? (g/save-form-ok? form)))
    (is (true? (g/add-form-ok? form))))
  (let [checked #js [#js {:value "1"}]
        form (js-obj "querySelector"
                     (fn [sel]
                       (cond
                         (= sel "[name='title']") #js {:value "題"}
                         (= sel "[name='start_at']") #js {:value "2026-09-18T08:00"}
                         (= sel "[name='end_at']") #js {:value "2026-09-18T17:00"}
                         (= sel "[name='work_name']") #js {:value ""}
                         :else nil))
                     "querySelectorAll"
                     (fn [_]
                       (set! (.-forEach checked) (fn [f] (.call f nil (aget checked 0))))
                       checked))]
    (is (false? (g/save-form-ok? form))))
  (let [form (js-obj "querySelector"
                     (fn [sel]
                       (cond
                         (= sel "[name='start_at']") #js {:value "2026-09-18T08:00"}
                         (= sel "[name='end_at']") #js {:value "2026-09-18T07:00"}
                         :else nil))
                     "querySelectorAll" (fn [_] #js []))]
    (set! (.-forEach (.querySelectorAll form "x")) (fn [_]))
    (is (false? (g/add-form-ok? form)))))

(deftest gantt-sync-test
  (let [title #js {:value ""}
        start #js {:value ""}
        end #js {:value ""}
        circle #js {:innerHTML "x"}
        ticks #js {:innerHTML "old"}
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
                                    "data-range" "day"
                                    "data-orient" "time-h"
                                    nil))
                  :querySelectorAll (fn [_] rows)}
        save-btn #js {:disabled false}
        add-btn #js {:disabled false}
        save-form #js {:id "gantt-save-form"
                       :querySelector (fn [sel]
                                        (cond
                                          (= sel "[name='title']") #js {:value "題"}
                                          (= sel "[name='start_at']") #js {:value "2026-09-18T08:00"}
                                          (= sel "[name='end_at']") #js {:value "2026-09-18T17:00"}
                                          (= sel "[name='work_name']") #js {:value "田植え"}
                                          :else nil))
                       :querySelectorAll (fn [_]
                                           (let [arr #js []]
                                             (set! (.-forEach arr) (fn [_]))
                                             arr))}
        add-form #js {:id "gantt-add-form"
                      :querySelector (fn [sel]
                                       (cond
                                         (= sel "[name='start_at']") #js {:value "2026-09-18T08:00"}
                                         (= sel "[name='end_at']") #js {:value "2026-09-18T17:00"}
                                         :else nil))
                      :querySelectorAll (fn [_]
                                          (let [arr #js []]
                                            (set! (.-forEach arr) (fn [_]))
                                            arr))}
        listeners (atom [])]
    (set! (.-forEach rows) (fn [f] (.call f nil row)))
    (set! (.-appendChild ticks) (fn [el]
                                  (set! (.-innerHTML ticks)
                                        (str (.-innerHTML ticks) (.-textContent el)))))
    (set! js/document
          #js {:getElementById
               (fn [id]
                 (case id
                   "gantt-new-title" title
                   "gantt-new-start" start
                   "gantt-new-end" end
                   "gantt-axis" axis
                   "gantt-ticks" ticks
                   "gantt-circle" circle
                   "gantt-save-form" save-form
                   "gantt-add-form" add-form
                   "gantt-save-btn" save-btn
                   "gantt-add-btn" add-btn
                   nil))
               :createElement (fn [_]
                                (let [el #js {:style #js {}}]
                                  (set! (.-className el) "")
                                  (set! (.-textContent el) "")
                                  el))
               :addEventListener (fn [ev f _] (swap! listeners conj [ev f]))})
    (reset! g/installed? false)
    (reset! g/validation-wired? false)
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
    (is (re-find #"/" (.-innerHTML ticks)))
    (is (false? (.-disabled save-btn)))
    (is (false? (.-disabled add-btn)))
    (is (pos? (count @listeners)))
    (set! (.-getAttribute axis)
          (fn [a]
            (case a
              "data-start" "2026-09-18T00:00"
              "data-end" "2026-09-21T00:00"
              "data-range" "day"
              "data-orient" "time-v"
              nil)))
    (set! (.-innerHTML ticks) "")
    (g/sync! @b/app-state b/dispatch!)
    (is (string? (.-top bar-style)))
    (is (re-find #"/" (.-innerHTML ticks)))
    (reset! b/app-state (assoc @b/app-state :gantt-progress nil))
    (g/sync! @b/app-state b/dispatch!)
    (is (= "" (.-innerHTML circle)))
    (reset! b/app-state (assoc @b/app-state :page :home))
    (g/sync! @b/app-state b/dispatch!)
    (set! js/document #js {:getElementById (fn [_] nil)
                           :addEventListener (fn [_ _ _])})
    (g/sync! (assoc (ui/init-state) :page :gantt) b/dispatch!)))
