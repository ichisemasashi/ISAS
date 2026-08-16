import { createHash } from "node:crypto";
import { createRemoteJWKSet, jwtVerify } from "jose";

function sameOriginEndpoint(value, origin, label) {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.origin !== origin) throw new Error(`${label} must use the local issuer origin`);
  return url.toString();
}

export function createKeycloakOidc({ issuer, clientId, clientSecret, crypto, fetchImpl = fetch, clock = () => Date.now() }) {
  if (!issuer || !clientId || !clientSecret || clientSecret.length < 32 || !crypto?.sealString || !crypto?.openString) throw new Error("Keycloak OIDC configuration is incomplete");
  const issuerUrl = new URL(issuer);
  if (issuerUrl.protocol !== "https:" || issuerUrl.origin !== "https://isas.localhost:8443") throw new Error("Keycloak issuer is outside the local allowlist");
  let metadata;
  let jwks;

  async function discover() {
    const response = await fetchImpl(`${issuer}/.well-known/openid-configuration`, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error("Keycloak discovery failed");
    const value = await response.json();
    if (value.issuer !== issuer || !Array.isArray(value.code_challenge_methods_supported) || !value.code_challenge_methods_supported.includes("S256")) throw new Error("Keycloak discovery contract mismatch");
    const origin = issuerUrl.origin;
    metadata = Object.freeze({
      authorizationEndpoint: sameOriginEndpoint(value.authorization_endpoint, origin, "authorization endpoint"),
      tokenEndpoint: sameOriginEndpoint(value.token_endpoint, origin, "token endpoint"),
      revocationEndpoint: sameOriginEndpoint(value.revocation_endpoint, origin, "revocation endpoint"),
      endSessionEndpoint: sameOriginEndpoint(value.end_session_endpoint, origin, "logout endpoint"),
      jwksUri: sameOriginEndpoint(value.jwks_uri, origin, "JWKS endpoint")
    });
    jwks = createRemoteJWKSet(new URL(metadata.jwksUri));
    return metadata;
  }

  async function endpoints() { return metadata || discover(); }
  const basic = () => `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString("base64")}`;

  return Object.freeze({
    async startupCheck() { await discover(); },
    async authorizationUrl({ state, nonce, codeChallenge, redirectUri, prompt, maxAge }) {
      const current = await endpoints();
      const url = new URL(current.authorizationEndpoint);
      const params = { client_id: clientId, response_type: "code", scope: "openid profile email", state, nonce, code_challenge: codeChallenge, code_challenge_method: "S256", redirect_uri: redirectUri };
      for (const [name, value] of Object.entries(params)) url.searchParams.set(name, value);
      if (prompt) url.searchParams.set("prompt", prompt);
      if (maxAge != null) url.searchParams.set("max_age", String(maxAge));
      return url.toString();
    },
    async exchangeCode({ code, verifier, nonce, redirectUri }) {
      const current = await endpoints();
      const response = await fetchImpl(current.tokenEndpoint, {
        method: "POST",
        headers: { Authorization: basic(), "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
        body: new URLSearchParams({ grant_type: "authorization_code", client_id: clientId, code, code_verifier: verifier, redirect_uri: redirectUri })
      });
      if (!response.ok) throw new Error("Keycloak token exchange failed");
      const tokenSet = await response.json();
      if (!tokenSet.id_token || !tokenSet.access_token) throw new Error("Keycloak token response is incomplete");
      const verified = await jwtVerify(tokenSet.id_token, jwks, { issuer, audience: clientId, algorithms: ["RS256"] });
      const claims = verified.payload;
      if (claims.nonce !== nonce || claims.token_use && claims.token_use !== "id") throw new Error("Keycloak ID token binding failed");
      if (claims.at_hash) {
        const expected = createHash("sha256").update(tokenSet.access_token).digest().subarray(0, 16).toString("base64url");
        if (claims.at_hash !== expected) throw new Error("Keycloak at_hash mismatch");
      }
      const factors = Array.isArray(claims.amr) ? claims.amr : [];
      const authenticationLevel = factors.some((value) => /webauthn|hwk|passkey/i.test(value)) ? "phishing-resistant"
        : factors.some((value) => /otp|mfa/i.test(value)) || Number(claims.acr || 0) >= 2 ? "mfa" : "single-factor";
      return {
        issuer,
        subject: claims.sub,
        authenticationLevel,
        authenticatedAt: Number(claims.auth_time || claims.iat) * 1000,
        tokenSetCiphertext: crypto.sealString(tokenSet, "oidc-token-set", "keycloak")
      };
    },
    logoutUrl(postLogoutRedirectUri) {
      if (!metadata) return null;
      const url = new URL(metadata.endSessionEndpoint);
      url.searchParams.set("client_id", clientId);
      url.searchParams.set("post_logout_redirect_uri", postLogoutRedirectUri);
      return url.toString();
    },
    async revoke(tokenSetCiphertext) { return this.revokeNow(tokenSetCiphertext); },
    async revokeNow(tokenSetCiphertext) {
      const tokenSet = crypto.openString(tokenSetCiphertext, "oidc-token-set", "keycloak");
      if (!tokenSet.refresh_token) return;
      const current = await endpoints();
      const response = await fetchImpl(current.revocationEndpoint, { method: "POST", headers: { Authorization: basic(), "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ token: tokenSet.refresh_token, token_type_hint: "refresh_token" }) });
      if (!response.ok) throw new Error("Keycloak token revocation failed");
    },
    async adminGlobalSignOut() { return false; },
    now: clock
  });
}
