(ns isas.p2-test
  "詳細試験仕様書_工程2 の項番に対応する自動試験。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.geo :as geo]
            [isas.http :as http]
            [isas.test-util :as tu]
            [isas.ui :as ui]
            [ring.mock.request :as mock]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "farm@example.com")
        usid (tu/user-sid app "farm@example.com" pw)
        pw2 (tu/invite-pw app asid "other@example.com")
        usid2 (tu/user-sid app "other@example.com" pw2)
        uid (:id (db/find-user-by-email (:ds sys) "farm@example.com"))]
    {:app app :asid asid :usid usid :usid2 usid2 :uid uid :pw pw}))

(deftest p2-routes-and-api
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid usid2 uid]} (farm sys)]
        (testing "P2-2.1-01〜03 台帳経路"
          (is (= :fields (:page (ui/route-for "/fields"))))
          (is (re-find #"圃場台帳" (html {:page :fields :fields []})))
          (is (re-find #"<th>名前</th>" (html {:page :fields :fields []})))
          (is (re-find #"<th>ha</th>" (html {:page :fields :fields []})))
          (is (re-find #"削除" (html {:page :fields :fields [{:id 1 :name "北" :area_ha 1.2 :area_m2 12000}]})))
          (is (= "/" (second (first (:fx (ui/guarded (assoc (ui/init-state) :page :fields :kind "user"))))))))
        (testing "P2-2.1-04〜06 地図経路"
          (is (= :map (:page (ui/route-for "/map"))))
          (is (= :map-place (:page (ui/route-for "/map/place"))))
          (is (re-find #"地図" (html {:page :map :place {:west 139 :south 35 :east 141 :north 37}})))
          (is (re-find #"手描き" (html {:page :map :place {:west 139 :south 35 :east 141 :north 37}})))
          (let [unset (html {:page :map})]
            (is (re-find #"先に作業場所の範囲を決めてください" unset))
            (is (re-find #"この範囲を作業場所にする" unset)))
          (is (re-find #"先に作業場所の範囲を決めてください" (html {:page :map-place}))))
        (testing "P2-2.1-07 利用者ホームに出口"
          (is (re-find #"href=\"/fields\"" (html {:page :home})))
          (is (re-find #"href=\"/map\"" (html {:page :home}))))
        (testing "P2-2.1-08〜09 管理者に工程2は無い"
          (is (not (re-find #"href=\"/fields\"|href=\"/map\"" (html {:page :home :kind "admin"}))))
          (is (= 403 (:status (tu/get-path app "/api/user/place" "admin" asid))))
          (is (= 403 (:status (tu/get-path app "/api/user/fields" "admin" asid)))))
        (testing "P2-2.2-01〜04 作業場所"
          (is (= "place_unset" (:code (tu/parse (tu/get-path app "/api/user/place" "user" usid)))))
          (is (= "place_invalid" (:code (tu/parse (tu/put-json app "/api/user/place" {:west 1} "user" usid)))))
          (is (true? (:ok (tu/parse (tu/put-json app "/api/user/place"
                                                {:west 139.0 :south 35.0 :east 141.0 :north 37.0}
                                                "user" usid)))))
          (is (true? (:ok (tu/parse (tu/get-path app "/api/user/place" "user" usid)))))
          (is (true? (:ok (tu/parse (tu/put-json app "/api/user/place/image"
                                                {:west 139.2 :south 35.2 :east 140.8 :north 36.8}
                                                "user" usid)))))
          (is (= 139.2 (:image_west (tu/parse (tu/get-path app "/api/user/place" "user" usid))))))
        (testing "P2-2.2-05〜08 下地"
          (is (true? (:ok (tu/parse (tu/get-path app "/api/user/basemaps" "user" usid)))))
          (is (= "basemap_kind" (:code (tu/parse (tu/get-path app "/api/user/basemaps/nope" "user" usid)))))
          (is (= "basemap_missing" (:code (tu/parse (tu/get-path app "/api/user/basemaps/aerial" "user" usid)))))
          (let [tmp (io/file (tu/temp-file "bmap" ".jpg" "fake-jpeg"))
                up (app (tu/as-user (assoc (mock/request :put "/api/user/basemaps/aerial")
                                           :multipart-params {"file" {:filename "a.jpg"
                                                                      :content-type "image/jpeg"
                                                                      :tempfile tmp}})
                                    "user" usid))]
            (is (true? (:ok (tu/parse up)))))
          (let [img (tu/get-path app "/api/user/basemaps/aerial" "user" usid)]
            (is (= 200 (:status img)))
            (is (re-find #"image/jpeg" (str (get-in img [:headers "Content-Type"]))))))
        (testing "P2-2.2-04 場所を変えると古い下地は使えない"
          (let [created (tu/parse (tu/post-json app "/api/user/fields" {:name "Keep" :geojson tu/square} "user" usid))]
            (is (true? (:ok created)))
            (is (true? (:ok (tu/parse (tu/put-json app "/api/user/place"
                                                  {:west 138.0 :south 34.0 :east 140.0 :north 36.0}
                                                  "user" usid)))))
            (is (nil? (:image_west (tu/parse (tu/get-path app "/api/user/place" "user" usid)))))
            (is (= "basemap_missing" (:code (tu/parse (tu/get-path app "/api/user/basemaps/aerial" "user" usid)))))
            (is (some #(= "Keep" (:name %)) (db/list-fields (:ds sys) uid)))))
        (testing "P2-2.2-09〜15 圃場 CRUD"
          (is (true? (:ok (tu/parse (tu/get-path app "/api/user/fields" "user" usid)))))
          (is (= "shape_not_area" (:code (tu/parse (tu/post-json app "/api/user/fields"
                                                               {:name "x" :geojson {:type "Point" :coordinates [0 0]}}
                                                               "user" usid)))))
          (let [c1 (tu/parse (tu/post-json app "/api/user/fields" {:name "A" :geojson tu/square} "user" usid))
                c2 (tu/parse (tu/post-json app "/api/user/fields" {:name "B" :geojson tu/square-east} "user" usid))
                id (get-in c1 [:field :id])
                id2 (get-in c2 [:field :id])
                listed (:fields (tu/parse (tu/get-path app "/api/user/fields" "user" usid)))]
            (is (true? (:ok c1)))
            (is (every? #(contains? (first listed) %) [:id :name :area_ha :area_m2 :geojson]))
            (is (true? (:ok (tu/parse (tu/put-json app (str "/api/user/fields/" id) {:name "A2"} "user" usid)))))
            (is (= "split_too_few" (:code (tu/parse (tu/post-json app (str "/api/user/fields/" id "/split")
                                                                {:polygons []} "user" usid)))))
            (let [line-id (get-in (tu/parse (tu/post-json app "/api/user/fields" {:name "割線" :geojson tu/square} "user" usid)) [:field :id])
                  line-sp (tu/parse (tu/post-json app (str "/api/user/fields/" line-id "/split")
                                                 {:line {:type "LineString"
                                                         :coordinates [[140.0005 35.999] [140.0005 36.002]]}}
                                                 "user" usid))]
              (is (true? (:ok line-sp)))
              (is (<= 2 (count (:fields line-sp)))))
            (is (= "merge_too_few" (:code (tu/parse (tu/post-json app "/api/user/fields/merge"
                                                                {:keep_id id :ids [id]} "user" usid)))))
            (let [sp (tu/parse (tu/post-json app (str "/api/user/fields/" id "/split")
                                            {:polygons [tu/square tu/square-east]} "user" usid))]
              (is (true? (:ok sp)))
              (is (= 2 (count (:fields sp))))
              (is (re-find #"仮-\d+（A2）" (get-in sp [:fields 0 :name]))))
            (let [listed2 (:fields (tu/parse (tu/get-path app "/api/user/fields" "user" usid)))
                  temps (vec (filter #(re-find #"^仮-.*（A2）" (:name %)) listed2))
                  a (:id (first temps))
                  b (:id (second temps))
                  mg (tu/parse (tu/post-json app "/api/user/fields/merge" {:keep_id a :ids [a b]} "user" usid))]
              (is (true? (:ok mg)))
              (is (= "A2" (get-in mg [:field :name])))
              (is (true? (:ok (tu/parse (tu/delete-path app (str "/api/user/fields/" a) "user" usid))))))
            (is (= "import_invalid" (:code (tu/parse (tu/post-json app "/api/user/fields/import" {} "user" usid)))))
            (let [tmp (io/file (tu/temp-file "imp" ".geojson" (geo/to-json tu/square)))
                  imp (app (tu/as-user (assoc (mock/request :post "/api/user/fields/import")
                                              :params {:file {:filename "a.geojson" :tempfile tmp}})
                                       "user" usid))]
              (is (true? (:ok (tu/parse imp)))))))
        (testing "P2-2.2-16〜20 禁止と権限"
          (is (nil? (http/match-api :get "/api/user/gsi")))
          (is (= "unauthorized" (:code (tu/parse (tu/get-path app "/api/user/place")))))
          (is (= 403 (:status (tu/put-json app "/api/user/place"
                                          {:west 1 :south 2 :east 3 :north 4} "admin" asid))))
          (let [c (tu/parse (tu/post-json app "/api/user/fields" {:name "Mine" :geojson tu/square} "user" usid))
                id (get-in c [:field :id])]
            (is (= "field_not_found" (:code (tu/parse (tu/put-json app (str "/api/user/fields/" id)
                                                                  {:name "Z"} "user" usid2)))))
            (is (some #(= "Mine" (:name %)) (:fields (tu/parse (tu/get-path app "/api/user/fields" "user" usid)))))))
        (testing "P2-2.2-c01〜c12 code"
          (is (= "forbidden" (:code (tu/parse (tu/get-path app "/api/user/place" "admin" asid)))))
          (is (= "先に作業場所の範囲を決めてください" (ui/code-message "place_unset")))
          (is (= "閉じた形で、面積が取れるものにしてください" (ui/code-message "shape_not_area")))
          (is (= "このファイルは区画として読めません" (ui/code-message "import_invalid")))
          (is (not (contains? (set (vals ui/messages)) "phone_map")))
          (is (nil? (http/match-api :post "/api/user/phone_map"))))))))

(deftest p2-screens-and-data
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app usid uid asid]} (farm sys)
            place {:west 139.0 :south 35.0 :east 141.0 :north 37.0}]
        (tu/put-json app "/api/user/place" place "user" usid)
        (testing "P2-2.3 作業場所画面"
          (let [h (html {:page :map-place :form {:west "129" :south "26" :east "146" :north "46"}})]
            (is (re-find #"name=\"west\"" h))
            (is (not (re-find #"地名|geocod" h)))
            (is (re-find #"この範囲を作業場所にする" h))
            (is (re-find #"空中写真で最終確認" h))
            (is (re-find #"国土地理院" h))
            (is (re-find #"data-place-mode=\"1\"" h))
            (is (re-find #"ol-map" h))
            (is (re-find #"value=\"129\"" h))
            (is (not (re-find #"cyberjapandata|openstreetmap" h)))))
        (testing "P2-2.3-03a 最終確認プレビュー"
          (let [prev (tu/parse (tu/post-json app "/api/user/place/preview" place "user" usid))]
            (is (true? (:ok prev)))
            (is (= "gsi" (:source prev)))
            (is (= "aerial" (:kind prev))))
          (is (re-find #"data-preview=\"aerial\"" (html {:page :map-place :place-preview "aerial"})))
          (is (re-find #"地理院地図に戻って範囲を直す"
                       (html {:page :map-place :place-preview "aerial"
                              :form {:west "140.1" :south "35.1" :east "140.2" :north "35.2"}})))
          (is (re-find #"data-west=\"140.1\""
                       (html {:page :map-place :place-preview "aerial"
                              :form {:west "140.1" :south "35.1" :east "140.2" :north "35.2"}}))))
        (testing "P2-2.4-02a 自動取込 API"
          (with-redefs [isas.gsi/stitch-bbox (fn [_ _]
                                               (let [img (java.awt.image.BufferedImage. 8 8 java.awt.image.BufferedImage/TYPE_INT_RGB)
                                                     baos (java.io.ByteArrayOutputStream.)]
                                                 (javax.imageio.ImageIO/write img "jpg" baos)
                                                 (.toByteArray baos)))]
            (let [imp (tu/parse (tu/post-json app "/api/user/emaff/import" {} "user" usid))]
              (is (true? (:ok imp)))
              (is (seq (:basemaps imp))))))
        (testing "P2-2.3-04 / P2-7-03 地図の初期は作業場所"
          (is (= :api (tu/fx-op (assoc (ui/init-state) :page :map :kind "user" :session {:email "a"}
                                       :place place :fields [{:id 1}])
                                [:basemaps-loaded {:basemaps []}]))))
        (testing "P2-2.3-05 既定は空中写真。無ければ下地なし"
          (let [ready (html {:page :map :place place :basemaps [{:kind "aerial" :ready true}
                                                               {:kind "standard" :ready true}]})
                none (html {:page :map :place place :basemaps []})]
            (is (re-find #"空中写真" ready))
            (is (not (re-find #"data-kind=\"standard\"" none)))))
        (testing "P2-2.4-02 農地ナビを埋め込まない"
          (is (not (re-find #"農地ナビ|iframe" (html {:page :map :place place})))))
        (testing "P2-2.5 台帳"
          (let [h (html {:page :fields :fields [{:id 1 :name "北" :area_ha 0.00 :area_m2 42}]})]
            (is (re-find #"北" h))
            (is (re-find #"0.00|0" h))
            (is (re-find #"42" h))
            (is (re-find #"<th>㎡</th>" h))
            (is (re-find #"href=\"/map\"" h))
            (is (not (re-find #"面積を入力|作物|地番|所有者" h)))))
        (testing "P2-2.5-03 名前の重複を許す"
          (let [a (tu/parse (tu/post-json app "/api/user/fields" {:name "同名" :geojson tu/square} "user" usid))
                b (tu/parse (tu/post-json app "/api/user/fields" {:name "同名" :geojson tu/square-east} "user" usid))]
            (is (true? (:ok a)))
            (is (true? (:ok b)))))
        (testing "P2-2.6 地図は自分の圃場。後工程は出さない。段階表示"
          (let [h (html {:page :map :place place :fields [{:id 1 :name "北"}]})]
            (is (re-find #"手描き|修正|分割|合筆" h))
            (is (not (re-find #"圃場を保存|合筆する|分割を保存" h)))
            (is (not (re-find #"ガント|指示" h)))
            (is (re-find #"圃場を保存" (html {:page :map :place place :map-mode "draw"})))
            (is (re-find #"やめる" (html {:page :map :place place :map-mode "draw"})))))
        (testing "P2-2.6-06 取込は Polygon ごと。点は飛ばす"
          (let [fc {:type "FeatureCollection"
                    :features [{:type "Feature" :geometry tu/square :properties {:name "取込A"}}
                               {:type "Feature" :geometry tu/square-east}
                               {:type "Feature" :geometry {:type "Point" :coordinates [140 36]}}]}
                t (io/file (tu/temp-file "imp" ".geojson" (geo/to-json fc)))
                r (fields/import-geojson sys uid {:filename "a.geojson" :tempfile t})]
            (is (true? (:ok r)))
            (is (= 2 (count (:fields r))))
            (is (= "取込A" (get-in r [:fields 0 :name])))
            (is (re-find #"仮-" (get-in r [:fields 1 :name])))))
        (testing "P2-2.7 / P2-3.2-04 ha と ㎡"
          (let [c (fields/create-field sys uid {:name "小" :geojson tu/square})
                f (:field c)]
            (is (integer? (:area_m2 f)))
            (is (number? (:area_ha f)))
            (is (pos? (:area_m2 f)))))
        (testing "P2-3.1-03 取消しで圃場などを消す"
          (let [c (fields/create-field sys uid {:name "残" :geojson tu/square})]
            (is (true? (:ok c)))
            (accounts/revoke-user sys uid)
            (is (empty? (db/list-fields (:ds sys) uid)))
            (is (= "place_unset" (:code (fields/get-place sys uid))))
            (accounts/invite sys "admin" 1 "farm@example.com")
            (is (= uid (:id (db/find-user-by-email (:ds sys) "farm@example.com"))))
            (is (empty? (db/list-fields (:ds sys) uid)))))
        (testing "P2-3.2-02 重なってよい"
          (let [inv (accounts/invite sys "admin" 1 "overlap@example.com")
                sid (tu/user-sid app "overlap@example.com" (:initial_password inv))]
            (tu/put-json app "/api/user/place" place "user" sid)
            (is (true? (:ok (tu/parse (tu/post-json app "/api/user/fields" {:name "重1" :geojson tu/square} "user" sid)))))
            (is (true? (:ok (tu/parse (tu/post-json app "/api/user/fields" {:name "重2" :geojson tu/square} "user" sid)))))))
        (testing "P2-4 表と Git"
          (let [names (tu/table-names (:ds sys))]
            (is (contains? names "users"))
            (is (contains? names "work_places"))
            (is (contains? names "basemaps"))
            (is (contains? names "fields"))
            (is (contains? names "paints")))
          (is (re-find #"data/basemaps/" (slurp (io/file ".gitignore")))))
        (testing "P2-5 文言"
          (is (= "圃場台帳" (:fields-title ui/messages)))
          (is (= "地図" (:map-title ui/messages)))
          (is (= "台帳と地図の編集はパソコンで開いてください" (:phone-map ui/messages)))
          (is (re-find #"パソコンで開いてください" (html {:page :map :narrow? true})))
          (is (re-find #"標準地図" (html {:page :map :place place :basemaps [{:kind "standard" :ready true}]}))))
        (testing "P2-6 工程2で作らないもの"
          (is (nil? (http/match-api :post "/api/user/orders")))
          (is (not (re-find #"cyberjapandata|openstreetmap|tile.openstreetmap" (html {:page :map :place place}))))
          (is (not (re-find #"地名検索" (html {:page :map-place}))))
          (is (not (re-find #"面積<input" (html {:page :fields :fields []})))))
        (testing "P2-S 操作シナリオ（試験書と画面の要点）"
          (let [doc (slurp (io/file "docs/詳細試験仕様書_工程2.md"))]
            (doseq [id ["P2-S-01" "P2-S-02" "P2-S-03" "P2-S-04" "P2-S-05"]]
              (is (re-find (re-pattern id) doc))))
          (let [change (html {:page :map-place :place place
                              :form {:west "140.04" :south "37.88" :east "140.06" :north "37.90"}})]
            (is (re-find #"いまの作業場所を変えられます" change))
            (is (not (re-find #"先に作業場所の範囲を決めてください" change)))
            (is (re-find #"data-west=\"140.04\"" change))
            (is (re-find #"作業場所を変える" (html {:page :map :place place})))))))))
