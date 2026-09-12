(ns isas.db
  (:require [next.jdbc :as jdbc]
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
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"])

(defn datasource [jdbc-url]
  (jdbc/with-options (jdbc/get-datasource {:jdbcUrl jdbc-url})
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn migrate! [ds]
  (run! (fn [sql] (jdbc/execute! ds [sql])) schema)
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
                         updated_at = excluded.updated_at
                       RETURNING *"
                      user-id west south east north (time/now-utc)]))

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

(defn insert-field! [ds {:keys [user-id name geojson]}]
  (jdbc/execute-one! ds
                     ["INSERT INTO fields (user_id, name, geojson, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?) RETURNING *"
                      user-id name geojson (time/now-utc) (time/now-utc)]))

(defn find-field [ds user-id id]
  (jdbc/execute-one! ds ["SELECT * FROM fields WHERE id = ? AND user_id = ?" id user-id]))

(defn list-fields [ds user-id]
  (jdbc/execute! ds ["SELECT * FROM fields WHERE user_id = ? ORDER BY id" user-id]))

(defn update-field! [ds id {:keys [name geojson]}]
  (jdbc/execute-one! ds
                     ["UPDATE fields SET name = ?, geojson = ?, updated_at = ? WHERE id = ? RETURNING *"
                      name geojson (time/now-utc) id]))

(defn delete-field! [ds id]
  (jdbc/execute-one! ds ["DELETE FROM fields WHERE id = ?" id]))

(defn field-names [ds user-id]
  (mapv :name (jdbc/execute! ds ["SELECT name FROM fields WHERE user_id = ?" user-id])))
