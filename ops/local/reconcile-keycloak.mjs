#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");
const envFile = resolve(root, ".local/secrets/runtime.env");
const origin = "https://isas.localhost:8443";
const realmName = "isas-local";

function readEnvironment(path) {
  const result = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator < 1) throw new Error("local environment file is invalid");
    result[line.slice(0, separator)] = line.slice(separator + 1);
  }
  return result;
}

const env = readEnvironment(envFile);
for (const name of ["KEYCLOAK_ADMIN", "KEYCLOAK_ADMIN_PASSWORD", "KEYCLOAK_CLIENT_SECRET", "LOCAL_OPERATOR_PASSWORD", "LOCAL_OPERATOR_TOTP_SECRET"]) {
  if (!env[name]) throw new Error(`${name} is required`);
}

async function responseJson(response, label) {
  if (!response.ok) throw new Error(`${label} failed with HTTP ${response.status}`);
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

const tokenResponse = await fetch(`${origin}/oidc/realms/master/protocol/openid-connect/token`, {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
  body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: env.KEYCLOAK_ADMIN, password: env.KEYCLOAK_ADMIN_PASSWORD }),
});
const token = (await responseJson(tokenResponse, "Keycloak administrator authentication")).access_token;
if (!token) throw new Error("Keycloak administrator token is missing");

async function admin(path, options = {}) {
  return fetch(`${origin}/oidc/admin/realms/${realmName}${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json", ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers },
  });
}

const realm = await responseJson(await admin(""), "read realm");
await responseJson(await admin("", {
  method: "PUT",
  body: JSON.stringify({
    ...realm,
    sslRequired: "external",
    registrationAllowed: false,
    resetPasswordAllowed: false,
    rememberMe: false,
    bruteForceProtected: true,
    otpPolicyType: "totp",
    otpPolicyAlgorithm: "HmacSHA1",
    otpPolicyDigits: 6,
    otpPolicyPeriod: 30,
    otpPolicyLookAheadWindow: 1,
  }),
}), "update realm MFA policy");

const clients = await responseJson(await admin("/clients?clientId=isas-bff"), "find BFF client");
if (clients.length !== 1) throw new Error("exactly one isas-bff client is required");
const client = clients[0];
await responseJson(await admin(`/clients/${encodeURIComponent(client.id)}`, {
  method: "PUT",
  body: JSON.stringify({
    ...client,
    secret: env.KEYCLOAK_CLIENT_SECRET,
    redirectUris: [`${origin}/api/bff/callback`],
    webOrigins: [origin],
    directAccessGrantsEnabled: false,
    publicClient: false,
    attributes: { ...client.attributes, "pkce.code.challenge.method": "S256", "post.logout.redirect.uris": `${origin}/*` },
  }),
}), "update BFF client");

const mappers = await responseJson(await admin(`/clients/${encodeURIComponent(client.id)}/protocol-mappers/models`), "read protocol mappers");
if (!mappers.some((mapper) => mapper.protocolMapper === "oidc-amr-mapper")) {
  await responseJson(await admin(`/clients/${encodeURIComponent(client.id)}/protocol-mappers/models`, {
    method: "POST",
    body: JSON.stringify({ name: "authentication-method-reference", protocol: "openid-connect", protocolMapper: "oidc-amr-mapper", consentRequired: false, config: { "id.token.claim": "true", "access.token.claim": "true" } }),
  }), "create AMR mapper");
}

// Keycloak's AMR mapper emits values from the completed execution's attached
// authenticator configuration. Built-in browser executions have no such
// configuration, so attach explicit RFC 8176-compatible references.
const browserExecutions = await responseJson(await admin("/authentication/flows/browser/executions"), "read browser authentication flow");
for (const [providerId, reference, maxAge] of [["auth-username-password-form", "pwd", "36000"], ["auth-otp-form", "otp", "300"]]) {
  const execution = browserExecutions.find((candidate) => candidate.providerId === providerId);
  if (!execution) throw new Error(`${providerId} is missing from the browser authentication flow`);
  const desired = { alias: `ISAS ${reference} AMR`, config: { "default.reference.value": reference, "default.reference.maxAge": maxAge } };
  if (execution.authenticationConfig) {
    const current = await responseJson(await admin(`/authentication/config/${encodeURIComponent(execution.authenticationConfig)}`), `read ${reference} authentication reference`);
    if (current.alias !== desired.alias || current.config?.["default.reference.value"] !== reference || current.config?.["default.reference.maxAge"] !== maxAge) {
      await responseJson(await admin(`/authentication/config/${encodeURIComponent(execution.authenticationConfig)}`, { method: "PUT", body: JSON.stringify({ ...current, ...desired }) }), `update ${reference} authentication reference`);
    }
  } else {
    await responseJson(await admin(`/authentication/executions/${encodeURIComponent(execution.id)}/config`, { method: "POST", body: JSON.stringify(desired) }), `create ${reference} authentication reference`);
  }
}

const users = await responseJson(await admin("/users?username=local-operator&exact=true"), "find local operator");
if (users.length !== 1) throw new Error("exactly one local operator is required");
await responseJson(await admin(`/users/${encodeURIComponent(users[0].id)}`, {
  method: "PUT",
  body: JSON.stringify({ ...users[0], email: "local-operator@invalid.example", emailVerified: true, firstName: "Local", lastName: "Operator", enabled: true }),
}), "update local operator profile");
let credentials = await responseJson(await admin(`/users/${encodeURIComponent(users[0].id)}/credentials`), "read local operator credentials");
if (!credentials.some((credential) => credential.type === "otp")) {
  const imported = await admin("/partialImport", {
    method: "POST",
    body: JSON.stringify({
      ifResourceExists: "OVERWRITE",
      users: [{
        id: "10000000-0000-4000-8000-000000000001",
        username: "local-operator",
        enabled: true,
        email: "local-operator@invalid.example",
        emailVerified: true,
        firstName: "Local",
        lastName: "Operator",
        credentials: [
          { type: "password", value: env.LOCAL_OPERATOR_PASSWORD, temporary: false },
          { id: "30000000-0000-4000-8000-000000000001", type: "otp", userLabel: "ISAS local TOTP", secretData: JSON.stringify({ value: env.LOCAL_OPERATOR_TOTP_SECRET }), credentialData: JSON.stringify({ subType: "totp", digits: 6, counter: 0, period: 30, algorithm: "HmacSHA1", secretEncoding: "BASE32" }) },
        ],
      }],
    }),
  });
  await responseJson(imported, "provision local operator MFA");
  const refreshed = await responseJson(await admin("/users?username=local-operator&exact=true"), "refresh local operator");
  if (refreshed.length !== 1) throw new Error("local operator reconcile failed");
  credentials = await responseJson(await admin(`/users/${encodeURIComponent(refreshed[0].id)}/credentials`), "verify local operator credentials");
}
const types = new Set(credentials.map((credential) => credential.type));
if (!types.has("password") || !types.has("otp")) throw new Error("local operator must have password and OTP credentials");

process.stdout.write("Keycloak local realm: OIDC PKCE, AMR, and TOTP MFA ready\n");
