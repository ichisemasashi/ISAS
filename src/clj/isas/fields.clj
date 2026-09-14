(ns isas.fields
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.db :as db]
            [isas.geo :as geo]
            [isas.log :as log]
            [isas.paints :as paints]))

(def kinds #{"standard" "aerial" "satellite"})

(defn kind-ok? [kind]
  (contains? kinds (str kind)))

(defn basemap-root [sys]
  (or (:basemap-dir sys) "data/basemaps"))

(defn- user-dir [sys user-id]
  (io/file (basemap-root sys) (str user-id)))

(defn next-temp-names [existing n]
  (let [used (set (keep (fn [nm]
                          (when-let [[_ d] (re-matches #"仮-(\d+)" (str nm))]
                            (Long/parseLong d)))
                        existing))]
    (loop [i 1 acc []]
      (cond
        (>= (count acc) n) acc
        (contains? used (long i)) (recur (inc i) acc)
        :else (recur (inc i) (conj acc (str "仮-" i)))))))

(defn- present-field [row]
  (let [gj (geo/parse-json (:geojson row))
        m2 (or (geo/area-m2 gj) 0.0)]
    {:id (:id row)
     :name (:name row)
     :area_m2 (geo/area-m2-int m2)
     :area_ha (geo/area-ha m2)
     :geojson gj}))

(defn- image-extent [row]
  (let [w (:image_west row)
        s (:image_south row)
        e (:image_east row)
        n (:image_north row)]
    (when (every? number? [w s e n])
      {:image_west w :image_south s :image_east e :image_north n})))

(defn get-place [sys user-id]
  (if-let [row (db/find-place (:ds sys) user-id)]
    (merge {:ok true
            :west (:west row)
            :south (:south row)
            :east (:east row)
            :north (:north row)}
           (image-extent row))
    {:ok false :code "place_unset"}))

(defn put-image-extent [sys user-id body]
  (if-not (db/find-place (:ds sys) user-id)
    {:ok false :code "place_unset"}
    (if (or (true? (:reset body)) (= "true" (str (:reset body))))
      (do
        (db/update-place-image! (:ds sys) user-id {:west nil :south nil :east nil :north nil})
        (log/info "下地の位置を作業場所に戻しました" :user-id user-id)
        {:ok true})
      (let [west (geo/as-number (:west body))
            south (geo/as-number (:south body))
            east (geo/as-number (:east body))
            north (geo/as-number (:north body))]
        (if-not (geo/valid-bbox? west south east north)
          (do
            (log/warn "下地の範囲が不正です" :user-id user-id)
            {:ok false :code "place_invalid"})
          (do
            (db/update-place-image! (:ds sys) user-id {:west west :south south :east east :north north})
            (log/info "下地の位置を保存しました" :user-id user-id)
            {:ok true}))))))

(defn- list-dir [dir]
  (let [fs (.listFiles dir)]
    (if (nil? fs) [] (vec fs))))

(defn delete-basemap-files [sys user-id]
  (let [dir (user-dir sys user-id)]
    (when (.isDirectory dir)
      (run! #(io/delete-file % true) (list-dir dir))
      (io/delete-file dir true)))
  (db/delete-basemaps! (:ds sys) user-id)
  (log/info "下地を消しました" :user-id user-id))

(defn put-place [sys user-id body]
  (let [west (geo/as-number (:west body))
        south (geo/as-number (:south body))
        east (geo/as-number (:east body))
        north (geo/as-number (:north body))]
    (if-not (geo/valid-bbox? west south east north)
      (do
        (log/warn "作業場所の範囲が不正です" :user-id user-id)
        {:ok false :code "place_invalid"})
      (do
        (db/upsert-place! (:ds sys) user-id {:west west :south south :east east :north north})
        (delete-basemap-files sys user-id)
        (log/info "作業場所を保存しました" :user-id user-id :west west :south south :east east :north north)
        {:ok true}))))

(defn list-basemap-status [sys user-id]
  (let [have (set (map :kind (db/list-basemaps (:ds sys) user-id)))]
    {:ok true
     :basemaps (mapv (fn [k] {:kind k :ready (contains? have k)})
                     ["standard" "aerial" "satellite"])}))

(defn- upload->map [u]
  (cond
    (nil? u) nil
    (map? u) (into {} (map (fn [[k v]]
                             [(keyword (if (or (keyword? k) (string? k) (symbol? k))
                                         (name k)
                                         (str k)))
                              v])
                           u))
    :else nil))

(defn detect-image [upload]
  (let [u (upload->map upload)
        ct (str (or (:content-type u) ""))
        nm (str/lower-case (str (or (:filename u) "")))]
    (cond
      (or (= ct "image/jpeg") (str/ends-with? nm ".jpg") (str/ends-with? nm ".jpeg"))
      {:content-type "image/jpeg" :ext ".jpg"}
      (or (= ct "image/png") (str/ends-with? nm ".png"))
      {:content-type "image/png" :ext ".png"}
      :else nil)))

(defn- tempfile [upload]
  (let [u (upload->map upload)]
    (or (:tempfile u) (:temp-file u))))

(defn put-basemap [sys user-id kind upload]
  (cond
    (not (kind-ok? kind))
    {:ok false :code "basemap_kind"}

    (nil? (db/find-place (:ds sys) user-id))
    {:ok false :code "place_unset"}

    :else
    (let [img (detect-image upload)
          tf (tempfile upload)]
      (if (or (nil? img) (nil? tf) (not (.isFile (io/file tf))))
        (do
          (log/warn "下地画像が読めません" :user-id user-id :kind kind)
          {:ok false :code "import_invalid"})
        (let [dir (user-dir sys user-id)
              rel (str user-id "/" kind (:ext img))
              dest (io/file (basemap-root sys) rel)]
          (.mkdirs dir)
          (io/copy (io/file tf) dest)
          (db/upsert-basemap! (:ds sys) {:user-id user-id
                                         :kind (str kind)
                                         :content-type (:content-type img)
                                         :body-ref rel})
          (log/info "下地画像を取り込みました" :user-id user-id :kind kind :path rel)
          {:ok true})))))

(defn put-basemap-bytes [sys user-id kind ^bytes bytes content-type]
  (cond
    (not (kind-ok? kind))
    {:ok false :code "basemap_kind"}

    (nil? (db/find-place (:ds sys) user-id))
    {:ok false :code "place_unset"}

    (or (nil? bytes) (zero? (alength bytes)))
    {:ok false :code "import_invalid"}

    :else
    (let [ext (if (= "image/png" content-type) ".png" ".jpg")
          ct (or content-type "image/jpeg")
          dir (user-dir sys user-id)
          rel (str user-id "/" kind ext)
          dest (io/file (basemap-root sys) rel)]
      (.mkdirs dir)
      (io/copy bytes dest)
      (db/upsert-basemap! (:ds sys) {:user-id user-id
                                     :kind (str kind)
                                     :content-type ct
                                     :body-ref rel})
      (log/info "下地画像を自動保存しました" :user-id user-id :kind kind :path rel)
      {:ok true})))

(defn get-basemap [sys user-id kind]
  (cond
    (not (kind-ok? kind))
    {:ok false :code "basemap_kind"}

    :else
    (if-let [row (db/find-basemap (:ds sys) user-id (str kind))]
      (let [f (io/file (basemap-root sys) (:body_ref row))]
        (if (.isFile f)
          {:ok true :file f :content-type (:content_type row)}
          (do
            (log/warn "下地ファイルがありません" :user-id user-id :kind kind)
            {:ok false :code "basemap_missing"})))
      {:ok false :code "basemap_missing"})))

(defn list-fields [sys user-id]
  {:ok true
   :fields (mapv present-field (db/list-fields (:ds sys) user-id))})

(defn create-field [sys user-id {:keys [name geojson]}]
  (let [gj (if (string? geojson) (geo/parse-json geojson) geojson)]
    (cond
      (not (geo/valid-shape? gj))
      (do
        (log/warn "圃場の形が面積を持てません" :user-id user-id)
        {:ok false :code "shape_not_area"})

      :else
      (let [nm (let [s (str/trim (str (or name "")))]
                 (if (str/blank? s)
                   (first (next-temp-names (db/field-names (:ds sys) user-id) 1))
                   s))
            row (db/insert-field! (:ds sys) {:user-id user-id :name nm :geojson (geo/to-json gj)})]
        (log/info "圃場を作りました" :user-id user-id :id (:id row) :name nm)
        {:ok true :field (present-field row)}))))

(defn update-field [sys user-id id body]
  (let [fid (geo/as-int id)
        row (when fid (db/find-field (:ds sys) user-id fid))]
    (if-not row
      {:ok false :code "field_not_found"}
      (let [nm (if (contains? body :name)
                 (let [s (str/trim (str (:name body)))]
                   (if (str/blank? s) (:name row) s))
                 (:name row))
            gj (if (contains? body :geojson)
                 (let [g (if (string? (:geojson body))
                           (geo/parse-json (:geojson body))
                           (:geojson body))]
                   (if (geo/valid-shape? g) g :bad))
                 (geo/parse-json (:geojson row)))]
        (if (= gj :bad)
          (do
            (log/warn "圃場の形が面積を持てません" :user-id user-id :id fid)
            {:ok false :code "shape_not_area"})
          (let [updated (db/update-field! (:ds sys) fid {:name nm :geojson (geo/to-json gj)})]
            (when (contains? body :geojson)
              (paints/clip-paints-to-field! sys fid gj))
            (log/info "圃場を更新しました" :user-id user-id :id fid)
            {:ok true :field (present-field updated)}))))))

(defn delete-field [sys user-id id]
  (let [fid (geo/as-int id)
        row (when fid (db/find-field (:ds sys) user-id fid))]
    (if-not row
      {:ok false :code "field_not_found"}
      (do
        (paints/delete-paints-for-field! sys fid)
        (db/delete-field! (:ds sys) fid)
        (log/info "圃場を消しました" :user-id user-id :id fid)
        {:ok true}))))

(defn- polygons-from-body [row body]
  (let [given (keep (fn [g]
                      (let [gj (if (string? g) (geo/parse-json g) g)]
                        (when (geo/valid-shape? gj) gj)))
                    (or (:polygons body) []))]
    (if (>= (count given) 2)
      given
      (when-let [line (:line body)]
        (geo/split-shape (geo/parse-json (:geojson row)) line)))))

(defn split-field [sys user-id id body]
  (let [fid (geo/as-int id)
        row (when fid (db/find-field (:ds sys) user-id fid))
        polys (when row (polygons-from-body row body))]
    (cond
      (nil? row)
      {:ok false :code "field_not_found"}

      (paints/field-has-paint? (:ds sys) fid)
      (do
        (log/warn "塗りが残っているので分割しません" :user-id user-id :id fid)
        {:ok false :code "field_has_paint"})

      (< (count polys) 2)
      {:ok false :code "split_too_few"}

      :else
      (let [names (next-temp-names (db/field-names (:ds sys) user-id) (count polys))]
        (db/delete-field! (:ds sys) fid)
        (let [created (mapv (fn [nm gj]
                              (db/insert-field! (:ds sys) {:user-id user-id :name nm :geojson (geo/to-json gj)}))
                            names polys)]
          (log/info "圃場を分割しました" :user-id user-id :from fid :count (count created))
          {:ok true :fields (mapv present-field created)})))))

(defn merge-fields [sys user-id body]
  (let [ids (vec (keep geo/as-int (:ids body)))
        keep-id (geo/as-int (:keep_id body))]
    (cond
      (< (count (set ids)) 2)
      {:ok false :code "merge_too_few"}

      (not (some #{keep-id} ids))
      {:ok false :code "merge_keep_missing"}

      :else
      (let [rows (keep (fn [id] (db/find-field (:ds sys) user-id id)) ids)]
        (if (not= (count rows) (count (set ids)))
          {:ok false :code "field_not_found"}
          (if (paints/any-field-has-paint? (:ds sys) ids)
            (do
              (log/warn "塗りが残っているので合筆しません" :user-id user-id :ids ids)
              {:ok false :code "field_has_paint"})
          (let [gjs (map geo/parse-json (map :geojson rows))
                union (geo/union-shapes gjs)
                keep-row (first (filter #(= keep-id (:id %)) rows))]
            (if-not (geo/valid-shape? union)
              {:ok false :code "shape_not_area"}
              (do
                (db/update-field! (:ds sys) keep-id {:name (:name keep-row) :geojson (geo/to-json union)})
                (run! (fn [id]
                        (when (not= id keep-id)
                          (db/delete-field! (:ds sys) id)))
                      ids)
                (log/info "圃場を合筆しました" :user-id user-id :keep keep-id :ids ids)
                {:ok true :field (present-field (db/find-field (:ds sys) user-id keep-id))})))))))))

(defn import-geojson [sys user-id upload]
  (let [u (upload->map upload)
        tf (tempfile u)
        text (cond
               (and tf (.isFile (io/file tf))) (slurp (io/file tf) :encoding "UTF-8")
               (string? (:body u)) (:body u)
               (bytes? (:bytes u)) (String. ^bytes (:bytes u) "UTF-8")
               :else nil)
        gj (geo/parse-json text)
        polys (if gj (geo/extract-polygons gj) [])]
    (if (empty? polys)
      (do
        (log/warn "区画ファイルを読めません" :user-id user-id)
        {:ok false :code "import_invalid"})
      (let [existing (db/field-names (:ds sys) user-id)
            unnamed (count (remove :name polys))
            temps (next-temp-names existing unnamed)
            names (loop [ps polys ts temps acc []]
                    (if (empty? ps)
                      acc
                      (let [p (first ps)]
                        (if (:name p)
                          (recur (rest ps) ts (conj acc (:name p)))
                          (recur (rest ps) (rest ts) (conj acc (first ts)))))))
            created (mapv (fn [nm p]
                            (db/insert-field! (:ds sys) {:user-id user-id
                                                         :name nm
                                                         :geojson (geo/to-json (dissoc p :name))}))
                          names polys)]
        (log/info "区画を取り込みました" :user-id user-id :count (count created))
        {:ok true :fields (mapv present-field created)}))))
