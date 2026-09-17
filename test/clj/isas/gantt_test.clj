(ns isas.gantt-test
  (:require [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.time :as time])
  (:import [java.time Instant]))

(deftest normalize-and-percent-test
  (is (= "title_required" (:code (gantt/normalize-title nil))))
  (is (= "title_required" (:code (gantt/normalize-title ""))))
  (is (= "title_required" (:code (gantt/normalize-title "   "))))
  (is (= "title_too_long" (:code (gantt/normalize-title (apply str (repeat 201 "あ"))))))
  (is (= "題" (:title (gantt/normalize-title " 題 "))))
  (is (= "time_invalid" (:code (gantt/normalize-times "bad" "2026-01-01T10:00"))))
  (is (= "time_invalid" (:code (gantt/normalize-times "2026-01-01T10:00:00" "2026-01-01T11:00"))))
  (is (= "time_order" (:code (gantt/normalize-times "2026-01-01T10:00" "2026-01-01T10:00"))))
  (is (= "time_order" (:code (gantt/normalize-times "2026-01-01T11:00" "2026-01-01T10:00"))))
  (is (true? (:ok (gantt/normalize-times "2026-01-01T08:00" "2026-01-01T17:00"))))
  (is (nil? (gantt/progress-percent nil nil false)))
  (is (nil? (gantt/progress-percent 0 0 false)))
  (is (= 100 (gantt/progress-percent 50 100 true)))
  (is (= 50 (gantt/progress-percent 50 100 false)))
  (is (= 99 (gantt/progress-percent 100 100 false)))
  (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-18T00:00:00Z"))]
    (let [d (gantt/default-new-row)]
      (is (= "新しい予定" (:title d)))
      (is (= "2026-09-18T08:00" (:start_at d)))
      (is (= "2026-09-18T17:00" (:end_at d)))
      (is (nil? (:work_name d)))
      (is (= [] (:field_ids d))))))

