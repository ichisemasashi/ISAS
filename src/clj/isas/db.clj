(ns isas.db
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [isas.log :as log]
            [isas.time :as time]))

(def schema
  ["PRAGMA foreign_keys = ON"
   "CREATE TABLE IF NOT EXISTS admins (
      id INTEGER PRIMARY KEY,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      created_at TEXT NOT NULL
    )"
   "CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      revoked_at TEXT,
      invited_by_kind TEXT NOT NULL,
      invited_by_id INTEGER NOT NULL,
      created_at TEXT NOT NULL
    )"
   "CREATE TABLE IF NOT EXISTS reset_tokens (
      id INTEGER PRIMARY KEY,
      kind TEXT NOT NULL,
      account_id INTEGER NOT NULL,
      token_hash TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      used_at TEXT
    )"
   "CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      kind TEXT NOT NULL,
      account_id INTEGER NOT NULL,
      expires_at TEXT NOT NULL,
      revoked_at TEXT
    )"
   "CREATE TABLE IF NOT EXISTS work_places (
      user_id INTEGER PRIMARY KEY,
      west REAL NOT NULL,
      south REAL NOT NULL,
      east REAL NOT NULL,
      north REAL NOT NULL,
      image_west REAL,
      image_south REAL,
      image_east REAL,
      image_north REAL,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS basemaps (
      user_id INTEGER NOT NULL,
      kind TEXT NOT NULL,
      content_type TEXT NOT NULL,
      body_ref TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      PRIMARY KEY (user_id, kind),
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS fields (
      id INTEGER PRIMARY KEY,
      user_id INTEGER NOT NULL,
      name TEXT NOT NULL,
      geojson TEXT NOT NULL,
      area_m2 INTEGER,
      area_ha REAL,
      memo TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS paints (
      id INTEGER PRIMARY KEY,
      field_id INTEGER NOT NULL,
      work_name TEXT NOT NULL,
      geojson TEXT NOT NULL,
      created_at TEXT NOT NULL,
      FOREIGN KEY (field_id) REFERENCES fields(id)
    )"
   "CREATE TABLE IF NOT EXISTS gantt_titles (
      id INTEGER PRIMARY KEY,
      user_id INTEGER NOT NULL,
      name TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      deleted_at TEXT,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS gantt_rows (
      id INTEGER PRIMARY KEY,
      user_id INTEGER NOT NULL,
      title_id INTEGER,
      title TEXT NOT NULL,
      start_at TEXT NOT NULL,
      end_at TEXT NOT NULL,
      work_name TEXT,
      execution_status TEXT NOT NULL DEFAULT 'not_started',
      version INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      deleted_at TEXT,
      FOREIGN KEY (user_id) REFERENCES users(id),
      FOREIGN KEY (title_id) REFERENCES gantt_titles(id)
    )"
   "CREATE TABLE IF NOT EXISTS gantt_targets (
      gantt_id INTEGER NOT NULL,
      field_id INTEGER NOT NULL,
      PRIMARY KEY (gantt_id, field_id),
      FOREIGN KEY (gantt_id) REFERENCES gantt_rows(id),
      FOREIGN KEY (field_id) REFERENCES fields(id)
    )"
   "CREATE TABLE IF NOT EXISTS gantt_progress_days (
      gantt_id INTEGER NOT NULL,
      day TEXT NOT NULL,
      percent INTEGER,
      applicable INTEGER NOT NULL,
      finalized_at TEXT NOT NULL,
      finalized_by TEXT NOT NULL,
      PRIMARY KEY (gantt_id, day),
      FOREIGN KEY (gantt_id) REFERENCES gantt_rows(id)
    )"
   "CREATE TABLE IF NOT EXISTS gantt_work_times (
      id INTEGER PRIMARY KEY,
      gantt_id INTEGER NOT NULL,
      start_at TEXT NOT NULL,
      end_at TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      deleted_at TEXT,
      FOREIGN KEY (gantt_id) REFERENCES gantt_rows(id)
    )"
   "CREATE TABLE IF NOT EXISTS gantt_checklist_items (
      id INTEGER PRIMARY KEY,
      gantt_id INTEGER NOT NULL,
      label TEXT NOT NULL,
      status TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      deleted_at TEXT,
      FOREIGN KEY (gantt_id) REFERENCES gantt_rows(id)
    )"
   "CREATE TABLE IF NOT EXISTS orders (
      id INTEGER PRIMARY KEY,
      issuer_id INTEGER NOT NULL,
      work_date TEXT NOT NULL,
      start_time TEXT NOT NULL,
      end_time TEXT NOT NULL,
      work_name TEXT NOT NULL,
      body TEXT NOT NULL,
      closed_at TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (issuer_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS order_recipients (
      order_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      PRIMARY KEY (order_id, user_id),
      FOREIGN KEY (order_id) REFERENCES orders(id),
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS order_targets (
      order_id INTEGER NOT NULL,
      field_id INTEGER NOT NULL,
      PRIMARY KEY (order_id, field_id),
      FOREIGN KEY (order_id) REFERENCES orders(id),
      FOREIGN KEY (field_id) REFERENCES fields(id)
    )"
   "CREATE TABLE IF NOT EXISTS journals (
      id INTEGER PRIMARY KEY,
      order_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      body TEXT NOT NULL,
      created_at TEXT NOT NULL,
      UNIQUE (order_id, user_id),
      FOREIGN KEY (order_id) REFERENCES orders(id),
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS relation_cuts (
      user_lo INTEGER NOT NULL,
      user_hi INTEGER NOT NULL,
      cut_at TEXT NOT NULL,
      PRIMARY KEY (user_lo, user_hi),
      FOREIGN KEY (user_lo) REFERENCES users(id),
      FOREIGN KEY (user_hi) REFERENCES users(id)
    )"
   "CREATE TABLE IF NOT EXISTS memos (
      id INTEGER PRIMARY KEY,
      parent_id INTEGER,
      author_kind TEXT NOT NULL,
      author_id INTEGER NOT NULL,
      status TEXT NOT NULL,
      body TEXT NOT NULL,
      published_at TEXT,
      content_saved_at TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      deleted_at TEXT,
      FOREIGN KEY (parent_id) REFERENCES memos(id)
    )"
   "CREATE TABLE IF NOT EXISTS memo_tags (
      memo_id INTEGER NOT NULL,
      label TEXT NOT NULL,
      PRIMARY KEY (memo_id, label),
      FOREIGN KEY (memo_id) REFERENCES memos(id)
    )"
   "CREATE TABLE IF NOT EXISTS memo_links (
      id INTEGER PRIMARY KEY,
      memo_id INTEGER NOT NULL,
      url TEXT NOT NULL,
      position INTEGER NOT NULL,
      FOREIGN KEY (memo_id) REFERENCES memos(id)
    )"
   "CREATE TABLE IF NOT EXISTS memo_attachments (
      id INTEGER PRIMARY KEY,
      memo_id INTEGER NOT NULL,
      filename TEXT NOT NULL,
      content_type TEXT NOT NULL,
      body_ref TEXT NOT NULL,
      created_at TEXT NOT NULL,
      deleted_at TEXT,
      FOREIGN KEY (memo_id) REFERENCES memos(id)
    )"
   "CREATE TABLE IF NOT EXISTS memo_bookmarks (
      memo_id INTEGER NOT NULL,
      bookmarker_kind TEXT NOT NULL,
      bookmarker_id INTEGER NOT NULL,
      created_at TEXT NOT NULL,
      PRIMARY KEY (memo_id, bookmarker_kind, bookmarker_id),
      FOREIGN KEY (memo_id) REFERENCES memos(id)
    )"])

