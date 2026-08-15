import assert from "node:assert/strict";
import { test } from "node:test";
import { createHash } from "node:crypto";
import { createCognitoOidc } from "../src/cognito-oidc.mjs";

const ISSUER = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_pool";
const CLIENT = "client-1";
const NOW = Date.parse("2026-08-15T00:00:00Z");

function fixture(overrides = {}) {
  const calls = [];
  const queued = [];
  const crypto = {
    async encrypt(value, metadata) { return JSON.stringify({ value, metadata }); },
    async decrypt(value) { return JSON.parse(value).value; },
  };
  const accessToken = "access-token";
  const claims = {
    token_use: "id",
    iss: ISSUER,
    aud: CLIENT,
    sub: "subject-1",
    nonce: "nonce-1",
    auth_time: NOW / 1000,
    origin_jti: "origin-1",
    at_hash: createHash("sha256").update(accessToken).digest().subarray(0, 16).toString("base64url"),
    ...overrides.claims,
  };
  const cognito = { async send(command) {
    calls.push(command);
    if (command.constructor.name === "GetUserAuthFactorsCommand") return { ConfiguredUserAuthFactors: ["WEB_AUTHN"] };
    if (command.constructor.name === "GetUserPoolMfaConfigCommand") return overrides.weakPool
      ? { MfaConfiguration: "OPTIONAL", SoftwareTokenMfaConfiguration: { Enabled: false }, WebAuthnConfiguration: { UserVerification: "preferred" } }
      : { MfaConfiguration: "ON", SoftwareTokenMfaConfiguration: { Enabled: true }, WebAuthnConfiguration: { UserVerification: "required", FactorConfiguration: "MULTI_FACTOR_WITH_USER_VERIFICATION" } };
    if (command.constructor.name === "DescribeUserPoolClientCommand") return { UserPoolClient: {
      AllowedOAuthFlows: ["code"],
      AllowedOAuthScopes: overrides.weakClient ? ["openid"] : ["openid", "profile", "email", "aws.cognito.signin.user.admin"],
      EnableTokenRevocation: !overrides.weakClient,
      GenerateSecret: false,
      CallbackURLs: ["https://isas.example/api/bff/callback"],
      LogoutURLs: ["https://isas.example/"],
    } };
    if (overrides.revokeFails && command.constructor.name === "RevokeTokenCommand") throw new Error("unavailable");
    return {};
  } };
  const provider = createCognitoOidc({
    issuer: ISSUER,
    userPoolId: "ap-northeast-1_pool",
    clientId: CLIENT,
    managedLoginOrigin: "https://auth.isas.example",
    cognito,
    crypto,
    clock: () => NOW,
    jwtVerifier: async () => ({ payload: claims }),
    fetchImpl: async () => new Response(JSON.stringify({
      id_token: "id-token", access_token: accessToken, refresh_token: "refresh-token", token_type: "Bearer",
    }), { status: 200, headers: { "Content-Type": "application/json" } }),
    enqueueRevocation: async (event) => queued.push(event),
  });
  return { provider, calls, queued };
}

test("uses authorization code with S256 PKCE and forces reauthentication for step-up", async () => {
  const { provider } = fixture();
  const location = new URL(await provider.authorizationUrl({
    state: "state-1", nonce: "nonce-1", codeChallenge: "challenge-1",
    redirectUri: "https://isas.example/api/bff/callback", prompt: "login", maxAge: 0,
  }));
  assert.equal(location.pathname, "/oauth2/authorize");
  assert.equal(location.searchParams.get("response_type"), "code");
  assert.equal(location.searchParams.get("code_challenge_method"), "S256");
  assert.equal(location.searchParams.get("prompt"), "login");
  assert.equal(location.searchParams.get("max_age"), "0");
});

test("verifies Cognito identity claims and encrypts the complete server token set", async () => {
  const { provider } = fixture();
  const identity = await provider.exchangeCode({
    code: "code-1", verifier: "verifier-1", nonce: "nonce-1",
    redirectUri: "https://isas.example/api/bff/callback",
  });
  assert.equal(identity.issuer, ISSUER);
  assert.equal(identity.subject, "subject-1");
  assert.equal(identity.authenticationLevel, "mfa");
  assert.equal(JSON.parse(identity.tokenSetCiphertext).version, 1);
  assert.equal(JSON.parse(identity.tokenSetCiphertext).resourceId, "subject-1:origin-1");
});

test("rejects nonce mismatch and queues upstream revocation without restoring local state", async () => {
  const mismatch = fixture({ claims: { nonce: "attacker" } });
  await assert.rejects(() => mismatch.provider.exchangeCode({ code: "c", verifier: "v", nonce: "nonce-1", redirectUri: "https://isas.example/api/bff/callback" }), /claims/);

  const failing = fixture({ revokeFails: true });
  const identity = await failing.provider.exchangeCode({ code: "c", verifier: "v", nonce: "nonce-1", redirectUri: "https://isas.example/api/bff/callback" });
  await failing.provider.revoke(identity.tokenSetCiphertext);
  assert.equal(failing.queued.length, 1);
  assert.equal(failing.queued[0].type, "cognito_token_revoke");
});

test("startup read-back refuses a Cognito configuration weaker than mandatory MFA", async () => {
  const good = fixture();
  await good.provider.startupCheck({ redirectUri: "https://isas.example/api/bff/callback", logoutUri: "https://isas.example/" });
  const bad = fixture({ weakPool: true });
  await assert.rejects(() => bad.provider.startupCheck({
    redirectUri: "https://isas.example/api/bff/callback", logoutUri: "https://isas.example/",
  }), /MFA must be ON/);
  const weakClient = fixture({ weakClient: true });
  await assert.rejects(() => weakClient.provider.startupCheck({
    redirectUri: "https://isas.example/api/bff/callback", logoutUri: "https://isas.example/",
  }), /required scopes and token revocation/);
});
