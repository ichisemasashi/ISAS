(ns isas.map
  (:require [isas.browser :as browser]
            ["ol/Map" :default OlMap]
            ["ol/View" :default View]
            ["ol/layer/Vector" :default VectorLayer]
            ["ol/layer/Image" :default ImageLayer]
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
  (when-let [m (:map @current)]
    (.setTarget m nil))
  (reset! current nil))

(defn- features-from [fields]
  (let [fmt (geojson-fmt)
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
  (when-let [form (.querySelector js/document (str "form[data-act='" act "']"))]
    (when-let [el (.querySelector form (str "input[name='" name "']"))]
      (set! (.-value el) (str v)))))

(defn- fill-place-form [ext]
  (set-form-input "save-place" "west" (aget ext 0))
  (set-form-input "save-place" "south" (aget ext 1))
  (set-form-input "save-place" "east" (aget ext 2))
  (set-form-input "save-place" "north" (aget ext 3)))

(defn- write-json [act name obj]
  (set-form-input act name (.stringify js/JSON (clj->js obj))))

(defn- start-draw [mode]
  (when-let [{:keys [map source]} @current]
    (let [draw (Draw. #js {:source source :type "Polygon"})]
      (.addInteraction map draw)
      (.on draw "drawend"
           (fn [ev]
             (let [fmt (geojson-fmt)
                   gj (.writeGeometryObject fmt (.getGeometry (.-feature ev)))]
               (if (= mode :split)
                 (let [ps (conj (or (:split-polys @current) []) gj)]
                   (swap! current assoc :split-polys ps)
                   (write-json "split-field" "polygons" ps))
                 (write-json "create-field" "geojson" gj))))))))

(defn- start-edit []
  (when-let [{:keys [map source]} @current]
    (let [modify (Modify. #js {:source source})]
      (.addInteraction map modify)
      (.on modify "modifyend"
           (fn [ev]
             (let [fmt (geojson-fmt)
                   feats (.getArray (.-features ev))
                   feat (when (and feats (pos? (.-length feats))) (aget feats 0))]
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

(defn- on-doc-click [ev]
  (when-let [btn (.closest (.-target ev) "[data-map]")]
    (on-tool (.getAttribute btn "data-map") (.getAttribute btn "data-kind"))))

(defn- bind-map-click [m]
  (.on m "click"
       (fn [evt]
         (.forEachFeatureAtPixel m (.-pixel evt)
                                 (fn [feat]
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
            layers (if (and place ready?)
                     #js [(image-layer kind place) vec-layer]
                     #js [vec-layer])
            view (View. #js {:projection "EPSG:4326"})
            m (OlMap. #js {:target el :layers layers :view view})
            ext (if place
                  #js [(:west place) (:south place) (:east place) (:north place)]
                  japan-extent)]
        (.fit view ext #js {:padding #js [16 16 16 16]})
        (fill-place-form ext)
        (.on m "moveend" (fn [_]
                           (when-let [v (.getView m)]
                             (fill-place-form (.calculateExtent v)))))
        (bind-map-click m)
        (reset! current {:map m :source src :view view :kind kind :split-polys [] :selected []})
        nil))))

(defn install! []
  (browser/register-map-sync! sync!)
  (when-not @installed?
    (reset! installed? true)
    (.addEventListener js/document "click" on-doc-click)))
