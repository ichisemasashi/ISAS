import assert from "node:assert/strict";
import test from "node:test";
import { createLocalTestUserAdministration } from "../src/local-test-user-administration.mjs";

const USER_ID = "33333333-3333-4333-8333-333333333333";
const TENANT_ID = "20000000-0000-4000-8000-000000000001";
const ISSUER = "https://isas.localhost:8443/oidc/realms/isas-local";

test("local web registration provisions first-login Keycloak credentials then scoped DB identity", async () => {
  const requests = [];
  const responses = [
    new Response(JSON.stringify({ access_token: "admin-token" }), { status: 200 }),
    new Response("[]", { status: 200 }),
    new Response("[]", { status: 200 }),
    new Response(null, { status: 200 }),
  ];
  const fetcher = async (url, init) => { requests.push({ url, init }); return responses.shift(); };
  const calls = [];
  const pool = { async query(sql, values) { calls.push({ sql, values }); return { rows: [{ result: { status: "active" } }] }; } };
  const administration = createLocalTestUserAdministration({ pool, issuer: ISSUER, adminUsername: "admin", adminPassword: "admin-password",
    fetcher, uuid: () => USER_ID, password: () => "Temporary!Password9" });
  const result = await administration.provision({ userId: "10000000-0000-4000-8000-000000000001", authContext: { tenantId: TENANT_ID } },
    { username: "web-worker", email: "worker@example.test", displayName: "Web作業者", roleKey: "worker" });
  assert.equal(result.temporaryPassword, "Temporary!Password9");
  assert.equal(result.email, "worker@example.test");
  assert.deepEqual(result.requiredActions, ["UPDATE_PASSWORD"]);
  const imported = JSON.parse(requests[3].init.body);
  assert.equal(imported.users[0].credentials[0].temporary, true);
  assert.deepEqual(imported.users[0].requiredActions, ["UPDATE_PASSWORD"]);
  assert.equal(imported.users[0].email, "worker@example.test");
  assert.match(calls[0].sql, /local_register_test_user/);
  assert.deepEqual(calls[0].values.slice(1, 4), [TENANT_ID, USER_ID, ISSUER]);
});

test("local web registration rejects duplicates before changing the database", async () => {
  const responses = [new Response(JSON.stringify({ access_token: "admin-token" }), { status: 200 }), new Response(JSON.stringify([{ id: USER_ID }]), { status: 200 })];
  const pool = { async query() { throw new Error("database must not be called"); } };
  const administration = createLocalTestUserAdministration({ pool, issuer: ISSUER, adminUsername: "admin", adminPassword: "admin-password", fetcher: async () => responses.shift() });
  await assert.rejects(() => administration.provision({ userId: USER_ID, authContext: { tenantId: TENANT_ID } },
    { username: "web-worker", email: "worker@example.test", displayName: "Web作業者", roleKey: "worker" }), (error) => error.code === "username_conflict");
});

test("local web registration keeps MFA enrollment for administrative roles", async () => {
  const requests = [];
  const responses = [new Response(JSON.stringify({ access_token: "admin-token" }), { status: 200 }), new Response("[]", { status: 200 }), new Response("[]", { status: 200 }), new Response(null, { status: 200 })];
  const pool = { async query() { return { rows: [{ result: { status: "active" } }] }; } };
  const administration = createLocalTestUserAdministration({ pool, issuer: ISSUER, adminUsername: "admin", adminPassword: "admin-password",
    fetcher: async (url, init) => { requests.push({ url, init }); return responses.shift(); }, uuid: () => USER_ID, password: () => "Temporary!Password9" });
  const result = await administration.provision({ userId: USER_ID, authContext: { tenantId: TENANT_ID } },
    { username: "web-admin", email: "admin@example.test", displayName: "Web管理者", roleKey: "group_admin" });
  assert.deepEqual(result.requiredActions, ["UPDATE_PASSWORD", "CONFIGURE_TOTP"]);
  assert.deepEqual(JSON.parse(requests[3].init.body).users[0].requiredActions, ["UPDATE_PASSWORD", "CONFIGURE_TOTP"]);
});

test("local web registration validates its isolated profile", () => {
  const pool = { async query() {} };
  assert.throws(() => createLocalTestUserAdministration({ pool, issuer: "https://cognito.example", adminUsername: "admin", adminPassword: "password" }), /configuration/);
});
