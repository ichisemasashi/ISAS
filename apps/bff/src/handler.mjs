import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

const SESSION_COOKIE = "__Host-isas_session";
const LOGIN_TTL_MS = 10 * 60 * 1000;
const SESSION_IDLE_MS = 12 * 60 * 60 * 1000;
const SESSION_ABSOLUTE_MS = 7 * 24 * 60 * 60 * 1000;
const CONTEXT_TTL_MS = 5 * 60 * 1000;
const MAX_JSON_BYTES = 8 * 1024;

function opaque(bytes = 32) {
  return randomBytes(bytes).toString("base64url");
}

function digest(value) {
  return createHash("sha256").update(value).digest("base64url");
}

function equalSecret(left, right) {
  const a = Buffer.from(left || "");
  const b = Buffer.from(right || "");
  return a.length === b.length && a.length > 0 && timingSafeEqual(a, b);
}

function parseCookies(header) {
  const result = new Map();
  for (const part of (header || "").split(";")) {
    const separator = part.indexOf("=");
    if (separator < 1) continue;
    result.set(part.slice(0, separator).trim(), part.slice(separator + 1).trim());
  }
  return result;
}

function safeReturnTo(candidate, origin) {
  try {
    const target = new URL(candidate || "/", origin);
    return target.origin === origin ? `${target.pathname}${target.search}${target.hash}` : "/";
  } catch {
    return "/";
  }
}

function noStoreHeaders(extra = {}) {
  return { "Cache-Control": "no-store", Pragma: "no-cache", ...extra };
}

function json(status, body, correlationId, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: noStoreHeaders({
      "Content-Type": "application/json; charset=utf-8",
      "X-Correlation-ID": correlationId,
      ...extraHeaders,
    }),
  });
}

function redirect(location, correlationId, extraHeaders = {}) {
  return new Response(null, {
    status: 302,
    headers: noStoreHeaders({ Location: location, "X-Correlation-ID": correlationId, ...extraHeaders }),
  });
}

function sessionCookie(value, maxAge = SESSION_ABSOLUTE_MS / 1000) {
  return `${SESSION_COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${maxAge}`;
}

function clearSessionCookie() {
  return `${SESSION_COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0`;
}

function validUnsafeRequest(request, origin) {
  if (request.headers.get("Origin") !== origin) return false;
  const fetchSite = request.headers.get("Sec-Fetch-Site");
  return !fetchSite || fetchSite === "same-origin";
}

async function readSmallJson(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") || "")) throw new Error("invalid content type");
  const declaredLength = Number(request.headers.get("Content-Length") || 0);
  if (declaredLength > MAX_JSON_BYTES) throw new Error("request too large");
  const text = await request.text();
  if (Buffer.byteLength(text) > MAX_JSON_BYTES) throw new Error("request too large");
  const value = JSON.parse(text || "{}");
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("invalid JSON object");
  return value;
}

function authenticationLevel(value) {
  if (value === "single-factor" || value === "mfa" || value === "phishing-resistant") return value;
  throw new Error("identity provider returned an unsupported authentication level");
}

