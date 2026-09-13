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
  (let [ol-el (js-obj "id" "ol-map")
        extent (js-obj "textContent" "")
        app-el (js-obj "innerHTML" "")
        west (make-input)
        south (make-input)
        east (make-input)
        north (make-input)
        geojson (make-input)
        split-id (make-input)
        split-polys (make-input)
        upd-id (make-input)
        upd-name (make-input)
        upd-geo (make-input)
        keep (make-input)
        ids (make-input)
        save (make-form {"west" west "south" south "east" east "north" north})
        create (make-form {"geojson" geojson})
        split (make-form {"id" split-id "polygons" split-polys})
        update (make-form {"id" upd-id "name" upd-name "geojson" upd-geo})
        merge (make-form {"keep_id" keep "ids" ids})
        forms {"save-place" save
               "create-field" create
               "split-field" split
               "update-field" update
               "merge-fields" merge}
        clicks (atom [])]
    (set! js/document
          (js-obj "getElementById"
                  (fn [id]
                    (case id
                      "ol-map" ol-el
                      "place-extent" extent
                      "app" app-el
                      nil))
                  "querySelector"
                  (fn [sel]
                    (let [act (second (re-find #"data-act='([^']+)'" (str sel)))]
                      (get forms act)))
                  "addEventListener"
                  (fn [ev f]
                    (when (= ev "click")
                      (swap! clicks conj f)))))
    (set! js/window (js-obj "innerWidth" 1200 "addEventListener" (fn [_ _])))
    {:west west :extent extent :clicks clicks :ol-el ol-el}))

(deftest map-install-and-tools-test
  (let [{:keys [west extent clicks]} (setup-dom!)]
    (reset! m/current nil)
    (reset! m/installed? false)
    (reset! b/app-state (assoc (ui/init-state)
                               :place {:west 139 :south 35 :east 141 :north 37}
                               :basemaps [{:kind "aerial" :ready true}]
                               :fields [{:id 1 :name "北"
                                         :geojson {:type "Polygon"
                                                    :coordinates [[[140 36] [140.1 36] [140.1 36.1] [140 36.1] [140 36]]]}}
                                        {:id 2 :name "無"}]))
    (m/install!)
    (m/install!)
    (is (true? @m/installed?))
    (b/apply-fx! [:html "<div id=\"ol-map\"></div>"])
    (is (some? (:map @m/current)))
    (is (re-find #"東経" (.-textContent extent)))
    (is (string? (.-value west)))
    (let [move (last-ol "moveend")]
      (call-ol move "moveend" #js {}))
    (let [click (last-ol "click")]
      (call-ol click "click" #js {:pixel #js [1 1]}))
    (when (seq @clicks)
      (let [fire (fn [op kind]
                   ((first @clicks)
                    (clj->js {:target {:closest (fn [_]
                                                  (js-obj "getAttribute"
                                                          (fn [a]
                                                            (cond
                                                              (= a "data-map") op
                                                              (= a "data-kind") kind
                                                              :else nil))))}})))]
        (fire "draw" nil)
        (let [draw (last-ol "drawend")]
          (call-ol draw "drawend" #js {:feature draw}))
        (fire "split" nil)
        (let [draw (last-ol "drawend")]
          (call-ol draw "drawend" #js {:feature draw}))
        (fire "edit" nil)
        (let [mod (last-ol "modifyend")]
          (when mod
            (set! (.-features mod) mod)
            (call-ol mod "modifyend" #js {:features mod})))
        (fire "merge" nil)
        (fire "basemap" "standard")
        (fire "nope" nil)
        ((first @clicks) (clj->js {:target {:closest (fn [_] nil)}}))))
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
          (call-ol mod "modifyend" #js {:features mod}))))))
