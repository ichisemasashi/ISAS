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
         (is (= "place_unset" (:code (emaff/preview-aerial sys uid {:west 1 :south 2 :east 3})))))
       (testing "bbox 指定で地理院へフォールバック"
         (let [r (emaff/preview-aerial sys uid {:west 139.0 :south 35.0 :east 141.0 :north 37.0})]
           (is (true? (:ok r)))
           (is (= "gsi" (:source r)))
           (is (= "aerial" (:kind r)))
           (is (= 139.0 (get-in r [:bbox :west])))))
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
       (testing "下地が取れて筆が取れないときは partial"
         (with-redefs [gsi/stitch-bbox (fn [_ _] tile)]
           (let [r (emaff/import-for-place sys uid)]
             (is (true? (:ok r)))
             (is (= "emaff_partial" (:code r)))
             (is (= 3 (count (:basemaps r))))
             (is (seq (:warnings r)))
             (is (every? :ready (:basemaps (fields/list-basemap-status sys uid)))))))
       (testing "warnings 無しの完了"
         (with-redefs [gsi/stitch-bbox (fn [_ _] tile)]
           (with-redefs-fn {#'emaff/import-polygons! (fn [_ _] {:fields [] :warnings []})}
             (fn []
               (fields/put-place sys uid {:west 139.2 :south 35.2 :east 139.25 :north 35.24})
               (let [r (emaff/import-for-place sys uid)]
                 (is (true? (:ok r)))
                 (is (nil? (:code r))))))))
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
             (is (= "emaff_unavailable" (:code r))))))))))
