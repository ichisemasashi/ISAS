(ns isas.b4-test
  "基本試験仕様書_工程4 の項番に対応する自動試験。経路名は見ない。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.http :as http]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.ui :as ui]))

(defn- html [opts]
  (tu/page-html (merge {:kind "user" :session {:email "a@example.com"}} opts)))

(deftest b4-blocks
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "b4@example.com")
            usid (tu/user-sid app "b4@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "b4@example.com"))
            pw2 (tu/invite-pw app asid "b4-other@example.com")
            usid2 (tu/user-sid app "b4-other@example.com" pw2)
            uid2 (:id (db/find-user-by-email (:ds sys) "b4-other@example.com"))
            f (fields/create-field sys uid {:name "北" :geojson tu/square})
            fid (get-in f [:field :id])
            f2 (fields/create-field sys uid {:name "南" :geojson tu/square-east})
            id2 (get-in f2 [:field :id])
            fo (fields/create-field sys uid2 {:name "他人" :geojson tu/square})
            oid (get-in fo [:field :id])
            wide (html {:page :gantt :fields [{:id fid :name "北"} {:id id2 :name "南"}]
                        :gantt-rows [{:id 1 :title "予定" :start_at "2026-09-18T08:00"
                                      :end_at "2026-09-18T17:00" :work_name "田植え"
                                      :field_ids [fid]}]
                        :gantt-selected 1
                        :gantt-progress {:ok true :applicable true :percent 25
                                         :fields [{:id fid :status "partial"}]}})]
        (testing "B4-2-01 / B4-2-02 / B4-5.3-01 / B4-10-01 パソコンでガントと％"
          (is (re-find #"ガント" wide))
          (is (re-find #"gantt-circle" wide))
          (is (re-find #"data-percent=\"25\"" wide))
          (is (re-find #"予定を足す" wide))
          (is (re-find #"題名|開始|終了|作業名|対象圃場" wide))
          (is (not (re-find #"指示|日誌" wide))))
        (testing "B4-2-03 / B4-5.4-01 狭い画面にガント編集は無い"
          (is (re-find #"パソコンで開いてください"
                       (html {:page :gantt :narrow? true :fields [{:id 1}]})))
          (is (not (re-find #"予定を足す|data-percent"
                            (html {:page :gantt :narrow? true :fields [{:id 1}]})))))
        (testing "B4-2-04 / B4-4.6-05 / B4-6-03 / B4-10-02 ガント画面に指示・日誌・切断・言語は出さない"
          (is (not (re-find #"指示|日誌|関係を切|言語切替" wide)))
          (is (some? (http/match-api :post "/api/user/orders")))
          (is (nil? (http/match-api :put "/api/user/locale"))))
        (testing "B4-3-01 / B4-5.2-01 / B4-5.3-02 権限"
          (is (= 403 (:status (tu/get-path app "/api/user/gantt" "admin" asid))))
          (is (not (re-find #"href=\"/gantt\"" (html {:page :home :kind "admin"}))))
          (is (true? (:ok (gantt/list-rows sys uid))))
          (let [pw0 (tu/invite-pw app asid "b4-zero@example.com")
                uid0 (:id (db/find-user-by-email (:ds sys) "b4-zero@example.com"))]
            (is (= "no_fields" (:code (gantt/list-rows sys uid0))))
            (is (re-find #"圃場が1枚以上" (html {:page :gantt :fields []})))))
        (testing "B4-4.3-01 圃場削除で対象から外す。行は残る"
          (let [r (gantt/create-row sys uid {:title "対象行"
                                             :start_at "2026-09-18T08:00"
                                             :end_at "2026-09-18T09:00"
                                             :work_name "田植え"
                                             :field_ids [id2]})
                gid (get-in r [:row :id])]
            (is (true? (:ok (fields/delete-field sys uid id2))))
            (is (= [] (db/list-gantt-targets (:ds sys) gid)))
            (is (some? (db/find-gantt-row (:ds sys) uid gid)))))
        (testing "B4-4.4-01 候補は自分の塗りとガントだけ"
          (gantt/create-row sys uid {:title "候補"
                                     :start_at "2026-09-19T08:00"
                                     :end_at "2026-09-19T09:00"
                                     :work_name "ガント作業"})
          (paints/create-paint sys uid {:field_id fid :work_name "塗り作業" :geojson tu/square-inner})
          (paints/create-paint sys uid2 {:field_id oid :work_name "秘密" :geojson tu/square-inner})
          (let [c (:work_names (gantt/work-name-candidates sys uid))]
            (is (some #{"ガント作業"} c))
            (is (some #{"塗り作業"} c))
            (is (not (some #{"秘密"} c)))))
        (testing "B4-4.5-01 / B4-4.5-02 / B4-7.3-01 対象付き行の％と色"
          (let [r (gantt/create-row sys uid {:title "％行"
                                             :start_at "2026-09-20T08:00"
                                             :end_at "2026-09-20T09:00"
                                             :work_name "塗り作業"
                                             :field_ids [fid]})
                gid (get-in r [:row :id])
                p (gantt/row-progress sys uid gid)]
            (is (true? (:applicable p)))
            (is (pos? (:percent p)))
            (is (= "partial" (:status (first (:fields p)))))
            (is (re-find #"data-target-ids" wide))
            (is (= "#e8e8e8" (:dim ui/paint-colors)))))
        (testing "B4-4.5-03 メモ・未選択はサークル無し"
          (is (not (re-find #"data-percent"
                            (html {:page :gantt :fields [{:id 1}]
                                   :gantt-rows [{:id 2 :title "メモ" :start_at "2026-09-18T08:00"
                                                 :end_at "2026-09-18T09:00" :field_ids []}]
                                   :gantt-selected 2}))))
          (is (not (re-find #"data-percent" (html {:page :gantt :fields [{:id 1}]})))))
        (testing "B4-4.5-04 日誌％は無い"
          (is (nil? (http/match-api :post "/api/user/journals")))
          (is (not (re-find #"日誌" wide))))
        (testing "B4-4.6-01 / B4-4.6-02 / B4-4.6-03 / B4-4.6-04 行の規則"
          (is (true? (:ok (gantt/create-row sys uid {:title "メモ行"
                                                     :start_at "2026-09-21T08:00"
                                                     :end_at "2026-09-21T09:00"}))))
          (is (= "work_name_required"
                 (:code (gantt/create-row sys uid {:title "要名"
                                                   :start_at "2026-09-21T10:00"
                                                   :end_at "2026-09-21T11:00"
                                                   :field_ids [fid]}))))
          (is (nil? (http/match-api :delete "/api/user/gantt/1")))
          (is (not (re-find #"消す|削除" wide)))
          (is (true? (:ok (gantt/create-row sys uid {:title "重1"
                                                     :start_at "2026-09-21T12:00"
                                                     :end_at "2026-09-21T14:00"}))))
          (is (true? (:ok (gantt/create-row sys uid {:title "重2"
                                                     :start_at "2026-09-21T12:00"
                                                     :end_at "2026-09-21T13:00"}))))
          (is (re-find #"data-range=\"day\"" (html {:page :gantt :fields [{:id 1}]})))
          (is (re-find #"gantt-ticks" (html {:page :gantt :fields [{:id 1}]})))
          (is (re-find #"週|月" (html {:page :gantt :fields [{:id 1}]})))
          (is (re-find #"id=\"gantt-save-btn\""
                       (html {:page :gantt :fields [{:id fid}]
                              :gantt-rows [{:id 1 :title "行" :start_at "2026-09-18T08:00"
                                            :end_at "2026-09-18T09:00" :field_ids []}]
                              :gantt-selected 1}))))
        (testing "B4-6-01 / B4-6-02 データ"
          (let [names (tu/table-names (:ds sys))
                cols (db/table-columns (:ds sys) "gantt_rows")]
            (is (contains? names "gantt_rows"))
            (is (contains? names "gantt_targets"))
            (is (not (contains? names "work_names")))
            (is (contains? names "orders"))
            (is (contains? cols "work_name"))
            (is (not (contains? cols "deleted")))
            (is (not (contains? cols "order_id")))))
        (testing "B4-7.3-02 ガントが無くても塗りは残る"
          (is (pos? (count (:work_names (paints/list-work-names sys uid)))))
          (is (some? (http/match-api :get "/api/user/gantt"))))))))

(deftest b4-spec-ids-present
  (let [doc (slurp (io/file "docs/基本試験仕様書_工程4.md"))
        ids ["B4-2-01" "B4-2-02" "B4-2-03" "B4-2-04" "B4-3-01" "B4-4.3-01" "B4-4.4-01"
             "B4-4.5-01" "B4-4.5-02" "B4-4.5-03" "B4-4.5-04"
             "B4-4.6-01" "B4-4.6-02" "B4-4.6-03" "B4-4.6-03a" "B4-4.6-04" "B4-4.6-05"
             "B4-5.2-01" "B4-5.3-01" "B4-5.3-02" "B4-5.4-01"
             "B4-6-01" "B4-6-02" "B4-6-03" "B4-7.3-01" "B4-7.3-02" "B4-10-01" "B4-10-02"]]
    (doseq [id ids]
      (is (re-find (re-pattern id) doc)))))
