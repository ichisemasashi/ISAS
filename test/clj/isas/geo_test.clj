(ns isas.geo-test
  (:require [clojure.test :refer [deftest is]]
            [isas.geo :as geo])
  (:import [org.locationtech.jts.geom Coordinate Geometry GeometryFactory]))

(def square
  {:type "Polygon"
   :coordinates [[[140.0 36.0]
                  [140.001 36.0]
                  [140.001 36.001]
                  [140.0 36.001]
                  [140.0 36.0]]]})

(def square-east
  {:type "Polygon"
   :coordinates [[[140.002 36.0]
                  [140.003 36.0]
                  [140.003 36.001]
                  [140.002 36.001]
                  [140.002 36.0]]]})

(deftest parse-and-keywordize-test
  (is (nil? (geo/parse-json "{")))
  (is (nil? (geo/parse-json nil)))
  (is (= "" (geo/strip-bom "")))
  (is (= "{\"a\":1}" (geo/strip-bom "{\"a\":1}")))
  (is (= {:type "X"} (geo/parse-json (str "\uFEFF{\"type\":\"X\"}"))))
  (is (= {:type "X"} (geo/keywordize {"type" "X"})))
  (is (= {:a 1} (geo/keywordize {:a 1})))
  (is (= [{:k 1}] (geo/keywordize [{"k" 1}])))
  (is (= 3 (geo/keywordize 3))))