(defn datasource [jdbc-url]
  (jdbc/with-options (jdbc/get-datasource {:jdbcUrl jdbc-url})
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn table-columns [ds table]
  (set (map (fn [row] (str (:name row)))
            (jdbc/execute! ds [(str "PRAGMA table_info(" table ")")]))))

(defn- ensure-column! [ds table column decl]
  (when-not (contains? (table-columns ds table) (str column))
    (jdbc/execute! ds [(str "ALTER TABLE " table " ADD COLUMN " column " " decl)])))

(defn- migrate-one-gantt-title!
  [ds row]
  (let [uid (:user_id row)
        nm (str (:title row))
        existing (jdbc/execute-one! ds
                                    ["SELECT * FROM gantt_titles WHERE user_id = ? AND name = ? AND deleted_at IS NULL ORDER BY id LIMIT 1"
                                     uid nm])
        tid (if-let [eid (:id existing)]
              eid
              (:id (jdbc/execute-one! ds
                                      ["INSERT INTO gantt_titles (user_id, name, created_at, updated_at) VALUES (?, ?, ?, ?) RETURNING *"
                                       uid nm (time/now-utc) (time/now-utc)])))]
    (jdbc/execute-one! ds ["UPDATE gantt_rows SET title_id = ? WHERE id = ?" tid (:id row)])))

(defn- migrate-gantt-titles!
  "既存のガント行を題名グループへ移す。同じ題名文字列は1つの題名にまとめる。"
  [ds]
  (run! #(migrate-one-gantt-title! ds %)
        (jdbc/execute! ds ["SELECT * FROM gantt_rows WHERE title_id IS NULL ORDER BY user_id, id"])))

(defn migrate! [ds]
  (run! (fn [sql] (jdbc/execute! ds [sql])) schema)
  (ensure-column! ds "work_places" "image_west" "REAL")
  (ensure-column! ds "work_places" "image_south" "REAL")
  (ensure-column! ds "work_places" "image_east" "REAL")
  (ensure-column! ds "work_places" "image_north" "REAL")
  (ensure-column! ds "users" "ui_lang" "TEXT")
  (ensure-column! ds "admins" "ui_lang" "TEXT")
  (ensure-column! ds "gantt_rows" "deleted_at" "TEXT")
  (ensure-column! ds "gantt_rows" "title_id" "INTEGER")
  (ensure-column! ds "gantt_rows" "execution_status" "TEXT NOT NULL DEFAULT 'not_started'")
  (jdbc/execute! ds ["UPDATE gantt_rows SET execution_status = 'not_started'
                      WHERE execution_status IS NULL OR TRIM(execution_status) = ''
                         OR execution_status NOT IN ('not_started', 'in_progress', 'done')"])
  (ensure-column! ds "gantt_rows" "version" "INTEGER NOT NULL DEFAULT 1")
  (ensure-column! ds "fields" "area_m2" "INTEGER")
  (ensure-column! ds "fields" "area_ha" "REAL")
  (ensure-column! ds "fields" "memo" "TEXT")
  (ensure-column! ds "memos" "gantt_id" "INTEGER")
  (migrate-gantt-titles! ds)
  (log/info "データベースの表を用意しました")
  ds)

(defn sqlite-url [path]
  (str "jdbc:sqlite:" path))

(defn count-admins [ds]
  (:c (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM admins"])))

(defn find-admin-by-email [ds email]
  (jdbc/execute-one! ds ["SELECT * FROM admins WHERE email = ?" email]))

(defn find-admin-by-id [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM admins WHERE id = ?" id]))

(defn insert-admin! [ds email password-hash]
  (jdbc/execute-one! ds
                     ["INSERT INTO admins (email, password_hash, created_at) VALUES (?, ?, ?) RETURNING *"
                      email password-hash (time/now-utc)]))

(defn update-admin-password! [ds id password-hash]
  (jdbc/execute-one! ds ["UPDATE admins SET password_hash = ? WHERE id = ?" password-hash id]))

(defn update-admin-ui-lang! [ds id ui-lang]
  (jdbc/execute-one! ds ["UPDATE admins SET ui_lang = ? WHERE id = ?" ui-lang id]))

(defn find-user-by-email [ds email]
  (jdbc/execute-one! ds ["SELECT * FROM users WHERE email = ?" email]))

(defn find-user-by-id [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM users WHERE id = ?" id]))

(defn insert-user! [ds {:keys [email password-hash invited-by-kind invited-by-id]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO users (email, password_hash, revoked_at, invited_by_kind, invited_by_id, created_at)
                       VALUES (?, ?, NULL, ?, ?, ?) RETURNING *"
                      email password-hash invited-by-kind invited-by-id (time/now-utc)]))

(defn reinvite-user! [ds id password-hash invited-by-kind invited-by-id]
  (jdbc/execute-one! ds
                     ["UPDATE users SET password_hash = ?, revoked_at = NULL, invited_by_kind = ?, invited_by_id = ?
                       WHERE id = ?"
                      password-hash invited-by-kind invited-by-id id]))

(defn update-user-password! [ds id password-hash]
  (jdbc/execute-one! ds ["UPDATE users SET password_hash = ? WHERE id = ?" password-hash id]))

(defn update-user-ui-lang! [ds id ui-lang]
  (jdbc/execute-one! ds ["UPDATE users SET ui_lang = ? WHERE id = ?" ui-lang id]))

(defn list-active-users [ds]
  (jdbc/execute! ds ["SELECT id, email FROM users WHERE revoked_at IS NULL ORDER BY email"]))

(defn revoke-user! [ds id]
  (jdbc/execute-one! ds ["UPDATE users SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL"
                         (time/now-utc) id]))

(defn insert-session! [ds {:keys [id kind account-id expires-at]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO sessions (id, kind, account_id, expires_at, revoked_at) VALUES (?, ?, ?, ?, NULL)"
                      id kind account-id expires-at]))

(defn find-session [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM sessions WHERE id = ?" id]))

(defn revoke-session! [ds id]
  (jdbc/execute-one! ds ["UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL"
                         (time/now-utc) id]))

(defn revoke-user-sessions! [ds user-id]
  (jdbc/execute-one! ds ["UPDATE sessions SET revoked_at = ? WHERE kind = 'user' AND account_id = ? AND revoked_at IS NULL"
                         (time/now-utc) user-id]))

(defn insert-reset-token! [ds {:keys [kind account-id token-hash expires-at]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO reset_tokens (kind, account_id, token_hash, expires_at, used_at) VALUES (?, ?, ?, ?, NULL) RETURNING *"
                      kind account-id token-hash expires-at]))

(defn invalidate-reset-tokens! [ds kind account-id]
  (jdbc/execute-one! ds ["UPDATE reset_tokens SET used_at = ? WHERE kind = ? AND account_id = ? AND used_at IS NULL"
                         (time/now-utc) kind account-id]))

(defn open-reset-tokens [ds kind]
  (jdbc/execute! ds ["SELECT * FROM reset_tokens WHERE kind = ? AND used_at IS NULL" kind]))

(defn mark-token-used! [ds id]
  (jdbc/execute-one! ds ["UPDATE reset_tokens SET used_at = ? WHERE id = ?" (time/now-utc) id]))

(defn find-place [ds user-id]
  (jdbc/execute-one! ds ["SELECT * FROM work_places WHERE user_id = ?" user-id]))

(defn upsert-place! [ds user-id {:keys [west south east north]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO work_places (user_id, west, south, east, north, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?)
                       ON CONFLICT(user_id) DO UPDATE SET
                         west = excluded.west, south = excluded.south,
                         east = excluded.east, north = excluded.north,
                         image_west = NULL, image_south = NULL,
                         image_east = NULL, image_north = NULL,
                         updated_at = excluded.updated_at
                       RETURNING *"
                      user-id west south east north (time/now-utc)]))

(defn update-place-image! [ds user-id {:keys [west south east north]}]
  (jdbc/execute-one! ds
                     ["UPDATE work_places SET image_west = ?, image_south = ?, image_east = ?, image_north = ?,
                         updated_at = ? WHERE user_id = ? RETURNING *"
                      west south east north (time/now-utc) user-id]))

(defn list-basemaps [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM basemaps WHERE user_id = ?" user-id]))

(defn find-basemap [ds user-id kind]
  (jdbc/execute-one! ds ["SELECT * FROM basemaps WHERE user_id = ? AND kind = ?" user-id kind]))

(defn upsert-basemap! [ds {:keys [user-id kind content-type body-ref]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO basemaps (user_id, kind, content_type, body_ref, updated_at)
                       VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT(user_id, kind) DO UPDATE SET
                         content_type = excluded.content_type,
                         body_ref = excluded.body_ref,
                         updated_at = excluded.updated_at
                       RETURNING *"
                      user-id kind content-type body-ref (time/now-utc)]))

(defn delete-basemaps! [ds user-id]
  (jdbc/execute-one! ds ["DELETE FROM basemaps WHERE user_id = ?" user-id]))

(defn delete-place! [ds user-id]
  (jdbc/execute-one! ds ["DELETE FROM work_places WHERE user_id = ?" user-id]))

(defn delete-order-targets-for-field! [ds field-id]
  (jdbc/execute-one! ds ["DELETE FROM order_targets WHERE field_id = ?" field-id]))

(defn delete-orders-for-user!
  "出した指示・受けた指示・日誌・対象・受け手を消す。"
  [ds user-id]
  (jdbc/execute-one! ds ["DELETE FROM journals WHERE user_id = ?" user-id])
  (jdbc/execute-one! ds ["DELETE FROM journals WHERE order_id IN (SELECT id FROM orders WHERE issuer_id = ?)"
                         user-id])
  (jdbc/execute-one! ds ["DELETE FROM order_recipients WHERE user_id = ?" user-id])
  (jdbc/execute-one! ds ["DELETE FROM order_recipients WHERE order_id IN (SELECT id FROM orders WHERE issuer_id = ?)"
                         user-id])
  (jdbc/execute-one! ds ["DELETE FROM order_targets WHERE order_id IN (SELECT id FROM orders WHERE issuer_id = ?)"
                         user-id])
  (jdbc/execute-one! ds ["DELETE FROM order_targets WHERE field_id IN (SELECT id FROM fields WHERE user_id = ?)"
                         user-id])
  (jdbc/execute-one! ds ["DELETE FROM orders WHERE issuer_id = ?" user-id]))

(defn delete-relation-cuts-for-user! [ds user-id]
  (jdbc/execute-one! ds ["DELETE FROM relation_cuts WHERE user_lo = ? OR user_hi = ?" user-id user-id]))

(defn delete-fields-for-user! [ds user-id]
  (delete-orders-for-user! ds user-id)
  (jdbc/execute-one! ds ["DELETE FROM gantt_targets WHERE field_id IN (SELECT id FROM fields WHERE user_id = ?)"
                         user-id])
  (jdbc/execute-one! ds ["DELETE FROM gantt_rows WHERE user_id = ?" user-id])
  (jdbc/execute-one! ds ["DELETE FROM paints WHERE field_id IN (SELECT id FROM fields WHERE user_id = ?)"
                         user-id])
  (jdbc/execute-one! ds ["DELETE FROM fields WHERE user_id = ?" user-id]))

(defn delete-reset-tokens-for-account! [ds kind account-id]
  (jdbc/execute-one! ds ["DELETE FROM reset_tokens WHERE kind = ? AND account_id = ?"
                         kind account-id]))

(defn delete-user-owned-data!
  "作業場所・下地行・圃場・塗り・指示・日誌・関係切断・再設定トークンを消す（下地ファイルは呼び出し側）。"
  [ds user-id]
  (delete-fields-for-user! ds user-id)
  (delete-relation-cuts-for-user! ds user-id)
  (delete-basemaps! ds user-id)
  (delete-place! ds user-id)
  (delete-reset-tokens-for-account! ds "user" user-id))

(defn insert-field! [ds {:keys [user-id name geojson area-m2 area-ha memo]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO fields (user_id, name, geojson, area_m2, area_ha, memo, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *"
                      user-id name geojson area-m2 area-ha (or memo "") (time/now-utc) (time/now-utc)]))

(defn find-field [ds user-id id]
  (jdbc/execute-one! ds ["SELECT * FROM fields WHERE id = ? AND user_id = ?" id user-id]))

(defn list-fields [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM fields WHERE user_id = ? ORDER BY id" user-id]))

(defn update-field! [ds id {:keys [name geojson area-m2 area-ha memo]}]
  (jdbc/execute-one! ds
                     ["UPDATE fields SET name = ?, geojson = ?, area_m2 = ?, area_ha = ?, memo = ?, updated_at = ? WHERE id = ? RETURNING *"
                      name geojson area-m2 area-ha (or memo "") (time/now-utc) id]))

(defn delete-field! [ds id]
  (jdbc/execute-one! ds ["DELETE FROM fields WHERE id = ?" id]))

(defn field-names [ds user-id]
  (mapv :name (jdbc/execute! ds ["SELECT name FROM fields WHERE user_id = ?" user-id])))

(defn insert-paint! [ds {:keys [field-id work-name geojson]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO paints (field_id, work_name, geojson, created_at)
                       VALUES (?, ?, ?, ?) RETURNING *"
                      field-id work-name geojson (time/now-utc)]))

(defn find-paint-for-user [ds user-id id]
  (jdbc/execute-one! ds
                     ["SELECT p.* FROM paints p JOIN fields f ON f.id = p.field_id
                       WHERE p.id = ? AND f.user_id = ?"
                      id user-id]))

(defn list-paints-for-field [ds field-id]
  (jdbc/execute! ds ["SELECT * FROM paints WHERE field_id = ? ORDER BY id" field-id]))

(defn list-paints-for-field-name [ds field-id work-name]
  (jdbc/execute! ds ["SELECT * FROM paints WHERE field_id = ? AND work_name = ? ORDER BY id"
                     field-id work-name]))

(defn list-work-names [ds user-id]
  (mapv :work_name
        (jdbc/execute! ds
                       ["SELECT DISTINCT p.work_name AS work_name FROM paints p
                         JOIN fields f ON f.id = p.field_id
                         WHERE f.user_id = ?
                         ORDER BY p.work_name"
                        user-id])))

(defn count-paints-for-field [ds field-id]
  (:c (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM paints WHERE field_id = ?" field-id])))

(defn delete-paint! [ds id]
  (jdbc/execute-one! ds ["DELETE FROM paints WHERE id = ?" id]))

(defn delete-paints-for-field! [ds field-id]
  (jdbc/execute-one! ds ["DELETE FROM paints WHERE field_id = ?" field-id]))

(defn delete-paints-for-field-name! [ds field-id work-name]
  (jdbc/execute-one! ds ["DELETE FROM paints WHERE field_id = ? AND work_name = ?" field-id work-name]))

(defn update-paint-geojson! [ds id geojson]
  (jdbc/execute-one! ds ["UPDATE paints SET geojson = ? WHERE id = ?" geojson id]))

(defn insert-gantt-title! [ds {:keys [user-id name]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO gantt_titles (user_id, name, created_at, updated_at)
                       VALUES (?, ?, ?, ?) RETURNING *"
                      user-id name (time/now-utc) (time/now-utc)]))

(defn update-gantt-title! [ds id name]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_titles SET name = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      name (time/now-utc) id]))

(defn find-gantt-title [ds user-id id]
  (jdbc/execute-one! ds ["SELECT * FROM gantt_titles WHERE id = ? AND user_id = ?" id user-id]))

(defn list-gantt-titles [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_titles WHERE user_id = ? AND deleted_at IS NULL
                      ORDER BY name, id"
                     user-id]))

(defn soft-delete-gantt-title! [ds id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_titles SET deleted_at = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      (time/now-utc) (time/now-utc) id]))

(defn soft-delete-gantt-rows-for-title! [ds title-id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_rows SET deleted_at = ?, updated_at = ?
                       WHERE title_id = ? AND deleted_at IS NULL"
                      (time/now-utc) (time/now-utc) title-id]))

(defn insert-gantt-row! [ds {:keys [user-id title-id title start-at end-at work-name execution-status]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO gantt_rows (user_id, title_id, title, start_at, end_at, work_name, execution_status, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *"
                      user-id title-id title start-at end-at work-name
                      (or execution-status "not_started")
                      (time/now-utc) (time/now-utc)]))

(defn update-gantt-row!
  "expected-version が nil 以外なら、版が一致するときだけ更新する。更新できなければ nil。"
  [ds id {:keys [title-id title start-at end-at work-name execution-status expected-version]}]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_rows SET title_id = ?, title = ?, start_at = ?, end_at = ?, work_name = ?,
                        execution_status = ?, updated_at = ?, version = version + 1
                       WHERE id = ? AND (? IS NULL OR version = ?) RETURNING *"
                      title-id title start-at end-at work-name execution-status (time/now-utc) id
                      expected-version expected-version]))

(defn update-gantt-row-status! [ds id execution-status]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_rows SET execution_status = ?, updated_at = ?, version = version + 1
                       WHERE id = ? RETURNING *"
                      execution-status (time/now-utc) id]))

(defn find-gantt-row [ds user-id id]
  (jdbc/execute-one! ds ["SELECT * FROM gantt_rows WHERE id = ? AND user_id = ?" id user-id]))

(defn find-gantt-row-by-id
  "利用者横断。ソフト削除済みも含む（メモ紐づけ要約用）。"
  [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM gantt_rows WHERE id = ?" id]))

(defn list-gantt-rows [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_rows WHERE user_id = ? AND deleted_at IS NULL ORDER BY start_at, id"
                     user-id]))

(defn list-gantt-rows-undeleted-all
  "管理者候補用。全利用者の未削除行。start_at → user_id → id。"
  [ds]
  (jdbc/execute! ds ["SELECT * FROM gantt_rows WHERE deleted_at IS NULL
                      ORDER BY start_at, user_id, id"]))

(defn list-gantt-rows-for-title [ds user-id title-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_rows
                      WHERE user_id = ? AND title_id = ? AND deleted_at IS NULL
                      ORDER BY start_at, id"
                     user-id title-id]))

(defn list-gantt-rows-all
  "進捗確定用。ソフト削除済みも含む。"
  [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_rows WHERE user_id = ? ORDER BY start_at, id" user-id]))

(defn soft-delete-gantt-row! [ds id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_rows SET deleted_at = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      (time/now-utc) (time/now-utc) id]))

(defn insert-gantt-work-time! [ds {:keys [gantt-id start-at end-at]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO gantt_work_times (gantt_id, start_at, end_at, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?) RETURNING *"
                      gantt-id start-at end-at (time/now-utc) (time/now-utc)]))

(defn update-gantt-work-time! [ds id {:keys [start-at end-at]}]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_work_times SET start_at = ?, end_at = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      start-at end-at (time/now-utc) id]))

(defn find-gantt-work-time [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM gantt_work_times WHERE id = ?" id]))

(defn list-gantt-work-times [ds gantt-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_work_times
                      WHERE gantt_id = ? AND deleted_at IS NULL
                      ORDER BY start_at, id"
                     gantt-id]))

(defn soft-delete-gantt-work-time! [ds id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_work_times SET deleted_at = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      (time/now-utc) (time/now-utc) id]))

(defn soft-delete-gantt-work-times-for-gantt! [ds gantt-id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_work_times SET deleted_at = ?, updated_at = ?
                       WHERE gantt_id = ? AND deleted_at IS NULL"
                      (time/now-utc) (time/now-utc) gantt-id]))

(defn soft-delete-gantt-work-times-for-title! [ds title-id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_work_times SET deleted_at = ?, updated_at = ?
                       WHERE deleted_at IS NULL
                         AND gantt_id IN (SELECT id FROM gantt_rows WHERE title_id = ?)"
                      (time/now-utc) (time/now-utc) title-id]))

(defn count-gantt-work-times [ds gantt-id]
  (:c (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM gantt_work_times
                              WHERE gantt_id = ? AND deleted_at IS NULL"
                             gantt-id])))

(defn insert-gantt-checklist-item! [ds {:keys [gantt-id label status]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO gantt_checklist_items (gantt_id, label, status, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?) RETURNING *"
                      gantt-id label status (time/now-utc) (time/now-utc)]))

(defn update-gantt-checklist-item! [ds id {:keys [label status]}]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_checklist_items SET label = ?, status = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      label status (time/now-utc) id]))

(defn find-gantt-checklist-item [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM gantt_checklist_items WHERE id = ?" id]))

(defn list-gantt-checklist-items [ds gantt-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_checklist_items
                      WHERE gantt_id = ? AND deleted_at IS NULL
                      ORDER BY id"
                     gantt-id]))

(defn soft-delete-gantt-checklist-item! [ds id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_checklist_items SET deleted_at = ?, updated_at = ?
                       WHERE id = ? AND deleted_at IS NULL RETURNING *"
                      (time/now-utc) (time/now-utc) id]))

(defn soft-delete-gantt-checklist-items-for-gantt! [ds gantt-id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_checklist_items SET deleted_at = ?, updated_at = ?
                       WHERE gantt_id = ? AND deleted_at IS NULL"
                      (time/now-utc) (time/now-utc) gantt-id]))

(defn soft-delete-gantt-checklist-items-for-title! [ds title-id]
  (jdbc/execute-one! ds
                     ["UPDATE gantt_checklist_items SET deleted_at = ?, updated_at = ?
                       WHERE deleted_at IS NULL
                         AND gantt_id IN (SELECT id FROM gantt_rows WHERE title_id = ?)"
                      (time/now-utc) (time/now-utc) title-id]))

(defn count-gantt-checklist [ds gantt-id]
  (let [row (jdbc/execute-one! ds
                               ["SELECT COUNT(*) AS total,
                                       SUM(CASE WHEN status = 'done' THEN 1 ELSE 0 END) AS done
                                 FROM gantt_checklist_items
                                 WHERE gantt_id = ? AND deleted_at IS NULL"
                                gantt-id])
        total (:total row)
        done (:done row)]
    {:total (if (nil? total) 0 total)
     :done (if (nil? done) 0 done)}))

(defn find-gantt-progress-day [ds gantt-id day]
  (jdbc/execute-one! ds ["SELECT * FROM gantt_progress_days WHERE gantt_id = ? AND day = ?"
                         gantt-id day]))

(defn insert-gantt-progress-day! [ds {:keys [gantt-id day percent applicable finalized-by]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO gantt_progress_days
                       (gantt_id, day, percent, applicable, finalized_at, finalized_by)
                       VALUES (?, ?, ?, ?, ?, ?) RETURNING *"
                      gantt-id day percent (if applicable 1 0) (time/now-utc) finalized-by]))

(defn list-gantt-progress-days [ds gantt-id]
  (jdbc/execute! ds ["SELECT * FROM gantt_progress_days WHERE gantt_id = ? ORDER BY day"
                     gantt-id]))

(defn list-gantt-targets [ds gantt-id]
  (mapv :field_id
        (jdbc/execute! ds ["SELECT field_id FROM gantt_targets WHERE gantt_id = ? ORDER BY field_id"
                           gantt-id])))

(defn replace-gantt-targets! [ds gantt-id field-ids]
  (jdbc/execute-one! ds ["DELETE FROM gantt_targets WHERE gantt_id = ?" gantt-id])
  (run! (fn [fid]
          (jdbc/execute-one! ds ["INSERT INTO gantt_targets (gantt_id, field_id) VALUES (?, ?)"
                                 gantt-id fid]))
        field-ids))

(defn delete-gantt-targets-for-field! [ds field-id]
  (jdbc/execute-one! ds ["DELETE FROM gantt_targets WHERE field_id = ?" field-id]))

(defn list-gantt-work-names [ds user-id]
  (mapv :work_name
        (jdbc/execute! ds
                       ["SELECT DISTINCT work_name AS work_name FROM gantt_rows
                         WHERE user_id = ? AND deleted_at IS NULL
                           AND work_name IS NOT NULL AND work_name <> ''
                         ORDER BY work_name"
                        user-id])))

(defn list-gantt-row-titles
  "作業・ガント行の表示名（title）。作業名入力の候補に含める。"
  [ds user-id]
  (mapv :title
        (jdbc/execute! ds
                       ["SELECT DISTINCT title AS title FROM gantt_rows
                         WHERE user_id = ? AND deleted_at IS NULL
                           AND title IS NOT NULL AND title <> ''
                         ORDER BY title"
                        user-id])))

(defn list-issued-order-work-names [ds user-id]
  (mapv :work_name
        (jdbc/execute! ds
                       ["SELECT DISTINCT work_name AS work_name FROM orders
                         WHERE issuer_id = ? AND work_name IS NOT NULL AND work_name <> ''
                         ORDER BY work_name"
                        user-id])))

(defn list-work-name-candidates [ds user-id]
  (->> (concat (list-work-names ds user-id)
               (list-gantt-work-names ds user-id)
               (list-gantt-row-titles ds user-id)
               (list-issued-order-work-names ds user-id))
       (remove str/blank?)
       distinct
       sort
       vec))

(defn insert-order! [ds {:keys [issuer-id work-date start-time end-time work-name body]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO orders (issuer_id, work_date, start_time, end_time, work_name, body,
                                           closed_at, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?) RETURNING *"
                      issuer-id work-date start-time end-time work-name body
                      (time/now-utc) (time/now-utc)]))

(defn update-order-times-body! [ds id {:keys [work-date start-time end-time body]}]
  (jdbc/execute-one! ds
                     ["UPDATE orders SET work_date = ?, start_time = ?, end_time = ?, body = ?, updated_at = ?
                       WHERE id = ? RETURNING *"
                      work-date start-time end-time body (time/now-utc) id]))

(defn close-order! [ds id]
  (jdbc/execute-one! ds
                     ["UPDATE orders SET closed_at = ?, updated_at = ? WHERE id = ? AND closed_at IS NULL RETURNING *"
                      (time/now-utc) (time/now-utc) id]))

(defn find-order [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM orders WHERE id = ?" id]))

(defn list-orders-issued [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM orders WHERE issuer_id = ? ORDER BY work_date DESC, id DESC" user-id]))

(defn list-orders-received [ds user-id]
  (jdbc/execute! ds
                 ["SELECT o.* FROM orders o
                   JOIN order_recipients r ON r.order_id = o.id
                   WHERE r.user_id = ?
                   ORDER BY o.work_date DESC, o.id DESC"
                  user-id]))

(defn set-order-recipients! [ds order-id user-ids]
  (jdbc/execute-one! ds ["DELETE FROM order_recipients WHERE order_id = ?" order-id])
  (run! (fn [uid]
          (jdbc/execute-one! ds ["INSERT INTO order_recipients (order_id, user_id) VALUES (?, ?)"
                                 order-id uid]))
        user-ids))

(defn set-order-targets! [ds order-id field-ids]
  (jdbc/execute-one! ds ["DELETE FROM order_targets WHERE order_id = ?" order-id])
  (run! (fn [fid]
          (jdbc/execute-one! ds ["INSERT INTO order_targets (order_id, field_id) VALUES (?, ?)"
                                 order-id fid]))
        field-ids))

(defn list-order-recipient-ids [ds order-id]
  (mapv :user_id
        (jdbc/execute! ds ["SELECT user_id FROM order_recipients WHERE order_id = ? ORDER BY user_id"
                           order-id])))

(defn list-order-target-ids [ds order-id]
  (mapv :field_id
        (jdbc/execute! ds ["SELECT field_id FROM order_targets WHERE order_id = ? ORDER BY field_id"
                           order-id])))

(defn order-recipient? [ds order-id user-id]
  (some? (jdbc/execute-one! ds ["SELECT 1 AS x FROM order_recipients WHERE order_id = ? AND user_id = ?"
                                order-id user-id])))

(defn insert-journal! [ds {:keys [order-id user-id body]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO journals (order_id, user_id, body, created_at)
                       VALUES (?, ?, ?, ?) RETURNING *"
                      order-id user-id body (time/now-utc)]))

(defn find-journal [ds order-id user-id]
  (jdbc/execute-one! ds ["SELECT * FROM journals WHERE order_id = ? AND user_id = ?"
                         order-id user-id]))

(defn list-journals-for-order [ds order-id]
  (jdbc/execute! ds ["SELECT * FROM journals WHERE order_id = ? ORDER BY id" order-id]))

(defn relation-pair [a b]
  (let [lo (min (long a) (long b))
        hi (max (long a) (long b))]
    [lo hi]))

(defn upsert-relation-cut! [ds user-a user-b]
  (let [[lo hi] (relation-pair user-a user-b)
        cut-at (time/now-utc)]
    (jdbc/execute-one! ds
                       ["INSERT INTO relation_cuts (user_lo, user_hi, cut_at)
                         VALUES (?, ?, ?)
                         ON CONFLICT(user_lo, user_hi) DO UPDATE SET cut_at = excluded.cut_at
                         RETURNING *"
                        lo hi cut-at])))

(defn find-relation-cut [ds user-a user-b]
  (let [[lo hi] (relation-pair user-a user-b)]
    (jdbc/execute-one! ds ["SELECT * FROM relation_cuts WHERE user_lo = ? AND user_hi = ?" lo hi])))

(defn field-in-open-order? [ds field-id]
  (some? (jdbc/execute-one! ds
                            ["SELECT 1 AS x FROM order_targets t
                              JOIN orders o ON o.id = t.order_id
                              WHERE t.field_id = ? AND o.closed_at IS NULL"
                             field-id])))

(defn any-field-in-open-order? [ds field-ids]
  (boolean (some #(field-in-open-order? ds %) field-ids)))

(defn open-order-between? [ds user-a user-b]
  "どちらの向きでも進行中の指示があるか。"
  (some? (jdbc/execute-one! ds
                            ["SELECT 1 AS x FROM orders o
                              JOIN order_recipients r ON r.order_id = o.id
                              WHERE o.closed_at IS NULL
                                AND ((o.issuer_id = ? AND r.user_id = ?)
                                  OR (o.issuer_id = ? AND r.user_id = ?))"
                             user-a user-b user-b user-a])))

(defn field-visible-to-viewer?
  "§3.4: 所有者でない受け手が、切断より後の指示で対象になった圃場か。"
  [ds viewer-id field-id]
  (let [field (jdbc/execute-one! ds ["SELECT * FROM fields WHERE id = ?" field-id])]
    (boolean
     (when (and field (not= (long (:user_id field)) (long viewer-id)))
       (let [owner-id (:user_id field)
             cut (find-relation-cut ds owner-id viewer-id)
             cut-at (:cut_at cut)]
         (some? (jdbc/execute-one! ds
                                   (if cut-at
                                     ["SELECT 1 AS x FROM order_targets t
                                       JOIN orders o ON o.id = t.order_id
                                       JOIN order_recipients r ON r.order_id = o.id
                                       WHERE t.field_id = ? AND o.issuer_id = ? AND r.user_id = ?
                                         AND o.created_at > ?"
                                      field-id owner-id viewer-id cut-at]
                                     ["SELECT 1 AS x FROM order_targets t
                                       JOIN orders o ON o.id = t.order_id
                                       JOIN order_recipients r ON r.order_id = o.id
                                       WHERE t.field_id = ? AND o.issuer_id = ? AND r.user_id = ?"
                                      field-id owner-id viewer-id]))))))))

(defn list-visible-other-fields [ds viewer-id]
  (jdbc/execute! ds
                 ["SELECT DISTINCT f.*, u.email AS owner_email
                   FROM fields f
                   JOIN users u ON u.id = f.user_id
                   JOIN order_targets t ON t.field_id = f.id
                   JOIN orders o ON o.id = t.order_id AND o.issuer_id = f.user_id
                   JOIN order_recipients r ON r.order_id = o.id AND r.user_id = ?
                   LEFT JOIN relation_cuts c
                     ON c.user_lo = CASE WHEN f.user_id < ? THEN f.user_id ELSE ? END
                    AND c.user_hi = CASE WHEN f.user_id < ? THEN ? ELSE f.user_id END
                   WHERE f.user_id <> ?
                     AND (c.cut_at IS NULL OR o.created_at > c.cut_at)
                   ORDER BY f.id"
                  viewer-id viewer-id viewer-id viewer-id viewer-id viewer-id]))

(defn list-related-other-work-names [ds viewer-id]
  (mapv :work_name
        (jdbc/execute! ds
                       ["SELECT DISTINCT o.work_name AS work_name
                         FROM fields f
                         JOIN order_targets t ON t.field_id = f.id
                         JOIN orders o ON o.id = t.order_id AND o.issuer_id = f.user_id
                         JOIN order_recipients r ON r.order_id = o.id AND r.user_id = ?
                         LEFT JOIN relation_cuts c
                           ON c.user_lo = CASE WHEN f.user_id < ? THEN f.user_id ELSE ? END
                          AND c.user_hi = CASE WHEN f.user_id < ? THEN ? ELSE f.user_id END
                         WHERE f.user_id <> ?
                           AND (c.cut_at IS NULL OR o.created_at > c.cut_at)
                           AND o.work_name IS NOT NULL AND o.work_name <> ''
                         ORDER BY o.work_name"
                        viewer-id viewer-id viewer-id viewer-id viewer-id viewer-id])))

(defn find-field-any [ds field-id]
  (jdbc/execute-one! ds ["SELECT * FROM fields WHERE id = ?" field-id]))

;;; memos (v2 phase 3)

(defn insert-memo! [ds {:keys [parent-id author-kind author-id status body published-at content-saved-at]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO memos (parent_id, author_kind, author_id, status, body,
                       published_at, content_saved_at, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *"
                      parent-id author-kind author-id status (or body "")
                      published-at content-saved-at (time/now-utc) (time/now-utc)]))

(defn find-memo [ds id]
  (jdbc/execute-one! ds ["SELECT * FROM memos WHERE id = ?" id]))

(defn set-memo-gantt!
  "紐づけの付け外し。content_saved_at は触らない。"
  [ds id gantt-id]
  (jdbc/execute-one! ds
                     ["UPDATE memos SET gantt_id = ?, updated_at = ? WHERE id = ? RETURNING *"
                      gantt-id (time/now-utc) id]))

(defn update-memo-body! [ds id body content-saved-at]
  (jdbc/execute-one! ds
                     ["UPDATE memos SET body = ?, content_saved_at = ?, updated_at = ? WHERE id = ?"
                      body content-saved-at (time/now-utc) id]))

(defn publish-memo! [ds id published-at content-saved-at]
  (jdbc/execute-one! ds
                     ["UPDATE memos SET status = 'published', published_at = ?, content_saved_at = ?, updated_at = ?
                       WHERE id = ?"
                      published-at content-saved-at (time/now-utc) id]))

(defn list-memo-descendant-ids
  "深さ上限なしで子孫の id を集める。削除済みの節も辿る（下に未削除が残り得るため）。"
  [ds root-id]
  (loop [frontier [root-id] seen #{}]
    (if (empty? frontier)
      (vec (disj seen root-id))
      (let [id (first frontier)
            kids (mapv :id (jdbc/execute! ds ["SELECT id FROM memos WHERE parent_id = ?" id]))
            new-ids (remove seen kids)]
        (recur (into (vec (rest frontier)) new-ids)
               (into seen (conj new-ids id)))))))

(defn soft-delete-memo-tree!
  "対象とすべての子孫に deleted_at を立てる（まだ無いものだけ）。"
  [ds root-id]
  (let [desc (list-memo-descendant-ids ds root-id)
        now (time/now-utc)
        ids (into [root-id] desc)]
    (run! (fn [id]
            (jdbc/execute-one! ds ["UPDATE memos SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL"
                                   now now id]))
          ids)
    {:root root-id :descendants (count desc)}))

(defn list-timeline-memos
  "公開済み・未削除の親メモを新しい順に返す。before-id はその行より古い側を続きとして返す。"
  [ds limit before-id]
  (if before-id
    (jdbc/execute! ds
                   ["SELECT * FROM memos
                     WHERE deleted_at IS NULL AND status = 'published' AND parent_id IS NULL
                       AND (published_at, id) < ((SELECT published_at FROM memos WHERE id = ?), ?)
                     ORDER BY published_at DESC, id DESC LIMIT ?"
                    before-id before-id limit])
    (jdbc/execute! ds
                   ["SELECT * FROM memos
                     WHERE deleted_at IS NULL AND status = 'published' AND parent_id IS NULL
                     ORDER BY published_at DESC, id DESC LIMIT ?"
                    limit])))

(defn list-memo-replies
  "直下の未削除返信。公開分と、見ている本人の下書きを返す（他人の下書きは出さない）。"
  [ds parent-id viewer-kind viewer-id]
  (jdbc/execute! ds
                 ["SELECT * FROM memos
                   WHERE deleted_at IS NULL AND parent_id = ?
                     AND (status = 'published' OR (author_kind = ? AND author_id = ?))
                   ORDER BY COALESCE(published_at, created_at) ASC, id ASC"
                  parent-id viewer-kind viewer-id]))

(defn count-memo-replies
  "直下の未削除・公開返信の数。"
  [ds parent-id]
  (:c (jdbc/execute-one! ds
                         ["SELECT COUNT(*) AS c FROM memos
                           WHERE deleted_at IS NULL AND status = 'published' AND parent_id = ?"
                          parent-id])))

(defn list-draft-memos [ds author-kind author-id]
  (jdbc/execute! ds
                 ["SELECT * FROM memos
                   WHERE deleted_at IS NULL AND status = 'draft'
                     AND author_kind = ? AND author_id = ?
                   ORDER BY updated_at DESC, id DESC"
                  author-kind author-id]))

(defn replace-memo-tags! [ds memo-id labels]
  (jdbc/execute! ds ["DELETE FROM memo_tags WHERE memo_id = ?" memo-id])
  (run! (fn [lab]
          (jdbc/execute-one! ds ["INSERT INTO memo_tags (memo_id, label) VALUES (?, ?)" memo-id lab]))
        labels))

(defn list-memo-tags
  "書いた順（rowid 順）で返す。"
  [ds memo-id]
  (mapv :label (jdbc/execute! ds ["SELECT label FROM memo_tags WHERE memo_id = ? ORDER BY rowid" memo-id])))

(defn replace-memo-links! [ds memo-id urls]
  (jdbc/execute! ds ["DELETE FROM memo_links WHERE memo_id = ?" memo-id])
  (run! (fn [[i url]]
          (jdbc/execute-one! ds ["INSERT INTO memo_links (memo_id, url, position) VALUES (?, ?, ?)"
                                 memo-id url i]))
        (map-indexed vector urls)))

(defn list-memo-links [ds memo-id]
  (mapv :url (jdbc/execute! ds
                            ["SELECT url FROM memo_links WHERE memo_id = ? ORDER BY position, id"
                             memo-id])))

(defn insert-memo-attachment! [ds {:keys [memo-id filename content-type body-ref]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO memo_attachments (memo_id, filename, content_type, body_ref, created_at)
                       VALUES (?, ?, ?, ?, ?) RETURNING *"
                      memo-id filename content-type body-ref (time/now-utc)]))

(defn update-memo-attachment-body-ref! [ds aid body-ref]
  (jdbc/execute-one! ds ["UPDATE memo_attachments SET body_ref = ? WHERE id = ?" body-ref aid]))

(defn find-memo-attachment [ds memo-id aid]
  (jdbc/execute-one! ds
                     ["SELECT * FROM memo_attachments
                       WHERE id = ? AND memo_id = ? AND deleted_at IS NULL"
                      aid memo-id]))

(defn list-memo-attachments [ds memo-id]
  (jdbc/execute! ds
                 ["SELECT * FROM memo_attachments
                   WHERE memo_id = ? AND deleted_at IS NULL ORDER BY id"
                  memo-id]))

(defn count-memo-attachments [ds memo-id]
  (:c (jdbc/execute-one! ds
                         ["SELECT COUNT(*) AS c FROM memo_attachments
                           WHERE memo_id = ? AND deleted_at IS NULL"
                          memo-id])))

(defn soft-delete-memo-attachment! [ds aid]
  (jdbc/execute-one! ds
                     ["UPDATE memo_attachments SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL"
                      (time/now-utc) aid]))

(defn upsert-memo-bookmark! [ds memo-id kind id]
  (jdbc/execute-one! ds
                     ["INSERT INTO memo_bookmarks (memo_id, bookmarker_kind, bookmarker_id, created_at)
                       VALUES (?, ?, ?, ?)
                       ON CONFLICT (memo_id, bookmarker_kind, bookmarker_id) DO NOTHING"
                      memo-id kind id (time/now-utc)]))

(defn find-memo-bookmark [ds memo-id kind id]
  (jdbc/execute-one! ds
                     ["SELECT * FROM memo_bookmarks
                       WHERE memo_id = ? AND bookmarker_kind = ? AND bookmarker_id = ?"
                      memo-id kind id]))

(defn delete-memo-bookmark! [ds memo-id kind id]
  (jdbc/execute-one! ds
                     ["DELETE FROM memo_bookmarks
                       WHERE memo_id = ? AND bookmarker_kind = ? AND bookmarker_id = ?"
                      memo-id kind id]))

(defn list-bookmarked-memos [ds]
  (jdbc/execute! ds
                 ["SELECT m.*, MAX(b.created_at) AS bookmark_latest
                   FROM memos m
                   JOIN memo_bookmarks b ON b.memo_id = m.id
                   WHERE m.deleted_at IS NULL AND m.status = 'published'
                   GROUP BY m.id
                   ORDER BY bookmark_latest DESC, m.id DESC"]))

(defn author-has-bookmarked? [ds memo-id kind id]
  (boolean (find-memo-bookmark ds memo-id kind id)))

(defn count-memo-bookmarks [ds memo-id]
  (:c (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM memo_bookmarks WHERE memo_id = ?" memo-id])))

(def ^:private memo-match-sql
  "本文・タグ・添付ファイル名のいずれかに部分一致するか（大小無視）。? は3つ要る。"
  "(LOWER(m.body) LIKE ?
     OR EXISTS (SELECT 1 FROM memo_tags t WHERE t.memo_id = m.id AND LOWER(t.label) LIKE ?)
     OR EXISTS (SELECT 1 FROM memo_attachments a
                WHERE a.memo_id = m.id AND a.deleted_at IS NULL AND LOWER(a.filename) LIKE ?))")

(defn- conj-where [acc sql args]
  (-> acc
      (update :where conj sql)
      (update :args into args)))

(defn search-memos
  "検索・高度な検索。from-utc/to-utc は published_at と比べる UTC 文字列（to-utc は含まない）。
  authors は [kind id] の組の並び。空の並びを渡すと結果は空になる。"
  [ds {:keys [q-like exclude-like from-utc to-utc authors scope drafts?
              viewer-kind viewer-id limit]}]
  (let [acc (cond-> {:where ["m.deleted_at IS NULL"] :args []}
              drafts?
              (conj-where "(m.status = 'published' OR (m.author_kind = ? AND m.author_id = ?))"
                          [viewer-kind viewer-id])

              (not drafts?)
              (conj-where "m.status = 'published'" [])

              (= "parents" scope)
              (conj-where "m.parent_id IS NULL" [])

              (some? q-like)
              (conj-where memo-match-sql [q-like q-like q-like])

              (some? exclude-like)
              (conj-where (str "NOT " memo-match-sql) [exclude-like exclude-like exclude-like])

              (some? from-utc)
              (conj-where "m.published_at IS NOT NULL AND m.published_at >= ?" [from-utc])

              (some? to-utc)
              (conj-where "m.published_at IS NOT NULL AND m.published_at < ?" [to-utc])

              (some? authors)
              (conj-where (str "("
                               (str/join " OR "
                                         (cons "0 = 1"
                                               (repeat (count authors)
                                                       "(m.author_kind = ? AND m.author_id = ?)")))
                               ")")
                          (vec (mapcat identity authors))))
        sql (str "SELECT * FROM memos m WHERE "
                 (str/join " AND " (:where acc))
                 " ORDER BY COALESCE(m.published_at, m.created_at) DESC, m.id DESC LIMIT ?")]
    (jdbc/execute! ds (into [sql] (conj (:args acc) limit)))))
