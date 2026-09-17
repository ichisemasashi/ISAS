(ns isas.p3-test
  "詳細試験仕様書_工程3 の項番に対応する自動試験。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.geo :as geo]
            [isas.http :as http]
            [isas.log :as log]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(defn- map-html [fields]
  (html {:page :map
         :place {:west 139 :south 35 :east 141 :north 37}
         :fields fields
         :map-mode "paint"
         :form {:work_name "田植え"}
         :work-names ["田植え"]}))

(defn- farm [sys]
  (let [app (tu/app sys)
        asid (tu/admin-sid app)
        pw (tu/invite-pw app asid "p3@example.com")
        usid (tu/user-sid app "p3@example.com" pw)
        pw2 (tu/invite-pw app asid "p3-other@example.com")
        usid2 (tu/user-sid app "p3-other@example.com" pw2)
        uid (:id (db/find-user-by-email (:ds sys) "p3@example.com"))
        uid2 (:id (db/find-user-by-email (:ds sys) "p3-other@example.com"))]
    {:app app :asid asid :usid usid :usid2 usid2 :uid uid :uid2 uid2}))

(defn- add-field [app usid name gj]
  (:field (tu/parse (tu/post-json app "/api/user/fields" {:name name :geojson gj} "user" usid))))

(deftest p3-map-and-copy
  (testing "P3-2.1-01 / P3-7-02 開き直すと作業名未選択"
    (let [s (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :home
                   :form {:work_name "田植え"} :paint-data {:work_name "田植え"})
          r (ui/handle s [:path {:path "/map" :search ""}])]
      (is (nil? (get-in r [:state :form :work_name])))
      (is (nil? (get-in r [:state :paint-data])))))
  (testing "P3-2.1-01a browse でも作業名を入れられる"
    (let [h (html {:page :map
                   :place {:west 139 :south 35 :east 141 :north 37}
                   :fields [{:id 1 :name "北"}]
                   :map-mode "browse"
                   :work-names ["田植え"]})]
      (is (re-find #"name=\"work_name\"" h))
      (is (re-find #"この作業名で見る" h))
      (is (not (re-find #"ブラシ" h)))))
  (testing "P3-2.1-01b 作業名ありは塗り優先"
    (let [h (map-html [{:id 1 :name "北"}])]
      (is (re-find #"ブラシ" h))
      (is (not (re-find #">手描き<" h)))
      (is (re-find #"圃場を直す" h))))
  (testing "P3-2.1-01c 圃場を直すと手描きが戻る"
    (let [h (html {:page :map
                   :place {:west 139 :south 35 :east 141 :north 37}
                   :fields [{:id 1 :name "北"}]
                   :map-mode "browse"
                   :form {:work_name "田植え"}})]
      (is (re-find #"手描き" h))
      (is (re-find #"name=\"work_name\"" h))
      (is (not (re-find #"ブラシ" h)))))
  (testing "P3-2.1-02 / P3-2.4-02〜04 色の具体値とラベル"
    (let [h (map-html [{:id 1 :name "北" :area_ha 1 :area_m2 10000}])]
      (is (re-find #"data-none=\"#c8c8c8\"" h))
      (is (re-find #"data-partial=\"#e6b800\"" h))
      (is (re-find #"data-done=\"#2e7d32\"" h))
      (is (re-find #">未<" h))
      (is (re-find #">一部<" h))
      (is (re-find #">済<" h))))
  (testing "P3-2.1-03 / P3-5-02 作業名が空では塗れない"
    (let [s (assoc (ui/init-state) :page :map :session {:email "a"} :kind "user")
          r (ui/handle s [:submit {:act "confirm-paint" :form {:field_id "1" :geojson "{}"}}])]
      (is (re-find #"作業名を入れてから塗ってください" (get-in r [:state :flash :text])))
      (is (= :html (ffirst (:fx r)))))
    (let [r (ui/handle (assoc (ui/init-state) :page :map)
                       [:submit {:act "complete-field" :form {:id "1"}}])]
      (is (re-find #"作業名を入れてから塗ってください" (get-in r [:state :flash :text])))))
  (testing "P3-2.1-04 / P3-3.2-01 下書きは確定まで API に送らない"
    (let [r (ui/handle (ui/init-state) [:submit {:act "discard-drafts" :form {}}])]
      (is (= :html (ffirst (:fx r))))
      (is (not= :api (ffirst (:fx r))))))
  (testing "P3-2.1-08 / P3-6-01 ガント・％・指示は出さない"
    (let [h (map-html [{:id 1 :name "北"}])]
      (is (not (re-find #"ガント|パーセントサークル|指示" h)))))
  (testing "P3-2.4-01 / P3-2.4-05 未選択は進捗色なし。下地は工程2のまま"
    (let [h (html {:page :map
                   :place {:west 139 :south 35 :east 141 :north 37}
                   :fields [{:id 1 :name "北"}]
                   :basemaps [{:kind "aerial" :ready true}]})]
      (is (re-find #"空中写真" h))
      (is (re-find #"手描き" h))
      (is (not (re-find #"下地を西へ" h)))
      (is (re-find #"下地を西へ" (html {:page :map
                                       :place {:west 139 :south 35 :east 141 :north 37}
                                       :map-mode "basemap"
                                       :basemaps [{:kind "aerial" :ready true}]})))))
  (testing "P3-5 文言"
    (let [h (map-html [{:id 1 :name "北"}])]
      (is (re-find #"作業名" h))
      (is (re-find #"塗りを確定する" h))
      (is (re-find #"下書きを捨てる" h))
      (is (re-find #"この圃場をこの作業名で全面完了にする" h))
      (is (re-find #"この塗りを消す" h))
      (is (re-find #"この圃場のこの作業名の塗りを全部消す" h))))
  (testing "P3-2.1-05a / P3-2.1-05b / P3-5-07a / P3-5-07b 選択表示は圃場名（試験書）"
    ;; 動的文言は cljs の map_test で確認する。ここでは試験書に項があり、API が name を返すことを固定する。
    (let [doc (slurp (io/file "docs/詳細試験仕様書_工程3.md"))]
      (doseq [id ["P3-2.1-05a" "P3-2.1-05b" "P3-5-07a" "P3-5-07b"]]
        (is (re-find (re-pattern id) doc)))
      (is (re-find #"選んでいる塗り: \{圃場名\}" doc))
      (is (re-find #"選んでいる圃場: \{圃場名\}" doc))
      (is (re-find #"塗り行の数字 ID は出ない" doc))
      (is (re-find #"圃場の数字 ID は出ない" doc))))
  (testing "P3-5-08 / P3-5-09 分割・合筆の拒否文言"
    (is (= "塗りが残っている圃場は分割できません。塗りを消してから行ってください"
           (ui/paint-block-text "split")))
    (is (= "塗りが残っている圃場は合筆できません。塗りを消してから行ってください"
           (ui/paint-block-text "merge")))
    (is (= (ui/paint-block-text "split") (ui/code-message "field_has_paint"))))
  (testing "P3-6-04 / P3-6-05 狭い画面と面積手入力は無い"
    (is (not (re-find #"ブラシ|全面完了" (html {:page :map :narrow? true :fields [{:id 1}]}))))
    (is (not (re-find #"面積を入力|面積<input" (map-html [{:id 1 :name "北"}]))))))

(deftest p3-api-and-data
  (tu/with-sys
    (fn [sys]
      (let [{:keys [app asid usid usid2 uid uid2]} (farm sys)
            logs (atom [])]
        (binding [log/*tap* #(swap! logs conj %)]
          (tu/put-json app "/api/user/place"
                       {:west 139.0 :south 35.0 :east 141.0 :north 37.0} "user" usid)
          (tu/put-json app "/api/user/place"
                       {:west 139.0 :south 35.0 :east 141.0 :north 37.0} "user" usid2)
          (let [f1 (add-field app usid "北" tu/square)
                f2 (add-field app usid "東" tu/square-east)
                o1 (add-field app usid2 "他人" tu/square)
                id (:id f1)
                id2 (:id f2)
                oid (:id o1)]
            (testing "P3-2.2-01 / P3-2.3-01 / P3-3.1-02 塗る前の候補は空"
              (is (= [] (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid))))))
            (testing "P3-2.2-03 / P3-2.2-c01 GET paints 空"
              (is (= "work_name_required" (:code (tu/parse (tu/get-path app "/api/user/paints" "user" usid)))))
              (is (= "work_name_required" (:code (tu/parse (tu/get-query app "/api/user/paints" {:work_name "  "} "user" usid))))))
            (testing "P3-2.2-c02 work_name_too_long"
              (is (= "work_name_too_long"
                     (:code (tu/parse (tu/post-json app "/api/user/paints"
                                                    {:field_id id :work_name (apply str (repeat 101 "あ"))
                                                     :geojson tu/square-inner}
                                                    "user" usid))))))
            (testing "P3-2.2-04 / P3-3.1-03 切り取り後に保存。1行"
              (let [r (tu/parse (tu/post-json app "/api/user/paints"
                                              {:field_id id :work_name "  田植え  "
                                               :geojson tu/square-inner}
                                              "user" usid))]
                (is (true? (:ok r)))
                (is (number? (get-in r [:paint :id])))
                (is (geo/valid-shape? (get-in r [:paint :geojson])))
                (is (some #(= "塗りを確定しました" (:msg %)) @logs))))
            (testing "P3-2.2-01 / P3-2.3-05 前後空白を除いて保存"
              (is (= ["田植え"] (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid))))))
            (testing "P3-2.2-02 / P3-2.2-14 / P3-2.2-s02 一部"
              (let [body (tu/parse (tu/get-query app "/api/user/paints" {:work_name "田植え"} "user" usid))
                    row (first (filter #(= id (:id %)) (:fields body)))]
                (is (true? (:ok body)))
                (is (= "田植え" (:work_name body)))
                (is (every? #(contains? row %) [:id :name :status :area_m2 :field_area_m2 :paints]))
                (is (= "北" (:name row)))
                (is (pos? (:area_m2 row)))
                (is (< (:area_m2 row) (:field_area_m2 row)))
                (is (= "partial" (:status row)))
                (is (= 1 (count (:paints row))))
                (is (contains? (first (:paints row)) :geojson))))
            (testing "P3-2.2-s01 塗っていない圃場は none"
              (let [body (tu/parse (tu/get-query app "/api/user/paints" {:work_name "田植え"} "user" usid))
                    row (first (filter #(= id2 (:id %)) (:fields body)))]
                (is (= "none" (:status row)))
                (is (zero? (:area_m2 row)))))
            (testing "P3-2.3-02 新しい名前は POST の work_name。マスタ API は無い"
              (is (nil? (http/match-api :post "/api/user/work-names")))
              (is (nil? (http/match-api :put "/api/user/work-names/1")))
              (is (true? (:ok (tu/parse (tu/post-json app "/api/user/paints"
                                                      {:field_id id :work_name "田植" :geojson tu/square-inner}
                                                      "user" usid))))))
            (testing "P3-2.3-05 / P3-7-01 田植えと田植は別"
              (let [names (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid)))]
                (is (= ["田植" "田植え"] (vec (sort names))))))
            (testing "P3-3.2-02 複数行は和集合。重なりは二重に数えない"
              (let [a (tu/parse (tu/post-json app "/api/user/paints"
                                              {:field_id id2 :work_name "草刈" :geojson tu/square-east}
                                              "user" usid))
                    b (tu/parse (tu/post-json app "/api/user/paints"
                                              {:field_id id2 :work_name "草刈" :geojson tu/square-east}
                                              "user" usid))
                    body (tu/parse (tu/get-query app "/api/user/paints" {:work_name "草刈"} "user" usid))
                    row (first (filter #(= id2 (:id %)) (:fields body)))
                    one (geo/area-m2-int (or (geo/area-m2 tu/square-east) 0))]
                (is (true? (:ok a)))
                (is (true? (:ok b)))
                (is (= 2 (count (:paints row))))
                (is (= one (:area_m2 row)))))
            (testing "P3-2.2-05 / P3-2.2-s03 / P3-3.3-01 全面完了"
              (let [r (tu/parse (tu/post-json app (str "/api/user/fields/" id "/complete")
                                              {:work_name "田植え"} "user" usid))
                    body (tu/parse (tu/get-query app "/api/user/paints" {:work_name "田植え"} "user" usid))
                    row (first (filter #(= id (:id %)) (:fields body)))]
                (is (true? (:ok r)))
                (is (= "done" (:status row)))
                (is (= 1 (count (:paints row))))
                (is (= (:area_m2 row) (:field_area_m2 row)))
                (is (some #(= "圃場を全面完了にしました" (:msg %)) @logs))))
            (testing "P3-2.2-06 塗りを1つ消す"
              (let [pid (get-in (first (filter #(= id2 (:id %))
                                               (:fields (tu/parse (tu/get-query app "/api/user/paints" {:work_name "草刈"} "user" usid)))))
                                [:paints 0 :id])
                    r (tu/parse (tu/delete-path app (str "/api/user/paints/" pid) "user" usid))]
                (is (true? (:ok r)))
                (is (some #(= "塗りを消しました" (:msg %)) @logs))))
            (testing "P3-2.2-c04 paint_not_found"
              (is (= "paint_not_found" (:code (tu/parse (tu/delete-path app "/api/user/paints/99999" "user" usid)))))
              (let [secret (tu/parse (tu/post-json app "/api/user/paints"
                                                   {:field_id oid :work_name "他人塗り" :geojson tu/square-inner}
                                                   "user" usid2))
                    pid (get-in secret [:paint :id])]
                (is (= "paint_not_found" (:code (tu/parse (tu/delete-path app (str "/api/user/paints/" pid) "user" usid)))))))
            (testing "P3-2.2-07 / P3-2.3-03 全部消すと候補から消える"
              (is (true? (:ok (tu/parse (tu/delete-query app (str "/api/user/fields/" id2 "/paints")
                                                         {:work_name "草刈"} "user" usid)))))
              (is (not (some #{"草刈"} (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid)))))))
            (testing "P3-2.2-08 管理者は forbidden"
              (is (= 403 (:status (tu/get-path app "/api/user/work-names" "admin" asid))))
              (is (= 403 (:status (tu/get-query app "/api/user/paints" {:work_name "田植え"} "admin" asid))))
              (is (= 403 (:status (tu/post-json app "/api/user/paints"
                                                {:field_id id :work_name "x" :geojson tu/square} "admin" asid)))))
            (testing "P3-2.2-09 / P3-3.1-01 他人の圃場は field_not_found"
              (is (= "field_not_found"
                     (:code (tu/parse (tu/post-json app "/api/user/paints"
                                                    {:field_id oid :work_name "田植え" :geojson tu/square}
                                                    "user" usid)))))
              (is (= "field_not_found"
                     (:code (tu/parse (tu/post-json app (str "/api/user/fields/" oid "/complete")
                                                    {:work_name "田植え"} "user" usid))))))
            (testing "P3-2.3-04 他人の作業名は出さない"
              (tu/post-json app "/api/user/paints" {:field_id oid :work_name "秘密" :geojson tu/square-inner} "user" usid2)
              (is (not (some #{"秘密"} (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid)))))))
            (testing "P3-2.2-10 / P3-7-03 split は field_has_paint"
              (let [r (tu/parse (tu/post-json app (str "/api/user/fields/" id "/split")
                                              {:polygons [tu/square tu/square-east]} "user" usid))]
                (is (= "field_has_paint" (:code r)))
                (is (some? (db/find-field (:ds sys) uid id)))))
            (testing "P3-2.2-11 merge は field_has_paint"
              (is (= "field_has_paint"
                     (:code (tu/parse (tu/post-json app "/api/user/fields/merge"
                                                    {:keep_id id :ids [id id2]} "user" usid)))))
              (is (some? (db/find-field (:ds sys) uid id2))))
            (testing "P3-2.2-12 / P3-3.5-01 境界修正後に切り直す"
              (let [keep (tu/parse (tu/put-json app (str "/api/user/fields/" id)
                                                {:geojson tu/square-inner} "user" usid))
                    gone (add-field app usid "消" tu/square)
                    _ (tu/post-json app "/api/user/paints"
                                    {:field_id (:id gone) :work_name "刈" :geojson tu/square-inner}
                                    "user" usid)
                    shrunk (tu/parse (tu/put-json app (str "/api/user/fields/" (:id gone))
                                                  {:geojson tu/square-nw} "user" usid))
                    body (tu/parse (tu/get-query app "/api/user/paints" {:work_name "刈"} "user" usid))
                    row (first (filter #(= (:id gone) (:id %)) (:fields body)))]
                (is (true? (:ok keep)))
                (is (true? (:ok shrunk)))
                (is (= "none" (:status row)))
                (is (empty? (:paints row)))
                (is (some #(= "面積が無くなった塗りを消しました" (:msg %)) @logs))))
            (testing "P3-2.2-13 / P3-7-04 圃場を消すと塗りも消える"
              (let [del (add-field app usid "削除対象" tu/square)
                    _ (tu/post-json app "/api/user/paints"
                                    {:field_id (:id del) :work_name "残さない" :geojson tu/square-inner}
                                    "user" usid)]
                (is (true? (:ok (tu/parse (tu/delete-path app (str "/api/user/fields/" (:id del)) "user" usid)))))
                (is (empty? (db/list-paints-for-field (:ds sys) (:id del))))
                (is (not (some #{"残さない"} (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid))))))))
            (testing "P3-2.2-c05 paint_empty"
              (is (= "paint_empty"
                     (:code (tu/parse (tu/post-json app "/api/user/paints"
                                                    {:field_id id2 :work_name "外" :geojson tu/square}
                                                    "user" usid))))))
            (testing "P3-4 表"
              (let [names (tu/table-names (:ds sys))
                    cols (db/table-columns (:ds sys) "paints")]
                (is (contains? names "fields"))
                (is (contains? names "paints"))
                (is (every? cols ["id" "field_id" "work_name" "geojson" "created_at"]))
                (is (not (contains? cols "area_m2")))
                (is (not (contains? cols "complete")))
                (is (not (contains? cols "user_id")))
                (is (not (contains? names "work_names")))
                (is (not (contains? names "gantt_rows")))))
            (testing "P3-6-02 改名 API は無い"
              (is (nil? (http/match-api :put "/api/user/work-names")))
              (is (nil? (http/match-api :post "/api/user/work-names/rename"))))
            (testing "P3-3.1-02 圃場0枚"
              (let [pw0 (tu/invite-pw app asid "p3-zero@example.com")
                    sid0 (tu/user-sid app "p3-zero@example.com" pw0)]
                (is (= [] (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" sid0)))))
                (is (= [] (:fields (tu/parse (tu/get-query app "/api/user/paints" {:work_name "田植え"} "user" sid0)))))
                (is (not (re-find #"ブラシ|全面完了" (html {:page :map
                                                          :place {:west 1 :south 2 :east 3 :north 4}
                                                          :fields []}))))))
            (testing "経路"
              (is (= [:work-names-get] (http/match-api :get "/api/user/work-names")))
              (is (= [:paints-get] (http/match-api :get "/api/user/paints")))
              (is (= [:paints-post] (http/match-api :post "/api/user/paints")))
              (is (= [:field-complete "1"] (http/match-api :post "/api/user/fields/1/complete")))
              (is (= [:field-paints-delete "1"] (http/match-api :delete "/api/user/fields/1/paints")))
              (is (= [:paint-delete "2"] (http/match-api :delete "/api/user/paints/2")))
              (is (nil? (http/match-api :get "/api/user/fields/1/complete")))
              (is (nil? (http/match-api :post "/api/user/paints/2")))
              (is (nil? (http/match-api :get "/api/user/fields/1/paints"))))))))))

(deftest p3-ui-handlers
  (let [s (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :map)]
    (testing "P3-2.1-05〜07 画面操作は API に載せる"
      (is (= :api (ffirst (:fx (ui/handle s [:submit {:act "select-work-name" :form {:work_name "田植え"}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "select-work-name" :form {:work_name "  "}}])))))
      (is (= :api (ffirst (:fx (ui/handle s [:submit {:act "confirm-paint"
                                                     :form {:field_id "1" :work_name "田植え" :geojson "{\"type\":\"Polygon\"}"}}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:field_id "1" :work_name "田植え"})
                                         [:submit {:act "confirm-paint"
                                                   :form {:geojson "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,0]]]}]"}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "confirm-paint" :form {:work_name "田植え"}}])))))
      (is (= :api (ffirst (:fx (ui/handle s [:submit {:act "complete-field" :form {:id "1" :work_name "田植え"}}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:field_id "9" :work_name "田植え"})
                                         [:submit {:act "complete-field" :form {}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "complete-field" :form {:work_name "田植え"}}])))))
      (is (= :api (ffirst (:fx (ui/handle s [:submit {:act "delete-paint" :form {:id "3"}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "delete-paint" :form {}}])))))
      (is (= :api (ffirst (:fx (ui/handle s [:submit {:act "delete-field-paints" :form {:id "1" :work_name "田植え"}}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:id "1" :work_name "田植え"})
                                         [:submit {:act "delete-field-paints" :form {}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "delete-field-paints" :form {:id "1"}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:submit {:act "delete-field-paints" :form {:work_name "田植え"}}])))))
      (is (re-find #"作業名を入れてから" (get-in (ui/handle s [:submit {:act "select-work-name" :form {}}]) [:state :flash :text]))))
    (is (= "1" (#'ui/field-id-of {} {:form {:field_id "1"}})))
    (is (= "2" (#'ui/field-id-of {:id "2"} {:form {:field_id "1"}})))
    (is (nil? (#'ui/field-id-of {} {:form {}})))
    (is (= "9" (#'ui/paint-id-of {} {:form {:paint-id "9"}})))
    (testing "読み込み連鎖"
      (is (= :api (ffirst (:fx (ui/handle s [:basemaps-loaded {:basemaps []}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:work-names-loaded {:work_names ["田植え"]}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:work-names-loaded {}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:work_name "田植え"})
                                         [:work-names-loaded {:work_names ["田植え"]}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:work_name "田植え"})
                                         [:submit {:act "complete-field" :form {:id "1"}}])))))
      (is (= :html (ffirst (:fx (ui/handle (assoc s :form {:work_name "田植え"})
                                          [:submit {:act "confirm-paint" :form {:field_id "1"}}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:work_name "田植え" :paint-geojson "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,0]]]}"})
                                         [:submit {:act "confirm-paint" :form {:field_id "1"}}])))))
      (is (= :api (ffirst (:fx (ui/handle (assoc s :form {:work_name "田植え"})
                                         [:submit {:act "delete-field-paints" :form {:id "1"}}])))))
      (is (= :html (ffirst (:fx (ui/handle s [:paints-loaded {:ok true :work_name "田植え" :fields []}])))))
      (is (true? (get-in (ui/handle s [:paints-loaded {:ok false :code "work_name_required"}]) [:state :flash :error?])))
      (is (= :api (ffirst (:fx (ui/handle s [:paint-save-result {:ok true}])))))
      (is (= "田植え" (get-in (ui/handle (assoc s :form {:work_name "田植え" :field_id "1" :paint-geojson "{}" :paint-id "9"})
                                         [:paint-save-result {:ok true}])
                              [:state :form :work_name])))
      (is (= "1" (get-in (ui/handle (assoc s :form {:work_name "田植え" :field_id "1" :paint-geojson "{}"})
                                    [:paint-save-result {:ok true}])
                         [:state :form :field_id])))
      (is (= "2" (get-in (ui/handle (assoc s :form {:id "2"})
                                    [:paint-save-result {:ok true}])
                         [:state :form :id])))
      (is (nil? (get-in (ui/handle (assoc s :form {})
                                   [:paint-save-result {:ok true}])
                        [:state :form :work_name])))
      (is (nil? (get-in (ui/handle (assoc s :form {:paint-geojson "{}" :paint-id "9"})
                                   [:paint-save-result {:ok true}])
                        [:state :form :paint-id])))
      (is (true? (get-in (ui/handle s [:paint-save-result {:ok false :code "paint_empty"}]) [:state :flash :error?])))
      (is (some? (#'ui/paint-geojson-of {} {:form {:paint-geojson "{\"type\":\"Polygon\"}"}})))
      (is (nil? (#'ui/paint-geojson-of {} {:form {}})))
      (is (nil? (#'ui/paint-id-of {} {:form {}})))
      (is (= "3" (#'ui/field-id-of {} {:form {:id "3"}})))
      (is (nil? (#'ui/field-id-of {:field_id "  "} {:form {}})))
      (is (re-find #"分割できません" (get-in (ui/handle (assoc s :last-field-act "split")
                                                    [:field-save-result {:ok false :code "field_has_paint"}])
                                           [:state :flash :text])))
      (is (re-find #"合筆できません" (get-in (ui/handle (assoc s :last-field-act "merge")
                                                    [:field-save-result {:ok false :code "field_has_paint"}])
                                           [:state :flash :text]))))
    (is (= "" (#'ui/work-name-of {} {})))
    (is (true? (#'ui/blank-work-name? {} {})))
    (testing "P3-6-03 他人地図の経路は無い"
      (is (nil? (http/match-api :get "/api/user/others/paints")))
      (is (nil? (http/match-api :post "/api/user/others/paints"))))
    (testing "P3-S 操作シナリオ（試験書と画面の要点）"
      (let [doc (slurp (io/file "docs/詳細試験仕様書_工程3.md"))]
        (doseq [id ["P3-S-01" "P3-S-02" "P3-S-03" "P3-S-04"]]
          (is (re-find (re-pattern id) doc))))
      (let [browse (html {:page :map
                          :place {:west 1 :south 2 :east 3 :north 4}
                          :fields [{:id 1 :name "北"}]
                          :map-mode "browse"})]
        (is (re-find #"select-work-name" browse))
        (is (re-find #"この作業名で見る" browse))
        (is (not (re-find #"ブラシ" browse))))
      (let [paint (html {:page :map
                         :place {:west 1 :south 2 :east 3 :north 4}
                         :fields [{:id 1 :name "北"}]
                         :map-mode "paint"
                         :form {:work_name "田植え"}})]
        (is (re-find #"ブラシ" paint))))))
