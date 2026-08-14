const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CAPABILITY = /^[a-z][a-z0-9_.:-]{0,127}$/;
const LIMITS = Object.freeze({ allowedTenants: 100, scopeFieldGroups: 1000, capabilities: 128, employerSubjectUsers: 1000 });

const CHECK_ROLE_SQL = `
SELECT current_user AS role_name, rolsuper, rolbypassrls
FROM pg_roles
WHERE rolname = current_user`;

const VALIDATE_SQL = `
SELECT
  user_id::text,
  tenant_id::text,
  allowed_tenants::text[] AS allowed_tenants,
  scope_field_groups::text[] AS scope_field_groups,
  caps::text[] AS capabilities,
  employer_subject_users::text[] AS employer_subject_users
FROM app_private.validate_auth_context(
  $1::uuid, $2::uuid, $3::uuid[], $4::uuid[], $5::text[], $6::uuid[]
)`;

const INJECT_SQL = `
SELECT
  set_config('app.user_id', $1::uuid::text, true),
  set_config('app.tenant_id', $2::uuid::text, true),
  set_config('app.allowed_tenants', $3::uuid[]::text, true),
  set_config('app.scope_field_groups', $4::uuid[]::text, true),
  set_config('app.caps', $5::text[]::text, true),
  set_config('app.employer_subject_users', $6::uuid[]::text, true),
  set_config('app.actor_pseudonym', $7::text, true)`;

function requireUuid(value, field) {
  if (typeof value !== "string" || !UUID.test(value)) throw new Error(`Invalid AuthContext: ${field}`);
  return value.toLowerCase();
}

function requireArray(values, field, limit, validator) {
  if (!Array.isArray(values) || values.length > limit) throw new Error(`Invalid AuthContext: ${field}`);
  const normalized = values.map((value, index) => validator(value, `${field}[${index}]`));
  if (new Set(normalized).size !== normalized.length) throw new Error(`Invalid AuthContext: duplicate ${field}`);
  return normalized;
}

function requireCapability(value, field) {
  if (typeof value !== "string" || !CAPABILITY.test(value)) throw new Error(`Invalid AuthContext: ${field}`);
  return value;
}

function normalizeInput(trusted) {
  if (!trusted || typeof trusted !== "object" || !trusted.authContext) throw new Error("Invalid AuthContext: missing trusted context");
  const context = trusted.authContext;
  const userId = requireUuid(context.userId, "userId");
  const tenantId = requireUuid(context.tenantId, "tenantId");
  const allowedTenants = requireArray(context.allowedTenants, "allowedTenants", LIMITS.allowedTenants, requireUuid);
  const scopeFieldGroups = requireArray(context.scopeFieldGroups, "scopeFieldGroups", LIMITS.scopeFieldGroups, requireUuid);
  const capabilities = requireArray(context.capabilities, "capabilities", LIMITS.capabilities, requireCapability);
  const employerSubjectUsers = requireArray(context.employerSubjectUsers, "employerSubjectUsers", LIMITS.employerSubjectUsers, requireUuid);
  if (!allowedTenants.includes(tenantId)) throw new Error("Invalid AuthContext: tenantId is outside allowedTenants");
  if (typeof trusted.actorPseudonym !== "string" || !trusted.actorPseudonym || trusted.actorPseudonym.length > 200) throw new Error("Invalid AuthContext: actorPseudonym");
  return { userId, tenantId, allowedTenants, scopeFieldGroups, capabilities, employerSubjectUsers, actorPseudonym: trusted.actorPseudonym };
}

function normalizeValidated(row) {
  if (!row) throw new Error("AuthContext was rejected by PostgreSQL");
  return normalizeInput({
    actorPseudonym: "validated-separately",
    authContext: {
      userId: row.user_id,
      tenantId: row.tenant_id,
      allowedTenants: row.allowed_tenants,
      scopeFieldGroups: row.scope_field_groups,
      capabilities: row.capabilities,
      employerSubjectUsers: row.employer_subject_users,
    },
  });
}

function operationClient(client) {
  return Object.freeze({
    query(text, values) {
      if (typeof text !== "string") throw new Error("Operation queries must use SQL text and parameters");
      const sql = text.trim().replace(/;$/, "");
      if (sql.includes(";") || /^(?:begin|start\s+transaction|commit|rollback|savepoint|release\s+savepoint|set|reset|discard)\b/i.test(sql) || /\bset_config\s*\(/i.test(sql)) {
        throw new Error("Transaction or session control is reserved for the AuthContext adapter");
      }
      return client.query(text, values);
    },
  });
}

export function createPostgresAuthContextAdapter(pool, { expectedRole = "app_user" } = {}) {
  if (!pool?.connect) throw new Error("A PostgreSQL-compatible pool is required");

  return {
    async transaction(trusted, operation, { readOnly = false } = {}) {
      if (typeof operation !== "function") throw new Error("A transaction operation is required");
      const requested = normalizeInput(trusted);
      const client = await pool.connect();
      let began = false;
      try {
        await client.query(readOnly ? "BEGIN READ ONLY" : "BEGIN");
        began = true;

        const roleResult = await client.query(CHECK_ROLE_SQL);
        const role = roleResult.rows?.[0];
        if (!role || role.role_name !== expectedRole || role.rolsuper || role.rolbypassrls) throw new Error("Unsafe PostgreSQL application role");

        const validation = await client.query(VALIDATE_SQL, [
          requested.userId,
          requested.tenantId,
          requested.allowedTenants,
          requested.scopeFieldGroups,
          requested.capabilities,
          requested.employerSubjectUsers,
        ]);
        const canonical = normalizeValidated(validation.rows?.[0]);
        if (canonical.userId !== requested.userId || canonical.tenantId !== requested.tenantId) throw new Error("PostgreSQL changed the AuthContext subject or write tenant");

        await client.query(INJECT_SQL, [
          canonical.userId,
          canonical.tenantId,
          canonical.allowedTenants,
          canonical.scopeFieldGroups,
          canonical.capabilities,
          canonical.employerSubjectUsers,
          requested.actorPseudonym,
        ]);

        const result = await operation(operationClient(client), Object.freeze({ ...canonical, actorPseudonym: requested.actorPseudonym }));
        await client.query("COMMIT");
        began = false;
        return result;
      } catch (error) {
        if (began) {
          try { await client.query("ROLLBACK"); } catch { /* Connection disposal belongs to the pool adapter. */ }
        }
        throw error;
      } finally {
        client.release();
      }
    },
  };
}

export const postgresAuthContextContract = Object.freeze({ CHECK_ROLE_SQL, VALIDATE_SQL, INJECT_SQL, LIMITS });
