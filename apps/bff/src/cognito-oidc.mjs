import { createHash, timingSafeEqual } from "node:crypto";
import {
  AdminUserGlobalSignOutCommand,
  DescribeUserPoolClientCommand,
  GetUserAuthFactorsCommand,
  GetUserPoolMfaConfigCommand,
  GlobalSignOutCommand,
  RevokeTokenCommand,
} from "@aws-sdk/client-cognito-identity-provider";
import { createRemoteJWKSet, jwtVerify } from "jose";

const TOKEN_RESPONSE_LIMIT = 64 * 1024;

function equal(left, right) {
  const a = Buffer.from(left || "");
  const b = Buffer.from(right || "");
  return a.length === b.length && a.length > 0 && timingSafeEqual(a, b);
}

function atHash(accessToken) {
  return createHash("sha256").update(accessToken).digest().subarray(0, 16).toString("base64url");
}

function exactHttpsOrigin(value, name) {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.origin !== value || url.pathname !== "/") throw new Error(`${name} must be an exact HTTPS origin`);
  return url.origin;
}

function exactIssuer(value) {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname === "/") {
    throw new Error("Cognito issuer must be an exact HTTPS issuer URL");
  }
  return url.toString().replace(/\/$/, "");
}

function tokenWrapper(value) {
  let parsed;
  try { parsed = JSON.parse(value); } catch { throw new Error("Invalid encrypted token set"); }
  if (parsed?.version !== 1 || !parsed.resourceId || !parsed.envelope) throw new Error("Invalid encrypted token set");
  return parsed;
}

async function responseJson(response) {
  if (!response.ok) throw Object.assign(new Error("Cognito token exchange failed"), { code: `oidc_http_${response.status}` });
  if (!/^application\/json(?:\s*;|$)/i.test(response.headers.get("content-type") || "")) throw new Error("Cognito token endpoint returned an invalid content type");
  const declared = Number(response.headers.get("content-length") || 0);
  if (declared > TOKEN_RESPONSE_LIMIT) throw new Error("Cognito token response is too large");
  const body = await response.text();
  if (Buffer.byteLength(body) > TOKEN_RESPONSE_LIMIT) throw new Error("Cognito token response is too large");
  return JSON.parse(body);
}

