(ns isas.emaff-test
  (:require [clojure.test :refer [deftest is testing]]
            [isas.accounts :as accounts]
            [isas.db :as db]
            [isas.emaff :as emaff]
            [isas.fields :as fields]
            [isas.gsi :as gsi]
            [isas.test-util :as tu])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(defn- tiny-jpeg-bytes []
  (let [img (BufferedImage. 16 16 BufferedImage/TYPE_INT_RGB)
        baos (java.io.ByteArrayOutputStream.)]
    (ImageIO/write img "jpg" baos)
    (.toByteArray baos)))

(defn- user-id [sys email]
  (let [admin (db/find-admin-by-email (:ds sys) "admin@example.com")]
    (accounts/invite sys "admin" (:id admin) email)
    (:id (db/find-user-by-email (:ds sys) email))))

(deftest preview-aerial
  (tu/with-sys
   (fn [sys]
     (let [uid (user-id sys "emaff-prev@example.com")]
       (testing "場所未設定"
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid nil))))
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {}))))
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {:west 1}))))
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {:west 1 :south 2}))))
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {:west 1 :south 2 :east 3}))))
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {:west "x" :south "35" :east "36" :north "37"})))))
       (testing "bbox 指定で地理院へフォールバック"
         (let [r (emaff/preview-aerial sys uid {:west 139.0 :south 35.0 :east 141.0 :north 37.0})]
           (is (true? (:ok r)))
           (is (= "gsi" (:source r)))
           (is (= "aerial" (:kind r)))
           (is (= 139.0 (get-in r [:bbox :west]))))
         (let [r (emaff/preview-aerial sys uid {:west "140.05" :south "35.10" :east "140.12" :north "35.18"})]
           (is (true? (:ok r)))
           (is (= 140.05 (get-in r [:bbox :west])))
           (is (= 35.18 (get-in r [:bbox :north]))))
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {:west 141.0 :south 35.0 :east 139.0 :north 37.0})))))
       (testing "保存済み作業場所を使う"
         (fields/put-place sys uid {:west 139.0 :south 35.0 :east 141.0 :north 37.0})
         (let [r (emaff/preview-aerial sys uid nil)]
           (is (true? (:ok r)))
           (is (= "gsi" (:source r)))))))))

(deftest import-for-place
  (tu/with-sys
   (fn [sys]
     (let [uid (user-id sys "emaff-imp@example.com")
           tile (tiny-jpeg-bytes)]
       (testing "場所未設定"
         (is (= "place_unset" (:code (emaff/import-for-place sys uid)))))
       (fields/put-place sys uid {:west 139.0 :south 35.0 :east 139.05 :north 35.04})
       (testing "下地3種が取れたら完了（区画は手作業）"
         (with-redefs [gsi/stitch-bbox (fn [_ _] tile)]
           (let [r (emaff/import-for-place sys uid)]
             (is (true? (:ok r)))
             (is (nil? (:code r)))
             (is (= 3 (count (:basemaps r))))
             (is (empty? (:warnings r)))
             (is (every? :ready (:basemaps (fields/list-basemap-status sys uid)))))))
       (testing "下地の一部だけ取れたときは partial"
         (with-redefs [gsi/stitch-bbox (fn [_ kind]
                                         (when (= "aerial" (str kind)) tile))]
           (fields/put-place sys uid {:west 139.2 :south 35.2 :east 139.25 :north 35.24})
           (let [r (emaff/import-for-place sys uid)]
             (is (true? (:ok r)))
             (is (= "emaff_partial" (:code r)))
             (is (= ["aerial"] (:basemaps r)))
             (is (seq (:warnings r))))))
       (testing "下地も取れないときは unavailable"
         (with-redefs [gsi/stitch-bbox (fn [_ _] nil)]
           (fields/put-place sys uid {:west 139.1 :south 35.1 :east 139.15 :north 35.14})
           (let [r (emaff/import-for-place sys uid)]
             (is (false? (:ok r)))
             (is (= "emaff_unavailable" (:code r))))))
       (testing "下地保存失敗は warnings"
         (with-redefs [gsi/stitch-bbox (fn [_ _] tile)
                       fields/put-basemap-bytes (fn [& _] {:ok false :code "import_invalid"})]
           (fields/put-place sys uid {:west 139.3 :south 35.3 :east 139.35 :north 35.34})
           (let [r (emaff/import-for-place sys uid)]
             (is (false? (:ok r)))
             (is (= "emaff_unavailable" (:code r))))))
       (testing "同時取込は emaff_busy"
         (reset! @#'emaff/import-inflight #{})
         (fields/put-place sys uid {:west 139.4 :south 35.4 :east 139.45 :north 35.44})
         (with-redefs [gsi/stitch-bbox (fn [_ _]
                                         (Thread/sleep 300)
                                         tile)]
           (let [f (future (emaff/import-for-place sys uid))
                 _ (Thread/sleep 50)
                 r2 (emaff/import-for-place sys uid)
                 r1 @f]
             (is (= "emaff_busy" (:code r2)))
             (is (true? (:ok r1)))
             (is (empty? @@#'emaff/import-inflight)))))))))
