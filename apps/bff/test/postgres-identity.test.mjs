import assert from "node:assert/strict";
import { test } from "node:test";
import { createPostgresIdentityAdapters } from "../src/postgres-identity.mjs";

test("resolves OIDC users and derives tenant authorization only through auth functions", async () => {
  const calls = [];
  const pool = { async query(sql, values) {
    calls.push({ sql, values });
    if (sql.includes("resolve_oidc_user")) return { rows: [{ user_id: "user-1", display_name: "佐藤 一郎", authorization_version: "9" }] };
    if (sql.includes("list_authorized_tenants")) return { rows: [{ tenant_id: "tenant-1", role_label: "管理者", membership_version: "3" }] };
    if (sql.includes("derive_authorization_context")) return { rows: [{
      user_id: "user-1", tenant_id: "tenant-1", role_label: "管理者", membership_version: "3",
      authorization_version: "9", scope_field_groups: ["field-group-1"], capabilities: ["journal:write"],
    }] };
    return { rows: [] };
  } };
  const adapters = createPostgresIdentityAdapters({
    pool, jurisdiction: "jp", shardId: "isas-jp-prod-01", pseudonymKey: "a".repeat(32),
  });
  const user = await adapters.users.resolve("https://issuer", "subject");
  assert.equal(user.authorizationVersion, "9");
  assert.equal(user.initials, "佐藤");
  assert.deepEqual(await adapters.authorization.listTenants("user-1"), [{
    id: "tenant-1", name: "tenant-1", roleLabel: "管理者", membershipVersion: "3",
  }]);
  const context = await adapters.authorization.deriveContext("user-1", "tenant-1");
  assert.equal(context.authorizationVersion, "9");
  assert.equal(context.jurisdictionId, "jp");
  assert.match(context.actorPseudonym, /^[A-Za-z0-9_-]+$/);
  assert.equal(calls.every(({ sql }) => sql.includes("app_private.")), true);
});

test("claims and completes the durable PostgreSQL revocation outbox", async () => {
  const calls = [];
  const pool = { async query(sql, values) {
    calls.push({ sql, values });
    if (sql.includes("claim_auth_revocation")) return { rows: [{
      event_id: "12", user_id: "user-1", tenant_id: "tenant-1", authorization_version: "8",
      reason: "membership.revoked", occurred_at: "2026-08-15T00:00:00Z",
    }] };
    if (sql.includes("complete_auth_revocation")) return { rows: [{ completed: true }] };
    return { rows: [{ release_auth_revocation: true }] };
  } };
  const { revocationOutbox } = createPostgresIdentityAdapters({
    pool, jurisdiction: "jp", shardId: "shard", pseudonymKey: "b".repeat(32),
  });
  const event = await revocationOutbox.claim();
  assert.equal(event.eventId, "12");
  assert.equal(event.authorizationVersion, "8");
  assert.equal(await revocationOutbox.complete(event.eventId, event.claimId), true);
  assert.equal(calls[0].values[1], 30);
});
