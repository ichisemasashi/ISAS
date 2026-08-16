#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const ROOT = resolve(import.meta.dirname, "../..");
const ORIGIN = "https://isas.localhost:8443";
const ISSUER = `${ORIGIN}/oidc/realms/isas-local`;
const REALM = "isas-local";
const TENANT_ID = "20000000-0000-4000-8000-000000000001";
const FIELD_GROUP_ID = "30000000-0000-4000-8000-000000000001";
const SYNTHETIC_ASSIGNMENT_ID = "43000000-0000-4000-8000-000000000001";
const ROLES = new Set(["worker", "field_supervisor", "organization_admin", "group_admin", "contractor"]);
const MFA_ROLES = new Set(["organization_admin", "group_admin"]);

export function parseArguments(argv) {
  const options = { username: "test-worker", displayName: "テスト作業者", role: "worker" };
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    if (option === "--help") return { help: true };
    if (!["--username", "--display-name", "--role"].includes(option) || index + 1 >= argv.length) throw new TypeError(`unknown or incomplete option: ${option}`);
    const value = argv[++index];
    if (option === "--username") options.username = value;
    if (option === "--display-name") options.displayName = value;
    if (option === "--role") options.role = value;
  }
  if (!/^[a-z][a-z0-9._-]{2,63}$/.test(options.username) || options.username === "local-operator") throw new TypeError("username must be 3-64 safe lowercase characters and cannot be local-operator");
  if (!options.displayName || options.displayName.length > 200 || /[\u0000-\u001f\u007f]/.test(options.displayName)) throw new TypeError("display name must be 1-200 printable characters");
  if (!ROLES.has(options.role)) throw new TypeError(`role must be one of: ${[...ROLES].join(", ")}`);
  return options;
}

function readEnvironment(path) {
  const result = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator < 1) throw new Error(`${path} is invalid`);
    result[line.slice(0, separator)] = line.slice(separator + 1);
  }
  return result;
}

function secret(bytes = 18) { return randomBytes(bytes).toString("base64url"); }
function base32Secret(bytes = 20) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const value of randomBytes(bytes)) bits += value.toString(2).padStart(8, "0");
  let encoded = "";
  for (let offset = 0; offset < bits.length; offset += 5) encoded += alphabet[Number.parseInt(bits.slice(offset, offset + 5).padEnd(5, "0"), 2)];
  return encoded;
}

