(ns isas.gantt-test
  (:require [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gantt :as gantt]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [isas.time :as time]
            [next.jdbc :as jdbc])
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
      (is (= "新しい作業" (:title d)))
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
          (testing "圃場0は一覧のみ成功（作成等は no_fields）"
          (let [listed (gantt/list-rows sys uid)]
            (is (true? (:ok listed)))
            (is (= [] (:rows listed))))
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
              oid (get-in fo [:field :id])
              title (:title (gantt/create-title sys uid {:name "題A"}))
              tid (:id title)
              title2 (:title (gantt/create-title sys uid2 {:name "他人題"}))
              tid2 (:id title2)]
          (testing "作成・一覧・並び"
            (let [a (gantt/create-row sys uid {:title_id tid :title "後"
                                               :start_at "2026-09-18T10:00"
                                               :end_at "2026-09-18T12:00"})
                  b (gantt/create-row sys uid {:title_id tid :title "先"
                                               :start_at "2026-09-18T08:00"
                                               :end_at "2026-09-18T09:00"
                                               :work_name "田植え"
                                               :field_ids [id1 id2]})
                  c (gantt/create-row sys uid {:title_id tid :title "同刻"
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
            (is (= "title_required" (:code (gantt/create-row sys uid {:title_id tid :title " "
                                                                      :start_at "2026-09-18T08:00"
                                                                      :end_at "2026-09-18T09:00"}))))
            (is (= "work_name_required" (:code (gantt/create-row sys uid {:title_id tid :title "要名"
                                                                          :start_at "2026-09-18T08:00"
                                                                          :end_at "2026-09-18T09:00"
                                                                          :field_ids [id1]}))))
            (is (= "field_not_found" (:code (gantt/create-row sys uid {:title_id tid :title "他人"
                                                                       :start_at "2026-09-18T08:00"
                                                                       :end_at "2026-09-18T09:00"
                                                                       :work_name "田植え"
                                                                       :field_ids [oid]}))))
            (is (= "field_not_found" (:code (gantt/create-row sys uid {:title_id tid :title "壊"
                                                                       :start_at "2026-09-18T08:00"
                                                                       :end_at "2026-09-18T09:00"
                                                                       :work_name "田植え"
                                                                       :field_ids ["x"]}))))
            (is (= "work_name_too_long" (:code (gantt/create-row sys uid {:title_id tid :title "長"
                                                                          :start_at "2026-09-18T08:00"
                                                                          :end_at "2026-09-18T09:00"
                                                                          :work_name (apply str (repeat 101 "あ"))}))))
            (is (true? (:ok (gantt/create-row sys uid {:title_id tid :title "単数対象"
                                                       :start_at "2026-09-18T13:00"
                                                       :end_at "2026-09-18T14:00"
                                                       :work_name "刈"
                                                       :field_ids id1})))))
          (testing "更新と進捗"
            (let [row (:row (gantt/create-row sys uid {:title_id tid :title "進捗"
                                                       :start_at "2026-09-19T08:00"
                                                       :end_at "2026-09-19T17:00"
                                                       :work_name "田植え"
                                                       :field_ids [id1 id2]}))
                  gid (:id row)
                  memo (:row (gantt/create-row sys uid {:title_id tid :title "メモ"
                                                        :start_at "2026-09-19T18:00"
                                                        :end_at "2026-09-19T19:00"}))]
              (is (= "gantt_not_found" (:code (gantt/update-row sys uid "x" {:title_id tid :title "a"
                                                                            :start_at "2026-09-19T08:00"
                                                                            :end_at "2026-09-19T09:00"}))))
              (is (= "gantt_not_found" (:code (gantt/update-row sys uid 99999 {:title_id tid :title "a"
                                                                              :start_at "2026-09-19T08:00"
                                                                              :end_at "2026-09-19T09:00"}))))
              (is (= "gantt_not_found" (:code (gantt/update-row sys uid2 gid {:title_id tid2 :title "盗"
                                                                             :start_at "2026-09-19T08:00"
                                                                             :end_at "2026-09-19T09:00"}))))
              (is (= "title_required" (:code (gantt/update-row sys uid gid {:title_id tid :title " "
                                                                            :start_at "2026-09-19T08:00"
                                                                            :end_at "2026-09-19T09:00"}))))
              (is (true? (:ok (gantt/update-row sys uid gid {:title_id tid :title "進捗2"
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
              (is (true? (:ok (gantt/update-row sys uid gid {:title_id tid :title "ラベルメモ"
                                                             :start_at "2026-09-19T08:00"
                                                             :end_at "2026-09-19T17:00"
                                                             :work_name "田植え"
                                                             :field_ids []}))))
              (is (false? (:applicable (gantt/row-progress sys uid gid))))))
          (testing "候補と圃場削除"
            (gantt/create-row sys uid {:title_id tid :title "候補行"
                                       :start_at "2026-09-20T08:00"
                                       :end_at "2026-09-20T09:00"
                                       :work_name "ガント専用"})
            (paints/create-paint sys uid2 {:field_id oid :work_name "秘密" :geojson tu/square-inner})
            (let [cands (:work_names (gantt/work-name-candidates sys uid))]
              (is (some #{"田植え"} cands))
              (is (some #{"ガント専用"} cands))
              (is (not (some #{"秘密"} cands))))
            (let [tgt (:row (gantt/create-row sys uid {:title_id tid :title "消す対象"
                                                       :start_at "2026-09-21T08:00"
                                                       :end_at "2026-09-21T09:00"
                                                       :work_name "田植え"
                                                       :field_ids [id2]}))
                  gid (:id tgt)]
              (is (= [id2] (db/list-gantt-targets (:ds sys) gid)))
              (is (true? (:ok (fields/delete-field sys uid id2))))
              (is (= [] (db/list-gantt-targets (:ds sys) gid)))
              (is (some? (db/find-gantt-row (:ds sys) uid gid)))))
          (testing "ソフト削除と日次進捗"
            (let [row (:row (gantt/create-row sys uid {:title_id tid :title "履歴行"
                                                       :start_at "2026-09-17T08:00"
                                                       :end_at "2026-09-17T17:00"
                                                       :work_name "田植え"
                                                       :field_ids [id1]}))
                  gid (:id row)]
              (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-19T01:00:00Z"))]
                (let [auto1 (gantt/finalize-missing-days-for-user sys uid)
                      days1 (:days (gantt/list-progress-days sys uid gid))
                      auto2 (gantt/finalize-missing-days-for-user sys uid)
                      hist (db/list-gantt-progress-days (:ds sys) gid)]
                  (is (pos? (:finalized auto1)))
                  (is (zero? (:finalized auto2)))
                  (is (some #(= "2026-09-18" (:day %)) days1))
                  (is (true? (:ok (gantt/soft-delete-row sys uid gid))))
                  (is (not (some #(= gid (:id %)) (:rows (gantt/list-rows sys uid)))))
                  (is (= (count hist) (count (db/list-gantt-progress-days (:ds sys) gid))))
                  (is (= "gantt_not_found" (:code (gantt/update-row sys uid gid
                                                                    {:title "x"
                                                                     :start_at "2026-09-17T08:00"
                                                                     :end_at "2026-09-17T17:00"}))))
                  (is (= "gantt_not_found" (:code (gantt/list-progress-days sys uid gid))))))
              (let [alive (:row (gantt/create-row sys uid {:title_id tid :title "管理確定"
                                                           :start_at "2026-09-18T08:00"
                                                           :end_at "2026-09-18T09:00"}))
                    aid (:id alive)
                    all (gantt/admin-finalize-progress sys {:day "2026-09-18"})
                    one (gantt/admin-finalize-progress sys {:day "2026-09-18"
                                                            :email "gantt@example.com"})
                    miss (gantt/admin-finalize-progress sys {:email "nobody@example.com"})
                    bad (gantt/admin-finalize-progress sys {:day "bad"})]
                (is (true? (:ok all)))
                (is (true? (:ok one)))
                (is (zero? (:finalized one)))
                (is (= "user_not_found" (:code miss)))
                (is (= "time_invalid" (:code bad)))
                (is (some #(= "2026-09-18" (:day %))
                          (:days (gantt/list-progress-days sys uid aid))))
                (db/insert-gantt-progress-day! (:ds sys) {:gantt-id aid
                                                          :day "2026-09-10"
                                                          :percent nil
                                                          :applicable false
                                                          :finalized-by "auto"})
                (is (some #(and (= "2026-09-10" (:day %)) (false? (:applicable %)))
                          (:days (gantt/list-progress-days sys uid aid)))))
            (let [pw0 (tu/invite-pw app asid "gantt-zero@example.com")
                  _ (tu/user-sid app "gantt-zero@example.com" pw0)
                  uid0 (:id (db/find-user-by-email (:ds sys) "gantt-zero@example.com"))]
              (is (= "no_fields" (:code (gantt/soft-delete-row sys uid0 1))))
              (is (= "no_fields" (:code (gantt/list-progress-days sys uid0 1)))))
            (is (= "gantt_not_found" (:code (gantt/soft-delete-row sys uid "x"))))
            (is (= "gantt_not_found" (:code (gantt/soft-delete-row sys uid 99999))))
            (is (= "gantt_not_found" (:code (gantt/list-progress-days sys uid 99999))))
            (let [pw0 (tu/invite-pw app asid "gantt-title-zero@example.com")
                  _ (tu/user-sid app "gantt-title-zero@example.com" pw0)
                  uid-t0 (:id (db/find-user-by-email (:ds sys) "gantt-title-zero@example.com"))]
              (testing "題名 CRUD"
                (is (= "no_fields" (:code (gantt/list-titles sys uid-t0))))
                (is (= "no_fields" (:code (gantt/create-title sys uid-t0 {:name "x"}))))
                (is (= "no_fields" (:code (gantt/update-title sys uid-t0 1 {:name "x"}))))
                (is (= "no_fields" (:code (gantt/soft-delete-title sys uid-t0 1))))
                (let [listed (:titles (gantt/list-titles sys uid))]
                  (is (some #(= "題A" (:name %)) listed)))
                (is (= "title_required" (:code (gantt/create-title sys uid {:name " "}))))
                (let [t (:title (gantt/create-title sys uid {:name "題B"}))
                      u (gantt/update-title sys uid (:id t) {:name "題B2"})]
                  (is (= "題B2" (get-in u [:title :name])))
                  (is (= "title_not_found" (:code (gantt/update-title sys uid 99999 {:name "x"}))))
                  (is (= "title_not_found" (:code (gantt/soft-delete-title sys uid 99999))))
                  (let [w (:row (gantt/create-row sys uid {:title_id (:id t)
                                                           :title "子作業"
                                                           :start_at "2026-09-28T08:00"
                                                           :end_at "2026-09-28T09:00"}))]
                    (is (true? (:ok (gantt/soft-delete-title sys uid (:id t)))))
                    (is (not (some #(= (:id t) (:id %)) (:titles (gantt/list-titles sys uid)))))
                    (is (not (some #(= (:id w) (:id %)) (:rows (gantt/list-rows sys uid)))))))))
            (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-10T01:00:00Z"))]
              (let [future-row (:row (gantt/create-row sys uid {:title_id tid :title "未来"
                                                                 :start_at "2026-09-20T08:00"
                                                                 :end_at "2026-09-20T09:00"}))]
                (is (zero? (:finalized (gantt/finalize-missing-days-for-user sys uid))))
                (is (true? (:ok (gantt/soft-delete-row sys uid (:id future-row)))))))
            (is (zero? (#'gantt/backfill-days-for-row sys uid {:id 1 :start_at "bad"} "2026-09-18" "auto")))
            (is (zero? (#'gantt/backfill-days-for-row sys uid {:id 1 :start_at "2026-09-18T08:00"} "bad" "auto")))
            (binding [time/*now-fn* (fn [] (Instant/parse "2026-12-01T01:00:00Z"))]
              (let [old (:row (gantt/create-row sys uid {:title_id tid :title "古い"
                                                         :start_at "2026-01-01T08:00"
                                                         :end_at "2026-01-01T09:00"}))]
                (is (pos? (:finalized (gantt/finalize-missing-days-for-user sys uid))))
                (is (true? (:ok (gantt/soft-delete-row sys uid (:id old))))))))))))))

(deftest gantt-titles-coverage-test
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "gtcov@example.com")
            usid (tu/user-sid app "gtcov@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "gtcov@example.com"))
            f (fields/create-field sys uid {:name "北" :geojson tu/square})
            fid (get-in f [:field :id])]
        (testing "title_id 検証と題名エッジ"
          (let [orphan (gantt/create-row sys uid {:title "独立"
                                                  :start_at "2026-09-18T08:00"
                                                  :end_at "2026-09-18T09:00"})]
            (is (true? (:ok orphan)))
            (is (nil? (get-in orphan [:row :title_id]))))
          (is (= "title_not_found"
                 (:code (gantt/create-row sys uid {:title_id "x" :title "x"
                                                   :start_at "2026-09-18T08:00"
                                                   :end_at "2026-09-18T09:00"}))))
          (is (= "title_not_found"
                 (:code (gantt/create-row sys uid {:title_id 99999 :title "x"
                                                   :start_at "2026-09-18T08:00"
                                                   :end_at "2026-09-18T09:00"}))))
          (let [t (:title (gantt/create-title sys uid {:name "移設元"}))
                tid (:id t)]
            (is (= "title_too_long"
                   (:code (gantt/update-title sys uid tid
                                              {:name (apply str (repeat 201 "あ"))}))))
            (is (true? (:ok (gantt/update-title sys uid tid {:name "移設先"}))))
            (is (= "title_not_found" (:code (gantt/update-title sys uid "bad" {:name "x"}))))
            (is (= "title_not_found" (:code (gantt/soft-delete-title sys uid "bad"))))
            (let [row (:row (gantt/create-row sys uid {:title_id tid :title "子"
                                                       :start_at "2026-09-18T08:00"
                                                       :end_at "2026-09-18T09:00"}))]
              (is (= 1 (count (db/list-gantt-rows-for-title (:ds sys) uid tid))))
              (is (some? row))
              (is (true? (:ok (gantt/soft-delete-title sys uid tid))))
              (is (= "title_not_found"
                     (:code (gantt/create-row sys uid {:title_id tid :title "後"
                                                       :start_at "2026-09-18T10:00"
                                                       :end_at "2026-09-18T11:00"}))))
              (is (= "title_not_found"
                     (:code (gantt/update-title sys uid tid {:name "再"}))))
              (is (= "title_not_found"
                     (:code (gantt/soft-delete-title sys uid tid)))))))
        (testing "migrate-gantt-titles!"
          (let [t (:title (gantt/create-title sys uid {:name "既存題"}))
                orphan (jdbc/execute-one!
                        (:ds sys)
                        ["INSERT INTO gantt_rows (user_id, title_id, title, start_at, end_at, work_name, created_at, updated_at)
                          VALUES (?, NULL, ?, ?, ?, NULL, ?, ?) RETURNING *"
                         uid "孤児題" "2026-09-18T08:00" "2026-09-18T09:00"
                         (time/now-utc) (time/now-utc)])
                same (jdbc/execute-one!
                      (:ds sys)
                      ["INSERT INTO gantt_rows (user_id, title_id, title, start_at, end_at, work_name, created_at, updated_at)
                        VALUES (?, NULL, ?, ?, ?, NULL, ?, ?) RETURNING *"
                       uid "既存題" "2026-09-18T10:00" "2026-09-18T11:00"
                       (time/now-utc) (time/now-utc)])]
            (#'db/migrate-gantt-titles! (:ds sys))
            (is (some? (:title_id (db/find-gantt-row (:ds sys) uid (:id orphan)))))
            (is (= (:id t) (:title_id (db/find-gantt-row (:ds sys) uid (:id same)))))
            (#'db/migrate-gantt-titles! (:ds sys))))))))