(deftest pair-and-ring-test
  (is (nil? (#'geo/pair "no")))
  (is (nil? (#'geo/pair [1])))
  (is (nil? (#'geo/pair ["a" "b"])))
  (is (= [140.0 36.0] (#'geo/pair [140 36])))
  (is (true? (geo/lon-lat? [140.0 36.0])))
  (is (false? (geo/lon-lat? [200.0 36.0])))
  (is (false? (geo/lon-lat? [140.0 100.0])))
  (is (true? (#'geo/projected? [[400000 3000000]])))
  (is (false? (#'geo/projected? [[140 36]])))
  (is (false? (#'geo/projected? [["a" "b"]])))
  (is (empty? (geo/ensure-closed [])))
  (is (= 5 (count (geo/ensure-closed [[140 36] [141 36] [141 37] [140 37]]))))
  (is (= 5 (count (geo/ensure-closed [[140 36] [141 36] [141 37] [140 37] [140 36]]))))
  (is (nil? (#'geo/ring-points [[140 36] [141 36]])))
  (is (nil? (#'geo/ring-points [[400000 3000000] [400010 3000000] [400010 3000010] [400000 3000010] [400000 3000000]])))
  (is (nil? (#'geo/ring-area-m2 [[140 36]])))
  (is (pos? (#'geo/ring-area-m2 (first (:coordinates square))))))

(deftest area-test
  (is (nil? (#'geo/polygon-coords {:type "LineString"})))
  (is (nil? (#'geo/polygon-area-m2 {:type "Polygon" :coordinates "no"})))
  (is (nil? (#'geo/polygon-area-m2 {:type "Polygon" :coordinates [[[140 36]]]})))
  (is (nil? (#'geo/polygon-area-m2 {:type "Polygon"
                                    :coordinates [(first (:coordinates square))
                                                  (first (:coordinates square))]})))
  (let [with-hole {:type "Polygon"
                   :coordinates [(first (:coordinates square))
                                 [[140.0002 36.0002]
                                  [140.0003 36.0002]
                                  [140.0003 36.0003]
                                  [140.0002 36.0003]
                                  [140.0002 36.0002]]]}]
    (is (pos? (#'geo/polygon-area-m2 with-hole)))
    (is (< (#'geo/polygon-area-m2 with-hole) (geo/area-m2 square))))
  (is (nil? (#'geo/multipolygon-area-m2 {:type "Polygon"})))
  (is (nil? (#'geo/multipolygon-area-m2 {:type "MultiPolygon" :coordinates "no"})))
  (is (nil? (#'geo/multipolygon-area-m2 {:type "MultiPolygon" :coordinates [[[ [140 36] ]]]})))
  (let [mp {:type "MultiPolygon" :coordinates [(:coordinates square) (:coordinates square-east)]}]
    (is (pos? (geo/area-m2 mp)))
    (is (true? (geo/valid-shape? mp))))
  (is (pos? (geo/area-m2 square)))
  (is (false? (geo/valid-shape? {:type "Point" :coordinates [140 36]})))
  (is (= 1.23 (geo/area-ha 12321.0)))
  (is (= 12321 (geo/area-m2-int 12321.4)))
  (is (nil? (#'geo/geom-type "x"))))

(deftest extract-polygons-test
  (is (= [square] (geo/extract-polygons square)))
  (is (= [] (geo/extract-polygons {:type "Polygon" :coordinates [[[140 36]]]})))
  (is (= [] (geo/extract-polygons {:type "MultiPolygon" :coordinates "no"})))
  (is (= 2 (count (geo/extract-polygons {:type "MultiPolygon"
                                         :coordinates [(:coordinates square) (:coordinates square-east)]}))))
  (is (= [] (geo/extract-polygons {:type "Feature" :geometry {:type "Point" :coordinates [1 2]}})))
  (is (= "A" (:name (first (geo/extract-polygons {:type "Feature"
                                                  :geometry square
                                                  :properties {:name "A"}})))))
  (is (nil? (:name (first (geo/extract-polygons {:type "Feature"
                                                 :geometry square
                                                 :properties {:name "  "}})))))
  (is (= 1 (count (geo/extract-polygons {:type "Feature" :geometry square}))))
  (is (= 2 (count (geo/extract-polygons {:type "FeatureCollection"
                                         :features [{:type "Feature" :geometry square}
                                                    {:type "Feature" :geometry square-east}]}))))
  (is (= [] (geo/extract-polygons {:type "FeatureCollection"})))
  (is (= 1 (count (geo/extract-polygons {:type "GeometryCollection"
                                         :geometries [square {:type "Point" :coordinates [0 0]}]}))))
  (is (= [] (geo/extract-polygons {:type "GeometryCollection"})))
  (is (= [] (geo/extract-polygons {:type "LineString" :coordinates [[0 0] [1 1]]})))
  (is (= [] (geo/extract-polygons "no"))))

(deftest jts-roundtrip-test
  (is (nil? (#'geo/coords->ring [[140 36]])))
  (is (nil? (#'geo/gj->jts {:type "LineString"})))
  (is (nil? (#'geo/gj->jts {:type "Polygon" :coordinates [[[140 36]]]})))
  (is (nil? (#'geo/gj->jts {:type "MultiPolygon" :coordinates []})))
  (is (nil? (#'geo/gj->jts {:type "MultiPolygon" :coordinates "no"})))
  (let [with-hole {:type "Polygon"
                   :coordinates [(first (:coordinates square))
                                 [[140.0002 36.0002]
                                  [140.0003 36.0002]
                                  [140.0003 36.0003]
                                  [140.0002 36.0003]
                                  [140.0002 36.0002]]]}
        g (#'geo/gj->jts with-hole)
        back (#'geo/polygon->gj g)]
    (is (= "Polygon" (:type back)))
    (is (= 2 (count (:coordinates back)))))
  (let [mp {:type "MultiPolygon" :coordinates [(:coordinates square) (:coordinates square-east)]}
        g (#'geo/gj->jts mp)
        back (#'geo/jts->gj g)]
    (is (= "MultiPolygon" (:type back)))
    (is (= 2 (count (:coordinates back)))))
  (is (nil? (#'geo/jts->gj (.createPoint (GeometryFactory.) (Coordinate. 1.0 2.0)))))
  (is (nil? (geo/intersect-shapes nil square)))
  (is (nil? (geo/intersect-shapes {:type "Point" :coordinates [140 36]} square)))
  (is (nil? (geo/intersect-shapes square square-east)))
  (is (geo/valid-shape? (geo/intersect-shapes square
                                             {:type "Polygon"
                                              :coordinates [[[140.0002 36.0002]
                                                             [140.0005 36.0002]
                                                             [140.0005 36.0005]
                                                             [140.0002 36.0005]
                                                             [140.0002 36.0002]]]})))
  ;; self-intersecting brush stroke (bowtie) overlapping the field must still clip
  (is (geo/valid-shape? (geo/intersect-shapes square
                                             {:type "Polygon"
                                              :coordinates [[[140.0002 36.0002]
                                                             [140.0005 36.0005]
                                                             [140.0002 36.0005]
                                                             [140.0005 36.0002]
                                                             [140.0002 36.0002]]]})))
  (let [gf (GeometryFactory.)
        empty-poly (.createPolygon gf nil nil)]
    (with-redefs [isas.geo/buffer0 (constantly empty-poly)]
      (is (nil? (#'geo/fix-geom
                 (#'geo/gj->jts {:type "Polygon"
                                 :coordinates [[[140.0002 36.0002]
                                                [140.0005 36.0005]
                                                [140.0002 36.0005]
                                                [140.0005 36.0002]
                                                [140.0002 36.0002]]]})))))
    (with-redefs [isas.geo/buffer0 (constantly nil)]
      (is (nil? (#'geo/fix-geom
                 (#'geo/gj->jts {:type "Polygon"
                                 :coordinates [[[140.0002 36.0002]
                                                [140.0005 36.0005]
                                                [140.0002 36.0005]
                                                [140.0005 36.0002]
                                                [140.0002 36.0002]]]}))))))
  (let [gf (GeometryFactory.)
        p (#'geo/gj->jts square)
        pe (#'geo/gj->jts square-east)
        pt (.createPoint gf (Coordinate. 140.0 36.0))
        empty (.intersection p pe)
        mp (#'geo/gj->jts {:type "MultiPolygon" :coordinates [(:coordinates square)]})
        gc1 (.createGeometryCollection gf (into-array Geometry [p]))
        gc2 (.createGeometryCollection gf (into-array Geometry [p pe]))
        gc0 (.createGeometryCollection gf (make-array Geometry 0))
        gcp (.createGeometryCollection gf (into-array Geometry [pt]))]
    (is (some? (#'geo/polygonal-geom p)))
    (is (some? (#'geo/polygonal-geom mp)))
    (is (nil? (#'geo/polygonal-geom nil)))
    (is (nil? (#'geo/polygonal-geom pt)))
    (is (nil? (#'geo/polygonal-geom empty)))
    (is (some? (#'geo/polygonal-geom gc1)))
    (is (some? (#'geo/polygonal-geom gc2)))
    (is (nil? (#'geo/polygonal-geom gc0)))
    (is (nil? (#'geo/polygonal-geom gcp))))
  (is (nil? (geo/union-shapes [])))
  (is (nil? (geo/union-shapes [{:type "Point" :coordinates [0 0]}])))
  (let [u (geo/union-shapes [square square-east])]
    (is (geo/valid-shape? u))
    (is (#{"Polygon" "MultiPolygon"} (:type u))))
  (let [u (geo/union-shapes [square square])]
    (is (= "Polygon" (:type u))))
  (is (nil? (geo/split-shape square [1 2 3])))
  (is (nil? (geo/split-shape square {:type "Point" :coordinates [140 36]})))
  (is (nil? (geo/split-shape square {:type "LineString" :coordinates [[140 36]]})))
  (is (nil? (geo/split-shape square {:type "LineString" :coordinates [[400000 3000000] [400010 3000010]]})))
  (is (nil? (geo/split-shape square {:type "LineString" :coordinates "no"})))
  (is (nil? (geo/split-shape {:type "Point" :coordinates [140 36]} {:type "LineString" :coordinates [[140 36] [141 36]]})))
  (is (> 2 (count (or (geo/split-shape square {:type "LineString" :coordinates [[139 35] [139 36]]}) []))))
  (let [cut (geo/split-shape square {:type "LineString"
                                     :coordinates [[140.0005 35.999] [140.0005 36.002]]})]
    (is (<= 2 (count cut)))
    (is (every? geo/valid-shape? cut)))
  (let [cut (geo/split-shape square "{\"type\":\"LineString\",\"coordinates\":[[140.0005,35.999],[140.0005,36.002]]}")]
    (is (<= 2 (count cut)))))

(deftest numbers-bbox-json-test
  (is (= 1.5 (geo/as-number 1.5)))
  (is (= 2.0 (geo/as-number " 2 ")))
  (is (nil? (geo/as-number "x")))
  (is (nil? (geo/as-number :no)))
  (is (= 3 (geo/as-int 3)))
  (is (= 4 (geo/as-int 4.2)))
  (is (= 5 (geo/as-int "5")))
  (is (nil? (geo/as-int "x")))
  (is (nil? (geo/as-int :no)))
  (is (true? (geo/valid-bbox? 129.0 26.0 146.0 46.0)))
  (is (false? (geo/valid-bbox? 146.0 26.0 129.0 46.0)))
  (is (false? (geo/valid-bbox? 129.0 46.0 146.0 26.0)))
  (is (false? (geo/valid-bbox? 200.0 26.0 201.0 46.0)))
  (is (false? (geo/valid-bbox? nil 26.0 146.0 46.0)))
  (let [s (geo/to-json (assoc square :name "x"))]
    (is (re-find #"Polygon" s))
    (is (not (re-find #"\"name\"" s)))))
