import { randomBytes, randomUUID } from "node:crypto";

const LOCAL_TENANT_ID = "20000000-0000-4000-8000-000000000001";
const LOCAL_FIELD_GROUP_ID = "30000000-0000-4000-8000-000000000001";
const LOCAL_ISSUER = "https://isas.localhost:8443/oidc/realms/isas-local";
const ALLOWED_ROLES = new Set(["worker", "field_supervisor", "organization_admin", "group_admin", "contractor"]);
const MFA_ROLES = new Set(["organization_admin", "group_admin"]);

function validate(input) {
  const username = typeof input?.username === "string" ? input.username.trim() : "";
  const email = typeof input?.email === "string" ? input.email.trim().toLowerCase() : "";
  const displayName = typeof input?.displayName === "string" ? input.displayName.trim() : "";
  const roleKey = typeof input?.roleKey === "string" ? input.roleKey : "";
  if (!/^[a-z][a-z0-9._-]{2,63}$/.test(username) || username === "local-operator") throw new TypeError("invalid local username");
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || /[\u0000-\u001f\u007f]/.test(email)) throw new TypeError("invalid email address");
  if (!displayName || displayName.length > 200 || /[\u0000-\u001f\u007f]/.test(displayName)) throw new TypeError("invalid display name");
  if (!ALLOWED_ROLES.has(roleKey)) throw new TypeError("invalid local role");
  return { username, email, displayName, roleKey };
}

async function responseJson(response, label) {
  if (!response.ok) throw Object.assign(new Error(`${label} failed with HTTP ${response.status}`), { code: "identity_provider_error" });
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export function createLocalTestUserAdministration({ pool, issuer, adminUsername, adminPassword, fetcher = fetch, uuid = randomUUID, password = () => `L!${randomBytes(18).toString("base64url")}9` }) {
  if (!pool?.query || issuer !== LOCAL_ISSUER || !adminUsername || !adminPassword) throw new Error("local test user administration configuration is invalid");
  const origin = new URL(issuer).origin;
  return Object.freeze({
    async provision(trusted, input) {
      const profile = validate(input);
      if (trusted?.authContext?.tenantId !== LOCAL_TENANT_ID || !trusted?.userId) throw Object.assign(new Error("local test user boundary rejected"), { code: "forbidden" });
      const userId = uuid();
      const temporaryPassword = password();
      const tokenResponse = await fetcher(`${origin}/oidc/realms/master/protocol/openid-connect/token`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
        body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: adminUsername, password: adminPassword }),
      });
      const token = (await responseJson(tokenResponse, "Keycloak administrator authentication"))?.access_token;
      if (!token) throw Object.assign(new Error("Keycloak administrator token is missing"), { code: "identity_provider_error" });
      const admin = (path, init = {}) => fetcher(`${origin}/oidc/admin/realms/isas-local${path}`, {
        ...init, headers: { Authorization: `Bearer ${token}`, Accept: "application/json", ...(init.body ? { "Content-Type": "application/json" } : {}) },
      });
      const existing = await responseJson(await admin(`/users?username=${encodeURIComponent(profile.username)}&exact=true`), "find local test user");
      if (existing.length) throw Object.assign(new Error("local username already exists"), { code: "username_conflict" });
      const existingEmail = await responseJson(await admin(`/users?email=${encodeURIComponent(profile.email)}&exact=true`), "find local test user email");
      if (existingEmail.length) throw Object.assign(new Error("local email already exists"), { code: "email_conflict" });

      const requiredActions = MFA_ROLES.has(profile.roleKey) ? ["UPDATE_PASSWORD", "CONFIGURE_TOTP"] : ["UPDATE_PASSWORD"];

      await responseJson(await admin("/partialImport", { method: "POST", body: JSON.stringify({
        ifResourceExists: "FAIL",
        users: [{ id: userId, username: profile.username, enabled: true,
          email: profile.email, emailVerified: true,
          firstName: profile.displayName, lastName: "Test",
          requiredActions,
          credentials: [{ type: "password", value: temporaryPassword, temporary: true }],
        }],
      }) }), "create local test user");

      try {
        const result = await pool.query(
          "SELECT app_private.local_register_test_user($1::uuid,$2::uuid,$3::uuid,$4::text,$5::text,$6::text,$7::uuid[]) AS result",
          [trusted.userId, LOCAL_TENANT_ID, userId, LOCAL_ISSUER, profile.displayName, profile.roleKey, [LOCAL_FIELD_GROUP_ID]],
        );
        if (!result.rows?.[0]?.result) throw new Error("local test user database registration returned no result");
      } catch (error) {
        await admin(`/users/${encodeURIComponent(userId)}`, { method: "DELETE" }).catch(() => undefined);
        throw error;
      }

      return { userId, username: profile.username, email: profile.email, displayName: profile.displayName, roleKey: profile.roleKey,
        fieldGroupIds: [LOCAL_FIELD_GROUP_ID], temporaryPassword, requiredActions, status: "ready_for_first_login" };
    },
  });
}