(deftest gantt-crud-and-progress-test
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "gantt@example.com")
            usid (tu/user-sid app "gantt@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "gantt@example.com"))
            pw2 (tu/invite-pw app asid "gantt-other@example.com")
            usid2 (tu/user-sid app "gantt-other@example.com" pw2)
            uid2 (:id (db/find-user-by-email (:ds sys) "gantt-other@example.com"))]
        (testing "圃場0は no_fields"
          (is (= "no_fields" (:code (gantt/list-rows sys uid))))
          (is (= "no_fields" (:code (gantt/create-row sys uid {:title "a"
                                                               :start_at "2026-09-18T08:00"
                                                               :end_at "2026-09-18T09:00"}))))
          (is (= "no_fields" (:code (gantt/update-row sys uid 1 {:title "a"
                                                                 :start_at "2026-09-18T08:00"
                                                                 :end_at "2026-09-18T09:00"}))))
          (is (= "no_fields" (:code (gantt/row-progress sys uid 1)))))
        (let [f1 (fields/create-field sys uid {:name "北" :geojson tu/square})
              f2 (fields/create-field sys uid {:name "南" :geojson tu/square-east})
              fo (fields/create-field sys uid2 {:name "他人" :geojson tu/square})
              id1 (get-in f1 [:field :id])
              id2 (get-in f2 [:field :id])
              oid (get-in fo [:field :id])]
          (testing "作成・一覧・並び"
            (let [a (gantt/create-row sys uid {:title "後"
                                               :start_at "2026-09-18T10:00"
                                               :end_at "2026-09-18T12:00"})
                  b (gantt/create-row sys uid {:title "先"
                                               :start_at "2026-09-18T08:00"
                                               :end_at "2026-09-18T09:00"
                                               :work_name "田植え"
                                               :field_ids [id1 id2]})
                  c (gantt/create-row sys uid {:title "同刻"
                                               :start_at "2026-09-18T08:00"
                                               :end_at "2026-09-18T09:30"})
                  rows (:rows (gantt/list-rows sys uid))]
              (is (true? (:ok a)))
              (is (true? (:ok b)))
              (is (nil? (get-in a [:row :work_name])))
              (is (= [] (get-in a [:row :field_ids])))
              (is (= [id1 id2] (get-in b [:row :field_ids])))
              (is (= ["先" "同刻" "後"] (mapv :title rows)))))
          (testing "検証エラー"
            (is (= "title_required" (:code (gantt/create-row sys uid {:title " "
                                                                      :start_at "2026-09-18T08:00"
                                                                      :end_at "2026-09-18T09:00"}))))
            (is (= "work_name_required" (:code (gantt/create-row sys uid {:title "要名"
                                                                          :start_at "2026-09-18T08:00"
                                                                          :end_at "2026-09-18T09:00"
                                                                          :field_ids [id1]}))))
            (is (= "field_not_found" (:code (gantt/create-row sys uid {:title "他人"
                                                                       :start_at "2026-09-18T08:00"
                                                                       :end_at "2026-09-18T09:00"
                                                                       :work_name "田植え"
                                                                       :field_ids [oid]}))))
            (is (= "field_not_found" (:code (gantt/create-row sys uid {:title "壊"
                                                                       :start_at "2026-09-18T08:00"
                                                                       :end_at "2026-09-18T09:00"
                                                                       :work_name "田植え"
                                                                       :field_ids ["x"]}))))
            (is (= "work_name_too_long" (:code (gantt/create-row sys uid {:title "長"
                                                                          :start_at "2026-09-18T08:00"
                                                                          :end_at "2026-09-18T09:00"
                                                                          :work_name (apply str (repeat 101 "あ"))}))))
            (is (true? (:ok (gantt/create-row sys uid {:title "単数対象"
                                                       :start_at "2026-09-18T13:00"
                                                       :end_at "2026-09-18T14:00"
                                                       :work_name "刈"
                                                       :field_ids id1})))))
          (testing "更新と進捗"
            (let [row (:row (gantt/create-row sys uid {:title "進捗"
                                                       :start_at "2026-09-19T08:00"
                                                       :end_at "2026-09-19T17:00"
                                                       :work_name "田植え"
                                                       :field_ids [id1 id2]}))
                  gid (:id row)
                  memo (:row (gantt/create-row sys uid {:title "メモ"
                                                        :start_at "2026-09-19T18:00"
                                                        :end_at "2026-09-19T19:00"}))]
              (is (= "gantt_not_found" (:code (gantt/update-row sys uid "x" {:title "a"
                                                                            :start_at "2026-09-19T08:00"
                                                                            :end_at "2026-09-19T09:00"}))))
              (is (= "gantt_not_found" (:code (gantt/update-row sys uid 99999 {:title "a"
                                                                              :start_at "2026-09-19T08:00"
                                                                              :end_at "2026-09-19T09:00"}))))
              (is (= "gantt_not_found" (:code (gantt/update-row sys uid2 gid {:title "盗"
                                                                             :start_at "2026-09-19T08:00"
                                                                             :end_at "2026-09-19T09:00"}))))
              (is (= "title_required" (:code (gantt/update-row sys uid gid {:title " "
                                                                            :start_at "2026-09-19T08:00"
                                                                            :end_at "2026-09-19T09:00"}))))
              (is (true? (:ok (gantt/update-row sys uid gid {:title "進捗2"
                                                             :start_at "2026-09-19T08:00"
                                                             :end_at "2026-09-19T17:00"
                                                             :work_name "田植え"
                                                             :field_ids [id1]}))))
              (let [na (gantt/row-progress sys uid (:id memo))]
                (is (true? (:ok na)))
                (is (false? (:applicable na)))
                (is (nil? (:percent na))))
              (is (= "gantt_not_found" (:code (gantt/row-progress sys uid 99999))))
              (let [p0 (gantt/row-progress sys uid gid)]
                (is (true? (:applicable p0)))
                (is (= 0 (:percent p0)))
                (is (= "none" (:status (first (:fields p0))))))
              (paints/create-paint sys uid {:field_id id1 :work_name "田植え" :geojson tu/square-inner})
              (let [pp (gantt/row-progress sys uid gid)]
                (is (= "partial" (:status (first (:fields pp)))))
                (is (pos? (:percent pp)))
                (is (< (:percent pp) 100)))
              (paints/complete-field sys uid id1 "田植え")
              (let [pd (gantt/row-progress sys uid gid)]
                (is (= 100 (:percent pd)))
                (is (= "done" (:status (first (:fields pd))))))
              (is (true? (:ok (gantt/update-row sys uid gid {:title "ラベルメモ"
                                                             :start_at "2026-09-19T08:00"
                                                             :end_at "2026-09-19T17:00"
                                                             :work_name "田植え"
                                                             :field_ids []}))))
              (is (false? (:applicable (gantt/row-progress sys uid gid))))))
          (testing "候補と圃場削除"
            (gantt/create-row sys uid {:title "候補行"
                                       :start_at "2026-09-20T08:00"
                                       :end_at "2026-09-20T09:00"
                                       :work_name "ガント専用"})
            (paints/create-paint sys uid2 {:field_id oid :work_name "秘密" :geojson tu/square-inner})
            (let [cands (:work_names (gantt/work-name-candidates sys uid))]
              (is (some #{"田植え"} cands))
              (is (some #{"ガント専用"} cands))
              (is (not (some #{"秘密"} cands))))
            (let [tgt (:row (gantt/create-row sys uid {:title "消す対象"
                                                       :start_at "2026-09-21T08:00"
                                                       :end_at "2026-09-21T09:00"
                                                       :work_name "田植え"
                                                       :field_ids [id2]}))
                  gid (:id tgt)]
              (is (= [id2] (db/list-gantt-targets (:ds sys) gid)))
              (is (true? (:ok (fields/delete-field sys uid id2))))
              (is (= [] (db/list-gantt-targets (:ds sys) gid)))
              (is (some? (db/find-gantt-row (:ds sys) uid gid))))))))))
