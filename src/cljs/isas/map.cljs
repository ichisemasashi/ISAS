(ns isas.map
  (:require [isas.browser :as browser]
            ["ol/Map" :default OlMap]
            ["ol/View" :default View]
            ["ol/layer/Vector" :default VectorLayer]
            ["ol/layer/Image" :default ImageLayer]
            ["ol/layer/Graticule" :default Graticule]
            ["ol/source/Vector" :default VectorSource]
            ["ol/source/ImageStatic" :default ImageStatic]
            ["ol/format/GeoJSON" :default GeoJSON]
            ["ol/interaction/Draw" :default Draw]
            ["ol/interaction/Modify" :default Modify]))

(def japan-extent #js [129 26 146 46])

(defonce current (atom nil))

(defonce installed? (atom false))

(defn- geojson-fmt []
  (GeoJSON. #js {:dataProjection "EPSG:4326" :featureProjection "EPSG:4326"}))

(defn- destroy! []
  (when-let [^js ol-map (:map @current)]
    (.setTarget ol-map nil))
  (reset! current nil))

(defn- features-from [fields]
  (let [^js fmt (geojson-fmt)
        arr #js []]
    (doseq [f fields]
      (when (:geojson f)
        (let [feat (.readFeature fmt (clj->js {:type "Feature"
                                               :geometry (:geojson f)
                                               :properties {:id (:id f) :name (:name f)}}))]
          (.push arr feat))))
    arr))

(defn- image-layer [kind place]
  (ImageLayer.
   #js {:source (ImageStatic.
                 #js {:url (str "/api/user/basemaps/" kind)
                      :imageExtent #js [(:west place) (:south place) (:east place) (:north place)]
                      :projection "EPSG:4326"})}))

(defn- set-form-input [act name v]
  (when-let [^js form (.querySelector js/document (str "form[data-act='" act "']"))]
    (when-let [^js el (.querySelector form (str "input[name='" name "']"))]
      (set! (.-value el) (str v)))))

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

(defn- start-draw [mode]
  (when-let [{:keys [^js map ^js source]} @current]
    (let [^js draw (Draw. #js {:source source :type "Polygon"})]
      (.addInteraction map draw)
      (.on draw "drawend"
           (fn [^js ev]
             (let [^js fmt (geojson-fmt)
                   ^js feat (.-feature ev)
                   gj (.writeGeometryObject fmt (.getGeometry feat))]
               (if (= mode :split)
                 (let [ps (conj (or (:split-polys @current) []) gj)]
                   (swap! current assoc :split-polys ps)
                   (write-json "split-field" "polygons" ps))
                 (write-json "create-field" "geojson" gj))))))))

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

(defn- on-tool [op kind]
  (cond
    (= op "draw") (start-draw :create)
    (= op "edit") (start-edit)
    (= op "split") (do (swap! current assoc :split-polys [])
                       (start-draw :split))
    (= op "merge") (swap! current assoc :selected [])
    (= op "basemap") (when kind
                       (swap! current assoc :kind kind)
                       (when-let [st @browser/app-state]
                         ((or @browser/map-sync-fn identity) (assoc st :basemap-kind kind) browser/dispatch!)))
    :else nil))

(defn- on-doc-click [^js ev]
  (when-let [^js btn (.closest (.-target ev) "[data-map]")]
    (on-tool (.getAttribute btn "data-map") (.getAttribute btn "data-kind"))))

(defn- bind-map-click [^js ol-map]
  (.on ol-map "click"
       (fn [^js evt]
         (.forEachFeatureAtPixel ol-map (.-pixel evt)
                                 (fn [^js feat]
                                   (let [id (.get feat "id")
                                         nm (.get feat "name")
                                         ids (vec (distinct (conj (or (:selected @current) []) id)))]
                                     (swap! current assoc :selected ids)
                                     (set-form-input "split-field" "id" id)
                                     (set-form-input "update-field" "id" id)
                                     (when nm
                                       (set-form-input "update-field" "name" nm))
                                     (set-form-input "merge-fields" "keep_id" (first ids))
                                     (write-json "merge-fields" "ids" ids)
                                     true))))))

(defn- sync! [state _dispatch]
  (let [el (.getElementById js/document "ol-map")]
    (destroy!)
    (when el
      (let [place (:place state)
            kind (or (:basemap-kind state) "aerial")
            ready? (boolean (some (fn [b] (and (= kind (:kind b)) (:ready b))) (:basemaps state)))
            src (VectorSource. #js {:features (features-from (or (:fields state) []))})
            vec-layer (VectorLayer. #js {:source src})
            ^js grid (Graticule. #js {:showLabels true :wrapX false})
            layers (if (and place ready?)
                     #js [(image-layer kind place) grid vec-layer]
                     #js [grid vec-layer])
            ^js view (View. #js {:projection "EPSG:4326"})
            ^js ol-map (OlMap. #js {:target el :layers layers :view view})
            ext (if place
                  #js [(:west place) (:south place) (:east place) (:north place)]
                  japan-extent)]
        (.fit view ext #js {:padding #js [16 16 16 16]})
        (fill-place-form ext)
        (.on ol-map "moveend" (fn [_]
                                (when-let [^js v (.getView ol-map)]
                                  (fill-place-form (.calculateExtent v)))))
        (bind-map-click ol-map)
        (reset! current {:map ol-map :source src :view view :kind kind :split-polys [] :selected []})
        nil))))

(defn install! []
  (browser/register-map-sync! sync!)
  (when-not @installed?
    (reset! installed? true)
    (.addEventListener js/document "click" on-doc-click)))
