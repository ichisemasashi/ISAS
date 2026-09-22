(ns isas.memos
  "第2版 工程3。メモ（タイムライン・投稿・下書き・入れ子返信・タグ・リンク・添付・ブックマーク）。
  利用者と管理者が同じ経路を使う。actor は {:kind \"user\"|\"admin\" :id 1 :email \"a@example.com\"}。
  時刻は UTC の ISO-8601 で持ち、編集窓は content_saved_at から30分（サーバ時刻が正本）。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [isas.crypto :as crypto]
            [isas.db :as db]
            [isas.geo :as geo]
            [isas.log :as log]
            [isas.time :as time])
  (:import [java.time Duration]))

(def body-max-code-points 2000)
(def tag-max 20)
(def tag-label-max 100)
(def link-max 20)
(def attachment-max 20)
(def edit-window-minutes 30)
(def page-limit-default 50)
(def page-limit-max 200)

(defn memo-root [sys]
  (or (:memo-dir sys) "data/memo-files"))

(defn- code-points [s]
  (let [t (str s)]
    (.codePointCount t 0 (.length t))))

(defn- actor-kind [actor]
  (str (:kind actor)))

(defn- actor-id [actor]
  (long (:id actor)))

(defn- actor-info [actor]
  {:actor-kind (actor-kind actor) :actor-id (actor-id actor)})

(defn- log-info!
  "操作ログを keyword 引数で残す（マップ1個渡しだと出ない実装・環境差を避ける）。"
  [msg data]
  (apply log/info msg (mapcat identity (seq data))))

(defn- deny
  "拒否を日本語でログに残し、失敗応答を返す。"
  [msg code data]
  (apply log/warn msg (mapcat identity (seq (assoc data :code code))))
  {:ok false :code code})

;;; 権限・状態・編集窓

(defn- admin? [actor]
  (= "admin" (actor-kind actor)))

(defn- published? [memo]
  (= "published" (str (:status memo))))

(defn- deleted? [memo]
  (some? (:deleted_at memo)))

(defn- author? [memo actor]
  (and (= (str (:author_kind memo)) (actor-kind actor))
       (= (long (:author_id memo)) (actor-id actor))))

(defn- visible?
  "§2.5.3 memo_not_found の裏。無い・削除済み・他人の下書きは見えない。"
  [memo actor]
  (and (some? memo)
       (not (deleted? memo))
       (or (published? memo) (author? memo actor))))

(defn- edit-deadline-instant
  "content_saved_at + 30分。読めない・無いときは nil（編集窓は閉じたとみなす）。"
  [memo]
  (when-let [cs (:content_saved_at memo)]
    (try
      (.plus (time/parse-instant (str cs)) (Duration/ofMinutes edit-window-minutes))
      (catch Exception _
        nil))))

(defn- edit-deadline [memo]
  (when-let [inst (edit-deadline-instant memo)]
    (time/format-instant inst)))

(defn- edit-window-open?
  "サーバ時計の Instant 同士で比較する（文字列往復の誤差を避ける）。"
  [memo]
  (if-let [dl (edit-deadline-instant memo)]
    (not (.isBefore dl (time/now-instant)))
    false))

(defn- can-edit?
  "§3.4 下書きは作成者がいつでも、公開済みは作成者かつ編集窓内のみ。"
  [memo actor]
  (if-not (author? memo actor)
    false
    (if (published? memo)
      (edit-window-open? memo)
      true)))

;;; 表示

(defn- account-email [sys kind id]
  (:email (if (= "admin" (str kind))
            (db/find-admin-by-id (:ds sys) id)
            (db/find-user-by-id (:ds sys) id))))

(defn present-attachment [row]
  {:id (:id row)
   :filename (:filename row)
   :content_type (:content_type row)})

