(ns isas.b2-test
  "基本試験仕様書_工程2 の項番に対応する自動試験。経路名は見ない。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.geo :as geo]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(deftest b2-blocks
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "b2@example.com")
            usid (tu/user-sid app "b2@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "b2@example.com"))
            place {:west 139.0 :south 35.0 :east 141.0 :north 37.0}]
        (testing "B2-2-01 作業場所と下地は利用者に1つ"
          (is (= "place_unset" (:code (fields/get-place sys uid))))
          (is (true? (:ok (fields/put-place sys uid place))))
          (is (= 1 (count (filter identity [(db/find-place (:ds sys) uid)]))))
          (let [st (fields/list-basemap-status sys uid)]
            (is (= 3 (count (:basemaps st))))))
        (testing "B2-2-02 台帳と地図の編集はパソコン"
          (is (re-find #"手描き" (html {:page :map :place place})))
          (is (re-find #"削除" (html {:page :fields :fields [{:id 1 :name "北" :area_ha 1 :area_m2 10000}]}))))
        (testing "B2-2-03 / B2-2-04 幅"
          (is (re-find #"圃場台帳" (html {:page :fields :fields []})))
          (is (re-find #"パソコンで開いてください" (html {:page :map :narrow? true})))
          (is (not (re-find #"手描き" (html {:page :map :narrow? true})))))
        (testing "B2-2-05 ガント・指示・言語切替は出さない"
          (is (not (re-find #"ガント|指示" (html {:page :map :place place}))))
          (is (nil? (http/match-api :get "/api/user/gantt"))))
        (testing "B2-2-06 管理者は圃場を持たない"
          (is (= 403 (:status (tu/get-path app "/api/user/fields" "admin" asid))))
          (is (not (re-find #"href=\"/fields\"" (html {:page :home :kind "admin"})))))
        (testing "B2-3-01 0枚でも場所と台帳は開ける"
          (is (true? (:ok (fields/get-place sys uid))))
          (is (empty? (remove #(= "残" (:name %)) (:fields (fields/list-fields sys uid)))))
          (is (re-find #"圃場台帳" (html {:page :fields :fields []}))))
        (testing "B2-4.2 作業場所"
          (is (not (re-find #"地名検索" (html {:page :map-place}))))
          (is (not (re-find #"cyberjapandata|openstreetmap" (html {:page :map :place place}))))
          (is (re-find #"先に作業場所の範囲を決めてください" (html {:page :map})))
          (is (true? (:ok (accounts/login sys "user" "b2@example.com" pw)))))
        (testing "B2-4.2-02〜04 下地3種。場所を変えたら古い下地は使わない"
          (let [img {:filename "a.jpg" :content-type "image/jpeg"
                     :tempfile (io/file (tu/temp-file "bmap" ".jpg" "x"))}]
            (is (true? (:ok (fields/put-basemap sys uid "aerial" img))))
            (is (true? (:ok (fields/put-basemap sys uid "standard" img))))
            (is (true? (:ok (fields/put-basemap sys uid "satellite" img))))
            (is (true? (:ok (fields/put-place sys uid {:west 138.0 :south 34.0 :east 140.0 :north 36.0}))))
            (is (= "basemap_missing" (:code (fields/get-basemap sys uid "aerial"))))))
        (testing "B2-4.3 圃場"
          (let [c1 (fields/create-field sys uid {:name "北" :geojson tu/square})
                c2 (fields/create-field sys uid {:name "北" :geojson tu/square-east})]
            (is (true? (:ok c1)))
            (is (true? (:ok c2)))
            (is (number? (get-in c1 [:field :area_ha])))
            (is (integer? (get-in c1 [:field :area_m2])))
            (is (= "shape_not_area" (:code (fields/create-field sys uid {:name "点" :geojson {:type "Point" :coordinates [0 0]}}))))
            (let [id (get-in c1 [:field :id])
                  sp (fields/split-field sys uid id {:polygons [tu/square tu/square-east]})]
              (is (true? (:ok sp)))
              (is (re-find #"仮-" (get-in sp [:fields 0 :name]))))
            (let [listed (:fields (fields/list-fields sys uid))
                  a (:id (first listed))
                  b (:id (second listed))
                  keep-name (:name (first listed))
                  mg (fields/merge-fields sys uid {:keep_id a :ids [a b]})]
              (is (true? (:ok mg)))
              (is (= keep-name (get-in mg [:field :name])))
              (is (true? (:ok (fields/delete-field sys uid a))))))
          (is (not (re-find #"作物|地番" (html {:page :fields :fields []})))))
        (testing "B2-4.3-06 工程2は塗りの有無を見ない"
          (let [c (fields/create-field sys uid {:name "割" :geojson tu/square})
                id (get-in c [:field :id])]
            (is (true? (:ok (fields/split-field sys uid id {:polygons [tu/square tu/square-east]}))))))
        (testing "B2-5.3 / B2-5.4"
          (is (re-find #"空中写真|標準地図|衛星" (html {:page :map :place place
                                                      :basemaps [{:kind "aerial" :ready true}]})))
          (is (not (re-find #"ガント" (html {:page :map :place place}))))
          (is (not (re-find #"下地を取り込む" (html {:page :map :narrow? true})))))
        (testing "B2-6 データ単位"
          (let [p (db/find-place (:ds sys) uid)
                names (tu/table-names (:ds sys))]
            (is (every? #(contains? p %) [:west :south :east :north]))
            (is (nil? (:place_name p)))
            (is (contains? names "work_places"))
            (is (contains? names "basemaps"))
            (is (contains? names "fields"))
            (is (not (contains? names "gantt_rows")))))
        (testing "B2-7.2 流れ"
          (is (true? (:ok (fields/put-place sys uid place))))
          (is (re-find #"この範囲を作業場所にする|空中写真|手描き" (html {:page :map :place place})))
          (is (true? (:ok (fields/create-field sys uid {:name "手" :geojson tu/square})))))
        (testing "B2-8 外部"
          (is (not (re-find #"農地ナビ|iframe" (html {:page :map :place place}))))
          (let [fc {:type "FeatureCollection"
                    :features [{:type "Feature" :geometry tu/square}
                               {:type "Feature" :geometry tu/square-east}]}
                r (fields/import-geojson sys uid {:body (geo/to-json fc)})]
            (is (= 2 (count (:fields r))))))
        (testing "B2-9 / B2-10"
          (is (nil? (http/match-api :post "/api/user/offline")))
          (is (nil? (http/match-api :get "/api/user/gantt"))))))))
