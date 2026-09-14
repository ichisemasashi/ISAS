(ns isas.fields-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.geo :as geo]
            [isas.test-util :as tu]))

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

(defn- user-id [sys email]
  (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")]
    (accounts/invite sys "admin" (:id admin) email)
    (:id (db/find-user-by-email (:ds sys) email))))

(defn- img [suffix content-type]
  (let [f (io/file (tu/temp-file "bmap" suffix "fake-image"))]
    {:filename (str "x" suffix)
     :content-type content-type
     :tempfile f}))

(deftest temp-names-and-upload-map-test
  (is (= ["仮-1" "仮-2"] (fields/next-temp-names [] 2)))
  (is (= ["仮-2" "仮-4"] (fields/next-temp-names ["仮-1" "n" "仮-3"] 2)))
  (is (true? (fields/kind-ok? "aerial")))
  (is (false? (fields/kind-ok? "other")))
  (is (nil? (#'fields/upload->map nil)))
  (is (nil? (#'fields/upload->map "no")))
  (is (= {:a 1} (#'fields/upload->map {:a 1})))
  (is (= {:a 1} (#'fields/upload->map {"a" 1})))
  (is (= {:x 1} (#'fields/upload->map {'x 1})))
  (is (= {:1 1} (#'fields/upload->map {1 1})))
  (is (= "data/basemaps" (fields/basemap-root {})))
  (is (= "tmp" (fields/basemap-root {:basemap-dir "tmp"})))
  (is (= {:content-type "image/jpeg" :ext ".jpg"} (fields/detect-image {:content-type "image/jpeg"})))
  (is (= {:content-type "image/jpeg" :ext ".jpg"} (fields/detect-image {:filename "A.JPEG"})))
  (is (= {:content-type "image/jpeg" :ext ".jpg"} (fields/detect-image {:filename "a.jpg"})))
  (is (= {:content-type "image/png" :ext ".png"} (fields/detect-image {:content-type "image/png"})))
  (is (= {:content-type "image/png" :ext ".png"} (fields/detect-image {:filename "a.PNG"})))
  (is (nil? (fields/detect-image {:filename "a.gif" :content-type "image/gif"})))
  (is (nil? (fields/detect-image nil)))
  (let [f (io/file (tu/temp-file "tmp" ".bin" "x"))]
    (is (= f (#'fields/tempfile {:tempfile f})))
    (is (= f (#'fields/tempfile {:temp-file f})))
    (is (= [] (#'fields/list-dir (io/file (tu/temp-file "notdir" ".txt" "x")))))
    (is (nil? (#'fields/tempfile {})))))

(deftest place-and-basemap-test
  (tu/with-sys
    (fn [sys]
      (let [uid (user-id sys "p@example.com")]
        (is (= "place_unset" (:code (fields/get-place sys uid))))
        (is (= "place_invalid" (:code (fields/put-place sys uid {:west 146 :south 26 :east 129 :north 46}))))
        (is (true? (:ok (fields/put-place sys uid {:west "139.5" :south "35.5" :east "140.5" :north "36.5"}))))
        (is (true? (:ok (fields/get-place sys uid))))
        (is (nil? (:image_west (fields/get-place sys uid))))
        (is (= "place_invalid" (:code (fields/put-image-extent sys uid {:west 140 :south 35 :east 139 :north 36}))))
        (is (true? (:ok (fields/put-image-extent sys uid {:west "139.6" :south "35.6" :east "140.4" :north "36.4"}))))
        (is (= 139.6 (:image_west (fields/get-place sys uid))))
        (is (true? (:ok (fields/put-image-extent sys uid {:reset true}))))
        (is (nil? (:image_west (fields/get-place sys uid))))
        (is (true? (:ok (fields/put-image-extent sys uid {:reset "true"}))))
        (is (not (some :ready (:basemaps (fields/list-basemap-status sys uid)))))
        (is (= "basemap_kind" (:code (fields/put-basemap sys uid "nope" (img ".jpg" "image/jpeg")))))
        (is (= "basemap_kind" (:code (fields/get-basemap sys uid "nope"))))
        (is (= "basemap_missing" (:code (fields/get-basemap sys uid "aerial"))))
        (is (= "import_invalid" (:code (fields/put-basemap sys uid "aerial" {:filename "a.jpg"}))))
        (is (= "import_invalid" (:code (fields/put-basemap sys uid "aerial" {:filename "a.gif" :content-type "image/gif" :tempfile (io/file (tu/temp-file "gif" ".gif" "x"))}))))
        (let [missing {:filename "a.jpg" :content-type "image/jpeg" :tempfile (io/file "/no/such/file.jpg")}]
          (is (= "import_invalid" (:code (fields/put-basemap sys uid "aerial" missing)))))
        (is (true? (:ok (fields/put-basemap sys uid "aerial" (img ".jpg" "image/jpeg")))))
        (is (true? (:ok (fields/put-basemap sys uid "standard" (img ".png" "image/png")))))
        (is (true? (:ok (fields/put-basemap sys uid "satellite" (img ".jpeg" "image/jpeg")))))
        (is (true? (:ready (first (filter #(= "aerial" (:kind %)) (:basemaps (fields/list-basemap-status sys uid)))))))
        (let [got (fields/get-basemap sys uid "aerial")]
          (is (true? (:ok got)))
          (is (= "image/jpeg" (:content-type got)))
          (is (.isFile (:file got))))
        (let [row (db/find-basemap (:ds sys) uid "aerial")
              f (io/file (fields/basemap-root sys) (:body_ref row))]
          (.delete f)
          (is (= "basemap_missing" (:code (fields/get-basemap sys uid "aerial")))))
        (is (true? (:ok (fields/put-image-extent sys uid {:west 139.7 :south 35.7 :east 140.3 :north 36.3}))))
        (is (true? (:ok (fields/put-place sys uid {:west 139.0 :south 35.0 :east 140.0 :north 36.0}))))
        (is (nil? (:image_west (fields/get-place sys uid))))
        (is (= "basemap_missing" (:code (fields/get-basemap sys uid "standard"))))
        (fields/delete-basemap-files sys uid)
        (let [dir (io/file (fields/basemap-root sys) (str uid))]
          (.mkdirs dir)
          (spit (io/file dir "keep.txt") "x")
          (.mkdirs (io/file dir "sub"))
          (fields/delete-basemap-files sys uid)
          (is (false? (.exists (io/file dir "keep.txt")))))
        (let [dir (io/file (fields/basemap-root sys) (str uid))]
          (.mkdirs dir)
          (fields/delete-basemap-files sys uid)
          (is (false? (.isDirectory dir))))))))

(deftest place-unset-basemap-test
  (tu/with-sys
    (fn [sys]
      (let [uid (user-id sys "q@example.com")]
        (is (= "place_unset" (:code (fields/put-basemap sys uid "aerial" (img ".jpg" "image/jpeg")))))
        (is (= "place_unset" (:code (fields/put-image-extent sys uid {:west 1 :south 2 :east 3 :north 4}))))
        (is (= "basemap_kind" (:code (fields/put-basemap-bytes sys uid "nope" (byte-array [1]) "image/jpeg"))))
        (is (= "place_unset" (:code (fields/put-basemap-bytes sys uid "aerial" (byte-array [1]) "image/jpeg"))))
        (fields/put-place sys uid {:west 139.0 :south 35.0 :east 141.0 :north 37.0})
        (is (= "import_invalid" (:code (fields/put-basemap-bytes sys uid "aerial" nil "image/jpeg"))))
        (is (= "import_invalid" (:code (fields/put-basemap-bytes sys uid "aerial" (byte-array 0) "image/jpeg"))))
        (is (true? (:ok (fields/put-basemap-bytes sys uid "aerial" (byte-array [1 2 3]) "image/jpeg"))))
        (is (true? (:ok (fields/put-basemap-bytes sys uid "standard" (byte-array [1 2 3]) "image/png"))))
        (is (true? (:ok (fields/put-basemap-bytes sys uid "satellite" (byte-array [1 2 3]) nil))))))))

(deftest field-crud-split-merge-import-test
  (tu/with-sys
    (fn [sys]
      (let [uid (user-id sys "f@example.com")
            uid2 (user-id sys "g@example.com")]
        (is (= [] (:fields (fields/list-fields sys uid))))
        (is (= "shape_not_area" (:code (fields/create-field sys uid {:name "x" :geojson {:type "Point" :coordinates [0 0]}}))))
        (is (= "shape_not_area" (:code (fields/create-field sys uid {:name "x" :geojson "{"}))))
        (let [c0 (fields/create-field sys uid {:geojson square})]
          (is (re-find #"仮-" (get-in c0 [:field :name]))))
        (let [c1 (fields/create-field sys uid {:name "北" :geojson square})
              c2 (fields/create-field sys uid {:name "" :geojson (geo/to-json square-east)})]
          (is (true? (:ok c1)))
          (is (re-find #"仮-" (get-in c2 [:field :name])))
          (is (number? (get-in c1 [:field :area_ha])))
          (is (integer? (get-in c1 [:field :area_m2])))
          (let [id (get-in c1 [:field :id])
                id2 (get-in c2 [:field :id])]
            (is (= "field_not_found" (:code (fields/update-field sys uid "x" {:name "a"}))))
            (is (= "field_not_found" (:code (fields/update-field sys uid2 id {:name "a"}))))
            (is (true? (:ok (fields/update-field sys uid id {:name "  "}))))
            (is (= "北" (get-in (fields/update-field sys uid id {}) [:field :name])))
            (is (true? (:ok (fields/update-field sys uid id {:name "北2"}))))
            (is (= "shape_not_area" (:code (fields/update-field sys uid id {:geojson {:type "Point" :coordinates [1 1]}}))))
            (is (true? (:ok (fields/update-field sys uid id {:geojson (geo/to-json square)}))))
            (is (= "field_not_found" (:code (fields/delete-field sys uid 99999))))
            (is (= "field_not_found" (:code (fields/split-field sys uid 99999 {:polygons [square square-east]}))))
            (is (= "split_too_few" (:code (fields/split-field sys uid id {}))))
            (is (= "split_too_few" (:code (fields/split-field sys uid id {:polygons [square]}))))
            (let [line-id (get-in (fields/create-field sys uid {:name "割線" :geojson square}) [:field :id])]
              (is (= "split_too_few" (:code (fields/split-field sys uid line-id {:line {:type "LineString" :coordinates [[139 35] [139 36]]}}))))
              (let [line-sp (fields/split-field sys uid line-id {:line {:type "LineString"
                                                                       :coordinates [[140.0005 35.999] [140.0005 36.002]]}})]
                (is (true? (:ok line-sp)))
                (is (<= 2 (count (:fields line-sp))))
                (is (re-find #"仮-" (get-in line-sp [:fields 0 :name])))))
            (let [sp (fields/split-field sys uid id {:polygons [(geo/to-json square) square-east]})]
              (is (true? (:ok sp)))
              (is (= 2 (count (:fields sp))))
              (is (re-find #"仮-" (get-in sp [:fields 0 :name]))))
            (is (= "merge_too_few" (:code (fields/merge-fields sys uid {:keep_id id2 :ids [id2]}))))
            (is (= "merge_keep_missing" (:code (fields/merge-fields sys uid {:keep_id 999999 :ids [id2 888888]}))))
            (let [listed (:fields (fields/list-fields sys uid))
                  a (:id (first listed))
                  b (:id (second listed))]
              (is (= "field_not_found" (:code (fields/merge-fields sys uid {:keep_id a :ids [a 99999]}))))
              (with-redefs [geo/union-shapes (fn [_] {:type "Point" :coordinates [0 0]})]
                (is (= "shape_not_area" (:code (fields/merge-fields sys uid {:keep_id a :ids [a b]})))))
              (let [mg (fields/merge-fields sys uid {:keep_id a :ids [a b]})]
                (is (true? (:ok mg)))
                (is (= a (get-in mg [:field :id]))))
              (is (true? (:ok (fields/delete-field sys uid a)))))
            (is (= "import_invalid" (:code (fields/import-geojson sys uid nil))))
            (is (= "import_invalid" (:code (fields/import-geojson sys uid {:body "{"}))))
            (let [fc {:type "FeatureCollection"
                      :features [{:type "Feature" :geometry square :properties {:name "取込A"}}
                                 {:type "Feature" :geometry square-east}
                                 {:type "Feature" :geometry {:type "Point" :coordinates [140 36]}}]}
                  t (io/file (tu/temp-file "imp" ".geojson" (geo/to-json fc)))
                  r (fields/import-geojson sys uid {:filename "a.geojson" :tempfile t})]
              (is (true? (:ok r)))
              (is (= 2 (count (:fields r))))
              (is (= "取込A" (get-in r [:fields 0 :name])))
              (is (re-find #"仮-" (get-in r [:fields 1 :name]))))
            (let [bom (str "\uFEFF" (geo/to-json {:type "FeatureCollection"
                                                  :features [{:type "Feature" :geometry square}]}))
                  r (fields/import-geojson sys uid {:body bom})]
              (is (true? (:ok r))))
            (let [r (fields/import-geojson sys uid {:body (geo/to-json square)})]
              (is (true? (:ok r))))
            (let [r (fields/import-geojson sys uid {:bytes (.getBytes (geo/to-json square-east) "UTF-8")})]
              (is (true? (:ok r))))
            (db/insert-field! (:ds sys) {:user-id uid :name "壊" :geojson "{"})
            (let [broken (first (filter #(= "壊" (:name %)) (:fields (fields/list-fields sys uid))))]
              (is (= 0 (:area_m2 broken)))
              (is (= 0.0 (:area_ha broken))))))))))
