(ns isas.gsi-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [isas.gsi :as gsi])
  (:import [java.awt.image BufferedImage]
           [java.net InetSocketAddress]
           [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [javax.imageio ImageIO]))

(defn- tiny-jpeg-bytes []
  (let [img (BufferedImage. 256 256 BufferedImage/TYPE_INT_RGB)
        baos (java.io.ByteArrayOutputStream.)]
    (ImageIO/write img "jpg" baos)
    (.toByteArray baos)))

(defn- with-tile-server [bytes status f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler (proxy [HttpHandler] []
                  (handle [^HttpExchange ex]
                    (let [body (or bytes (byte-array 0))
                          len (if (= 200 status) (alength body) 0)]
                      (.sendResponseHeaders ex status len)
                      (when (and (= 200 status) (pos? len))
                        (with-open [os (.getResponseBody ex)]
                          (.write os ^bytes body)))
                      (.close ex))))]
    (.createContext server "/" handler)
    (.start server)
    (try
      (let [port (.getPort (.getAddress server))]
        (f (str "http://127.0.0.1:" port "/tile.jpg")))
      (finally
        (.stop server 0)))))

(deftest layer-url-and-zoom
  (is (re-find #"/xyz/std/10/1/2\.png$" (gsi/layer-url "std" 10 1 2)))
  (is (re-find #"/xyz/seamlessphoto/8/3/4\.jpg$" (gsi/layer-url "seamlessphoto" 8 3 4)))
  (is (>= (gsi/zoom-for-bbox 139.0 35.0 139.05 35.04) 14))
  (is (>= (gsi/zoom-for-bbox 139.0 35.0 139.05 35.04 "aerial") 14))
  (is (<= 9 (gsi/zoom-for-bbox 139.0 35.0 139.05 35.04 "satellite") 13))
  (is (<= 6 (gsi/zoom-for-bbox 120 20 140 40 "standard") 18))
  (is (<= 9 (gsi/zoom-for-bbox 139 35 142 37 "satellite") 13))
  (is (>= (gsi/zoom-for-bbox 139.0 35.0 139.2 35.1 "aerial") 14))
  ;; 広域では優先帯に収まらず下げる
  (is (< (gsi/zoom-for-bbox -170 -70 170 70 "aerial") 14))
  (with-redefs [gsi/max-tiles 0]
    (is (= 14 (gsi/zoom-for-bbox 139.0 35.0 139.05 35.04 "aerial"))))
  (is (true? (gsi/complete-bbox? {:west 1 :south 2 :east 3 :north 4})))
  (is (false? (gsi/complete-bbox? {:west 1 :south 2 :east 3})))
  (is (false? (gsi/complete-bbox? {:west 1 :south 2})))
  (is (false? (gsi/complete-bbox? {:west 1})))
  (is (false? (gsi/complete-bbox? {}))))

(deftest http-get-bytes-paths
  (let [tile (tiny-jpeg-bytes)]
    (with-tile-server tile 200
      (fn [url]
        (let [got (gsi/http-get-bytes url)]
          (is (bytes? got))
          (is (= (alength tile) (alength got))))))
    (with-tile-server nil 404
      (fn [url]
        (is (nil? (gsi/http-get-bytes url)))))
    (is (nil? (gsi/http-get-bytes "http://127.0.0.1:1/no-such-tile")))))

(deftest stitch-bbox-paths
  (let [tile (tiny-jpeg-bytes)
        box {:west 139.0 :south 35.0 :east 139.05 :north 35.04}]
    (testing "ローカル HTTP 経由で合成"
      (with-tile-server tile 200
        (fn [url]
          (with-redefs [gsi/layer-url (fn [_ _ _ _] url)]
            (let [bytes (gsi/stitch-bbox box "aerial")]
              (is (bytes? bytes))
              (is (some? (ImageIO/read (io/input-stream bytes)))))))))
    (testing "mock 取得で standard / aerial / satellite。衛星は空中より低いズーム"
      (with-redefs [gsi/http-get-bytes (fn [_] tile)]
        (is (some? (gsi/stitch-bbox box "standard")))
        (is (some? (gsi/stitch-bbox box "aerial")))
        (is (some? (gsi/stitch-bbox box "satellite")))
        (let [za (gsi/zoom-for-bbox (:west box) (:south box) (:east box) (:north box) "aerial")
              zs (gsi/zoom-for-bbox (:west box) (:south box) (:east box) (:north box) "satellite")]
          (is (>= za 14))
          (is (<= 9 zs 13))
          (is (> za zs)))))
    (testing "不明な kind / 不完全な範囲"
      (is (nil? (gsi/stitch-bbox box "moon")))
      (is (nil? (gsi/stitch-bbox {} "aerial")))
      (is (nil? (gsi/stitch-bbox {:west 139.0 :south 35.0 :east 139.05} "aerial"))))
    (testing "タイル無しでも灰の JPEG"
      (with-redefs [gsi/http-get-bytes (fn [_] nil)]
        (is (some? (gsi/stitch-bbox box "aerial")))))
    (testing "読めないバイトはスキップ"
      (with-redefs [gsi/http-get-bytes (fn [_] (byte-array [0 1 2]))]
        (is (some? (gsi/stitch-bbox box "aerial")))))
    (testing "東西南北が逆／タイル数上限"
      (is (nil? (gsi/stitch-bbox {:west 140.0 :south 35.0 :east 139.0 :north 35.04} "aerial")))
      (is (nil? (gsi/stitch-bbox {:west 139.0 :south 36.0 :east 139.05 :north 35.0} "aerial")))
      (with-redefs [gsi/max-tiles 0
                    gsi/http-get-bytes (fn [_] tile)]
        (is (nil? (gsi/stitch-bbox box "aerial")))))))