export function createBffHandler({ origin, redirectUri, stores, identityProvider, users, authorization, clock = () => Date.now() }) {
  if (!origin || new URL(origin).origin !== origin) throw new Error("origin must be an exact URL origin");
  if (new URL(redirectUri).origin !== origin) throw new Error("redirectUri must use the BFF origin");

  async function authenticatedSession(request) {
    const rawSessionId = parseCookies(request.headers.get("Cookie")).get(SESSION_COOKIE);
    if (!rawSessionId) return null;
    const sessionHash = digest(rawSessionId);
    const session = await stores.sessions.get(sessionHash);
    if (!session) return null;
    const now = clock();
    if (now - session.lastSeenAt >= SESSION_IDLE_MS || now >= session.expiresAt) {
      await stores.sessions.delete(sessionHash);
      await stores.contexts.deleteForSession(sessionHash);
      return null;
    }
    await stores.sessions.touch(sessionHash, now);
    return { sessionHash, session };
  }

  return async function handle(request) {
    const correlationId = opaque(12);
    const url = new URL(request.url);
    if (url.origin !== origin || !url.pathname.startsWith("/api/bff/")) return json(404, { error: "not_found" }, correlationId);

    try {
      if (request.method === "GET" && url.pathname === "/api/bff/login") {
        const state = opaque();
        const nonce = opaque();
        const verifier = opaque(48);
        const challenge = digest(verifier);
        await stores.loginAttempts.put(digest(state), {
          nonce,
          verifier,
          returnTo: safeReturnTo(url.searchParams.get("return_to"), origin),
          expiresAt: clock() + LOGIN_TTL_MS,
        });
        const location = await identityProvider.authorizationUrl({ state, nonce, codeChallenge: challenge, redirectUri });
        return redirect(location, correlationId);
      }

      if (request.method === "GET" && url.pathname === "/api/bff/callback") {
        const state = url.searchParams.get("state") || "";
        const code = url.searchParams.get("code") || "";
        const attempt = state ? await stores.loginAttempts.take(digest(state)) : null;
        if (!attempt || !code || attempt.expiresAt <= clock()) return json(400, { error: "authentication_failed" }, correlationId);

        const identity = await identityProvider.exchangeCode({ code, verifier: attempt.verifier, nonce: attempt.nonce, redirectUri });
        const user = await users.resolve(identity.issuer, identity.subject);
        if (!user) return json(403, { error: "authentication_failed" }, correlationId);

        const rawSessionId = opaque();
        const sessionHash = digest(rawSessionId);
        const now = clock();
        await stores.sessions.put(sessionHash, {
          user,
          authenticationLevel: authenticationLevel(identity.authenticationLevel),
          authenticatedAt: identity.authenticatedAt,
          csrfToken: opaque(),
          tokenSetCiphertext: identity.tokenSetCiphertext,
          createdAt: now,
          lastSeenAt: now,
          expiresAt: now + SESSION_ABSOLUTE_MS,
        });
        return redirect(attempt.returnTo, correlationId, { "Set-Cookie": sessionCookie(rawSessionId) });
      }

      if (request.method === "GET" && url.pathname === "/api/bff/session") {
        const authenticated = await authenticatedSession(request);
        if (!authenticated) return json(401, { error: "authentication_required" }, correlationId, { "Set-Cookie": clearSessionCookie() });
        const tenants = await authorization.listTenants(authenticated.session.user.id);
        return json(200, {
          user: {
            id: authenticated.session.user.id,
            displayName: authenticated.session.user.displayName,
            initials: authenticated.session.user.initials,
            authenticationLevel: authenticated.session.authenticationLevel,
          },
          tenants,
          csrfToken: authenticated.session.csrfToken,
          accessMode: "online",
        }, correlationId);
      }

      if (request.method === "POST" && url.pathname === "/api/bff/contexts") {
        if (!validUnsafeRequest(request, origin)) return json(403, { error: "request_rejected" }, correlationId);
        const authenticated = await authenticatedSession(request);
        if (!authenticated) return json(401, { error: "authentication_required" }, correlationId);
        if (!equalSecret(request.headers.get("X-CSRF-Token"), authenticated.session.csrfToken)) return json(403, { error: "request_rejected" }, correlationId);
        const body = await readSmallJson(request);
        if (typeof body.tenantId !== "string" || !body.tenantId) return json(400, { error: "invalid_request" }, correlationId);

        const derived = await authorization.deriveContext(authenticated.session.user.id, body.tenantId);
        if (!derived) return json(403, { error: "request_rejected" }, correlationId);
        const contextId = opaque();
        const expiresAt = clock() + CONTEXT_TTL_MS;
        await stores.contexts.put(digest(contextId), {
          sessionHash: authenticated.sessionHash,
          userId: authenticated.session.user.id,
          tenantId: body.tenantId,
          jurisdictionId: derived.jurisdictionId,
          shardId: derived.shardId,
          purpose: "tenant",
          membershipVersion: derived.membershipVersion,
          expiresAt,
        });
        return json(201, {
          contextId,
          tenantId: body.tenantId,
          tenantName: derived.tenantName,
          roleLabel: derived.roleLabel,
          membershipVersion: derived.membershipVersion,
          authorizationSnapshotId: derived.authorizationSnapshotId,
          capabilities: derived.capabilities || [],
          expiresAt: new Date(expiresAt).toISOString(),
        }, correlationId);
      }

      if (request.method === "POST" && url.pathname === "/api/bff/logout") {
        if (!validUnsafeRequest(request, origin)) return json(403, { error: "request_rejected" }, correlationId);
        const authenticated = await authenticatedSession(request);
        if (!authenticated) return new Response(null, { status: 204, headers: noStoreHeaders({ "Set-Cookie": clearSessionCookie(), "X-Correlation-ID": correlationId }) });
        if (!equalSecret(request.headers.get("X-CSRF-Token"), authenticated.session.csrfToken)) return json(403, { error: "request_rejected" }, correlationId);
        await readSmallJson(request);
        await stores.sessions.delete(authenticated.sessionHash);
        await stores.contexts.deleteForSession(authenticated.sessionHash);
        try {
          await identityProvider.revoke?.(authenticated.session.tokenSetCiphertext);
        } catch {
          // The local session remains revoked even when the upstream IdP is unavailable.
        }
        return new Response(null, { status: 204, headers: noStoreHeaders({ "Set-Cookie": clearSessionCookie(), "X-Correlation-ID": correlationId }) });
      }

      return json(404, { error: "not_found" }, correlationId);
    } catch {
      return json(500, { error: "request_failed" }, correlationId);
    }
  };
}

