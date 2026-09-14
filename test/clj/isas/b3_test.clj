(ns isas.b3-test
  "基本試験仕様書_工程3 の項番に対応する自動試験。経路名は見ない。"
  (:require [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.http :as http]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(def place {:west 139.0 :south 35.0 :east 141.0 :north 37.0})

(deftest b3-blocks
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "b3@example.com")
            usid (tu/user-sid app "b3@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "b3@example.com"))
            pw2 (tu/invite-pw app asid "b3-other@example.com")
            usid2 (tu/user-sid app "b3-other@example.com" pw2)
            uid2 (:id (db/find-user-by-email (:ds sys) "b3-other@example.com"))
            f (fields/create-field sys uid {:name "北" :geojson tu/square})
            fid (get-in f [:field :id])
            wide (html {:page :map :place place :fields [{:id fid :name "北"}]
                        :map-mode "paint" :form {:work_name "田植え"}})]
        (testing "B3-2-01 / B3-2-02 所有者のパソコンで塗れる"
          (is (re-find #"ブラシ|塗りを確定する" wide))
          (is (true? (:ok (paints/create-paint sys uid {:field_id fid :work_name "田植え" :geojson tu/square-inner})))))
        (testing "B3-2-03 / B3-5.4-01 狭い画面に塗りは持たない"
          (is (not (re-find #"ブラシ|全面完了|塗りを確定する" (html {:page :map :narrow? true :place place :fields [{:id 1}]})))))
        (testing "B3-2-04 / B3-4.5-06 / B3-6-04 / B3-7.3-03 / B3-10-02 ガントと指示は出さない"
          (is (not (re-find #"ガント|パーセントサークル|指示" wide)))
          (is (nil? (http/match-api :get "/api/user/gantt")))
          (is (nil? (http/match-api :post "/api/user/orders"))))
        (testing "B3-3-01 / B3-5.2-01 管理者は塗れない"
          (is (= 403 (:status (tu/post-json app "/api/user/paints"
                                            {:field_id fid :work_name "x" :geojson tu/square} "admin" asid))))
          (is (not (re-find #"ブラシ|全面完了" (html {:page :home :kind "admin"})))))
        (testing "B3-3-02 / B3-4.4-08 他人の圃場は見ない・塗れない"
          (let [of (fields/create-field sys uid2 {:name "他人" :geojson tu/square})]
            (is (= "field_not_found" (:code (paints/create-paint sys uid {:field_id (get-in of [:field :id])
                                                                         :work_name "田植え"
                                                                         :geojson tu/square-inner}))))
            (is (not (some #{"秘密"} (:work_names (paints/list-work-names sys uid)))))))
        (testing "B3-4.3-01 塗りがある圃場は分割・合筆できない"
          (is (= "field_has_paint" (:code (fields/split-field sys uid fid {:polygons [tu/square tu/square-east]}))))
          (let [f2 (fields/create-field sys uid {:name "東" :geojson tu/square-east})]
            (is (= "field_has_paint" (:code (fields/merge-fields sys uid {:keep_id fid :ids [fid (get-in f2 [:field :id])]}))))))
        (testing "B3-4.4-01 / B3-6-02 マスタは持たず完全一致"
          (is (true? (:ok (paints/create-paint sys uid {:field_id fid :work_name " 田植 " :geojson tu/square-inner}))))
          (let [names (:work_names (paints/list-work-names sys uid))]
            (is (some #{"田植え"} names))
            (is (some #{"田植"} names))
            (is (not (some #{" 田植 "} names)))))
        (testing "B3-4.4-02 候補は塗った名前だけ"
          (is (= (set (:work_names (paints/list-work-names sys uid)))
                 (set (:work_names (tu/parse (tu/get-path app "/api/user/work-names" "user" usid)))))))
        (testing "B3-4.4-03 塗りが残っている名前は履歴から消えない"
          (is (some #{"田植え"} (:work_names (paints/list-work-names sys uid)))))
        (testing "B3-4.4-04 下書きは正本にしない"
          (is (= :html (ffirst (:fx (ui/handle (ui/init-state) [:submit {:act "discard-drafts" :form {}}]))))))
        (testing "B3-4.4-05 / B3-6-03 複数塗りは合わせ、面積は形から"
          (let [f3 (fields/create-field sys uid {:name "合" :geojson tu/square})
                id3 (get-in f3 [:field :id])]
            (paints/create-paint sys uid {:field_id id3 :work_name "合" :geojson tu/square-inner})
            (paints/create-paint sys uid {:field_id id3 :work_name "合" :geojson tu/square-inner})
            (let [row (first (filter #(= id3 (:id %)) (:fields (paints/list-paints sys uid "合"))))]
              (is (= 2 (count (:paints row))))
              (is (pos? (:area_m2 row)))
              (is (< (:area_m2 row) (:field_area_m2 row))))))
        (testing "B3-4.4-06 / B3-4.5-03 / B3-7.3-01 全面完了は済"
          (is (true? (:ok (paints/complete-field sys uid fid "田植え"))))
          (is (= "done" (:status (first (filter #(= fid (:id %)) (:fields (paints/list-paints sys uid "田植え"))))))))
        (testing "B3-4.4-07 / B3-4.5-01 消すと未に戻る"
          (is (true? (:ok (paints/delete-field-paints sys uid fid "田植え"))))
          (is (= "none" (:status (first (filter #(= fid (:id %)) (:fields (paints/list-paints sys uid "田植え"))))))))
        (testing "B3-4.5-02 一部"
          (paints/create-paint sys uid {:field_id fid :work_name "田植え" :geojson tu/square-inner})
          (is (= "partial" (:status (first (filter #(= fid (:id %)) (:fields (paints/list-paints sys uid "田植え"))))))))
        (testing "B3-4.5-04 開き直すと未選択"
          (let [r (ui/handle (assoc (ui/init-state) :session {:email "a"} :kind "user" :page :home
                                    :form {:work_name "田植え"} :paint-data {:work_name "田植え"})
                             [:path {:path "/map" :search ""}])]
            (is (nil? (get-in r [:state :paint-data])))))
        (testing "B3-4.5-05 選ぶと自分の全圃場に色の材料がある"
          (let [body (paints/list-paints sys uid "田植え")]
            (is (true? (:ok body)))
            (is (= (count (:fields (fields/list-fields sys uid))) (count (:fields body))))))
        (testing "B3-5.3-01 地図に塗りと全面完了"
          (is (re-find #"全面完了" wide))
          (is (not (re-find #"手描き" wide)))
          (is (re-find #"圃場を直す" wide))
          (is (re-find #"手描き" (html {:page :map :place place :fields [{:id fid}] :map-mode "browse"}))))
        (testing "B3-5.3-02 0枚でも塗りメニューは開けるが作業名なしではブラシ無し"
          (is (not (re-find #"ブラシ|全面完了" (html {:page :map :place place :fields []})))))
        (testing "B3-6-01 塗り単位。下書き表は無い"
          (let [names (tu/table-names (:ds sys))
                cols (db/table-columns (:ds sys) "paints")]
            (is (contains? names "paints"))
            (is (every? cols ["field_id" "work_name" "geojson"]))
            (is (not (contains? names "drafts")))))
        (testing "B3-7.3-02 田植えと田植は別"
          (is (some #{"田植え"} (:work_names (paints/list-work-names sys uid))))
          (is (some #{"田植"} (:work_names (paints/list-work-names sys uid)))))
        (testing "B3-7.3-04 / B3-10-01 ガントが無くても塗りは残る"
          (is (pos? (count (:work_names (paints/list-work-names sys uid)))))
          (is (nil? (http/match-api :get "/api/user/gantt"))))))))
