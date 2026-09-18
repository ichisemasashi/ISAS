(ns isas.orders-test
  (:require [clojure.test :refer [deftest is testing]]
            [isas.db :as db]
            [isas.orders :as orders]
            [isas.test-util :as tu]
            [isas.time :as time])
  (:import [java.time Instant]))

(deftest time-helpers-for-orders
  (binding [time/*now-fn* (fn [] (Instant/parse "2026-09-12T00:00:00Z"))]
    (is (= "2026-09-12" (time/today-work-date)))
    (is (= "08:00" (time/order-default-start)))
    (is (= "17:00" (time/order-default-end)))
    (is (true? (time/work-date-ok? "2026-09-12")))
    (is (false? (time/work-date-ok? "2026/09/12")))
    (is (true? (time/clock-time-ok? "08:00")))
    (is (false? (time/clock-time-ok? "8:00")))
    (is (= "time_invalid" (:code (time/normalize-order-times "bad" "08:00" "17:00"))))
    (is (= "time_order" (:code (time/normalize-order-times "2026-09-12" "17:00" "08:00"))))
    (is (= "time_order" (:code (time/normalize-order-times "2026-09-12" "08:00" "08:00"))))
    (is (true? (:ok (time/normalize-order-times "2026-09-12" "08:00" "17:00"))))
    (is (= (orders/default-new-order)
           {:work_date "2026-09-12" :start_time "08:00" :end_time "17:00"
            :work_name "" :body "" :recipient_emails [] :field_ids []}))))

(deftest order-crud-and-visibility
  (tu/with-sys
    (fn [sys]
      (let [app (tu/app sys)
            asid (tu/admin-sid app)
            pw-a (tu/invite-pw app asid "oa@example.com")
            pw-b (tu/invite-pw app asid "ob@example.com")
            sid-a (tu/user-sid app "oa@example.com" pw-a)
            sid-b (tu/user-sid app "ob@example.com" pw-b)
            uid-a (:id (db/find-user-by-email (:ds sys) "oa@example.com"))
            uid-b (:id (db/find-user-by-email (:ds sys) "ob@example.com"))
            fa (tu/parse (tu/post-json app "/api/user/fields" {:name "北" :geojson tu/square} "user" sid-a))
            fb (tu/parse (tu/post-json app "/api/user/fields" {:name "南" :geojson tu/square-east} "user" sid-a))
            id1 (get-in fa [:field :id])
            id2 (get-in fb [:field :id])]
        (testing "作成と一覧"
          (let [r (orders/create-order sys uid-a {:work_date "2026-09-12"
                                                  :start_time "08:00"
                                                  :end_time "17:00"
                                                  :work_name "田植え"
                                                  :body "お願いします"
                                                  :recipient_emails ["ob@example.com"]
                                                  :field_ids [id1]})]
            (is (true? (:ok r)))
            (is (= "issuer" (:role r)))
            (is (= "open" (:status r)))
            (let [listed (orders/list-orders sys uid-a)
                  recv (orders/list-orders sys uid-b)]
              (is (= 1 (count (:sent listed))))
              (is (= 1 (count (:received recv)))))))
        (testing "地図と他人"
          (let [oid (get-in (first (:sent (orders/list-orders sys uid-a))) [:id])
                m (orders/order-map sys uid-b oid)
                of (orders/others-fields sys uid-b)
                wn (orders/others-work-names sys uid-b)]
            (is (true? (:ok m)))
            (is (= 1 (count (:fields m))))
            (is (= 1 (count (:fields of))))
            (is (some #{"田植え"} (:work_names wn)))
            (is (= "work_name_unrelated"
                   (:code (orders/others-paints sys uid-b "無関係"))))))
        (testing "閉じと切断"
          (let [oid (get-in (first (:sent (orders/list-orders sys uid-a))) [:id])]
            (is (true? (:ok (orders/close-order sys uid-a oid))))
            (is (true? (:ok (orders/cut-relation sys "oa@example.com" "ob@example.com"))))
            (is (empty? (:fields (orders/get-order sys uid-b oid))))
            (is (seq (filter :visible (:fields (orders/get-order sys uid-a oid)))))
            (is (= "relation_idle" (:code (orders/cut-relation sys "oa@example.com" "ob@example.com"))))))
        (testing "候補に出した指示の作業名"
          (is (some #{"田植え"} (db/list-work-name-candidates (:ds sys) uid-a)))
          (is (not (some #{"田植え"} (db/list-work-name-candidates (:ds sys) uid-b)))))
        (is (false? (orders/field-in-open-order? sys id1)))
        (is (false? (orders/any-field-in-open-order? sys [id1 id2])))))))
