(ns isas.geo
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [net.sf.geographiclib Geodesic PolygonArea]
           [org.locationtech.jts.geom Coordinate GeometryFactory LinearRing MultiPolygon Polygon PrecisionModel]))

(def ^:private gf (GeometryFactory. (PrecisionModel.) 4326))

(defn keywordize [x]
  (walk/postwalk
   (fn [v]
     (if (map? v)
       (into {} (map (fn [[k val]]
                       [(if (string? k) (keyword k) k) val])
                     v))
       v))
   x))

(defn parse-json [s]
  (try
    (keywordize (json/read-str (str s)))
    (catch Exception _
      nil)))

(defn- pair [p]
  (when (sequential? p)
    (let [x (first p)
          y (second p)]
      (when (and (number? x) (number? y))
        [(double x) (double y)]))))

(defn lon-lat? [[lon lat]]
  (and (<= -180.0 lon 180.0)
       (<= -90.0 lat 90.0)))

(defn- projected? [coords]
  (boolean (some (fn [p]
                   (let [xy (pair p)]
                     (and xy (not (lon-lat? xy)))))
                 coords)))

(defn ensure-closed [coords]
  (let [v (mapv identity coords)]
    (cond
      (empty? v) v
      (= (pair (first v)) (pair (last v))) v
      :else (conj v (first v)))))

(defn- ring-points [coords]
  (let [closed (ensure-closed coords)
        pts (keep pair closed)]
    (when (and (>= (count pts) 4)
               (every? lon-lat? pts)
               (not (projected? coords)))
      pts)))

(defn- ring-area-m2 [coords]
  (when-let [pts (ring-points coords)]
    (let [poly (PolygonArea. Geodesic/WGS84 false)]
      (run! (fn [[lon lat]] (.AddPoint poly lat lon)) (butlast pts))
      (Math/abs (.area (.Compute poly))))))

(defn- polygon-coords [gj]
  (when (and (map? gj) (= "Polygon" (str (:type gj))))
    (:coordinates gj)))

(defn- polygon-area-m2 [gj]
  (when-let [rings (polygon-coords gj)]
    (when (sequential? rings)
      (when-let [outer (ring-area-m2 (first rings))]
        (let [holes (keep ring-area-m2 (rest rings))
              a (- outer (reduce + 0.0 holes))]
          (when (pos? a) a))))))

(defn- multipolygon-area-m2 [gj]
  (when (and (map? gj) (= "MultiPolygon" (str (:type gj))))
    (when (sequential? (:coordinates gj))
      (let [parts (keep (fn [rings]
                          (polygon-area-m2 {:type "Polygon" :coordinates rings}))
                        (:coordinates gj))
            a (reduce + 0.0 parts)]
        (when (pos? a) a)))))

(defn area-m2 [gj]
  (or (polygon-area-m2 gj)
      (multipolygon-area-m2 gj)))

(defn area-ha [m2]
  (/ (Math/round (* (/ (double m2) 10000.0) 100.0)) 100.0))

(defn area-m2-int [m2]
  (long (Math/round (double m2))))

(defn valid-shape? [gj]
  (boolean (area-m2 gj)))

(defn- geom-type [gj]
  (when (map? gj)
    (str (:type gj))))

(defn extract-polygons [gj]
  (let [t (geom-type gj)]
    (cond
      (= t "Polygon")
      (if (valid-shape? gj) [gj] [])

      (= t "MultiPolygon")
      (if (sequential? (:coordinates gj))
        (vec (keep (fn [rings]
                     (let [p {:type "Polygon" :coordinates rings}]
                       (when (valid-shape? p) p)))
                   (:coordinates gj)))
        [])

      (= t "Feature")
      (let [ps (extract-polygons (:geometry gj))
            n (get-in gj [:properties :name])]
        (mapv (fn [p] (if (and n (not (str/blank? (str n))))
                        (assoc p :name (str n))
                        p))
              ps))

      (= t "FeatureCollection")
      (vec (mapcat extract-polygons (or (:features gj) [])))

      (= t "GeometryCollection")
      (vec (mapcat extract-polygons (or (:geometries gj) [])))

      :else
      [])))

(defn- ring->coords [ring]
  (mapv (fn [c] [(.getX c) (.getY c)]) (.getCoordinates ring)))

(defn- polygon->gj [^Polygon p]
  {:type "Polygon"
   :coordinates (into [(ring->coords (.getExteriorRing p))]
                      (map ring->coords
                           (map #(.getInteriorRingN p %) (range (.getNumInteriorRing p)))))})

(defn- jts->gj [g]
  (cond
    (instance? Polygon g)
    (polygon->gj g)

    (instance? MultiPolygon g)
    {:type "MultiPolygon"
     :coordinates (mapv (fn [i]
                          (:coordinates (polygon->gj (.getGeometryN g i))))
                        (range (.getNumGeometries g)))}

    :else nil))

(defn- coords->ring [coords]
  (when-let [pts (ring-points coords)]
    (let [arr (into-array Coordinate (map (fn [[x y]] (Coordinate. x y)) pts))]
      (.createLinearRing gf arr))))

(defn- gj->jts [gj]
  (let [t (geom-type gj)]
    (cond
      (= t "Polygon")
      (when-let [rings (:coordinates gj)]
        (when-let [shell (coords->ring (first rings))]
          (let [holes (into-array LinearRing (keep coords->ring (rest rings)))]
            (.createPolygon gf shell holes))))

      (= t "MultiPolygon")
      (when (sequential? (:coordinates gj))
        (let [ps (into-array Polygon
                             (keep (fn [rings] (gj->jts {:type "Polygon" :coordinates rings}))
                                   (:coordinates gj)))]
          (when (pos? (alength ps))
            (.createMultiPolygon gf ps))))

      :else nil)))

(defn union-shapes [gjs]
  (let [geoms (keep gj->jts gjs)]
    (when (seq geoms)
      (jts->gj (reduce (fn [a b] (.union a b)) geoms)))))

(defn as-number [x]
  (cond
    (number? x) (double x)
    (string? x) (try (Double/parseDouble (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn as-int [x]
  (cond
    (integer? x) (long x)
    (number? x) (long x)
    (string? x) (try (Long/parseLong (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn valid-bbox? [west south east north]
  (and (every? number? [west south east north])
       (< west east)
       (< south north)
       (lon-lat? [west south])
       (lon-lat? [east north])))

(defn to-json [gj]
  (json/write-str (dissoc gj :name)))