(defn- present-gantt-summary
  "紐づけ要約。無いときは nil。削除済み作業も id・title・deleted を返す。"
  [sys memo]
  (when-let [gid (geo/as-int (:gantt_id memo))]
    (if-let [row (db/find-gantt-row-by-id (:ds sys) gid)]
      {:id (:id row)
       :title (:title row)
       :deleted (some? (:deleted_at row))}
      {:id gid :title nil :deleted true})))

(defn- can-link-gantt?
  "付け外しは作成者または管理者。"
  [memo actor]
  (or (author? memo actor) (admin? actor)))

(defn- linkable-parent?
  "公開済み・親・未削除のみ。"
  [memo]
  (and (some? memo)
       (not (deleted? memo))
       (published? memo)
       (nil? (:parent_id memo))))

(defn present-memo
  "§2.5.1 のメモオブジェクト。editable_until は公開済みかつ作成者本人のときだけ入る。
  第2版工程5: gantt 要約（id・title・deleted）を含む。"
  [sys memo actor]
  (let [ds (:ds sys)
        mid (:id memo)
        mine? (author? memo actor)]
    {:id mid
     :parent_id (:parent_id memo)
     :status (:status memo)
     :body (:body memo)
     :author_kind (:author_kind memo)
     :author_id (:author_id memo)
     :author_email (account-email sys (:author_kind memo) (:author_id memo))
     :published_at (:published_at memo)
     :content_saved_at (:content_saved_at memo)
     :created_at (:created_at memo)
     :updated_at (:updated_at memo)
     :editable_until (if mine?
                       (if (published? memo) (edit-deadline memo) nil)
                       nil)
     :can_edit (can-edit? memo actor)
     :can_delete (if mine? true (admin? actor))
     :can_link_gantt (boolean (and (linkable-parent? memo) (can-link-gantt? memo actor)))
     :tags (db/list-memo-tags ds mid)
     :links (db/list-memo-links ds mid)
     :attachments (mapv present-attachment (db/list-memo-attachments ds mid))
     :bookmarked (db/author-has-bookmarked? ds mid (actor-kind actor) (actor-id actor))
     :bookmark_count (db/count-memo-bookmarks ds mid)
     :reply_count (db/count-memo-replies ds mid)
     :gantt (present-gantt-summary sys memo)}))

;;; 入力の正規化（§3.2）

(defn- normalize-body [raw]
  (let [b (str/trim (str (or raw "")))]
    (if (> (code-points b) body-max-code-points)
      {:ok false :code "body_too_long"}
      {:ok true :body b})))

(defn- as-vector [raw]
  (cond
    (nil? raw) []
    (sequential? raw) (vec raw)
    :else [raw]))

(defn- normalize-tags [raw]
  (let [labels (->> (as-vector raw)
                    (map (fn [t] (str/trim (str (or t "")))))
                    distinct
                    vec)]
    (cond
      (> (count labels) tag-max)
      {:ok false :code "tag_limit"}

      (some (fn [t] (or (str/blank? t) (> (code-points t) tag-label-max))) labels)
      {:ok false :code "tag_invalid"}

      :else
      {:ok true :tags labels})))

