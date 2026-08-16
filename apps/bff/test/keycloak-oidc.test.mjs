import assert from "node:assert/strict";
import test from "node:test";
import { createKeycloakOidc } from "../src/keycloak-oidc.mjs";

const issuer = "https://isas.localhost:8443/oidc/realms/isas-local";
const crypto = { sealString() { return "sealed"; }, openString() { return {}; } };

test("Keycloak discovery is same-origin and authorization uses PKCE S256", async () => {
  const provider = createKeycloakOidc({ issuer, clientId: "isas-bff", clientSecret: "x".repeat(32), crypto, fetchImpl: async () => new Response(JSON.stringify({
    issuer,
    authorization_endpoint: `${issuer}/protocol/openid-connect/auth`, token_endpoint: `${issuer}/protocol/openid-connect/token`,
    revocation_endpoint: `${issuer}/protocol/openid-connect/revoke`, end_session_endpoint: `${issuer}/protocol/openid-connect/logout`,
    jwks_uri: `${issuer}/protocol/openid-connect/certs`, code_challenge_methods_supported: ["S256"]
  }), { status: 200, headers: { "Content-Type": "application/json" } }) });
  await provider.startupCheck();
  const url = new URL(await provider.authorizationUrl({ state: "state", nonce: "nonce", codeChallenge: "challenge", redirectUri: "https://isas.localhost:8443/api/bff/callback" }));
  assert.equal(url.searchParams.get("code_challenge_method"), "S256");
  assert.equal(url.searchParams.get("state"), "state");
  assert.equal(url.origin, "https://isas.localhost:8443");
});

test("Keycloak discovery rejects an external token endpoint", async () => {
  const provider = createKeycloakOidc({ issuer, clientId: "isas-bff", clientSecret: "x".repeat(32), crypto, fetchImpl: async () => new Response(JSON.stringify({ issuer, authorization_endpoint: `${issuer}/auth`, token_endpoint: "https://evil.example/token", revocation_endpoint: `${issuer}/revoke`, end_session_endpoint: `${issuer}/logout`, jwks_uri: `${issuer}/certs`, code_challenge_methods_supported: ["S256"] }), { status: 200, headers: { "Content-Type": "application/json" } }) });
  await assert.rejects(() => provider.startupCheck(), /local issuer origin/);
});
