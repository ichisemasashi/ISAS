(ns isas.emaff
  "eMAFF 由来データの自動取得。公式 API が無い場合は地理院タイルへフォールバックする。"
  (:require [clojure.string :as str]
            [isas.db :as db]
            [isas.fields :as fields]
            [isas.gsi :as gsi]
            [isas.log :as log]))

(def ^:private import-inflight (atom #{}))

(defn- try-begin-import! [user-id]
  (let [id (long user-id)
        [old _] (swap-vals! import-inflight
                            (fn [s]
                              (if (contains? s id) s (conj s id))))]
    (not (contains? old id))))

(defn- end-import! [user-id]
  (swap! import-inflight disj (long user-id)))

(defn- parse-coord [v]
  (cond
    (number? v) (double v)
    (string? v)
    (try
      (Double/parseDouble (str/trim v))
      (catch Exception _ nil))
    :else nil))

(defn- normalize-bbox [b]
  (let [w (parse-coord (:west b))
        s (parse-coord (:south b))
        e (parse-coord (:east b))
        n (parse-coord (:north b))]
    (when (and w s e n (< w e) (< s n))
      {:west w :south s :east e :north n})))

(defn preview-aerial
  "最終確認用。eMAFF が取れないときは地理院空中写真を指定する。"
  [sys user-id bbox]
  (let [place (db/find-place (:ds sys) user-id)
        from-body (normalize-bbox bbox)
        from-place (when place (normalize-bbox place))
        box (or from-body from-place)
        source-of (cond from-body "request" from-place "saved-place" :else nil)]
    (cond
      (nil? box)
      (do
        (log/warn "最終確認の範囲がありません"
                  :user-id user-id
                  :body-keys (when bbox (vec (keys bbox)))
                  :has-saved-place (boolean place))
        {:ok false :code "place_unset"})

      :else
      (do
        ;; eMAFF に安定した公開タイル API が無いため、確認用は地理院空中写真とする。
        (log/info "作業場所の最終確認を用意します"
                  :user-id user-id
                  :source "gsi"
                  :kind "aerial"
                  :bbox-from source-of
                  :west (:west box) :south (:south box)
                  :east (:east box) :north (:north box)
                  :span-lon (- (:east box) (:west box))
                  :span-lat (- (:north box) (:south box)))
        {:ok true
         :source "gsi"
         :kind "aerial"
         :note "確認用の空中写真です（地理院）。この範囲でよければ確定してください"
         :bbox box}))))

(defn- import-basemaps! [sys user-id place]
  (reduce
   (fn [acc kind]
     (log/info "下地の自動合成を始めます" :user-id user-id :kind kind
               :west (:west place) :south (:south place)
               :east (:east place) :north (:north place))
     (let [bytes (gsi/stitch-bbox place kind)]
       (if bytes
         (do
           (log/info "下地の自動合成が終わりました"
                     :user-id user-id :kind kind :bytes (alength ^bytes bytes))
           (let [r (fields/put-basemap-bytes sys user-id kind bytes "image/jpeg")]
             (if (:ok r)
               (do
                 (log/info "下地を自動保存しました" :user-id user-id :kind kind)
                 (update acc :basemaps conj kind))
               (do
                 (log/warn "下地の自動保存に失敗しました"
                           :user-id user-id :kind kind :code (:code r))
                 (update acc :warnings conj {:kind kind :code (:code r)})))))
         (do
           (log/warn "下地の自動合成に失敗しました" :user-id user-id :kind kind)
           (update acc :warnings conj {:kind kind :code "emaff_unavailable"})))))
   {:basemaps [] :warnings []}
   ["standard" "aerial" "satellite"]))

(defn import-for-place
  "作業場所について下地3種を自動取得する。筆ポリゴンは手作業の区画取込を使う。"
  [sys user-id]
  (let [place (db/find-place (:ds sys) user-id)]
    (cond
      (nil? place)
      (do
        (log/warn "自動取込できません（作業場所が未設定）" :user-id user-id)
        {:ok false :code "place_unset"})

      (not (try-begin-import! user-id))
      (do
        (log/warn "自動取込を拒否しました（別の取込が進行中）" :user-id user-id)
        {:ok false :code "emaff_busy"})

      :else
      (try
        (let [box (select-keys place [:west :south :east :north])
              _ (log/info "作業場所の下地自動取込を始めます" :user-id user-id
                          :west (:west box) :south (:south box)
                          :east (:east box) :north (:north box))
              bm (import-basemaps! sys user-id box)
              warnings (vec (:warnings bm))
              ok-bm (seq (:basemaps bm))]
          (log/info "筆ポリゴンは手作業の区画取込を使います" :user-id user-id)
          (cond
            (and ok-bm (empty? warnings))
            (do
              (log/info "下地の自動取込が完了しました" :user-id user-id :basemaps (:basemaps bm))
              {:ok true :basemaps (:basemaps bm) :fields [] :warnings []})

            ok-bm
            (do
              (log/info "下地の自動取込は一部または警告付きです"
                        :user-id user-id :basemaps (:basemaps bm) :warnings warnings)
              {:ok true :code "emaff_partial"
               :basemaps (:basemaps bm) :fields [] :warnings warnings})

            :else
            (do
              (log/warn "自動取込に失敗しました" :user-id user-id :warnings warnings)
              {:ok false :code "emaff_unavailable" :warnings warnings})))
        (finally
          (end-import! user-id))))))
