import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createBffHandler, createContextResolver } from "../src/handler.mjs";
import { createMemoryStores } from "../src/memory-stores.mjs";

const ORIGIN = "https://isas.example";
const REDIRECT_URI = `${ORIGIN}/api/bff/callback`;

function fixture({ authenticationLevel = "phishing-resistant" } = {}) {
  let now = Date.parse("2026-08-14T00:00:00Z");
  const stores = createMemoryStores();
  const calls = { authorize: [], exchange: [], revoke: [] };
  const identityProvider = {
    async authorizationUrl(input) {
      calls.authorize.push(input);
      return `https://idp.example/authorize?state=${encodeURIComponent(input.state)}`;
    },
    async exchangeCode(input) {
      calls.exchange.push(input);
      return {
        issuer: "https://idp.example",
        subject: "subject-1",
        authenticationLevel,
        authenticatedAt: new Date(now).toISOString(),
        tokenSetCiphertext: "encrypted-token-set",
      };
    },
    async revoke(value) { calls.revoke.push(value); },
    logoutUrl(returnTo) { return `https://idp.example/logout?return_to=${encodeURIComponent(returnTo)}`; },
  };
  const users = {
    async resolve(issuer, subject) {
      return issuer === "https://idp.example" && subject === "subject-1"
        ? { id: "user-1", displayName: "佐藤 一郎", initials: "佐", authorizationVersion: "7" }
        : null;
    },
  };
  const authorization = {
    async listTenants(userId) {
      assert.equal(userId, "user-1");
      return [{ id: "tenant-1", name: "山形みどり農園", roleLabel: "現場チーム" }];
    },
    async deriveContext(userId, tenantId) {
      if (userId !== "user-1" || tenantId !== "tenant-1") return null;
      return {
        jurisdictionId: "jp",
        shardId: "jp-1",
        tenantName: "山形みどり農園",
        roleLabel: "現場チーム",
        membershipVersion: "membership-1",
        authorizationVersion: "7",
        authorizationSnapshotId: "snapshot-1",
        actorPseudonym: "actor-user-1",
        scopeFieldGroups: ["field-group-1"],
        capabilities: ["journal:write"],
      };
    },
  };
  const handle = createBffHandler({ origin: ORIGIN, redirectUri: REDIRECT_URI, stores, identityProvider, users, authorization, clock: () => now });
  const resolveContext = createContextResolver({ stores, authorization, clock: () => now });
  return { handle, resolveContext, calls, advance: (milliseconds) => { now += milliseconds; } };
}

async function login(fx, returnTo = "/today") {
  const start = await fx.handle(new Request(`${ORIGIN}/api/bff/login?return_to=${encodeURIComponent(returnTo)}`));
  assert.equal(start.status, 302);
  const state = new URL(start.headers.get("Location")).searchParams.get("state");
  const callback = await fx.handle(new Request(`${REDIRECT_URI}?code=code-1&state=${encodeURIComponent(state)}`));
  assert.equal(callback.status, 302);
  return callback.headers.get("Set-Cookie").split(";")[0];
}

