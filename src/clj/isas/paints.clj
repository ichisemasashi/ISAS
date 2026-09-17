(ns isas.paints
  (:require [clojure.string :as str]
            [isas.db :as db]
            [isas.geo :as geo]
            [isas.log :as log]))

(defn normalize-work-name [s]
  (let [t (str/trim (str (or s "")))]
    (cond
      (str/blank? t) {:ok false :code "work_name_required"}
      (> (count t) 100) {:ok false :code "work_name_too_long"}
      :else {:ok true :work-name t})))

(defn paint-status [paint-area field-area]
  (let [pa (double (or paint-area 0.0))
        fa (double (or field-area 0.0))]
    (cond
      (<= pa 0.0) "none"
      (and (pos? fa) (< (Math/abs (- pa fa)) 1.0)) "done"
      (< pa fa) "partial"
      :else "done")))

(defn field-has-paint? [ds field-id]
  (pos? (long (db/count-paints-for-field ds field-id))))

(defn any-field-has-paint? [ds field-ids]
  (boolean (some #(field-has-paint? ds %) field-ids)))

(defn- present-paint [row]
  {:id (:id row)
   :geojson (geo/parse-json (:geojson row))})

(defn field-paint-summary [field-row paint-rows]
  (let [fgj (geo/parse-json (:geojson field-row))
        fa (or (geo/area-m2 fgj) 0.0)
        gjs (keep (fn [r]
                    (let [g (geo/parse-json (:geojson r))]
                      (when (geo/valid-shape? g) g)))
                  paint-rows)
        union (when (seq gjs) (geo/union-shapes gjs))
        clipped (when union (or (geo/intersect-shapes union fgj) union))
        pa (or (geo/area-m2 clipped) 0.0)]
    {:id (:id field-row)
     :name (:name field-row)
     :status (paint-status pa fa)
     :area_m2 (geo/area-m2-int pa)
     :field_area_m2 (geo/area-m2-int fa)
     :paints (mapv present-paint paint-rows)}))

(defn list-work-names [sys user-id]
  {:ok true
   :work_names (db/list-work-names (:ds sys) user-id)})

(defn list-paints [sys user-id work-name]
  (let [nw (normalize-work-name work-name)]
    (if-not (:ok nw)
      nw
      (let [wn (:work-name nw)
            fields (db/list-fields (:ds sys) user-id)]
        {:ok true
         :work_name wn
         :fields (mapv (fn [f]
                         (field-paint-summary f (db/list-paints-for-field-name (:ds sys) (:id f) wn)))
                       fields)}))))

(defn create-paint [sys user-id {:keys [field_id work_name geojson]}]
  (let [nw (normalize-work-name work_name)
        fid (geo/as-int field_id)
        field (when fid (db/find-field (:ds sys) user-id fid))
        gj (if (string? geojson) (geo/parse-json geojson) geojson)]
    (cond
      (not (:ok nw))
      (do
        (log/warn "作業名が使えないので塗りません" :user-id user-id :code (:code nw))
        nw)

      (nil? field)
      (do
        (log/warn "塗り先の圃場がありません" :user-id user-id :field-id field_id)
        {:ok false :code "field_not_found"})

      :else
      (let [fgj (geo/parse-json (:geojson field))
            clipped (geo/intersect-shapes gj fgj)]
        (if-not (geo/valid-shape? clipped)
          (do
            (log/warn "切り取り後に面積が取れません" :user-id user-id :field-id fid)
            {:ok false :code "paint_empty"})
          (let [row (db/insert-paint! (:ds sys) {:field-id fid
                                                 :work-name (:work-name nw)
                                                 :geojson (geo/to-json clipped)})]
            (log/info "塗りを確定しました"
                      :user-id user-id
                      :field-id fid
                      :paint-id (:id row)
                      :work-name (:work-name nw))
            {:ok true :paint (present-paint row)}))))))

(defn complete-field [sys user-id id work-name]
  (let [nw (normalize-work-name work-name)
        fid (geo/as-int id)
        field (when fid (db/find-field (:ds sys) user-id fid))]
    (cond
      (not (:ok nw))
      (do
        (log/warn "作業名が使えないので全面完了しません" :user-id user-id :code (:code nw))
        nw)

      (nil? field)
      (do
        (log/warn "全面完了の圃場がありません" :user-id user-id :field-id id)
        {:ok false :code "field_not_found"})

      :else
      (do
        (db/delete-paints-for-field-name! (:ds sys) fid (:work-name nw))
        (let [row (db/insert-paint! (:ds sys) {:field-id fid
                                               :work-name (:work-name nw)
                                               :geojson (:geojson field)})]
          (log/info "圃場を全面完了にしました"
                    :user-id user-id
                    :field-id fid
                    :work-name (:work-name nw)
                    :paint-id (:id row))
          {:ok true})))))

(defn delete-paint [sys user-id id]
  (let [pid (geo/as-int id)
        row (when pid (db/find-paint-for-user (:ds sys) user-id pid))]
    (if-not row
      (do
        (log/warn "塗りが見つかりません" :user-id user-id :paint-id id)
        {:ok false :code "paint_not_found"})
      (do
        (db/delete-paint! (:ds sys) pid)
        (log/info "塗りを消しました" :user-id user-id :paint-id pid :field-id (:field_id row))
        {:ok true}))))

(defn delete-field-paints [sys user-id id work-name]
  (let [nw (normalize-work-name work-name)
        fid (geo/as-int id)
        field (when fid (db/find-field (:ds sys) user-id fid))]
    (cond
      (not (:ok nw))
      (do
        (log/warn "作業名が使えないので塗りを消しません" :user-id user-id :code (:code nw))
        nw)

      (nil? field)
      (do
        (log/warn "塗りの圃場がありません" :user-id user-id :field-id id)
        {:ok false :code "field_not_found"})

      :else
      (do
        (db/delete-paints-for-field-name! (:ds sys) fid (:work-name nw))
        (log/info "圃場の作業名の塗りを全部消しました"
                  :user-id user-id
                  :field-id fid
                  :work-name (:work-name nw))
        {:ok true}))))

(defn clip-paints-to-field! [sys field-id new-gj]
  (run! (fn [row]
          (let [gj (geo/parse-json (:geojson row))
                clipped (geo/intersect-shapes gj new-gj)]
            (if (geo/valid-shape? clipped)
              (do
                (db/update-paint-geojson! (:ds sys) (:id row) (geo/to-json clipped))
                (log/info "塗りを新しい圃場形に切り直しました" :paint-id (:id row) :field-id field-id))
              (do
                (db/delete-paint! (:ds sys) (:id row))
                (log/info "面積が無くなった塗りを消しました" :paint-id (:id row) :field-id field-id)))))
        (db/list-paints-for-field (:ds sys) field-id)))

(defn delete-paints-for-field! [sys field-id]
  (db/delete-paints-for-field! (:ds sys) field-id)
  (log/info "圃場の塗りを消しました" :field-id field-id))
