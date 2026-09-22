(ns isas.map-test
  (:require [cljs.test :refer [deftest is]]
            [goog.object :as gobj]
            [isas.browser :as b]
            [isas.map :as m]
            [isas.ui :as ui]))

(defn- last-ol [ev]
  (gobj/get (.-__olLast js/globalThis) ev))

(defn- call-ol [obj ev arg]
  (when-let [f (and obj (gobj/get (.-_on obj) ev))]
    (f arg)))

(defn- make-input []
  (js-obj "value" ""))

(defn- make-form [fields]
  (let [m (into {} fields)]
    (js-obj "querySelector"
            (fn [sel]
              (let [n (second (re-find #"name='([^']+)'" (str sel)))]
                (get m n))))))

(defn- setup-dom! []
  (let [ol-el (js-obj "id" "ol-map"
                      "getAttribute" (fn [_] nil))
        extent (js-obj "textContent" "")
        app-el (js-obj "innerHTML" "")
        west (make-input)
        south (make-input)
        east (make-input)
        north (make-input)
        iwest (make-input)
        isouth (make-input)
        ieast (make-input)
        inorth (make-input)
        geojson (make-input)
        split-id (make-input)
        split-polys (make-input)
        split-line (make-input)
        hint (js-obj "textContent" "")
        selection (js-obj "textContent" "")
        upd-id (make-input)
        upd-name (make-input)
        upd-geo (make-input)
        keep (make-input)
        ids (make-input)
        wn (make-input)
        p-field (make-input)
        p-wn (make-input)
        p-geo (make-input)
        c-id (make-input)
        c-wn (make-input)
        d-id (make-input)
        da-id (make-input)
        da-wn (make-input)
        save (make-form {"west" west "south" south "east" east "north" north})
        img (make-form {"west" iwest "south" isouth "east" ieast "north" inorth})
        create (make-form {"geojson" geojson})
        split (make-form {"id" split-id "polygons" split-polys "line" split-line})
        update (make-form {"id" upd-id "name" upd-name "geojson" upd-geo})
        merge (make-form {"keep_id" keep "ids" ids})
        select-wn (make-form {"work_name" wn})
        confirm (make-form {"field_id" p-field "work_name" p-wn "geojson" p-geo})
        complete (make-form {"id" c-id "work_name" c-wn})
        del-paint (make-form {"id" d-id})
        del-all (make-form {"id" da-id "work_name" da-wn})
        confirm-btn (js-obj "disabled" true "hidden" true)
        discard-btn (js-obj "disabled" true "hidden" true)
        complete-btn (js-obj "disabled" true "hidden" true)
        delete-all-btn (js-obj "disabled" true "hidden" true)
        delete-btn (js-obj "disabled" true "hidden" true)
        field-note (js-obj "hidden" false)
        stroke-note (js-obj "hidden" false)
        draft-note (js-obj "hidden" false)
        forms {"save-place" save
               "preview-place" save
               "save-image-extent" img
               "create-field" create
               "split-field" split
               "update-field" update
               "merge-fields" merge
               "select-work-name" select-wn
               "confirm-paint" confirm
               "complete-field" complete
               "delete-paint" del-paint
               "delete-field-paints" del-all}
        clicks (atom [])]
    (set! js/document
          (js-obj "getElementById"
                  (fn [id]
                    (case id
                      "ol-map" ol-el
                      "place-extent" extent
                      "map-hint" hint
                      "map-selection" selection
                      "app" app-el
                      "paint-confirm-btn" confirm-btn
                      "paint-discard-btn" discard-btn
                      "paint-complete-btn" complete-btn
                      "paint-delete-all-btn" delete-all-btn
                      "paint-delete-btn" delete-btn
                      "paint-field-note" field-note
                      "paint-stroke-note" stroke-note
                      "paint-draft-note" draft-note
                      nil))
                  "querySelector"
                  (fn [sel]
                    (let [act (second (re-find #"data-act='([^']+)'" (str sel)))]
                      (get forms act)))
                  "addEventListener"
                  (fn [ev f]
                    (when (or (= ev "click") (= ev "change"))
                      (swap! clicks conj f)))))
    (set! js/window (js-obj "innerWidth" 1200 "addEventListener" (fn [_ _])))
    {:west west :extent extent :clicks clicks :ol-el ol-el
     :confirm-btn confirm-btn :discard-btn discard-btn
     :complete-btn complete-btn :delete-all-btn delete-all-btn
     :delete-btn delete-btn :field-note field-note :stroke-note stroke-note
     :d-id d-id}))

(deftest map-install-and-tools-test
  (let [{:keys [west extent clicks confirm-btn discard-btn complete-btn
                delete-all-btn delete-btn field-note stroke-note d-id]} (setup-dom!)]
    (reset! m/current nil)
    (reset! m/installed? false)
    (reset! b/app-state (assoc (ui/init-state)
                               :place {:west 139 :south 35 :east 141 :north 37}
                               :basemaps [{:kind "aerial" :ready true}]
                               :fields [{:id 1 :name "北"
                                         :geojson {:type "Polygon"
                                                    :coordinates [[[140 36] [140.1 36] [140.1 36.1] [140 36.1] [140 36]]]}}
                                        {:id 2 :name "無"}]
                               :paint-data {:work_name "田植え"
                                            :fields [{:id 1 :name "北" :status "partial"
                                                      :paints [{:id 9
                                                                :geojson {:type "Polygon"
                                                                          :coordinates [[[140 36] [140.05 36] [140.05 36.05] [140 36.05] [140 36]]]}}]}]}))
    (is (some? (m/style-for nil "none" false)))
    (is (some? (m/style-for nil "none" true)))
    (is (some? (m/style-for nil "partial" true)))
    (is (some? (m/style-for nil "done" true)))
    (is (some? (m/style-for nil "dim" true)))
    (is (some? (m/style-for nil "x" true)))
    (is (some? (m/style-for "paint" "none" true)))
    (is (nil? (m/drafts-geojson [])))
    (is (= "Polygon" (:type (m/drafts-geojson [{:type "Polygon" :coordinates [[[1 2]]]}]))))
    (is (= "MultiPolygon" (:type (m/drafts-geojson [{:type "Polygon" :coordinates [[[1 2]]]}
                                                    {:type "Polygon" :coordinates [[[3 4]]]}]))))
    (is (= "MultiPolygon" (:type (m/drafts-geojson [{:type "MultiPolygon" :coordinates [[[[1 2]]]]}
                                                    {:type "Polygon" :coordinates [[[3 4]]]}]))))
    (m/install!)
    (m/install!)
    (is (true? @m/installed?))
    (b/apply-fx! [:html "<div id=\"ol-map\"></div>"])
    (is (some? (:map @m/current)))
    (when-let [sf (:style-fn @m/current)]
      (let [feat (js-obj "get" (fn [k]
                                 (cond
                                   (= k "kind") "paint"
                                   (= k "status") "partial"
                                   (= k "painted") true
                                   :else nil)))]
        (sf feat nil)))
    (is (re-find #"東経" (.-textContent extent)))
    (is (string? (.-value west)))
    (let [move (last-ol "moveend")]
      (call-ol move "moveend" #js {}))
    (let [click (last-ol "click")
          sel #(.getElementById js/document "map-selection")]
      (call-ol click "click" #js {:pixel #js [1 1]})
      (when click
        ;; P3-2.1-05a / P3-5-07a: paint click shows field name, never paints.id
        (set! (.-props click) (js-obj "paint-id" 9 "id" 1 "name" "北"))
        (call-ol click "click" #js {:pixel #js [1 1]})
        (let [t (.-textContent (sel))]
          (is (= "選んでいる塗り: 北" t))
          (is (nil? (re-find #"選んでいる塗り: 9" t)))
          (is (nil? (re-find #"選んでいる塗り: 1" t))))
        ;; P3-2.1-05b / P3-5-07b: field click shows field name, never fields.id
        (set! (.-props click) (js-obj "id" 1 "name" "北"))
        (call-ol click "click" #js {:pixel #js [1 1]})
        (let [t (.-textContent (sel))]
          (is (= "選んでいる圃場: 北" t))
          (is (nil? (re-find #"選んでいる圃場: 1" t))))
        ;; Regression: missing name must not fall back to paints.id
        (set! (.-props click) (js-obj "paint-id" 9 "id" 1))
        (call-ol click "click" #js {:pixel #js [1 1]})
        (let [t (.-textContent (sel))]
          (is (nil? (re-find #"選んでいる塗り: 9" t)))
          (is (re-find #"選んでいる塗り:" t)))))
    (when (seq @clicks)
      (let [fire (fn [op kind]
                   (let [handlers @clicks
                         click-h (first handlers)
                         change-h (second handlers)]
                     (if (= op "basemap")
                       (when change-h
                         (change-h (clj->js {:target (js-obj "tagName" "SELECT"
                                                             "value" kind
                                                             "getAttribute"
                                                             (fn [a]
                                                               (when (= a "data-map") "basemap")))})))
                       (when click-h
                         (click-h (clj->js {:target {:closest (fn [_]
                                                                (js-obj "tagName" "BUTTON"
                                                                        "getAttribute"
                                                                        (fn [a]
                                                                          (cond
                                                                            (= a "data-map") op
                                                                            (= a "data-kind") kind
                                                                            (= a "data-dir") (if (= op "image-shift") "east" nil)
                                                                            (= a "data-factor") (if (= op "image-scale") "1.06" nil)
                                                                            (= a "data-hint") (str "hint-" op)
                                                                            :else nil))))}}))))))
            enter (fn [mode]
                    (swap! b/app-state assoc :map-mode mode :page :map
                           :place {:west 129 :south 26 :east 146 :north 46})
                    (b/apply-fx! [:html "<div id=\"ol-map\"></div>"]))]
        (enter "draw")
        (let [draw (last-ol "drawend")]
          (call-ol draw "drawend" #js {:feature draw}))
        (enter "split")
        ;; 分割モード直後は圃場クリックで選べるよう Draw を付けない
        (is (= :split (:tool @m/current)))
        (is (nil? (:draw @m/current)))
        (is (false? (:drawing? @m/current)))
        (let [click (last-ol "click")]
          (when click
            (set! (.-props click) (js-obj "id" 1 "name" "北"))
            (call-ol click "click" #js {:pixel #js [1 1]})
            (is (= "1" (str (:active-field @m/current))))
            (is (true? (:drawing? @m/current)))
            (is (some? (:draw @m/current)))))
        (let [draw (last-ol "drawend")]
          (call-ol draw "drawend" #js {:feature draw})
          (is (false? (:drawing? @m/current))))
        (enter "edit")
        (let [mod (last-ol "modifyend")]
          (when mod
            (set! (.-features mod) mod)
            (call-ol mod "modifyend" #js {:features mod})))
        (enter "paint")
        (is (true? (.-hidden confirm-btn)))
        (is (true? (.-hidden discard-btn)))
        (fire "brush" nil)
        (let [draw (last-ol "drawend")]
          (call-ol draw "drawend" #js {:feature draw})
          ;; P3-S-02: one brush stroke clears Draw so pan works again
          (is (nil? (:draw @m/current)))
          (is (false? (:drawing? @m/current)))
          (is (false? (.-hidden confirm-btn)))
          (is (false? (.-hidden discard-btn))))
        (fire "discard" nil)
        (is (nil? (seq (:drafts @m/current))))
        (is (true? (.-hidden confirm-btn)))
        (let [click (last-ol "click")]
          (when click
            (set! (.-props click) (js-obj "id" 1 "name" "北"))
            (call-ol click "click" #js {:pixel #js [1 1]})
            (is (false? (.-hidden complete-btn)))
            (is (false? (.-hidden delete-all-btn)))
            (is (true? (.-hidden field-note)))
            (is (true? (.-hidden delete-btn)))
            (set! (.-value d-id) "9")
            (m/refresh-paint-actions!)
            (is (= "9" (str (.-value d-id))))
            (is (false? (.-hidden delete-btn)))
            (is (true? (.-hidden stroke-note)))))
        (enter "merge")
        (fire "image-shift" nil)
        (fire "image-scale" nil)
        (fire "image-reset" nil)
        (fire "basemap" "standard")
        (fire "nope" nil)
        (when-let [change-h (second @clicks)]
          (change-h (clj->js {:target (js-obj "tagName" "SELECT"
                                              "value" ""
                                              "getAttribute" (fn [a] (when (= a "data-map") "basemap")))}))
          (change-h (clj->js {:target (js-obj "tagName" "DIV"
                                              "value" "x"
                                              "getAttribute" (fn [_] nil))})))
        ((first @clicks) (clj->js {:target {:closest (fn [_] nil)}}))
        ((first @clicks) (clj->js {:target {:closest (fn [_]
                                                       (js-obj "tagName" "SELECT"
                                                               "getAttribute" (fn [a]
                                                                                (when (= a "data-map") "basemap"))))}}))))
    (b/apply-fx! [:html "<div id=\"ol-map\"></div>"])
    (set! js/document (js-obj "getElementById" (fn [_] nil)
                              "querySelector" (fn [_] nil)
                              "addEventListener" (fn [_ _])))
    (b/apply-fx! [:html "<p>x</p>"])
    (is (nil? (:map @m/current)))
    (reset! m/current nil)
    (let [{:keys [extent]} (setup-dom!)]
      (reset! b/app-state (ui/init-state))
      (b/register-map-sync! @b/map-sync-fn)
      (m/install!)
      (b/apply-fx! [:html "<div id=\"ol-map\"></div>"])
      (is (re-find #"東経 129" (.-textContent extent)))
      (let [mod (last-ol "modifyend")]
        (when mod
          (set! (.-_arr mod) #js [])
          (call-ol mod "modifyend" #js {:features mod})))))
  (let [ol-el (js-obj "id" "ol-map"
                      "getAttribute" (fn [a]
                                       (case a
                                         "data-gantt-mode" "1"
                                         "data-target-ids" "1"
                                         nil)))
        app-el (js-obj "innerHTML" "")]
    (set! js/document (js-obj "getElementById" (fn [id]
                                                 (case id
                                                   "ol-map" ol-el
                                                   "app" app-el
                                                   "map-extent" (js-obj "textContent" "")
                                                   nil))
                              "querySelector" (fn [_] nil)
                              "addEventListener" (fn [_ _])))
    (set! js/window (js-obj "innerWidth" 1200 "addEventListener" (fn [_ _])))
    (reset! m/current nil)
    (reset! m/installed? false)
    (reset! b/app-state (assoc (ui/init-state)
                               :page :gantt
                               :place {:west 139 :south 35 :east 141 :north 37}
                               :basemaps [{:kind "aerial" :ready true}]
                               :fields [{:id 1 :name "北"
                                         :geojson {:type "Polygon"
                                                   :coordinates [[[140 36] [140.1 36] [140.1 36.1] [140 36.1] [140 36]]]}}
                                        {:id 2 :name "南"
                                         :geojson {:type "Polygon"
                                                   :coordinates [[[140.2 36] [140.3 36] [140.3 36.1] [140.2 36.1] [140.2 36]]]}}]
                               :gantt-progress {:ok true :applicable true :percent 10
                                                :fields [{:id 1 :status "partial"}]}
                               :paint-data {:work_name "田植え"
                                            :fields [{:id 1 :status "done"}]}))
    (m/install!)
    (b/apply-fx! [:html "<div id=\"ol-map\" data-gantt-mode=\"1\"></div>"])
    (is (some? (:map @m/current)))
    (is (nil? (:draw @m/current))))
  ;; 指示地図: order-map の status で色付け
  (let [ol-el (js-obj "id" "ol-map"
                      "getAttribute" (fn [a]
                                       (case a
                                         "data-order-mode" "1"
                                         "data-target-ids" "1"
                                         nil)))
        app-el (js-obj "innerHTML" "")]
    (set! js/document (js-obj "getElementById" (fn [id]
                                                 (case id
                                                   "ol-map" ol-el
                                                   "app" app-el
                                                   "map-extent" (js-obj "textContent" "")
                                                   nil))
                              "querySelector" (fn [_] nil)
                              "addEventListener" (fn [_ _])))
    (set! js/window (js-obj "innerWidth" 1200 "addEventListener" (fn [_ _])))
    (reset! m/current nil)
    (reset! m/installed? false)
    (reset! b/app-state (assoc (ui/init-state)
                               :page :order
                               :place {:west 139 :south 35 :east 141 :north 37}
                               :basemaps [{:kind "aerial" :ready true}]
                               :order-map {:work_name "田植え"
                                           :fields [{:id 1 :name "北" :status "partial"
                                                     :geojson {:type "Polygon"
                                                               :coordinates [[[140 36] [140.1 36]
                                                                              [140.1 36.1] [140 36.1] [140 36]]]}}]}))
    (m/install!)
    (b/apply-fx! [:html "<div id=\"ol-map\" data-order-mode=\"1\" data-target-ids=\"1\"></div>"])
    (is (some? (:map @m/current)))
    (is (false? (:drawing? @m/current)))
    (when-let [sf (:style-fn @m/current)]
      (let [feat (js-obj "get" (fn [k]
                                 (cond
                                   (= k "kind") nil
                                   (= k "status") "partial"
                                   (= k "painted") true
                                   :else nil)))]
        (is (some? (sf feat nil)))))
    ;; 他人地図（形のみ → 色付き）
    (let [ol-el (js-obj "id" "ol-map"
                        "getAttribute" (fn [a]
                                         (case a
                                           "data-others-mode" "1"
                                           "data-target-ids" "1"
                                           nil)))
          app-el (js-obj "innerHTML" "")]
      (set! js/document (js-obj "getElementById" (fn [id]
                                                   (case id
                                                     "ol-map" ol-el
                                                     "app" app-el
                                                     "map-extent" (js-obj "textContent" "")
                                                     nil))
                                "querySelector" (fn [_] nil)
                                "addEventListener" (fn [_ _])))
      (reset! m/current nil)
      (reset! b/app-state (assoc (ui/init-state)
                                 :page :others
                                 :place {:west 139 :south 35 :east 141 :north 37}
                                 :basemaps [{:kind "aerial" :ready true}]
                                 :others-fields [{:id 1 :name "北"
                                                  :geojson {:type "Polygon"
                                                            :coordinates [[[140 36] [140.1 36]
                                                                           [140.1 36.1] [140 36.1] [140 36]]]}}]
                                 :others-paint-data {:work_name "田植え"
                                                     :fields [{:id 1 :status "done"}]}))
      (b/apply-fx! [:html "<div id=\"ol-map\" data-others-mode=\"1\" data-target-ids=\"1\"></div>"])
      (is (some? (:map @m/current))))))
