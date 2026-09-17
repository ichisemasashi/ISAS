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
   ;; seamlessphoto はズームで中身が切り替わる（9〜13: ランドサット、14〜18: 空中写真）。
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

(defn- lon->pixel-x [lon z]
  (* (/ (+ lon 180.0) 360.0) (Math/pow 2.0 z) tile-size))

(defn- lat->pixel-y [lat z]
  (let [rad (* lat (/ Math/PI 180.0))
        n (Math/pow 2.0 z)]
    (* (/ (- 1.0 (/ (Math/log (+ (Math/tan rad) (/ 1.0 (Math/cos rad)))) Math/PI)) 2.0) n tile-size)))

(defn- tile-count [west south east north z]
  (let [x0 (lon->tile west z)
        x1 (lon->tile east z)
        y0 (lat->tile north z)
        y1 (lat->tile south z)]
    (* (inc (- x1 x0)) (inc (- y1 y0)))))

(defn- zoom-range [kind]
  (case (str kind)
    ;; 全国ランドサットモザイク（衛星）
    "satellite" [9 13]
    ;; シームレス空中写真
    "aerial" [14 18]
    ;; 標準地図
    [6 18]))

(defn- choose-zoom [west south east north kind]
  (let [[zmin zmax] (zoom-range kind)
        high-first (range zmax (dec zmin) -1)
        fit (filter #(<= (tile-count west south east north %) max-tiles) high-first)]
    (or (first fit)
        ;; 最小ズームでも収まらないときはさらに下げる（広域の保険）
        (first (filter #(<= (tile-count west south east north %) max-tiles)
                       (range (dec zmin) 1 -1)))
        zmin)))

(def ^:private http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn http-get-bytes
  "地理院タイル取得。試験では with-redefs で差し替え可能。"
  [url]
  (try
    (let [req (-> (HttpRequest/newBuilder)
                  (.uri (URI/create url))
                  (.timeout (Duration/ofSeconds 20))
                  (.GET)
                  (.build))
          res (.send http-client req (HttpResponse$BodyHandlers/ofByteArray))]
      (when (= 200 (.statusCode res))
        (.body res)))
    (catch Exception e
      (log/warn "地理院タイルを取れません" :url url :error (.getMessage e))
      nil)))

(defn- read-tile [bytes]
  (when bytes
    (ImageIO/read (io/input-stream bytes))))

(defn zoom-for-bbox
  ([west south east north] (choose-zoom west south east north "aerial"))
  ([west south east north kind] (choose-zoom west south east north kind)))

(defn complete-bbox?
  [{:keys [west south east north]}]
  (boolean (and west south east north)))

(defn- tile-grid-ok? [cols rows]
  (<= (* cols rows) max-tiles))

(defn- paint-tile! [g layer z x0 y0 x y]
  (when-let [tile (read-tile (http-get-bytes (layer-url layer z x y)))]
    (.drawImage g tile (* (- x x0) tile-size) (* (- y y0) tile-size) nil)))

(defn- write-jpeg [^BufferedImage img]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (ImageIO/write img "jpg" baos)
    (.toByteArray baos)))

(defn- clamp [v lo hi]
  (max lo (min hi v)))

(defn- crop-to-bbox
  "タイルモザイク（Webメルカトル）から指定の東西南北だけを切り出す。"
  [^BufferedImage mosaic z x0 y0 west south east north]
  (let [origin-x (* x0 tile-size)
        origin-y (* y0 tile-size)
        px0 (- (lon->pixel-x west z) origin-x)
        px1 (- (lon->pixel-x east z) origin-x)
        py0 (- (lat->pixel-y north z) origin-y)
        py1 (- (lat->pixel-y south z) origin-y)
        x (int (Math/floor (clamp (min px0 px1) 0 (dec (.getWidth mosaic)))))
        y (int (Math/floor (clamp (min py0 py1) 0 (dec (.getHeight mosaic)))))
        x2 (int (Math/ceil (clamp (max px0 px1) 1 (.getWidth mosaic))))
        y2 (int (Math/ceil (clamp (max py0 py1) 1 (.getHeight mosaic))))
        w (max 1 (- x2 x))
        h (max 1 (- y2 y))
        ww (min w (- (.getWidth mosaic) x))
        hh (min h (- (.getHeight mosaic) y))]
    (log/info "地理院タイル合成を範囲どおりに切り出しました"
              :z z :x x :y y :width ww :height hh
              :west west :south south :east east :north north)
    (.getSubimage mosaic x y ww hh)))

(defn stitch-bbox
  "作業場所の東西南北について地理院タイルを合成し、範囲どおりに切り出した JPEG を返す。
  空中写真はズーム14以上、衛星は9〜13（ランドサット）を優先する。失敗時は nil。"
  [bbox kind]
  (let [layer (get kind->layer (str kind))
        {:keys [west south east north]} bbox]
    (when (and layer (complete-bbox? bbox) (< west east) (< south north))
      (let [z (choose-zoom west south east north kind)
            x0 (lon->tile west z)
            x1 (lon->tile east z)
            y0 (lat->tile north z)
            y1 (lat->tile south z)
            cols (inc (- x1 x0))
            rows (inc (- y1 y0))]
        (log/info "地理院タイル合成の範囲を決めました"
                  :kind kind :layer layer :z z
                  :west west :south south :east east :north north
                  :x0 x0 :x1 x1 :y0 y0 :y1 y1 :cols cols :rows rows
                  :tiles (* cols rows) :max-tiles max-tiles)
        (if-not (tile-grid-ok? cols rows)
          (do
            (log/warn "地理院タイル数が上限を超えるか不正です"
                      :kind kind :cols cols :rows rows :max-tiles max-tiles)
            nil)
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
            (let [cropped (crop-to-bbox img z x0 y0 west south east north)
                  out (write-jpeg cropped)]
              (log/info "地理院タイルを JPEG に合成しました"
                        :kind kind :layer layer :z z
                        :width (.getWidth cropped) :height (.getHeight cropped)
                        :bytes (alength ^bytes out))
              out)))))))