describe("BFF OIDC and session boundary", () => {
  test("binds state, nonce and PKCE before issuing a hardened opaque cookie", async () => {
    const fx = fixture();
    const start = await fx.handle(new Request(`${ORIGIN}/api/bff/login?return_to=${encodeURIComponent("https://attacker.example/steal")}`));
    const state = new URL(start.headers.get("Location")).searchParams.get("state");
    const callback = await fx.handle(new Request(`${REDIRECT_URI}?code=code-1&state=${encodeURIComponent(state)}`));

    assert.equal(callback.status, 302);
    assert.equal(callback.headers.get("Location"), "/");
    assert.match(callback.headers.get("Set-Cookie"), /^__Host-isas_session=[^;]+; Path=\/; Secure; HttpOnly; SameSite=Lax;/);
    assert.equal(fx.calls.exchange[0].nonce, fx.calls.authorize[0].nonce);
    assert.equal(fx.calls.exchange[0].verifier.length > 32, true);

    const replay = await fx.handle(new Request(`${REDIRECT_URI}?code=code-1&state=${encodeURIComponent(state)}`));
    assert.equal(replay.status, 400);
  });

  test("returns session bootstrap data without exposing the OIDC token set", async () => {
    const fx = fixture();
    const cookie = await login(fx);
    const response = await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } }));
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.equal(body.user.id, "user-1");
    assert.equal(body.tenants[0].id, "tenant-1");
    assert.equal(typeof body.csrfToken, "string");
    assert.equal(JSON.stringify(body).includes("encrypted-token-set"), false);
  });

  test("forces fresh authentication for step-up and replaces only the same subject session", async () => {
    const fx = fixture();
    const oldCookie = await login(fx);
    const start = await fx.handle(new Request(`${ORIGIN}/api/bff/login?step_up=1&return_to=%2Fexports`, {
      headers: { Cookie: oldCookie },
    }));
    assert.equal(fx.calls.authorize.at(-1).prompt, "login");
    assert.equal(fx.calls.authorize.at(-1).maxAge, 0);
    const state = new URL(start.headers.get("Location")).searchParams.get("state");
    const callback = await fx.handle(new Request(`${REDIRECT_URI}?code=step-up&state=${state}`));
    assert.equal(callback.status, 302);
    assert.equal(callback.headers.get("Location"), "/exports");
    assert.equal((await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: oldCookie } }))).status, 401);
  });

  test("rejects step-up when the identity provider returns only one factor", async () => {
    const fx = fixture({ authenticationLevel: "single-factor" });
    const oldCookie = await login(fx);
    const start = await fx.handle(new Request(`${ORIGIN}/api/bff/login?step_up=1&return_to=%2Fexports`, { headers: { Cookie: oldCookie } }));
    const state = new URL(start.headers.get("Location")).searchParams.get("state");
    const callback = await fx.handle(new Request(`${REDIRECT_URI}?code=step-up&state=${state}`));
    assert.equal(callback.status, 403);
    assert.equal((await callback.json()).error, "mfa_required");
    assert.deepEqual(fx.calls.revoke, ["encrypted-token-set"]);
    assert.equal((await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: oldCookie } }))).status, 200);
  });

  test("requires same-origin CSRF proof and derives tenant context server-side", async () => {
    const fx = fixture();
    const cookie = await login(fx);
    const session = await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } })).then((response) => response.json());
    const request = (headers = {}) => new Request(`${ORIGIN}/api/bff/contexts`, {
      method: "POST",
      headers: { Cookie: cookie, Origin: ORIGIN, "Sec-Fetch-Site": "same-origin", "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken, ...headers },
      body: JSON.stringify({ tenantId: "tenant-1", userId: "attacker", roleLabel: "owner" }),
    });

    assert.equal((await fx.handle(request({ Origin: "https://attacker.example" }))).status, 403);
    assert.equal((await fx.handle(request({ "X-CSRF-Token": "wrong" }))).status, 403);

    const response = await fx.handle(request());
    const context = await response.json();
    assert.equal(response.status, 201);
    assert.equal(context.tenantId, "tenant-1");
    assert.equal(context.roleLabel, "現場チーム");
    assert.equal(typeof context.contextId, "string");
    assert.equal("userId" in context, false);
  });

  test("revokes the server session and clears its cookie on logout", async () => {
    const fx = fixture();
    const cookie = await login(fx);
    const session = await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } })).then((response) => response.json());
    const logout = await fx.handle(new Request(`${ORIGIN}/api/bff/logout`, {
      method: "POST",
      headers: { Cookie: cookie, Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken },
      body: "{}",
    }));

    assert.equal(logout.status, 204);
    assert.match(logout.headers.get("Set-Cookie"), /Max-Age=0/);
    assert.match(logout.headers.get("X-ISAS-Logout-Location"), /^https:\/\/idp\.example\/logout/);
    assert.deepEqual(fx.calls.revoke, ["encrypted-token-set"]);
    assert.equal((await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } }))).status, 401);
  });

  test("keeps local logout successful when upstream token revocation is unavailable", async () => {
    const fx = fixture();
    fx.calls.revoke.length = 0;
    const cookie = await login(fx);
    const session = await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } })).then((response) => response.json());
    fx.calls.revoke.push = () => { throw new Error("IdP unavailable"); };

    const logout = await fx.handle(new Request(`${ORIGIN}/api/bff/logout`, {
      method: "POST",
      headers: { Cookie: cookie, Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken },
      body: "{}",
    }));

    assert.equal(logout.status, 204);
    assert.match(logout.headers.get("Set-Cookie"), /Max-Age=0/);
    assert.equal((await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } }))).status, 401);
  });

  test("resolves a context only when it is bound to the current session and current authorization", async () => {
    const fx = fixture();
    const cookie = await login(fx);
    const session = await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } })).then((response) => response.json());
    const issued = await fx.handle(new Request(`${ORIGIN}/api/bff/contexts`, {
      method: "POST",
      headers: { Cookie: cookie, Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken },
      body: JSON.stringify({ tenantId: "tenant-1" }),
    })).then((response) => response.json());

    const trusted = await fx.resolveContext(new Request(`${ORIGIN}/api/commands`, {
      headers: {
        Cookie: cookie,
        "X-ISAS-Context": issued.contextId,
        "X-ISAS-User": "attacker",
        "X-ISAS-Capabilities": "admin:*",
      },
    }));

    assert.equal(trusted.userId, "user-1");
    assert.equal(trusted.actorPseudonym, "actor-user-1");
    assert.deepEqual(trusted.authContext, {
      userId: "user-1",
      tenantId: "tenant-1",
      allowedTenants: ["tenant-1"],
      scopeFieldGroups: ["field-group-1"],
      capabilities: ["journal:write"],
      employerSubjectUsers: [],
    });
    assert.equal(await fx.resolveContext(new Request(`${ORIGIN}/api/commands`, { headers: { Cookie: "__Host-isas_session=wrong", "X-ISAS-Context": issued.contextId } })), null);

    fx.advance(5 * 60 * 1000 + 1);
    assert.equal(await fx.resolveContext(new Request(`${ORIGIN}/api/commands`, { headers: { Cookie: cookie, "X-ISAS-Context": issued.contextId } })), null);
  });

  test("expires idle sessions instead of trusting the cookie lifetime", async () => {
    const fx = fixture();
    const cookie = await login(fx);
    fx.advance(12 * 60 * 60 * 1000 + 1);

    const response = await fx.handle(new Request(`${ORIGIN}/api/bff/session`, { headers: { Cookie: cookie } }));

    assert.equal(response.status, 401);
    assert.match(response.headers.get("Set-Cookie"), /Max-Age=0/);
  });
});
