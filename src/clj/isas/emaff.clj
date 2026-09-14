(ns isas.emaff
  "eMAFF 由来データの自動取得。公式 API が無い場合は地理院タイルへフォールバックする。"
  (:require [isas.db :as db]
            [isas.fields :as fields]
            [isas.gsi :as gsi]
            [isas.log :as log]))

(defn- full-bbox? [b]
  (and (:west b) (:south b) (:east b) (:north b)))

(defn preview-aerial
  "最終確認用。eMAFF が取れないときは地理院空中写真を指定する。"
  [sys user-id bbox]
  (let [place (db/find-place (:ds sys) user-id)
        from-body (when (full-bbox? bbox)
                    (select-keys bbox [:west :south :east :north]))
        box (or from-body
                (when place (select-keys place [:west :south :east :north])))]
    (if-not box
      {:ok false :code "place_unset"}
      (do
        ;; eMAFF に安定した公開タイル API が無いため、確認用は地理院空中写真とする。
        (log/info "作業場所の最終確認に地理院空中写真を使います" :user-id user-id)
        {:ok true
         :source "gsi"
         :kind "aerial"
         :note "eMAFF 空中写真を直接取得できないため、座標付きの地理院空中写真で確認します"
         :bbox (select-keys box [:west :south :east :north])}))))

(defn- import-basemaps! [sys user-id place]
  (reduce
   (fn [acc kind]
     (let [bytes (gsi/stitch-bbox place kind)]
       (if bytes
         (let [r (fields/put-basemap-bytes sys user-id kind bytes "image/jpeg")]
           (if (:ok r)
             (update acc :basemaps conj kind)
             (update acc :warnings conj {:kind kind :code (:code r)})))
         (update acc :warnings conj {:kind kind :code "emaff_unavailable"}))))
   {:basemaps [] :warnings []}
   ["standard" "aerial" "satellite"]))

(defn- import-polygons!
  "筆ポリゴンの自動取得。現状 eMAFF 公開 API が無いため警告のみ返す。"
  [sys user-id]
  (log/warn "筆ポリゴンの自動取得は eMAFF 公開 API が無いため手作業取込を使ってください"
            :user-id user-id)
  {:fields []
   :warnings [{:code "emaff_unavailable"
               :msg "筆ポリゴンは自動取得できません。区画ファイルを取り込んでください"}]})

(defn import-for-place
  "作業場所について下地を自動取得し、筆ポリゴンの自動取得を試みる。"
  [sys user-id]
  (let [place (db/find-place (:ds sys) user-id)]
    (if-not place
      {:ok false :code "place_unset"}
      (let [box (select-keys place [:west :south :east :north])
            bm (import-basemaps! sys user-id box)
            pg (import-polygons! sys user-id)
            warnings (vec (concat (:warnings bm) (:warnings pg)))
            ok-bm (seq (:basemaps bm))]
        (cond
          (and ok-bm (empty? warnings))
          (do
            (log/info "eMAFF／代替の自動取込が完了しました" :user-id user-id :basemaps (:basemaps bm))
            {:ok true :basemaps (:basemaps bm) :fields (:fields pg) :warnings []})

          ok-bm
          (do
            (log/info "下地の自動取込は一部または警告付きです" :user-id user-id :warnings warnings)
            {:ok true :code "emaff_partial"
             :basemaps (:basemaps bm) :fields (:fields pg) :warnings warnings})

          :else
          (do
            (log/warn "自動取込に失敗しました" :user-id user-id :warnings warnings)
            {:ok false :code "emaff_unavailable" :warnings warnings}))))))
