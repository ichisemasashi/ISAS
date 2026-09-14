(ns isas.gsi
  "国土地理院の公開 XYZ タイルを下地画像に合成する。"
  (:require [clojure.java.io :as io]
            [isas.log :as log])
  (:import [java.awt Color RenderingHints]
           [java.awt.image BufferedImage]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [javax.imageio ImageIO]
           [java.time Duration]))

(def ^:private tile-size 256)

(def max-tiles 64)

(def kind->layer
  {"standard" "std"
   "aerial" "seamlessphoto"
   "satellite" "seamlessphoto"})

(defn layer-url [layer z x y]
  (let [ext (if (= "std" layer) "png" "jpg")]
    (str "https://cyberjapandata.gsi.go.jp/xyz/" layer "/" z "/" x "/" y "." ext)))

(defn- lon->tile [lon z]
  (int (Math/floor (* (/ (+ lon 180.0) 360.0) (Math/pow 2.0 z)))))

(defn- lat->tile [lat z]
  (let [rad (* lat (/ Math/PI 180.0))
        n (Math/pow 2.0 z)]
    (int (Math/floor (* (/ (- 1.0 (/ (Math/log (+ (Math/tan rad) (/ 1.0 (Math/cos rad)))) Math/PI)) 2.0) n)))))

(defn- choose-zoom [west south east north]
  (let [span (max (- east west) (- north south))]
    (cond
      (> span 10) 6
      (> span 2) 8
      (> span 0.5) 10
      (> span 0.1) 12
      :else 14)))

(defn http-get-bytes
  "地理院タイル取得。試験では with-redefs で差し替え可能。"
  [url]
  (try
    (let [client (-> (HttpClient/newBuilder)
                     (.connectTimeout (Duration/ofSeconds 10))
                     (.build))
          req (-> (HttpRequest/newBuilder)
                  (.uri (URI/create url))
                  (.timeout (Duration/ofSeconds 20))
                  (.GET)
                  (.build))
          res (.send client req (HttpResponse$BodyHandlers/ofByteArray))]
      (when (= 200 (.statusCode res))
        (.body res)))
    (catch Exception e
      (log/warn "地理院タイルを取れません" :url url :error (.getMessage e))
      nil)))

(defn- read-tile [bytes]
  (when bytes
    (ImageIO/read (io/input-stream bytes))))

(defn zoom-for-bbox [west south east north]
  (choose-zoom west south east north))

(defn complete-bbox?
  [{:keys [west south east north]}]
  (boolean (and west south east north)))

(defn- tile-grid-ok? [cols rows]
  (and (pos? cols) (pos? rows) (<= (* cols rows) max-tiles)))

(defn- paint-tile! [g layer z x0 y0 x y]
  (when-let [tile (read-tile (http-get-bytes (layer-url layer z x y)))]
    (.drawImage g tile (* (- x x0) tile-size) (* (- y y0) tile-size) nil)))

(defn- write-jpeg [^BufferedImage img]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (ImageIO/write img "jpg" baos)
    (.toByteArray baos)))

(defn stitch-bbox
  "作業場所の東西南北について地理院タイルを合成し JPEG バイト列を返す。
  失敗時は nil。"
  [bbox kind]
  (let [layer (get kind->layer (str kind))]
    (when (and layer (complete-bbox? bbox))
      (let [{:keys [west south east north]} bbox
            z (choose-zoom west south east north)
            x0 (lon->tile west z)
            x1 (lon->tile east z)
            y0 (lat->tile north z)
            y1 (lat->tile south z)
            cols (inc (- x1 x0))
            rows (inc (- y1 y0))]
        (when (tile-grid-ok? cols rows)
          (let [img (BufferedImage. (* cols tile-size) (* rows tile-size) BufferedImage/TYPE_INT_RGB)
                g (.createGraphics img)
                xs (range x0 (inc x1))
                ys (range y0 (inc y1))]
            (.setColor g Color/LIGHT_GRAY)
            (.fillRect g 0 0 (.getWidth img) (.getHeight img))
            (.setRenderingHint g RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
            (run! (fn [x]
                    (run! (fn [y] (paint-tile! g layer z x0 y0 x y)) ys))
                  xs)
            (.dispose g)
            (write-jpeg img)))))))