(defn- link-ok?
  "簡易検証。http／https のスキームを要る。"
  [url]
  (boolean (re-matches #"(?i)https?://\S+" url)))

(defn- normalize-links [raw]
  (let [urls (mapv (fn [u] (str/trim (str (if (nil? u) "" u)))) (as-vector raw))]
    (cond
      (> (count urls) link-max)
      {:ok false :code "link_limit"}

      (some (complement link-ok?) urls)
      {:ok false :code "link_invalid"}

      :else
      {:ok true :links urls})))

(defn- parent-ready?
  "返信先は未削除の公開メモだけ（他人の下書きも自分の下書きも親にしない）。"
  [parent]
  (and (some? parent) (not (deleted? parent)) (published? parent)))

(defn- find-visible [sys actor id]
  (let [mid (geo/as-int id)
        memo (when mid (db/find-memo (:ds sys) mid))]
    (when (visible? memo actor)
      memo)))

;;; タイムライン・取得

(defn- page-limit [raw]
  (min page-limit-max (max 1 (or (geo/as-int raw) page-limit-default))))

(defn list-timeline
  "§2.2 未削除の公開済み親メモのみ。published_at の新しいものが上。"
  [sys actor {:keys [limit before_id]}]
  (let [lim (page-limit limit)
        before (geo/as-int before_id)
        rows (db/list-timeline-memos (:ds sys) lim before)]
    (log-info! "メモのタイムラインを返しました" (assoc (actor-info actor) :count (count rows) :limit lim :before-id before))
    {:ok true :memos (mapv #(present-memo sys % actor) rows)}))

(defn get-memo [sys actor id]
  (if-let [memo (find-visible sys actor id)]
    (do
      (log-info! "メモを返しました" (assoc (actor-info actor) :memo-id (:id memo)))
      {:ok true :memo (present-memo sys memo actor)})
    (deny "メモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))))

(defn list-replies
  "§2.5 直下の未削除返信。公開分と、見ている本人の下書き返信。"
  [sys actor id]
  (if-let [memo (find-visible sys actor id)]
    (let [rows (db/list-memo-replies (:ds sys) (:id memo) (actor-kind actor) (actor-id actor))]
      (log-info! "メモの返信を一覧しました" (assoc (actor-info actor) :memo-id (:id memo) :count (count rows)))
      {:ok true :replies (mapv #(present-memo sys % actor) rows)})
    (deny "返信を見るメモがありません" "memo_not_found"
          (assoc (actor-info actor) :memo-id (str id)))))

(defn list-drafts
  "§2.3 ログイン中の本人の未削除の下書きだけ。"
  [sys actor]
  (let [rows (db/list-draft-memos (:ds sys) (actor-kind actor) (actor-id actor))]
    (log-info! "メモの下書きを一覧しました" (assoc (actor-info actor) :count (count rows)))
    {:ok true :memos (mapv #(present-memo sys % actor) rows)}))

;;; 作成・更新・公開・削除

(defn create-memo
  "§2.5 新規。親メモまたは parent_id 付き返信。status は draft／published。"
  [sys actor body]
  (let [ds (:ds sys)
        status (if (= "published" (str (:status body))) "published" "draft")
        parent-raw (:parent_id body)
        parent-given? (if (nil? parent-raw)
                        false
                        (not (str/blank? (str parent-raw))))
        parent-id (when parent-given? (geo/as-int parent-raw))
        parent (when parent-id (db/find-memo ds parent-id))
        nb (normalize-body (:body body))
        nt (normalize-tags (:tags body))
        nl (normalize-links (:links body))]
    (cond
      (if parent-given? (not (parent-ready? parent)) false)
      (deny "返信先のメモがありません" "parent_not_found"
            (assoc (actor-info actor) :parent-id (str parent-raw)))

      (not (:ok nb))
      (deny "メモの本文が使えません" (:code nb) (actor-info actor))

      (not (:ok nt))
      (deny "メモのタグが使えません" (:code nt) (actor-info actor))

      (not (:ok nl))
      (deny "メモのリンクが使えません" (:code nl) (actor-info actor))

      (if (= "published" status) (str/blank? (:body nb)) false)
      (deny "本文が空のメモは公開できません" "body_required" (actor-info actor))

      :else
      (let [now (time/now-utc)
            row (db/insert-memo! ds {:parent-id parent-id
                                     :author-kind (actor-kind actor)
                                     :author-id (actor-id actor)
                                     :status status
                                     :body (:body nb)
                                     :published-at (when (= "published" status) now)
                                     :content-saved-at now})
            mid (:id row)]
        (db/replace-memo-tags! ds mid (:tags nt))
        (db/replace-memo-links! ds mid (:links nl))
        (log-info! "メモを作りました" (assoc (actor-info actor)
                         :memo-id mid :status status :parent-id parent-id
                         :tags (count (:tags nt)) :links (count (:links nl))
                         :body-length (code-points (:body nb))))
        {:ok true :memo (present-memo sys (db/find-memo ds mid) actor)}))))

(defn- apply-memo-content!
  "本文・タグ・リンクを保存し、提示用のメモを返す。"
  [sys actor memo body]
  (let [ds (:ds sys)
        mid (:id memo)
        nb (if (contains? body :body)
             (normalize-body (:body body))
             {:ok true :body (:body memo)})
        nt (if (contains? body :tags)
             (normalize-tags (:tags body))
             {:ok true :tags (db/list-memo-tags ds mid)})
        nl (if (contains? body :links)
             (normalize-links (:links body))
             {:ok true :links (db/list-memo-links ds mid)})]
    (cond
      (not (:ok nb))
      (deny "メモの本文が使えません" (:code nb) (assoc (actor-info actor) :memo-id mid))
      (not (:ok nt))
      (deny "メモのタグが使えません" (:code nt) (assoc (actor-info actor) :memo-id mid))
      (not (:ok nl))
      (deny "メモのリンクが使えません" (:code nl) (assoc (actor-info actor) :memo-id mid))
      (published? memo)
      (if (str/blank? (:body nb))
        (deny "公開済みのメモの本文は空にできません" "body_required"
              (assoc (actor-info actor) :memo-id mid))
        (let [now (time/now-utc)]
          (db/update-memo-body! ds mid (:body nb) now)
          (db/replace-memo-tags! ds mid (:tags nt))
          (db/replace-memo-links! ds mid (:links nl))
          (log-info! "メモを直しました" (assoc (actor-info actor)
                           :memo-id mid :status (:status memo)
                           :tags (count (:tags nt)) :links (count (:links nl))
                           :content-saved-at now))
          {:ok true :memo (present-memo sys (db/find-memo ds mid) actor)}))
      :else
      (let [now (time/now-utc)]
        (db/update-memo-body! ds mid (:body nb) now)
        (db/replace-memo-tags! ds mid (:tags nt))
        (db/replace-memo-links! ds mid (:links nl))
        (log-info! "メモを直しました" (assoc (actor-info actor)
                         :memo-id mid :status (:status memo)
                         :tags (count (:tags nt)) :links (count (:links nl))
                         :content-saved-at now))
        {:ok true :memo (present-memo sys (db/find-memo ds mid) actor)}))))

(defn update-memo
  "§3.4 本文・タグ・リンクの更新。作成者本人のみ。公開済みは編集窓内のみ。保存で窓を延長する。"
  [sys actor id body]
  (let [memo (find-visible sys actor id)]
    (cond
      (nil? memo)
      (deny "直すメモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))

      (not (author? memo actor))
      (deny "作成者以外はメモの本文を直せません" "forbidden_memo"
            (assoc (actor-info actor) :memo-id (:id memo)))

      (if (published? memo)
        (not (edit-window-open? memo))
        false)
      (deny "メモの編集できる期限を過ぎています" "edit_window_closed"
            (assoc (actor-info actor) :memo-id (:id memo)
                   :editable-until (edit-deadline memo)))

      :else
      (apply-memo-content! sys actor memo body))))

(defn publish-memo
  "§3.3 下書きを公開する。published_at と content_saved_at を現在 UTC で立てる。"
  [sys actor id]
  (let [ds (:ds sys)
        memo (find-visible sys actor id)]
    (cond
      (nil? memo)
      (deny "公開するメモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))

      (published? memo)
      (deny "公開済みのメモは公開できません" "not_draft"
            (assoc (actor-info actor) :memo-id (:id memo)))

      (str/blank? (str (:body memo)))
      (deny "本文が空のメモは公開できません" "body_required"
            (assoc (actor-info actor) :memo-id (:id memo)))

      (if (:parent_id memo)
        (not (parent-ready? (db/find-memo ds (:parent_id memo))))
        false)
      (deny "返信先が公開済みでないので公開できません" "parent_not_found"
            (assoc (actor-info actor) :memo-id (:id memo) :parent-id (:parent_id memo)))

      :else
      (let [now (time/now-utc)
            mid (:id memo)]
        (db/publish-memo! ds mid now now)
        (log-info! "メモを公開しました" (assoc (actor-info actor) :memo-id mid :parent-id (:parent_id memo)
                         :published-at now))
        {:ok true :memo (present-memo sys (db/find-memo ds mid) actor)}))))

(defn soft-delete-memo
  "§3.6 作成者または管理者がソフト削除する。すべての子孫にも deleted_at を立てる。"
  [sys actor id]
  (let [memo (find-visible sys actor id)]
    (cond
      (nil? memo)
      (deny "消すメモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))

      (if (author? memo actor)
        false
        (not (admin? actor)))
      (deny "作成者と管理者以外はメモを消せません" "forbidden_memo"
            (assoc (actor-info actor) :memo-id (:id memo)))

      :else
      (let [r (db/soft-delete-memo-tree! (:ds sys) (:id memo))]
        (log-info! "メモをソフト削除しました" (assoc (actor-info actor) :memo-id (:id memo)
                         :descendants (:descendants r)))
        {:ok true}))))

;;; 検索（§2.5.2）

(defn- tokyo-day-start-utc
  "Asia/Tokyo の暦日 YYYY-MM-DD の0時を UTC 文字列にする。plus-days で翌日にできる。"
  [day plus-days]
  (when-let [d (time/parse-work-date day)]
    (time/format-instant (.toInstant (.atStartOfDay (.plusDays d (long plus-days)) time/tokyo)))))

(defn- like-term [raw]
  (let [t (str/trim (str (or raw "")))]
    (when-not (str/blank? t)
      (str "%" (str/lower-case t) "%"))))

(defn- resolve-authors
  "作成者メール（完全一致）を [kind id] の並びにする。利用者・管理者の両方を見る。"
  [ds email]
  (let [e (crypto/normalize-email email)]
    (->> [(when-let [u (db/find-user-by-email ds e)] ["user" (:id u)])
          (when-let [a (db/find-admin-by-email ds e)] ["admin" (:id a)])]
         (remove nil?)
         vec)))

(defn- given? [raw]
  (not (str/blank? (str (or raw "")))))

(defn search
  "§2.5.2 キーワード（本文・タグ・添付ファイル名）・除外語・期間（東京の暦日）・作成者。
  scope は parents／all（省略時 all）。drafts=1 のとき本人の下書きも対象。"
  [sys actor {:keys [q exclude from to author scope drafts limit]}]
  (let [ds (:ds sys)
        scope' (if (= "parents" (str scope)) "parents" "all")
        drafts? (contains? #{"1" "true" "yes"} (str/lower-case (str (or drafts ""))))
        lim (page-limit limit)
        from-utc (when (given? from) (tokyo-day-start-utc from 0))
        to-utc (when (given? to) (tokyo-day-start-utc to 1))
        authors (when (given? author) (resolve-authors ds author))]
    (when (and (given? from) (nil? from-utc))
      (apply log/warn "検索の開始日を読めないので無視します" (mapcat identity (seq (assoc (actor-info actor) :from (str from))))))
    (when (and (given? to) (nil? to-utc))
      (apply log/warn "検索の終了日を読めないので無視します" (mapcat identity (seq (assoc (actor-info actor) :to (str to))))))
    (when (and (given? author) (empty? authors))
      (apply log/warn "検索の作成者が見つかりません" (mapcat identity (seq (assoc (actor-info actor) :author (str author))))))
    (let [rows (db/search-memos ds {:q-like (like-term q)
                                    :exclude-like (like-term exclude)
                                    :from-utc from-utc
                                    :to-utc to-utc
                                    :authors authors
                                    :scope scope'
                                    :drafts? drafts?
                                    :viewer-kind (actor-kind actor)
                                    :viewer-id (actor-id actor)
                                    :limit lim})]
      (log-info! "メモを検索しました" (assoc (actor-info actor)
                       :count (count rows) :q (str q) :exclude (str exclude)
                       :from (str from) :to (str to) :author (str author)
                       :scope scope' :drafts drafts? :limit lim))
      {:ok true :memos (mapv #(present-memo sys % actor) rows)})))

;;; 添付（§3.7）

(defn- upload-map [upload]
  (when (map? upload)
    (into {} (map (fn [[k v]] [(keyword (name k)) v])) upload)))

(defn- upload-file [u]
  (or (:tempfile u) (:temp-file u)))

(defn- base-name
  "パス区切りを落とした元ファイル名。空なら file とする。"
  [raw]
  (let [s (str/trim (str (if (nil? raw) "" raw)))
        n (str (last (str/split s #"[/\\]")))]
    (if (str/blank? n) "file" n)))

(defn- disk-name
  "ディスク上の名前。元名は DB に持つので、ここでは安全な文字だけにする。"
  [aid filename]
  (let [safe (str/replace filename #"[^A-Za-z0-9._-]" "_")
        tail (if (> (count safe) 60) (subs safe (- (count safe) 60)) safe)]
    (str aid "-" tail)))

(defn- upload-content-type [raw]
  (let [c (str/trim (str (or raw "")))]
    (if (str/blank? c) "application/octet-stream" c)))

(defn- attachment-file [sys att]
  (io/file (memo-root sys) (:body_ref att)))

(defn add-attachment
  "§2.5 ファイル1つ追加（multipart）。下書きは作成者がいつでも、公開済みは編集窓内のみ。"
  [sys actor id upload]
  (let [ds (:ds sys)
        memo (find-visible sys actor id)
        u (upload-map upload)
        tf (upload-file u)]
    (log-info! "メモ添付の要求を受けました"
               (assoc (actor-info actor)
                      :memo-id (str id)
                      :has-upload (some? u)
                      :filename (when u (base-name (:filename u)))))
    (cond
      (nil? memo)
      (deny "添付するメモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))

      (not (author? memo actor))
      (deny "作成者以外はメモに添付できません" "forbidden_memo"
            (assoc (actor-info actor) :memo-id (:id memo)))

      (if (published? memo) (not (edit-window-open? memo)) false)
      (deny "メモの編集できる期限を過ぎています" "edit_window_closed"
            (assoc (actor-info actor) :memo-id (:id memo) :editable-until (edit-deadline memo)))

      (>= (long (db/count-memo-attachments ds (:id memo))) attachment-max)
      (deny "メモの添付が上限です" "attachment_limit"
            (assoc (actor-info actor) :memo-id (:id memo) :max attachment-max))

      (if (nil? tf)
        true
        (not (.isFile (io/file tf))))
      (deny "添付ファイルを読めません" "attachment_invalid"
            (assoc (actor-info actor) :memo-id (:id memo)))

      :else
      (let [mid (:id memo)
            filename (base-name (:filename u))
            ctype (upload-content-type (:content-type u))
            row (db/insert-memo-attachment! ds {:memo-id mid
                                                :filename filename
                                                :content-type ctype
                                                :body-ref ""})
            rel (str mid "/" (disk-name (:id row) filename))
            dest (io/file (memo-root sys) rel)
            now (time/now-utc)]
        (.mkdirs (io/file (memo-root sys) (str mid)))
        (io/copy (io/file tf) dest)
        (db/update-memo-attachment-body-ref! ds (:id row) rel)
        (db/update-memo-body! ds mid (:body memo) now)
        (log-info! "メモに添付しました" (assoc (actor-info actor)
                         :memo-id mid :attachment-id (:id row) :filename filename
                         :content-type ctype :path rel :content-saved-at now))
        {:ok true
         :attachment (present-attachment (db/find-memo-attachment ds mid (:id row)))
         :memo (present-memo sys (db/find-memo ds mid) actor)}))))

(defn get-attachment
  "ファイル本体。読める人はそのメモが見える人。"
  [sys actor id aid]
  (let [memo (find-visible sys actor id)
        att (when memo (db/find-memo-attachment (:ds sys) (:id memo) (geo/as-int aid)))]
    (cond
      (nil? memo)
      (deny "添付を見るメモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))

      (nil? att)
      (deny "メモの添付がありません" "attachment_not_found"
            (assoc (actor-info actor) :memo-id (:id memo) :attachment-id (str aid)))

      (not (.isFile (attachment-file sys att)))
      (deny "添付ファイルが見つかりません" "attachment_not_found"
            (assoc (actor-info actor) :memo-id (:id memo) :attachment-id (:id att)
                   :path (:body_ref att)))

      :else
      (do
        (log-info! "メモの添付を返しました" (assoc (actor-info actor) :memo-id (:id memo) :attachment-id (:id att)
                         :filename (:filename att)))
        {:ok true
         :file (attachment-file sys att)
         :content-type (:content_type att)
         :filename (:filename att)}))))

(defn delete-attachment
  "添付の削除。下書きは作成者がいつでも、公開済みは編集窓内のみ。"
  [sys actor id aid]
  (let [ds (:ds sys)
        memo (find-visible sys actor id)
        att (when memo (db/find-memo-attachment ds (:id memo) (geo/as-int aid)))]
    (cond
      (nil? memo)
      (deny "添付を消すメモがありません" "memo_not_found" (assoc (actor-info actor) :memo-id (str id)))

      (not (author? memo actor))
      (deny "作成者以外はメモの添付を消せません" "forbidden_memo"
            (assoc (actor-info actor) :memo-id (:id memo)))

      (if (published? memo) (not (edit-window-open? memo)) false)
      (deny "メモの編集できる期限を過ぎています" "edit_window_closed"
            (assoc (actor-info actor) :memo-id (:id memo) :editable-until (edit-deadline memo)))

      (nil? att)
      (deny "消すメモの添付がありません" "attachment_not_found"
            (assoc (actor-info actor) :memo-id (:id memo) :attachment-id (str aid)))

      :else
      (let [mid (:id memo)
            now (time/now-utc)]
        (db/soft-delete-memo-attachment! ds (:id att))
        (io/delete-file (attachment-file sys att) true)
        (db/update-memo-body! ds mid (:body memo) now)
        (log-info! "メモの添付を消しました" (assoc (actor-info actor) :memo-id mid :attachment-id (:id att)
                         :filename (:filename att) :content-saved-at now))
        {:ok true :memo (present-memo sys (db/find-memo ds mid) actor)}))))

;;; ブックマーク（§3.8・§2.4）

(defn add-bookmark
  "公開済み・未削除メモにのみ付与。同じ人の二重付けは成功のまま戻す。"
  [sys actor id]
  (let [ds (:ds sys)
        memo (find-visible sys actor id)]
    (cond
      (nil? memo)
      (deny "ブックマークするメモがありません" "memo_not_found"
            (assoc (actor-info actor) :memo-id (str id)))

      (not (published? memo))
      (deny "下書きにはブックマークを付けられません" "forbidden_memo"
            (assoc (actor-info actor) :memo-id (:id memo)))

      :else
      (let [mid (:id memo)
            already? (db/author-has-bookmarked? ds mid (actor-kind actor) (actor-id actor))]
        (db/upsert-memo-bookmark! ds mid (actor-kind actor) (actor-id actor))
        (log-info! "メモにブックマークを付けました" (assoc (actor-info actor) :memo-id mid :already already?))
        {:ok true :memo (present-memo sys (db/find-memo ds mid) actor)}))))

(defn remove-bookmark
  "自分が付けたブックマークだけ外せる。他人の印は残る。"
  [sys actor id]
  (let [ds (:ds sys)
        mid (geo/as-int id)
        mine (when mid (db/find-memo-bookmark ds mid (actor-kind actor) (actor-id actor)))]
    (if (nil? mine)
      (deny "外すブックマークがありません" "bookmark_not_found"
            (assoc (actor-info actor) :memo-id (str id)))
      (do
        (db/delete-memo-bookmark! ds mid (actor-kind actor) (actor-id actor))
        (log-info! "メモのブックマークを外しました" (assoc (actor-info actor) :memo-id mid))
        {:ok true}))))

(defn list-bookmarks
  "§2.4 1件以上ブックマークが付いている未削除の公開メモ。最新の created_at 降順。"
  [sys actor]
  (let [rows (db/list-bookmarked-memos (:ds sys))]
    (log-info! "メモのブックマーク一覧を返しました" (assoc (actor-info actor) :count (count rows)))
    {:ok true :memos (mapv #(present-memo sys % actor) rows)}))

;;; ガント任意紐づけ（第2版工程5）

(defn- resolve-linkable-gantt
  "新規紐づけ先。未削除のみ。利用者は自分の行、管理者は全利用者。"
  [sys actor gantt-id]
  (let [gid (geo/as-int gantt-id)]
    (cond
      (nil? gid)
      nil

      :else
      (let [row (db/find-gantt-row-by-id (:ds sys) gid)]
        (cond
          (or (nil? row) (some? (:deleted_at row)))
          nil

          (admin? actor)
          row

          (and (= "user" (actor-kind actor))
               (= (actor-id actor) (long (:user_id row))))
          row

          :else
          nil)))))

(defn link-gantt!
  "公開済み親に作業を1件紐づける（既存があれば置き換え）。編集窓・content_saved_at は独立。"
  [sys actor id body]
  (let [mid (geo/as-int id)
        memo (when mid (db/find-memo (:ds sys) mid))
        ctx (assoc (actor-info actor) :memo-id mid :gantt-id (:gantt_id body))]
    (cond
      (or (nil? memo) (deleted? memo) (not (visible? memo actor)))
      (deny "紐づけ先のメモがありません" "memo_not_found" ctx)

      (not (linkable-parent? memo))
      (deny "下書きまたは返信には紐づけできません" "link_not_allowed" ctx)

      (not (can-link-gantt? memo actor))
      (deny "メモの紐づけは作成者または管理者だけです" "forbidden_memo" ctx)

      (nil? (geo/as-int (:gantt_id body)))
      (deny "紐づけ先の作業 ID が必要です" "gantt_id_required" ctx)

      :else
      (let [row (resolve-linkable-gantt sys actor (:gantt_id body))]
        (if-not row
          (deny "紐づけ先の作業がありません" "gantt_not_found"
                (assoc ctx :gantt-id (str (:gantt_id body))))
          (let [gid (:id row)
                updated (db/set-memo-gantt! (:ds sys) mid gid)]
            (log-info! "メモに作業を紐づけました"
                       (assoc (actor-info actor) :memo-id mid :gantt-id gid
                              :replaced-gantt-id (:gantt_id memo)))
            {:ok true :memo (present-memo sys updated actor)}))))))

(defn unlink-gantt!
  "紐づけを外す。無くても成功（冪等）。"
  [sys actor id]
  (let [mid (geo/as-int id)
        memo (when mid (db/find-memo (:ds sys) mid))
        ctx (assoc (actor-info actor) :memo-id mid)]
    (cond
      (or (nil? memo) (deleted? memo) (not (visible? memo actor)))
      (deny "紐づけ解除のメモがありません" "memo_not_found" ctx)

      (not (linkable-parent? memo))
      (deny "下書きまたは返信の紐づけは外せません" "link_not_allowed" ctx)

      (not (can-link-gantt? memo actor))
      (deny "メモの紐づけ解除は作成者または管理者だけです" "forbidden_memo" ctx)

      :else
      (let [prev (:gantt_id memo)
            updated (if (nil? prev)
                      memo
                      (db/set-memo-gantt! (:ds sys) mid nil))]
        (log-info! "メモの作業紐づけを外しました"
                   (assoc (actor-info actor) :memo-id mid :previous-gantt-id prev))
        {:ok true :memo (present-memo sys updated actor)}))))
