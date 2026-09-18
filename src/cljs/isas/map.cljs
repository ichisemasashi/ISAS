(ns isas.map
  (:require [clojure.string :as str]
            [isas.browser :as browser]
            [isas.ui :as ui]
            ["ol/Map" :default OlMap]
            ["ol/View" :default View]
            ["ol/layer/Vector" :default VectorLayer]
            ["ol/layer/Image" :default ImageLayer]
            ["ol/layer/Tile" :default TileLayer]
            ["ol/layer/Graticule" :default Graticule]
            ["ol/source/Vector" :default VectorSource]
            ["ol/source/ImageStatic" :default ImageStatic]
            ["ol/source/XYZ" :default XYZ]
            ["ol/proj" :as ol-proj]
            ["ol/format/GeoJSON" :default GeoJSON]
            ["ol/interaction/Draw" :default Draw]
            ["ol/interaction/Modify" :default Modify]
            ["ol/style/Style" :default Style]
            ["ol/style/Fill" :default Fill]
            ["ol/style/Stroke" :default Stroke]))

(def japan-extent #js [129 26 146 46])

(defn- finite-num [v]
  (let [n (js/Number v)]
    (when (js/isFinite n) n)))

(defn- form-extent-4326 [form]
  (let [w (finite-num (:west form))
        s (finite-num (:south form))
        e (finite-num (:east form))
        n (finite-num (:north form))]
    (when (and w s e n (< w e) (< s n))
      #js [w s e n])))

(defn- place-extent-4326 [place]
  (when place
    (let [w (:west place) s (:south place) e (:east place) n (:north place)]
      (when (and w s e n)
        #js [w s e n]))))

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
                   "none" (:none ui/paint-colors)
                   "partial" (:partial ui/paint-colors)
                   "done" (:done ui/paint-colors)
                   "dim" (:dim ui/paint-colors)
                   (:none ui/paint-colors))
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

(defn- features-from [fields paint-data gantt-ctx]
  (let [^js fmt (geojson-fmt)
        arr #js []
        by (into {} (map (fn [f] [(:id f) f]) (or (:fields paint-data) [])))
        colored? (some? paint-data)
        gantt-mode? (boolean (:gantt-mode? gantt-ctx))
        targets (or (:targets gantt-ctx) #{})
        progress-by (into {}
                          (mapcat (fn [f]
                                    (let [id (:id f)]
                                      [[id f] [(str id) f]]))
                                  (or (:fields (:progress gantt-ctx)) [])))]
    (doseq [f fields]
      (when (:geojson f)
        (let [fid (:id f)
              p (get by fid)
              is-target? (or (contains? targets fid)
                             (contains? targets (str fid)))
              status (cond
                       (and gantt-mode? is-target?)
                       (str (or (:status (or (get progress-by fid)
                                             (get progress-by (str fid))))
                                "none"))
                       gantt-mode? "dim"
                       :else (or (:status p) "none"))
              painted (boolean (or gantt-mode? colored?))
              feat (.readFeature fmt (clj->js {:type "Feature"
                                               :geometry (:geojson f)
                                               :properties {:id fid
                                                            :name (:name f)
                                                            :status status
                                                            :painted painted}}))]
          (.set feat "id" fid)
          (.set feat "name" (:name f))
          (.set feat "status" status)
          (.set feat "painted" painted)
          (.push arr feat))))
    (when (and paint-data (not gantt-mode?))
      (doseq [pf (:fields paint-data)
              p (:paints pf)]
        (when (:geojson p)
          (let [feat (.readFeature fmt (clj->js {:type "Feature"
                                                 :geometry (:geojson p)
                                                 :properties {:paint-id (:id p)
                                                              :kind "paint"
                                                              :id (:id pf)
                                                              :name (:name pf)}}))]
            (.set feat "paint-id" (:id p))
            (.set feat "kind" "paint")
            (.set feat "id" (:id pf))
            (.set feat "name" (:name pf))
            (.push arr feat)))))
    arr))

(defn- status-map-features
  "指示／他人地図用。status がある枚は進捗色、無い枚は輪郭だけ。"
  [fields]
  (let [^js fmt (geojson-fmt)
        arr #js []]
    (doseq [f fields]
      (when (:geojson f)
        (let [fid (:id f)
              has-status? (contains? f :status)
              status (str (or (:status f) "none"))
              painted has-status?
              feat (.readFeature fmt (clj->js {:type "Feature"
                                               :geometry (:geojson f)
                                               :properties {:id fid
                                                            :name (:name f)
                                                            :status status
                                                            :painted painted}}))]
          (.set feat "id" fid)
          (.set feat "name" (:name f))
          (.set feat "status" status)
          (.set feat "painted" painted)
          (.push arr feat))))
    arr))

(defn- source-extent [^js src]
  (when (and src (fn? (.-getExtent src)))
    (let [e (.getExtent src)]
      (when (and e
                 (js/isFinite (aget e 0))
                 (js/isFinite (aget e 1))
                 (js/isFinite (aget e 2))
                 (js/isFinite (aget e 3))
                 (< (aget e 0) (aget e 2))
                 (< (aget e 1) (aget e 3)))
        e))))

(defn- image-source [kind box]
  (ImageStatic.
   #js {:url (str "/api/user/basemaps/" kind)
        :imageExtent #js [(:west box) (:south box) (:east box) (:north box)]
        :projection "EPSG:4326"}))

(defn- image-layer [kind box]
  (ImageLayer. #js {:source (image-source kind box)}))

(def gsi-attr "国土地理院")

(defn- gsi-xyz-url [layer]
  (let [ext (if (= "std" layer) "png" "jpg")]
    (str "https://cyberjapandata.gsi.go.jp/xyz/" layer "/{z}/{x}/{y}." ext)))

(defn- gsi-tile-layer [layer]
  (TileLayer.
   #js {:source (XYZ. #js {:url (gsi-xyz-url layer)
                           :attributions gsi-attr
                           :maxZoom 18})}))

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
  (doseq [act ["save-place" "preview-place" "cancel-place-preview"]]
    (set-form-input act "west" (aget ext 0))
    (set-form-input act "south" (aget ext 1))
    (set-form-input act "east" (aget ext 2))
    (set-form-input act "north" (aget ext 3)))
  (when-let [^js el (.getElementById js/document "place-extent")]
    (set! (.-textContent el)
          (str "東経 " (deg (aget ext 0)) "〜" (deg (aget ext 2))
               "　北緯 " (deg (aget ext 1)) "〜" (deg (aget ext 3))))))

(defn- set-hint [text]
  (when-let [^js el (.getElementById js/document "map-hint")]
    (set! (.-textContent el) (str text))))

(defn- set-selection [text]
  (when-let [^js el (.getElementById js/document "map-selection")]
    (set! (.-textContent el) (str text))))

(defonce ^:private suppress-select-until (atom 0))

(defn- field-key [id]
  (when (some? id)
    (let [s (str/trim (str id))]
      (when-not (str/blank? s) s))))

(defn- same-field? [a b]
  (and (some? a) (some? b) (= (field-key a) (field-key b))))

(defn- current-work-name []
  (or (some-> (.querySelector js/document "form[data-act='select-work-name'] input[name='work_name']")
              .-value)
      (get-in @browser/app-state [:form :work_name])
      ""))

(defn- remember-form! [m]
  (when (and @browser/app-state (seq m))
    (swap! browser/app-state update :form merge m)))

(defn- write-json [act name obj]
  (let [s (.stringify js/JSON (clj->js obj))]
    (set-form-input act name s)
    (remember-form! {(keyword name) s})))

(defn- set-field-targets! [id]
  (when-let [s (field-key id)]
    (set-form-input "confirm-paint" "field_id" s)
    (set-form-input "complete-field" "id" s)
    (set-form-input "delete-field-paints" "id" s)
    (set-form-input "split-field" "id" s)
    (set-form-input "update-field" "id" s)
    (remember-form! {:field_id s :id s})))

(defn- set-work-name-targets! [wn]
  (let [w (str (or wn ""))]
    (set-form-input "confirm-paint" "work_name" w)
    (set-form-input "complete-field" "work_name" w)
    (set-form-input "delete-field-paints" "work_name" w)
    (when-not (str/blank? w)
      (remember-form! {:work_name w}))))

(defn- write-drafts! []
  (let [gj (drafts-geojson (or (:drafts @current) []))
        id (or (:active-field @current)
               (field-key (last (:selected @current))))
        wn (current-work-name)
        gj-str (when gj (.stringify js/JSON (clj->js gj)))]
    (when gj-str
      (set-form-input "confirm-paint" "geojson" gj-str)
      (remember-form! {:paint-geojson gj-str}))
    (set-field-targets! id)
    (set-work-name-targets! wn)))

(defn- clear-drafts-only! []
  (swap! current assoc :drafts [])
  (when-let [^js src (:draft-source @current)]
    (when (.-clear src)
      (.clear src)))
  (set-form-input "confirm-paint" "geojson" "")
  (remember-form! {:paint-geojson ""}))

(defn- suppress-select! []
  (reset! suppress-select-until (+ (.now js/Date) 600)))

(defn- select-suppressed? []
  (or (< (.now js/Date) @suppress-select-until)
      (boolean (:drawing? @current))))

(defn- stop-draw!
  "Draw インタラクションを外し、地図のドラッグ（パン）を取り戻す。"
  []
  (when-let [{:keys [^js map draw]} @current]
    (when (and map draw)
      (.removeInteraction map draw))
    (swap! current assoc :draw nil :drawing? false)))

(defn- start-draw [mode]
  (when-let [{:keys [^js map ^js source draft-source]} @current]
    (stop-draw!)
    (let [src (if (= mode :brush) (or draft-source source) source)
          typ (if (= mode :split) "LineString" "Polygon")
          opts #js {:source src :type typ :freehand (= mode :brush)}
          ^js draw (Draw. opts)]
      (swap! current assoc :drawing? true :tool mode :draw draw)
      (.addInteraction map draw)
      (.on draw "drawend"
           (fn [^js ev]
             (let [^js fmt (geojson-fmt)
                   ^js feat (.-feature ev)
                   gj (js->clj (.writeGeometryObject fmt (.getGeometry feat)) :keywordize-keys true)]
               (suppress-select!)
               (cond
                 (= mode :split) (write-json "split-field" "line" gj)
                 (= mode :brush) (do (swap! current update :drafts (fnil conj []) gj)
                                     (write-drafts!))
                 :else (write-json "create-field" "geojson" gj))
               ;; 一筆ごとに Draw を外し、拡大・ドラッグ（パン）を可能にする。
               (stop-draw!)
               (when (= mode :brush)
                 (swap! current assoc :tool :brush))))))))

(defn- discard-drafts! []
  (stop-draw!)
  (clear-drafts-only!)
  (set-selection ""))

(defn- start-edit []
  (stop-draw!)
  (when-let [{:keys [^js map ^js source]} @current]
    (let [^js modify (Modify. #js {:source source})]
      (swap! current assoc :tool :edit :drawing? false :draw nil)
      (.addInteraction map modify)
      (.on modify "modifyend"
           (fn [^js ev]
             (let [^js fmt (geojson-fmt)
                   ^js features (.-features ev)
                   feats (.getArray features)
                   ^js feat (when (and feats (pos? (.-length feats))) (aget feats 0))]
               (when feat
                 (set-form-input "update-field" "id" (.get feat "id"))
                 (remember-form! {:id (str (.get feat "id"))})
                 (when-let [nm (.get feat "name")]
                   (set-form-input "update-field" "name" nm)
                   (remember-form! {:name (str nm)}))
                 (write-json "update-field" "geojson"
                             (.writeGeometryObject fmt (.getGeometry feat))))))))))

(defn- apply-map-mode! [state]
  (let [mode (ui/map-mode state)]
    (stop-draw!)
    (case mode
      "draw" (start-draw :create)
      "edit" (start-edit)
      ;; 分割は先に圃場をクリックで選ぶ。Draw を先に付けるとクリックが線になる。
      "split" (do (reset! suppress-select-until 0)
                  (swap! current assoc :tool :split :drawing? false))
      "merge" (do (reset! suppress-select-until 0)
                  (swap! current assoc :tool :merge :drawing? false)
                  (set-selection ""))
      ;; 塗りモードではブラシボタンを押すまで Draw を付けない（地図ドラッグを残す）
      "paint" (swap! current assoc :tool nil :drawing? false)
      (swap! current assoc :tool nil :drawing? false))))

(defn- on-tool [op kind dir factor]
  (cond
    (= op "brush") (do (swap! current assoc :tool :brush :selected
                              (if-let [a (:active-field @current)] [a] []))
                       (start-draw :brush))
    (= op "discard") (discard-drafts!)
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
         (when-not (select-suppressed?)
           (.forEachFeatureAtPixel ol-map (.-pixel evt)
                                   (fn [^js feat]
                                     (let [paint-id (.get feat "paint-id")
                                           id (field-key (.get feat "id"))
                                           nm (.get feat "name")
                                           merge? (= :merge (:tool @current))]
                                       (when paint-id
                                         (set-form-input "delete-paint" "id" paint-id)
                                         (remember-form! {:paint-id (str paint-id)})
                                         ;; 画面には圃場名を出す。塗り行の内部 ID は出さない。
                                         (set-selection (str "選んでいる塗り: " (or nm id))))
                                       (when id
                                         (let [prev (:active-field @current)
                                               ids (if merge?
                                                     (vec (distinct (conj (or (:selected @current) []) id)))
                                                     [id])]
                                           (when (and prev (not (same-field? prev id)) (seq (:drafts @current)))
                                             (clear-drafts-only!))
                                           (swap! current assoc :selected ids :active-field id)
                                           (set-field-targets! id)
                                           (set-work-name-targets! (current-work-name))
                                           (when nm
                                             (set-form-input "update-field" "name" nm)
                                             (remember-form! {:name (str nm)}))
                                           (when merge?
                                             (set-form-input "merge-fields" "keep_id" (first ids))
                                             (write-json "merge-fields" "ids" ids))
                                           (when-not paint-id
                                             (set-selection (str "選んでいる圃場: " (or nm id)
                                                                 (when (and merge? (> (count ids) 1))
                                                                   (str "（合筆の対象 " (count ids) "枚）")))))
                                           ;; 圃場を選んだあとで線引きを付ける（このクリックでは線を始めない）
                                           (when (= :split (:tool @current))
                                             (start-draw :split))))
                                       true)))))))

(defn- sync! [state _dispatch]
  (let [el (.getElementById js/document "ol-map")]
    (destroy!)
    (when el
      (let [place (:place state)
            place-mode? (= "1" (.getAttribute el "data-place-mode"))
            preview? (= "aerial" (.getAttribute el "data-preview"))
            gantt-mode-attr? (= "1" (.getAttribute el "data-gantt-mode"))
            order-mode? (= "1" (.getAttribute el "data-order-mode"))
            others-mode? (= "1" (.getAttribute el "data-others-mode"))
            progress-map? (or order-mode? others-mode?)
            target-attr (str (or (.getAttribute el "data-target-ids") ""))
            target-ids (->> (str/split target-attr #",")
                            (map str/trim)
                            (remove str/blank?)
                            (mapcat (fn [s]
                                      (let [n (js/parseInt s 10)]
                                        (cond-> [s]
                                          (js/isFinite n) (conj n)))))
                            set)
            progress (:gantt-progress state)
            gantt-applicable? (and gantt-mode-attr?
                                   (seq target-ids)
                                   progress
                                   (:applicable progress))
            gantt-ctx (when gantt-applicable?
                        {:gantt-mode? true
                         :targets target-ids
                         :progress progress})
            order-fields (when order-mode?
                           (or (:fields (:order-map state)) []))
            others-fields
            (when others-mode?
              (let [ofs (or (:others-fields state) [])
                    by (into {} (map (fn [f] [(:id f) f])
                                     (or (:fields (:others-paint-data state)) [])))]
                (mapv (fn [f]
                        (if-let [p (get by (:id f))]
                          (assoc f :status (:status p))
                          f))
                      ofs)))
            map-fields (cond
                         order-mode? order-fields
                         others-mode? others-fields
                         :else (or (:fields state) []))
            kind (or (:basemap-kind state) "aerial")
            ready? (boolean (some (fn [b] (and (= kind (:kind b)) (:ready b))) (:basemaps state)))
            style-fn (fn [feat _]
                       (style-for (.get feat "kind") (.get feat "status") (.get feat "painted")))
            paint-data (when-not (or gantt-applicable? progress-map?) (:paint-data state))
            feats (if progress-map?
                    (status-map-features map-fields)
                    (features-from map-fields paint-data gantt-ctx))
            src (VectorSource. #js {:features feats})
            draft-src (VectorSource. #js {:features #js []})
            vec-layer (VectorLayer. #js {:source src :style style-fn})
            draft-layer (VectorLayer. #js {:source draft-src})
            ^js grid (Graticule. #js {:showLabels true :wrapX false})
            img-box (when place (current-image-box place))
            img (when (and place (not place-mode?) ready? img-box) (image-layer kind img-box))
            gsi (when place-mode?
                  (gsi-tile-layer (if preview? "seamlessphoto" "std")))
            layers (cond
                     gsi #js [gsi grid vec-layer draft-layer]
                     img #js [img grid vec-layer draft-layer]
                     :else #js [grid vec-layer draft-layer])
            map-proj (if place-mode? "EPSG:3857" "EPSG:4326")
            ^js view (View. #js {:projection map-proj})
            ^js ol-map (OlMap. #js {:target el :layers layers :view view})
            field-ext (when progress-map? (source-extent src))
            ext-4326 (or field-ext
                         (when place-mode? (form-extent-4326 (:form state)))
                         (when place-mode? (place-extent-4326 place))
                         (when place-mode?
                           (form-extent-4326 {:west (.getAttribute el "data-west")
                                              :south (.getAttribute el "data-south")
                                              :east (.getAttribute el "data-east")
                                              :north (.getAttribute el "data-north")}))
                         (place-extent-4326 place)
                         japan-extent)
            fit-ext (if place-mode?
                      (ol-proj/transformExtent ext-4326 "EPSG:4326" "EPSG:3857")
                      ext-4326)
            active (field-key (or (get-in state [:form :field_id]) (get-in state [:form :id])))
            selected (if active [active] [])
            read-only? (or gantt-mode-attr? progress-map?)]
        (.fit view fit-ext #js {:padding #js [16 16 16 16]})
        (when-not progress-map?
          (fill-place-form ext-4326)
          (fill-image-form img-box))
        (.on ol-map "moveend" (fn [_]
                                (when-let [^js v (.getView ol-map)]
                                  (let [e (.calculateExtent v)
                                        e4326 (if place-mode?
                                                (ol-proj/transformExtent e "EPSG:3857" "EPSG:4326")
                                                e)]
                                    (when-not progress-map?
                                      (fill-place-form e4326))))))
        (when-not read-only?
          (bind-map-click ol-map))
        (reset! current {:map ol-map :source src :view view :kind kind
                         :image-layer img :place place :image-ext img-box
                         :split-polys [] :selected selected :active-field active
                         :drafts [] :draft-source draft-src :style-fn style-fn
                         :tool nil :drawing? false})
        (when (and active (not read-only?))
          (set-field-targets! active)
          (set-work-name-targets! (or (get-in state [:form :work_name]) (current-work-name))))
        (when-not read-only?
          (apply-map-mode! state))
        nil))))

(defn install! []
  (browser/register-map-sync! sync!)
  (when-not @installed?
    (reset! installed? true)
    (.addEventListener js/document "click" on-doc-click)))
