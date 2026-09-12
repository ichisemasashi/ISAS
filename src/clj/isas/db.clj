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