export function createCognitoOidc({
  issuer,
  userPoolId,
  clientId,
  managedLoginOrigin,
  cognito,
  crypto,
  fetchImpl = fetch,
  jwtVerifier,
  enqueueRevocation,
  clock = () => Date.now(),
}) {
  const trustedIssuer = exactIssuer(issuer);
  const loginOrigin = exactHttpsOrigin(managedLoginOrigin, "Cognito managed login origin");
  if (!userPoolId || !clientId || !cognito?.send || !crypto) throw new Error("Cognito OIDC configuration is incomplete");
  const jwks = createRemoteJWKSet(new URL(`${trustedIssuer}/.well-known/jwks.json`), {
    timeoutDuration: 3000,
    cooldownDuration: 30000,
    cacheMaxAge: 10 * 60 * 1000,
  });
  const verify = jwtVerifier || ((token) => jwtVerify(token, jwks, {
    issuer: trustedIssuer,
    audience: clientId,
    algorithms: ["RS256"],
    clockTolerance: 5,
  }));

  async function decryptTokenSet(ciphertext) {
    const wrapper = tokenWrapper(ciphertext);
    return crypto.decrypt(wrapper.envelope, { purpose: "oidc-token-set", resourceId: wrapper.resourceId });
  }

  async function revokeNow(ciphertext) {
    const tokens = await decryptTokenSet(ciphertext);
    const operations = [];
    if (tokens.refreshToken) operations.push(cognito.send(new RevokeTokenCommand({ Token: tokens.refreshToken, ClientId: clientId })));
    if (tokens.accessToken) operations.push(cognito.send(new GlobalSignOutCommand({ AccessToken: tokens.accessToken })));
    const results = await Promise.allSettled(operations);
    const errors = results.filter(({ status }) => status === "rejected").map(({ reason }) => reason);
    if (errors.length) throw new AggregateError(errors, "Cognito token revocation failed");
  }

  return Object.freeze({
    async authorizationUrl({ state, nonce, codeChallenge, redirectUri, prompt, maxAge }) {
      const url = new URL("/oauth2/authorize", loginOrigin);
      url.searchParams.set("response_type", "code");
      url.searchParams.set("client_id", clientId);
      url.searchParams.set("redirect_uri", redirectUri);
      url.searchParams.set("scope", "openid profile email aws.cognito.signin.user.admin");
      url.searchParams.set("state", state);
      url.searchParams.set("nonce", nonce);
      url.searchParams.set("code_challenge", codeChallenge);
      url.searchParams.set("code_challenge_method", "S256");
      if (prompt) url.searchParams.set("prompt", prompt);
      if (maxAge != null) url.searchParams.set("max_age", String(maxAge));
      return url.toString();
    },

    logoutUrl(returnTo) {
      const url = new URL("/logout", loginOrigin);
      url.searchParams.set("client_id", clientId);
      url.searchParams.set("logout_uri", returnTo);
      return url.toString();
    },

    async exchangeCode({ code, verifier, nonce, redirectUri }) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 5000);
      let tokens;
      try {
        const response = await fetchImpl(new URL("/oauth2/token", loginOrigin), {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
          body: new URLSearchParams({
            grant_type: "authorization_code",
            client_id: clientId,
            code,
            redirect_uri: redirectUri,
            code_verifier: verifier,
          }),
          signal: controller.signal,
        });
        tokens = await responseJson(response);
      } finally {
        clearTimeout(timer);
      }
      if (typeof tokens.id_token !== "string" || typeof tokens.access_token !== "string" || typeof tokens.refresh_token !== "string"
        || String(tokens.token_type).toLowerCase() !== "bearer") {
        throw new Error("Cognito token response is incomplete");
      }
      const verified = await verify(tokens.id_token);
      const claims = verified.payload || verified;
      if (claims.token_use !== "id" || claims.iss !== trustedIssuer || claims.aud !== clientId || !claims.sub || !equal(claims.nonce, nonce)) {
        throw new Error("Cognito ID token claims are invalid");
      }
      if (claims.at_hash && !equal(claims.at_hash, atHash(tokens.access_token))) throw new Error("Cognito access token hash is invalid");
      const authTime = Number(claims.auth_time);
      const nowSeconds = Math.floor(clock() / 1000);
      if (!Number.isSafeInteger(authTime) || authTime > nowSeconds + 5 || nowSeconds - authTime > 3600) {
        throw new Error("Cognito authentication time is invalid");
      }

      const factors = await cognito.send(new GetUserAuthFactorsCommand({ AccessToken: tokens.access_token }));
      const hasPasskey = factors.ConfiguredUserAuthFactors?.includes("WEB_AUTHN");
      const hasTotp = factors.UserMFASettingList?.includes("SOFTWARE_TOKEN_MFA");
      if (!hasPasskey && !hasTotp) throw new Error("Cognito user has no approved MFA factor");

      const tokenFamilyId = claims.origin_jti || claims.jti;
      if (typeof tokenFamilyId !== "string" || !tokenFamilyId) throw new Error("Cognito token revocation identifier is missing");
      const resourceId = `${claims.sub}:${tokenFamilyId}`;
      const envelope = await crypto.encrypt({
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token,
        originJti: claims.origin_jti,
        subject: claims.sub,
      }, { purpose: "oidc-token-set", resourceId });
      return {
        issuer: trustedIssuer,
        subject: claims.sub,
        authenticationLevel: "mfa",
        authenticatedAt: new Date(authTime * 1000).toISOString(),
        tokenSetCiphertext: JSON.stringify({ version: 1, resourceId, envelope }),
      };
    },

    async revoke(ciphertext) {
      try {
        await revokeNow(ciphertext);
      } catch (error) {
        if (!enqueueRevocation) throw error;
        await enqueueRevocation({ type: "cognito_token_revoke", tokenSetCiphertext: ciphertext });
      }
    },

    revokeNow,

    async adminGlobalSignOut(username) {
      if (typeof username !== "string" || !username) throw new Error("Cognito username is required");
      await cognito.send(new AdminUserGlobalSignOutCommand({ UserPoolId: userPoolId, Username: username }));
    },

    async startupCheck({ redirectUri, logoutUri }) {
      const [pool, client] = await Promise.all([
        cognito.send(new GetUserPoolMfaConfigCommand({ UserPoolId: userPoolId })),
        cognito.send(new DescribeUserPoolClientCommand({ UserPoolId: userPoolId, ClientId: clientId })),
      ]);
      if (pool.MfaConfiguration !== "ON" || pool.SoftwareTokenMfaConfiguration?.Enabled !== true) {
        throw new Error("Cognito MFA must be ON with TOTP enabled");
      }
      if (pool.WebAuthnConfiguration?.UserVerification !== "required"
        || pool.WebAuthnConfiguration?.FactorConfiguration !== "MULTI_FACTOR_WITH_USER_VERIFICATION") {
        throw new Error("Cognito WebAuthn must require user verification as an MFA factor");
      }
      const configured = client.UserPoolClient;
      if (!configured?.AllowedOAuthFlows?.includes("code") || configured.GenerateSecret) throw new Error("Cognito app client must use public authorization code flow");
      const requiredScopes = ["openid", "profile", "email", "aws.cognito.signin.user.admin"];
      if (!requiredScopes.every((scope) => configured.AllowedOAuthScopes?.includes(scope)) || configured.EnableTokenRevocation !== true) {
        throw new Error("Cognito app client must enable required scopes and token revocation");
      }
      if (!configured.CallbackURLs?.includes(redirectUri) || !configured.LogoutURLs?.includes(logoutUri)) throw new Error("Cognito callback or logout URL is not registered");
      return true;
    },
  });
}

export const cognitoOidcInternals = Object.freeze({ atHash, tokenWrapper });
