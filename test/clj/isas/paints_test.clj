(ns isas.paints-test
  (:require [clojure.test :refer [deftest is]]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.geo :as geo]
            [isas.http :as http]
            [isas.paints :as paints]
            [isas.test-util :as tu]
            [ring.mock.request :as mock]))

(deftest normalize-and-status-test
  (is (= "work_name_required" (:code (paints/normalize-work-name nil))))
  (is (= "work_name_required" (:code (paints/normalize-work-name ""))))
  (is (= "work_name_required" (:code (paints/normalize-work-name "   "))))
  (is (= "work_name_too_long" (:code (paints/normalize-work-name (apply str (repeat 101 "x"))))))
  (is (true? (:ok (paints/normalize-work-name (apply str (repeat 100 "あ"))))))
  (is (= "田植え" (:work-name (paints/normalize-work-name " 田植え "))))
  (is (= "none" (paints/paint-status 0 100)))
  (is (= "none" (paints/paint-status nil nil)))
  (is (= "partial" (paints/paint-status 50 100)))
  (is (= "done" (paints/paint-status 100 100)))
  (is (= "done" (paints/paint-status 99.6 100)))
  (is (= "done" (paints/paint-status 200 100)))
  (is (= "done" (paints/paint-status 5 0))))

(deftest paints-unit-test
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw (tu/invite-pw app asid "pt@example.com")
            usid (tu/user-sid app "pt@example.com" pw)
            uid (:id (db/find-user-by-email (:ds sys) "pt@example.com"))
            f (fields/create-field sys uid {:name "北" :geojson tu/square})
            fid (get-in f [:field :id])]
        (is (false? (paints/field-has-paint? (:ds sys) fid)))
        (is (false? (paints/any-field-has-paint? (:ds sys) [fid])))
        (is (= "work_name_required" (:code (paints/list-paints sys uid ""))))
        (is (= "work_name_required" (:code (paints/create-paint sys uid {:field_id fid :work_name "" :geojson tu/square}))))
        (is (= "field_not_found" (:code (paints/create-paint sys uid {:field_id "x" :work_name "a" :geojson tu/square}))))
        (is (= "field_not_found" (:code (paints/create-paint sys uid {:work_name "a" :geojson tu/square}))))
        (is (true? (:ok (paints/create-paint sys uid {:field_id (str fid)
                                                     :work_name "刈"
                                                     :geojson (geo/to-json tu/square-inner)}))))
        (is (true? (paints/field-has-paint? (:ds sys) fid)))
        (is (true? (:ok (fields/update-field sys uid fid {:name "北改"}))))
        (is (pos? (count (db/list-paints-for-field (:ds sys) fid))))
        (is (true? (:ok (fields/update-field sys uid fid {:geojson tu/square-inner}))))
        (is (pos? (count (db/list-paints-for-field (:ds sys) fid))))
        (is (= "work_name_required" (:code (paints/complete-field sys uid fid "  "))))
        (is (= "work_name_too_long" (:code (paints/complete-field sys uid fid (apply str (repeat 101 "あ"))))))
        (is (= "field_not_found" (:code (paints/complete-field sys uid 99999 "刈"))))
        (is (= "paint_not_found" (:code (paints/delete-paint sys uid nil))))
        (is (= "paint_not_found" (:code (paints/delete-paint sys uid "x"))))
        (is (= "work_name_required" (:code (paints/delete-field-paints sys uid fid nil))))
        (is (= "field_not_found" (:code (paints/delete-field-paints sys uid 99999 "刈"))))
        (let [row (first (db/list-paints-for-field (:ds sys) fid))]
          (db/update-paint-geojson! (:ds sys) (:id row) "{")
          (let [sum (paints/field-paint-summary (db/find-field (:ds sys) uid fid)
                                                (db/list-paints-for-field (:ds sys) fid))]
            (is (= "none" (:status sum)))
            (is (= 1 (count (:paints sum))))))
        (paints/delete-paints-for-field! sys fid)
        (is (false? (paints/field-has-paint? (:ds sys) fid)))
        (is (= 401 (:status (tu/get-path app "/api/user/work-names"))))
        (let [bad (app (tu/as-user (-> (mock/request :post "/api/user/paints")
                                       (mock/content-type "application/json")
                                       (mock/body "{"))
                                   "user" usid))]
          (is (= "paint_empty" (:code (tu/parse bad)))))
        (let [bad (app (tu/as-user (-> (mock/request :post (str "/api/user/fields/" fid "/complete"))
                                       (mock/content-type "application/json")
                                       (mock/body "{"))
                                   "user" usid))]
          (is (= "work_name_required" (:code (tu/parse bad)))))
        (is (= {} (http/query-params {:query-string nil})))
        (is (= {} (http/query-params {:query-string ""})))
        (is (= {:flag ""} (http/query-params {:query-string "flag"})))
        (is (= {:work_name "田植え"} (http/query-params {:query-string "work_name=%E7%94%B0%E6%A4%8D%E3%81%88"})))
        (is (= {:a "1" :b "2"} (http/query-params {:query-string "a=1&b=2"})))
        (is (= {:work_name "%"} (http/query-params {:query-string "work_name=%"})))
        (is (= "work_name_required"
               (:code (tu/parse (tu/delete-query app (str "/api/user/fields/" fid "/paints") {} "user" usid)))))
        (is (true? (:ok (paints/create-paint sys uid {:field_id fid :work_name "残" :geojson tu/square-inner}))))
        (is (true? (:ok (fields/delete-field sys uid fid))))
        (is (empty? (db/list-paints-for-field (:ds sys) fid)))
        (let [f2 (fields/create-field sys uid {:name "縁" :geojson tu/square})
              id2 (get-in f2 [:field :id])]
          (is (true? (:ok (paints/create-paint sys uid {:field_id id2 :work_name "縁" :geojson tu/square-inner}))))
          (paints/clip-paints-to-field! sys id2 tu/square)
          (is (pos? (count (db/list-paints-for-field (:ds sys) id2))))
          (paints/clip-paints-to-field! sys id2 tu/square-nw)
          (is (zero? (count (db/list-paints-for-field (:ds sys) id2))))
          (let [empty-sum (paints/field-paint-summary {:id id2 :geojson "{"} [])
                outside (paints/field-paint-summary {:id id2 :geojson (geo/to-json tu/square)}
                                                    [{:id 1 :geojson (geo/to-json tu/square-east)}])]
            (is (= "none" (:status empty-sum)))
            (is (pos? (:area_m2 outside)))))))))
