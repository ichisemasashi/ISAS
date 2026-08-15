import { createHash, createHmac, randomUUID } from "node:crypto";

function one(result) {
  return result.rows?.length === 1 ? result.rows[0] : null;
}

function snapshotId(row) {
  return createHash("sha256")
    .update(`${row.user_id}:${row.tenant_id}:${row.membership_version}:${row.authorization_version}`)
    .digest("base64url");
}

export function createPostgresIdentityAdapters({ pool, jurisdiction, shardId, pseudonymKey }) {
  if (!pool?.query || !jurisdiction || !shardId || !pseudonymKey || Buffer.byteLength(pseudonymKey) < 32) {
    throw new Error("PostgreSQL identity adapter configuration is incomplete");
  }

  const actorPseudonym = (userId) => createHmac("sha256", pseudonymKey).update(userId).digest("base64url");

  const users = Object.freeze({
    async resolve(issuer, subject) {
      const row = one(await pool.query(
        "SELECT * FROM app_private.resolve_oidc_user($1::text, $2::text)",
        [issuer, subject],
      ));
      if (!row) return null;
      const displayName = row.display_name;
      return {
        id: row.user_id,
        displayName,
        initials: [...displayName.trim()].slice(0, 2).join(""),
        authorizationVersion: String(row.authorization_version),
      };
    },
  });

  const authorization = Object.freeze({
    async listTenants(userId) {
      const result = await pool.query(
        "SELECT * FROM app_private.list_authorized_tenants($1::uuid)",
        [userId],
      );
      return result.rows.map((row) => ({
        id: row.tenant_id,
        name: row.tenant_id,
        roleLabel: row.role_label,
        membershipVersion: String(row.membership_version),
      }));
    },

    async deriveContext(userId, tenantId) {
      const row = one(await pool.query(
        "SELECT * FROM app_private.derive_authorization_context($1::uuid, $2::uuid)",
        [userId, tenantId],
      ));
      if (!row) return null;
      return {
        jurisdictionId: jurisdiction,
        shardId,
        tenantName: row.tenant_id,
        roleLabel: row.role_label,
        membershipVersion: String(row.membership_version),
        authorizationVersion: String(row.authorization_version),
        authorizationSnapshotId: snapshotId(row),
        actorPseudonym: actorPseudonym(row.user_id),
        scopeFieldGroups: row.scope_field_groups || [],
        capabilities: row.capabilities || [],
      };
    },
  });

  const revocationOutbox = Object.freeze({
    async claim(leaseSeconds = 30) {
      const claimId = randomUUID();
      const row = one(await pool.query(
        "SELECT * FROM app_private.claim_auth_revocation($1::uuid, $2::integer)",
        [claimId, leaseSeconds],
      ));
      if (!row) return null;
      return {
        claimId,
        eventId: String(row.event_id),
        userId: row.user_id,
        tenantId: row.tenant_id,
        authorizationVersion: String(row.authorization_version),
        reason: row.reason,
        occurredAt: new Date(row.occurred_at).toISOString(),
      };
    },
    async complete(eventId, claimId) {
      const row = one(await pool.query(
        "SELECT app_private.complete_auth_revocation($1::bigint, $2::uuid) AS completed",
        [eventId, claimId],
      ));
      return row?.completed === true;
    },
    async release(eventId, claimId) {
      await pool.query("SELECT app_private.release_auth_revocation($1::bigint, $2::uuid)", [eventId, claimId]);
    },
  });

  return Object.freeze({ users, authorization, revocationOutbox });
}
