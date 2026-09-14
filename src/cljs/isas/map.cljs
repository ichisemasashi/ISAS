(ns isas.map
  (:require [clojure.string :as str]
            [isas.browser :as browser]
            [isas.ui :as ui]
            ["ol/Map" :default OlMap]
            ["ol/View" :default View]
            ["ol/layer/Vector" :default VectorLayer]
            ["ol/layer/Image" :default ImageLayer]
            ["ol/layer/Graticule" :default Graticule]
            ["ol/source/Vector" :default VectorSource]
            ["ol/source/ImageStatic" :default ImageStatic]
            ["ol/format/GeoJSON" :default GeoJSON]
            ["ol/interaction/Draw" :default Draw]
            ["ol/interaction/Modify" :default Modify]
            ["ol/style/Style" :default Style]
            ["ol/style/Fill" :default Fill]
            ["ol/style/Stroke" :default Stroke]))

(def japan-extent #js [129 26 146 46])

(defonce current (atom nil))

(defonce installed? (atom false))

(defn- geojson-fmt []
  (GeoJSON. #js {:dataProjection "EPSG:4326" :featureProjection "EPSG:4326"}))

(defn- destroy! []
  (when-let [^js ol-map (:map @current)]
    (.setTarget ol-map nil))
  (reset! current nil))

(defn style-for [kind status painted?]
  (let [fill (if (= "paint" (str kind))
               "rgba(21,101,192,0.25)"
               (if painted?
                 (case (str status)
                   "none" "#c8c8c8"
                   "partial" "#e6b800"
                   "done" "#2e7d32"
                   "#c8c8c8")
                 "rgba(0,0,0,0)"))
        stroke (if (= "paint" (str kind)) "#1565c0" "#333333")
        width (if (= "paint" (str kind)) 2 1)]
    (Style. #js {:stroke (Stroke. #js {:color stroke :width width})
                 :fill (Fill. #js {:color fill})})))

(defn drafts-geojson [drafts]
  (let [ds (vec drafts)]
    (cond
      (empty? ds) nil
      (= 1 (count ds)) (first ds)
      :else {:type "MultiPolygon"
             :coordinates (mapv (fn [g]
                                  (if (= "Polygon" (str (:type g)))
                                    (:coordinates g)
                                    (first (or (:coordinates g) []))))
                                ds)})))

(defn- features-from [fields paint-data]
  (let [^js fmt (geojson-fmt)
        arr #js []
        by (into {} (map (fn [f] [(:id f) f]) (or (:fields paint-data) [])))
        colored? (some? paint-data)]
    (doseq [f fields]
      (when (:geojson f)
        (let [p (get by (:id f))
              feat (.readFeature fmt (clj->js {:type "Feature"
                                               :geometry (:geojson f)
                                               :properties {:id (:id f)
                                                            :name (:name f)
                                                            :status (or (:status p) "none")
                                                            :painted colored?}}))]
          (.set feat "id" (:id f))
          (.set feat "name" (:name f))
          (.set feat "status" (or (:status p) "none"))
          (.set feat "painted" colored?)
          (.push arr feat))))
    (when paint-data
      (doseq [pf (:fields paint-data)
              p (:paints pf)]
        (when (:geojson p)
          (let [feat (.readFeature fmt (clj->js {:type "Feature"
                                                 :geometry (:geojson p)
                                                 :properties {:paint-id (:id p)
                                                              :kind "paint"
                                                              :id (:id pf)}}))]
            (.set feat "paint-id" (:id p))
            (.set feat "kind" "paint")
            (.set feat "id" (:id pf))
            (.push arr feat)))))
    arr))

(defn- image-source [kind box]
  (ImageStatic.
   #js {:url (str "/api/user/basemaps/" kind)
        :imageExtent #js [(:west box) (:south box) (:east box) (:north box)]
        :projection "EPSG:4326"}))

(defn- image-layer [kind box]
  (ImageLayer. #js {:source (image-source kind box)}))

(defn- place-box [place]
  (select-keys place [:west :south :east :north]))

(defn- current-image-box [place]
  (or (:image-ext @current) (ui/image-bbox place) (place-box place)))

(defn- set-form-input [act name v]
  (when-let [^js form (.querySelector js/document (str "form[data-act='" act "']"))]
    (when-let [^js el (.querySelector form (str "input[name='" name "']"))]
      (set! (.-value el) (str v)))))

(defn- fill-image-form [box]
  (when box
    (set-form-input "save-image-extent" "west" (:west box))
    (set-form-input "save-image-extent" "south" (:south box))
    (set-form-input "save-image-extent" "east" (:east box))
    (set-form-input "save-image-extent" "north" (:north box))))

(defn- remember-image [box]
  (swap! current assoc :image-ext box)
  (when-let [st @browser/app-state]
    (swap! browser/app-state update :place merge
           {:image_west (:west box)
            :image_south (:south box)
            :image_east (:east box)
            :image_north (:north box)}))
  (fill-image-form box))

(defn- apply-image-box [box]
  (when (and box (< (:west box) (:east box)) (< (:south box) (:north box)))
    (remember-image box)
    (when-let [{:keys [^js image-layer kind]} @current]
      (when image-layer
        (.setSource image-layer (image-source kind box))))))

(defn- shift-image [dir]
  (when-let [place (or (:place @current) (:place @browser/app-state))]
    (let [box (current-image-box place)
          w (- (:east box) (:west box))
          h (- (:north box) (:south box))
          step 0.03
          [dx dy] (case dir
                    "west" [(- (* w step)) 0]
                    "east" [(* w step) 0]
                    "south" [0 (- (* h step))]
                    "north" [0 (* h step)]
                    [0 0])]
      (apply-image-box (ui/shift-bbox box dx dy)))))

(defn- scale-image [factor]
  (when-let [place (or (:place @current) (:place @browser/app-state))]
    (apply-image-box (ui/scale-bbox (current-image-box place) factor))))

(defn- reset-image []
  (when-let [place (or (:place @current) (:place @browser/app-state))]
    (apply-image-box (place-box place))))

(defn- deg [n]
  (.toFixed (js/Number n) 2))

(defn- fill-place-form [ext]
  (set-form-input "save-place" "west" (aget ext 0))
  (set-form-input "save-place" "south" (aget ext 1))
  (set-form-input "save-place" "east" (aget ext 2))
  (set-form-input "save-place" "north" (aget ext 3))
  (when-let [^js el (.getElementById js/document "place-extent")]
    (set! (.-textContent el)
          (str "東経 " (deg (aget ext 0)) "〜" (deg (aget ext 2))
               "　北緯 " (deg (aget ext 1)) "〜" (deg (aget ext 3))))))

(defn- write-json [act name obj]
  (set-form-input act name (.stringify js/JSON (clj->js obj))))

(defn- set-hint [text]
  (when-let [^js el (.getElementById js/document "map-hint")]
    (set! (.-textContent el) (str text))))

(defn- set-selection [text]
  (when-let [^js el (.getElementById js/document "map-selection")]
    (set! (.-textContent el) (str text))))

(defn- current-work-name []
  (or (some-> (.querySelector js/document "form[data-act='select-work-name'] input[name='work_name']")
              .-value)
      (get-in @browser/app-state [:form :work_name])
      ""))

(defn- remember-form! [m]
  (when (and @browser/app-state (seq m))
    (swap! browser/app-state update :form merge m)))

(defn- set-field-targets! [id]
  (when id
    (let [s (str id)]
      (set-form-input "confirm-paint" "field_id" s)
      (set-form-input "complete-field" "id" s)
      (set-form-input "delete-field-paints" "id" s)
      (set-form-input "split-field" "id" s)
      (set-form-input "update-field" "id" s)
      (remember-form! {:field_id s :id s}))))

(defn- set-work-name-targets! [wn]
  (let [w (str (or wn ""))]
    (set-form-input "confirm-paint" "work_name" w)
    (set-form-input "complete-field" "work_name" w)
    (set-form-input "delete-field-paints" "work_name" w)
    (when-not (str/blank? w)
      (remember-form! {:work_name w}))))

(defn- write-drafts! []
  (let [gj (drafts-geojson (or (:drafts @current) []))
        id (first (:selected @current))
        wn (current-work-name)
        gj-str (when gj (.stringify js/JSON (clj->js gj)))]
    (when gj-str
      (set-form-input "confirm-paint" "geojson" gj-str)
      (remember-form! {:paint-geojson gj-str}))
    (set-field-targets! id)
    (set-work-name-targets! wn)))

(defn- start-draw [mode]
  (when-let [{:keys [^js map ^js source draft-source]} @current]
    (let [src (if (= mode :brush) (or draft-source source) source)
          typ (if (= mode :split) "LineString" "Polygon")
          opts #js {:source src :type typ :freehand (= mode :brush)}
          ^js draw (Draw. opts)]
      (.addInteraction map draw)
      (.on draw "drawend"
           (fn [^js ev]
             (let [^js fmt (geojson-fmt)
                   ^js feat (.-feature ev)
                   gj (js->clj (.writeGeometryObject fmt (.getGeometry feat)) :keywordize-keys true)]
               (cond
                 (= mode :split) (write-json "split-field" "line" gj)
                 (= mode :brush) (do (swap! current update :drafts (fnil conj []) gj)
                                     (write-drafts!))
                 :else (write-json "create-field" "geojson" gj))))))))

(defn- discard-drafts! []
  (swap! current assoc :drafts [])
  (when-let [^js src (:draft-source @current)]
    (when (.-clear src)
      (.clear src)))
  (set-form-input "confirm-paint" "geojson" "")
  (remember-form! {:paint-geojson ""})
  (set-selection ""))

(defn- start-edit []
  (when-let [{:keys [^js map ^js source]} @current]
    (let [^js modify (Modify. #js {:source source})]
      (.addInteraction map modify)
      (.on modify "modifyend"
           (fn [^js ev]
             (let [^js fmt (geojson-fmt)
                   ^js features (.-features ev)
                   feats (.getArray features)
                   ^js feat (when (and feats (pos? (.-length feats))) (aget feats 0))]
               (when feat
                 (set-form-input "update-field" "id" (.get feat "id"))
                 (when-let [nm (.get feat "name")]
                   (set-form-input "update-field" "name" nm))
                 (write-json "update-field" "geojson"
                             (.writeGeometryObject fmt (.getGeometry feat))))))))))

(defn- on-tool [op kind dir factor]
  (cond
    (= op "draw") (start-draw :create)
    (= op "edit") (start-edit)
    (= op "split") (start-draw :split)
    (= op "brush") (start-draw :brush)
    (= op "discard") (discard-drafts!)
    (= op "merge") (do (swap! current assoc :selected [])
                       (set-selection ""))
    (= op "image-shift") (shift-image dir)
    (= op "image-scale") (scale-image (js/parseFloat (str factor)))
    (= op "image-reset") (reset-image)
    (= op "basemap") (when kind
                       (swap! current assoc :kind kind)
                       (when-let [st @browser/app-state]
                         ((or @browser/map-sync-fn identity) (assoc st :basemap-kind kind) browser/dispatch!)))
    :else nil))

(defn- on-doc-click [^js ev]
  (when-let [^js btn (.closest (.-target ev) "[data-map]")]
    (when-let [h (.getAttribute btn "data-hint")]
      (set-hint h))
    (on-tool (.getAttribute btn "data-map")
             (.getAttribute btn "data-kind")
             (.getAttribute btn "data-dir")
             (.getAttribute btn "data-factor"))))

(defn- bind-map-click [^js ol-map]
  (.on ol-map "click"
       (fn [^js evt]
         (.forEachFeatureAtPixel ol-map (.-pixel evt)
                                 (fn [^js feat]
                                   (let [paint-id (.get feat "paint-id")
                                         id (.get feat "id")
                                         nm (.get feat "name")]
                                     (when paint-id
                                       (set-form-input "delete-paint" "id" paint-id)
                                       (remember-form! {:paint-id (str paint-id)})
                                       (set-selection (str "選んでいる塗り: " paint-id)))
                                     (when id
                                       (let [ids (vec (distinct (conj (or (:selected @current) []) id)))]
                                         (swap! current assoc :selected ids)
                                         (set-field-targets! id)
                                         (set-work-name-targets! (current-work-name))
                                         (when nm
                                           (set-form-input "update-field" "name" nm)
                                           (remember-form! {:name (str nm)}))
                                         (set-form-input "merge-fields" "keep_id" (first ids))
                                         (write-json "merge-fields" "ids" ids)
                                         (when-not paint-id
                                           (set-selection (str "選んでいる圃場: " (or nm id)
                                                               (when (> (count ids) 1)
                                                                 (str "（合筆の対象 " (count ids) "枚）")))))))
                                     true))))))

(defn- sync! [state _dispatch]
  (let [el (.getElementById js/document "ol-map")]
    (destroy!)
    (when el
      (let [place (:place state)
            kind (or (:basemap-kind state) "aerial")
            ready? (boolean (some (fn [b] (and (= kind (:kind b)) (:ready b))) (:basemaps state)))
            style-fn (fn [feat _]
                       (style-for (.get feat "kind") (.get feat "status") (.get feat "painted")))
            src (VectorSource. #js {:features (features-from (or (:fields state) []) (:paint-data state))})
            draft-src (VectorSource. #js {:features #js []})
            vec-layer (VectorLayer. #js {:source src :style style-fn})
            draft-layer (VectorLayer. #js {:source draft-src})
            ^js grid (Graticule. #js {:showLabels true :wrapX false})
            img-box (when place (current-image-box place))
            img (when (and place ready? img-box) (image-layer kind img-box))
            layers (if img
                     #js [img grid vec-layer draft-layer]
                     #js [grid vec-layer draft-layer])
            ^js view (View. #js {:projection "EPSG:4326"})
            ^js ol-map (OlMap. #js {:target el :layers layers :view view})
            ext (if place
                  #js [(:west place) (:south place) (:east place) (:north place)]
                  japan-extent)]
        (.fit view ext #js {:padding #js [16 16 16 16]})
        (fill-place-form ext)
        (fill-image-form img-box)
        (.on ol-map "moveend" (fn [_]
                                (when-let [^js v (.getView ol-map)]
                                  (fill-place-form (.calculateExtent v)))))
        (bind-map-click ol-map)
        (let [fid (or (get-in state [:form :field_id]) (get-in state [:form :id]))
              selected (if (and fid (not (str/blank? (str fid))))
                         (let [n (when (string? fid) (js/parseInt fid 10))
                               id (if (and n (not (js/isNaN n))) n fid)]
                           [id])
                         [])]
          (reset! current {:map ol-map :source src :view view :kind kind
                           :image-layer img :place place :image-ext img-box
                           :split-polys [] :selected selected :drafts []
                           :draft-source draft-src :style-fn style-fn})
          (when (seq selected)
            (set-field-targets! (first selected))
            (set-work-name-targets! (or (get-in state [:form :work_name]) (current-work-name)))))
        nil))))

(defn install! []
  (browser/register-map-sync! sync!)
  (when-not @installed?
    (reset! installed? true)
    (.addEventListener js/document "click" on-doc-click)))
