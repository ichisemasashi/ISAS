import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createPostgresAuthContextAdapter } from "../src/postgres-auth-context.mjs";

const U1 = "aaaaaaaa-0000-7000-8000-000000000001";
const T1 = "11111111-1111-7111-8111-111111111111";
const T2 = "22222222-2222-7222-8222-222222222222";
const F1 = "f1111111-1111-7111-8111-111111111111";

function trusted(overrides = {}) {
  return {
    actorPseudonym: "actor-u1",
    authContext: {
      userId: U1,
      tenantId: T1,
      allowedTenants: [T1],
      scopeFieldGroups: [F1],
      capabilities: ["journal:write"],
      employerSubjectUsers: [],
      ...overrides,
    },
  };
}

function fakePool({ role = { role_name: "app_user", rolsuper: false, rolbypassrls: false }, validation = undefined } = {}) {
  const calls = [];
  let released = false;
  const client = {
    async query(sql, values) {
      calls.push({ sql, values });
      if (sql.includes("FROM pg_roles")) return { rows: role ? [role] : [] };
      if (sql.includes("validate_auth_context")) {
        if (validation === null) return { rows: [] };
        return { rows: [validation || {
          user_id: U1,
          tenant_id: T1,
          allowed_tenants: [T1],
          scope_field_groups: [F1],
          capabilities: ["journal:write"],
          employer_subject_users: [],
        }] };
      }
      return { rows: [{ ok: true }] };
    },
    release() { released = true; },
  };
  return { pool: { async connect() { return client; } }, client, calls, released: () => released };
}

describe("PostgreSQL AuthContext transaction adapter", () => {
  test("injects only the DB-validated canonical context inside one transaction", async () => {
    const db = fakePool({ validation: {
      user_id: U1,
      tenant_id: T1,
      allowed_tenants: [T1, T2],
      scope_field_groups: [],
      capabilities: ["journal:write"],
      employer_subject_users: [],
    } });
    const adapter = createPostgresAuthContextAdapter(db.pool);

    const result = await adapter.transaction(trusted(), async (client, canonical) => {
      assert.deepEqual(canonical.allowedTenants, [T1, T2]);
      return client.query("SELECT count(*) FROM field_record");
    }, { readOnly: true });

    assert.deepEqual(result.rows, [{ ok: true }]);
    assert.equal(db.calls[0].sql, "BEGIN READ ONLY");
    assert.match(db.calls[1].sql, /FROM pg_roles/);
    assert.match(db.calls[2].sql, /validate_auth_context/);
    assert.match(db.calls[3].sql, /set_config\('app\.allowed_tenants'/);
    assert.deepEqual(db.calls[3].values, [U1, T1, [T1, T2], [], ["journal:write"], [], "actor-u1"]);
    assert.equal(db.calls[4].sql, "SELECT count(*) FROM field_record");
    assert.equal(db.calls[5].sql, "COMMIT");
    assert.equal(db.released(), true);
  });

  test("rolls back and never calls the operation when PostgreSQL rejects the context", async () => {
    const db = fakePool({ validation: null });
    const adapter = createPostgresAuthContextAdapter(db.pool);
    let operated = false;

    await assert.rejects(() => adapter.transaction(trusted(), async () => { operated = true; }), /rejected by PostgreSQL/);

    assert.equal(operated, false);
    assert.equal(db.calls.at(-1).sql, "ROLLBACK");
    assert.equal(db.released(), true);
  });

  test("rejects a validation result that substitutes another subject or write tenant", async () => {
    for (const replacement of [
      { user_id: "bbbbbbbb-0000-7000-8000-000000000002", tenant_id: T1 },
      { user_id: U1, tenant_id: T2 },
    ]) {
      const db = fakePool({ validation: {
        ...replacement,
        allowed_tenants: [replacement.tenant_id],
        scope_field_groups: [],
        capabilities: [],
        employer_subject_users: [],
      } });
      const adapter = createPostgresAuthContextAdapter(db.pool);
      await assert.rejects(() => adapter.transaction(trusted(), async () => undefined), /changed the AuthContext subject or write tenant/);
      assert.equal(db.calls.some(({ sql }) => sql.includes("set_config")), false);
      assert.equal(db.calls.at(-1).sql, "ROLLBACK");
    }
  });

  test("refuses owner, superuser and BYPASSRLS connections before context injection", async () => {
    for (const role of [
      { role_name: "app_owner", rolsuper: false, rolbypassrls: false },
      { role_name: "app_user", rolsuper: true, rolbypassrls: false },
      { role_name: "app_user", rolsuper: false, rolbypassrls: true },
    ]) {
      const db = fakePool({ role });
      const adapter = createPostgresAuthContextAdapter(db.pool);
      await assert.rejects(() => adapter.transaction(trusted(), async () => undefined), /Unsafe PostgreSQL application role/);
      assert.equal(db.calls.some(({ sql }) => sql.includes("set_config")), false);
      assert.equal(db.calls.at(-1).sql, "ROLLBACK");
    }
  });

  test("rejects malformed, duplicate and over-broad client context before checkout", async () => {
    const cases = [
      trusted({ userId: "not-a-uuid" }),
      trusted({ allowedTenants: [T1, T1] }),
      trusted({ tenantId: T2, allowedTenants: [T1] }),
      trusted({ capabilities: ["admin *"] }),
      trusted({ allowedTenants: Array.from({ length: 101 }, (_, index) => `11111111-1111-7111-8111-${String(index).padStart(12, "0")}`) }),
    ];
    for (const context of cases) {
      const db = fakePool();
      const adapter = createPostgresAuthContextAdapter(db.pool);
      await assert.rejects(() => adapter.transaction(context, async () => undefined), /Invalid AuthContext/);
      assert.equal(db.calls.length, 0);
    }
  });

  test("rolls back application errors and releases the checked-out connection", async () => {
    const db = fakePool();
    const adapter = createPostgresAuthContextAdapter(db.pool);

    await assert.rejects(() => adapter.transaction(trusted(), async () => { throw new Error("command failed"); }), /command failed/);

    assert.equal(db.calls.at(-1).sql, "ROLLBACK");
    assert.equal(db.released(), true);
  });

  test("does not expose transaction or session control to application operations", async () => {
    for (const sql of ["COMMIT", "RESET ALL", "SET LOCAL app.user_id = 'x'", "SELECT set_config('app.caps', '{}', true)", "SELECT 1; COMMIT"]) {
      const db = fakePool();
      const adapter = createPostgresAuthContextAdapter(db.pool);
      await assert.rejects(() => adapter.transaction(trusted(), (client) => client.query(sql)), /reserved for the AuthContext adapter/);
      assert.equal(db.calls.at(-1).sql, "ROLLBACK");
    }
  });
});
