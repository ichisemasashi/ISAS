export type AccessMode = "online" | "offline-write" | "offline-read" | "locked";

export type AuthenticatedUser = {
  id: string;
  displayName: string;
  initials: string;
  authenticationLevel: "single-factor" | "mfa" | "phishing-resistant";
};

export type TenantOption = {
  id: string;
  name: string;
  roleLabel: string;
};

export type RequestContext = {
  contextId: string;
  tenantId: string;
  tenantName: string;
  roleLabel: string;
  membershipVersion: string;
  authorizationSnapshotId: string;
  expiresAt: string;
};

export type AuthBootstrap = {
  user: AuthenticatedUser;
  tenants: TenantOption[];
  csrfToken: string;
  accessMode: AccessMode;
  accessModeExpiresAt?: string;
};

export type AppAuthorization = {
  user: AuthenticatedUser;
  context: RequestContext;
  accessMode: AccessMode;
  accessModeExpiresAt?: string;
};

export interface AuthGateway {
  bootstrap(signal?: AbortSignal): Promise<AuthBootstrap | null>;
  createContext(tenantId: string, csrfToken: string, signal?: AbortSignal): Promise<RequestContext>;
  login(returnTo: string): void;
  logout(csrfToken: string): Promise<void>;
}

type FetchLike = typeof fetch;

function sameOriginReturnTo(value: string, origin: string): string {
  const target = new URL(value, origin);
  if (target.origin !== origin) return "/";
  return `${target.pathname}${target.search}${target.hash}`;
}

async function readJson(response: Response): Promise<unknown> {
  if (!response.ok) throw new Error(`BFF request failed (${response.status})`);
  return response.json();
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.length === 0) throw new Error(`Invalid BFF response: ${field}`);
  return value;
}

function parseBootstrap(value: unknown): AuthBootstrap {
  if (!isRecord(value) || !isRecord(value.user) || !Array.isArray(value.tenants)) throw new Error("Invalid BFF session response");
  const mode = value.accessMode;
  if (mode !== "online" && mode !== "offline-write" && mode !== "offline-read" && mode !== "locked") throw new Error("Invalid BFF access mode");
  const level = value.user.authenticationLevel;
  if (level !== "single-factor" && level !== "mfa" && level !== "phishing-resistant") throw new Error("Invalid authentication level");
  return {
    user: {
      id: requireString(value.user.id, "user.id"),
      displayName: requireString(value.user.displayName, "user.displayName"),
      initials: requireString(value.user.initials, "user.initials"),
      authenticationLevel: level,
    },
    tenants: value.tenants.map((tenant, index) => {
      if (!isRecord(tenant)) throw new Error(`Invalid BFF response: tenants[${index}]`);
      return {
        id: requireString(tenant.id, `tenants[${index}].id`),
        name: requireString(tenant.name, `tenants[${index}].name`),
        roleLabel: requireString(tenant.roleLabel, `tenants[${index}].roleLabel`),
      };
    }),
    csrfToken: requireString(value.csrfToken, "csrfToken"),
    accessMode: mode,
    accessModeExpiresAt: typeof value.accessModeExpiresAt === "string" ? value.accessModeExpiresAt : undefined,
  };
}

function parseContext(value: unknown): RequestContext {
  if (!isRecord(value)) throw new Error("Invalid BFF context response");
  return {
    contextId: requireString(value.contextId, "contextId"),
    tenantId: requireString(value.tenantId, "tenantId"),
    tenantName: requireString(value.tenantName, "tenantName"),
    roleLabel: requireString(value.roleLabel, "roleLabel"),
    membershipVersion: requireString(value.membershipVersion, "membershipVersion"),
    authorizationSnapshotId: requireString(value.authorizationSnapshotId, "authorizationSnapshotId"),
    expiresAt: requireString(value.expiresAt, "expiresAt"),
  };
}

export function createBffAuthGateway(fetcher: FetchLike = fetch, navigation: Pick<Location, "assign" | "origin"> = window.location): AuthGateway {
  return {
    async bootstrap(signal) {
      const response = await fetcher("/api/bff/session", {
        method: "GET",
        credentials: "include",
        cache: "no-store",
        headers: { Accept: "application/json" },
        signal,
      });
      if (response.status === 401) return null;
      return parseBootstrap(await readJson(response));
    },
    async createContext(tenantId, csrfToken, signal) {
      const response = await fetcher("/api/bff/contexts", {
        method: "POST",
        credentials: "include",
        cache: "no-store",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "X-CSRF-Token": csrfToken,
        },
        body: JSON.stringify({ tenantId }),
        signal,
      });
      return parseContext(await readJson(response));
    },
    login(returnTo) {
      const safeReturnTo = sameOriginReturnTo(returnTo, navigation.origin);
      navigation.assign(`/api/bff/login?return_to=${encodeURIComponent(safeReturnTo)}`);
    },
    async logout(csrfToken) {
      const response = await fetcher("/api/bff/logout", {
        method: "POST",
        credentials: "include",
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken },
        body: "{}",
      });
      if (!response.ok) throw new Error(`Logout failed (${response.status})`);
    },
  };
}

const demoTenant: TenantOption = { id: "tenant-yamagata-midori", name: "山形みどり農園", roleLabel: "現場チーム" };

export const demoAuthorization: AppAuthorization = {
  user: { id: "user-sato", displayName: "佐藤 一郎", initials: "佐", authenticationLevel: "phishing-resistant" },
  context: {
    contextId: "ctx-demo-current-tab",
    tenantId: demoTenant.id,
    tenantName: demoTenant.name,
    roleLabel: demoTenant.roleLabel,
    membershipVersion: "membership-demo-v1",
    authorizationSnapshotId: "snapshot-demo-v1",
    expiresAt: "2026-08-14T12:00:00+09:00",
  },
  accessMode: "online",
};

export const demoAuthGateway: AuthGateway = {
  async bootstrap() {
    return { user: demoAuthorization.user, tenants: [demoTenant], csrfToken: "demo-csrf-not-for-production", accessMode: "online" };
  },
  async createContext() { return demoAuthorization.context; },
  login() { /* The development fixture is already authenticated. */ },
  async logout() { /* The development fixture has no server session. */ },
};