export function createContextResolver({ stores, authorization, clock = () => Date.now() }) {
  return async function resolve(request, expectedPurpose = "tenant") {
    const rawSessionId = parseCookies(request.headers.get("Cookie")).get(SESSION_COOKIE);
    const rawContextId = request.headers.get("X-ISAS-Context");
    if (!rawSessionId || !rawContextId) return null;

    const sessionHash = digest(rawSessionId);
    const contextHash = digest(rawContextId);
    const [session, context] = await Promise.all([
      stores.sessions.get(sessionHash),
      stores.contexts.get(contextHash),
    ]);
    if (!session || !context || context.sessionHash !== sessionHash || context.purpose !== expectedPurpose) return null;

    const now = clock();
    if (now - session.lastSeenAt >= SESSION_IDLE_MS || now >= session.expiresAt || now >= context.expiresAt) {
      if (now - session.lastSeenAt >= SESSION_IDLE_MS || now >= session.expiresAt) {
        await stores.sessions.delete(sessionHash);
        await stores.contexts.deleteForSession(sessionHash);
      } else {
        await stores.contexts.delete(contextHash);
      }
      return null;
    }

    const current = await authorization.deriveContext(session.user.id, context.tenantId);
    if (!current || current.jurisdictionId !== context.jurisdictionId || current.shardId !== context.shardId) return null;
    await stores.sessions.touch(sessionHash, now);

    return {
      sessionHash,
      contextHash,
      userId: session.user.id,
      authenticationLevel: session.authenticationLevel,
      authenticatedAt: session.authenticatedAt,
      jurisdictionId: current.jurisdictionId,
      shardId: current.shardId,
      authorizationSnapshotId: current.authorizationSnapshotId,
      membershipVersion: current.membershipVersion,
      actorPseudonym: current.actorPseudonym,
      csrfToken: session.csrfToken,
      authContext: {
        userId: session.user.id,
        tenantId: context.tenantId,
        allowedTenants: [context.tenantId],
        scopeFieldGroups: current.scopeFieldGroups || [],
        capabilities: current.capabilities || [],
        employerSubjectUsers: [],
      },
    };
  };
}
