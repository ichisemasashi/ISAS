function one(result) { return result.rows?.length === 1 ? result.rows[0] : null; }
function timestamp(value) { return new Date(value).toISOString(); }
function rejected() { return Object.assign(new Error("authorization version was revoked"), { code: "authorization_revoked" }); }

export function createPostgresLocalStores({ pool, crypto }) {
  if (!pool?.query || !crypto?.seal || !crypto?.open) throw new Error("local store configuration is incomplete");
  const encode = (value, purpose, id) => crypto.seal(value, purpose, id);
  const decode = (value, purpose, id) => value == null ? null : crypto.open(value, purpose, id);

  const loginAttempts = Object.freeze({
    async put(hash, value) {
      await pool.query("SELECT local_support.put_login_attempt($1::text,$2::bytea,$3::timestamptz)", [hash, encode(value, "login-attempt", hash), timestamp(value.expiresAt)]);
    },
    async take(hash) {
      const row = one(await pool.query("SELECT local_support.take_login_attempt($1::text) AS ciphertext", [hash]));
      return decode(row?.ciphertext, "login-attempt", hash);
    }
  });

  const sessions = Object.freeze({
    async put(hash, value) {
      const row = one(await pool.query("SELECT local_support.put_session($1::text,$2::uuid,$3::bigint,$4::bytea,$5::timestamptz,$6::timestamptz) AS accepted", [hash, value.user.id, value.user.authorizationVersion, encode(value, "session", hash), timestamp(value.lastSeenAt), timestamp(value.expiresAt)]));
      if (row?.accepted !== true) throw rejected();
    },
    async get(hash) {
      const row = one(await pool.query("SELECT local_support.get_session($1::text) AS ciphertext", [hash]));
      return decode(row?.ciphertext, "session", hash);
    },
    async touch(hash, lastSeenAt) { await pool.query("SELECT local_support.touch_session($1::text,$2::timestamptz)", [hash, timestamp(lastSeenAt)]); },
    async delete(hash) { await pool.query("SELECT local_support.delete_session($1::text)", [hash]); }
  });

  const contexts = Object.freeze({
    async put(hash, value) {
      const row = one(await pool.query("SELECT local_support.put_context($1::text,$2::text,$3::uuid,$4::bigint,$5::bytea,$6::timestamptz) AS accepted", [hash, value.sessionHash, value.userId, value.authorizationVersion, encode(value, "context", hash), timestamp(value.expiresAt)]));
      if (row?.accepted !== true) throw rejected();
    },
    async get(hash) {
      const row = one(await pool.query("SELECT local_support.get_context($1::text) AS ciphertext", [hash]));
      return decode(row?.ciphertext, "context", hash);
    },
    async delete(hash) { await pool.query("SELECT local_support.delete_context($1::text)", [hash]); },
    async deleteForSession(hash) { await pool.query("SELECT local_support.delete_contexts_for_session($1::text)", [hash]); }
  });

  return Object.freeze({
    loginAttempts,
    sessions,
    contexts,
    async invalidate(event) {
      const row = one(await pool.query("SELECT local_support.invalidate_user($1::uuid,$2::bigint) AS result", [event.userId, event.authorizationVersion]));
      return { applied: true, ...(row?.result || {}) };
    },
    async startupCheck() {
      const row = one(await pool.query("SELECT pg_get_userbyid(n.nspowner) AS owner, has_schema_privilege(current_user,'local_support','USAGE') AS allowed FROM pg_namespace n WHERE n.nspname='local_support'"));
      if (row?.owner !== "local_support_owner" || row.allowed !== true) throw new Error("local support schema ownership/grant check failed");
    }
  });
}