function provisionSecrets(username) {
  const directory = resolve(ROOT, ".local/secrets/test-users");
  const path = resolve(directory, `${username}.env`);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  chmodSync(directory, 0o700);
  if (existsSync(path)) return { path, values: readEnvironment(path), created: false };
  const values = { USER_ID: randomUUID(), USERNAME: username, PASSWORD: secret(), TOTP_SECRET: base32Secret() };
  writeFileSync(path, `${Object.entries(values).map(([key, value]) => `${key}=${value}`).join("\n")}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  chmodSync(path, 0o600);
  return { path, values, created: true };
}

async function json(response, label) {
  if (!response.ok) throw new Error(`${label} failed with HTTP ${response.status}`);
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

async function provisionKeycloak(options, user, runtime) {
  const tokenResponse = await fetch(`${ORIGIN}/oidc/realms/master/protocol/openid-connect/token`, {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: runtime.KEYCLOAK_ADMIN, password: runtime.KEYCLOAK_ADMIN_PASSWORD }),
  });
  const token = (await json(tokenResponse, "Keycloak administrator authentication")).access_token;
  const admin = (path, init = {}) => fetch(`${ORIGIN}/oidc/admin/realms/${REALM}${path}`, {
    ...init, headers: { Authorization: `Bearer ${token}`, Accept: "application/json", ...(init.body ? { "Content-Type": "application/json" } : {}) },
  });
  let matches = await json(await admin(`/users?username=${encodeURIComponent(options.username)}&exact=true`), "find test user");
  const email = `${options.username}@invalid.example`;
  const requiresMfa = MFA_ROLES.has(options.role);
  if (matches.length === 0) {
    await json(await admin("/partialImport", { method: "POST", body: JSON.stringify({
      ifResourceExists: "FAIL",
      users: [{
        id: user.USER_ID, username: options.username, enabled: true,
        email, emailVerified: true,
        firstName: options.displayName, lastName: "Test",
        credentials: [{ type: "password", value: user.PASSWORD, temporary: false },
          ...(requiresMfa ? [{ type: "otp", userLabel: "ISAS local TOTP", secretData: JSON.stringify({ value: user.TOTP_SECRET }), credentialData: JSON.stringify({ subType: "totp", digits: 6, counter: 0, period: 30, algorithm: "HmacSHA1", secretEncoding: "BASE32" }) }] : [])],
      }],
    }) }), "create Keycloak test user");
    matches = await json(await admin(`/users?username=${encodeURIComponent(options.username)}&exact=true`), "refresh test user");
  }
  if (matches.length !== 1 || matches[0].id !== user.USER_ID) throw new Error("Keycloak username conflicts with another local user; no database change was made");
  await json(await admin(`/users/${encodeURIComponent(user.USER_ID)}`, { method: "PUT", body: JSON.stringify({
    ...matches[0], enabled: true, email, emailVerified: true,
    firstName: options.displayName, lastName: "Test",
  }) }), "update Keycloak test user");
  let credentials = await json(await admin(`/users/${encodeURIComponent(user.USER_ID)}/credentials`), "verify test user credentials");
  if (!requiresMfa) {
    for (const credential of credentials.filter(({ type }) => type === "otp")) {
      await json(await admin(`/users/${encodeURIComponent(user.USER_ID)}/credentials/${encodeURIComponent(credential.id)}`, { method: "DELETE" }), "remove non-administrator OTP credential");
    }
    credentials = await json(await admin(`/users/${encodeURIComponent(user.USER_ID)}/credentials`), "verify password-only test user credentials");
  }
  const types = new Set(credentials.map(({ type }) => type));
  if (!types.has("password") || (requiresMfa ? !types.has("otp") : types.has("otp"))) throw new Error("test user authentication policy does not match its role");
}

function provisionDatabase(options, user) {
  const compose = ["compose", "--project-directory", ROOT, "--env-file", resolve(ROOT, ".local/secrets/runtime.env"), "-f", resolve(ROOT, "compose.local.yml")];
  const sql = `\\set ON_ERROR_STOP on
BEGIN;
SET ROLE auth_context_owner;
INSERT INTO priv.auth_user(user_id,issuer,subject,display_name,status)
VALUES(:'USER_ID'::uuid, :'ISSUER', :'USER_ID', :'DISPLAY_NAME', 'active')
ON CONFLICT(user_id) DO UPDATE SET display_name=excluded.display_name,status='active',updated_at=clock_timestamp();
INSERT INTO priv.auth_membership(tenant_id,user_id,role_key,status,valid_until)
VALUES(:'TENANT_ID'::uuid, :'USER_ID'::uuid, :'ROLE_KEY', 'active', NULL)
ON CONFLICT(tenant_id,user_id) DO UPDATE SET role_key=excluded.role_key,status='active',valid_until=NULL,updated_at=clock_timestamp();
INSERT INTO priv.auth_membership_field_group(tenant_id,user_id,field_group_id)
VALUES(:'TENANT_ID'::uuid, :'USER_ID'::uuid, :'FIELD_GROUP_ID'::uuid)
ON CONFLICT DO NOTHING;
RESET ROLE;
UPDATE app.work_assignment SET assignee_user_id=:'USER_ID'::uuid, unassigned_at=NULL, version=version+1
WHERE tenant_id=:'TENANT_ID'::uuid AND assignment_id=:'ASSIGNMENT_ID'::uuid
  AND assignee_user_id IS DISTINCT FROM :'USER_ID'::uuid;
COMMIT;
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM priv.auth_user u JOIN priv.auth_membership m USING(user_id)
  JOIN priv.auth_membership_field_group s USING(tenant_id,user_id)
  WHERE u.user_id=:'USER_ID'::uuid AND u.issuer=:'ISSUER' AND u.subject=:'USER_ID'
    AND u.status='active' AND m.tenant_id=:'TENANT_ID'::uuid AND m.role_key=:'ROLE_KEY' AND m.status='active'
    AND s.field_group_id=:'FIELD_GROUP_ID'::uuid
) THEN 'test-user-ready' ELSE 'invalid' END;
`;
  const args = [...compose, "exec", "-T", "database", "psql", "-X", "-At", "-v", "ON_ERROR_STOP=1",
    "-v", `USER_ID=${user.USER_ID}`, "-v", `ISSUER=${ISSUER}`, "-v", `DISPLAY_NAME=${options.displayName}`,
    "-v", `ROLE_KEY=${options.role}`, "-v", `TENANT_ID=${TENANT_ID}`, "-v", `FIELD_GROUP_ID=${FIELD_GROUP_ID}`,
    "-v", `ASSIGNMENT_ID=${SYNTHETIC_ASSIGNMENT_ID}`, "-U", "postgres", "-d", "isas"];
  const output = execFileSync("docker", args, { input: sql, encoding: "utf8", stdio: ["pipe", "pipe", "inherit"] });
  if (!output.trimEnd().endsWith("test-user-ready")) throw new Error("database test user verification failed");
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  if (options.help) {
    process.stdout.write("usage: ops/local/register-test-user.sh [--username name] [--display-name name] [--role worker]\n");
    return;
  }
  const runtime = readEnvironment(resolve(ROOT, ".local/secrets/runtime.env"));
  for (const key of ["KEYCLOAK_ADMIN", "KEYCLOAK_ADMIN_PASSWORD"]) if (!runtime[key]) throw new Error(`${key} is required`);
  const secrets = provisionSecrets(options.username);
  for (const key of ["USER_ID", "USERNAME", "PASSWORD", "TOTP_SECRET"]) if (!secrets.values[key]) throw new Error(`${secrets.path} is missing ${key}`);
  if (secrets.values.USERNAME !== options.username) throw new Error(`${secrets.path} belongs to another username`);
  await provisionKeycloak(options, secrets.values, runtime);
  provisionDatabase(options, secrets.values);
  process.stdout.write(`test user ready: ${options.username} (${options.role}, ${MFA_ROLES.has(options.role) ? "password + TOTP" : "email/username + password"})\ncredentials: ${secrets.path}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main().catch((error) => { process.stderr.write(`register test user failed: ${error.message}\n`); process.exitCode = 1; });
