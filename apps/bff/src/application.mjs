import { createMvpApiHandler } from "./api-handler.mjs";
import { createBffHandler, createContextResolver } from "./handler.mjs";
import { createPostgresAuthContextAdapter } from "./postgres-auth-context.mjs";
import { createPostgresMvpRepository } from "./postgres-mvp-repository.mjs";

export function createApplicationRouter({ bffHandler, apiHandler }) {
  return async (request) => {
    const path = new URL(request.url).pathname;
    let response;
    if (path.startsWith("/api/bff/")) response = await bffHandler(request);
    else if (path.startsWith("/api/v1/")) response = await apiHandler(request);
    else response = new Response(JSON.stringify({ type: "not_found", status: 404 }), { status: 404, headers: { "Content-Type": "application/problem+json", "Cache-Control": "no-store" } });
    const secured = new Response(response.body, response);
    secured.headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    secured.headers.set("Cross-Origin-Resource-Policy", "same-origin");
    secured.headers.set("Permissions-Policy", "camera=(), geolocation=(self), microphone=()");
    secured.headers.set("Referrer-Policy", "no-referrer");
    secured.headers.set("X-Content-Type-Options", "nosniff");
    secured.headers.set("X-Frame-Options", "DENY");
    return secured;
  };
}

export function createPriorityDatabase(databasePools, { adapterFactory = createPostgresAuthContextAdapter } = {}) {
  if (!databasePools?.p0 || !databasePools?.p1 || !databasePools?.p2) throw new Error("p0, p1 and p2 database pools are required");
  const adapters = Object.fromEntries(["p0", "p1", "p2"].map((poolClass) => {
    const item = databasePools[poolClass];
    if (!item?.pool || !item.expectedRole) throw new Error(`${poolClass} database pool and expected role are required`);
    return [poolClass, adapterFactory(item.pool, { expectedRole: item.expectedRole })];
  }));
  return Object.freeze({
    transaction(trusted, operation, options = {}) {
      const poolClass = options.poolClass || "p1";
      if (!Object.hasOwn(adapters, poolClass)) throw new Error(`unsupported application pool class: ${poolClass}`);
      return adapters[poolClass].transaction(trusted, operation, options);
    },
  });
}

export function createIsasApplication({ origin, redirectUri, stores, identityProvider, users, authorization, securityAdministration, attachmentStorage, mapStorage, pool, databasePools, clock, logger }) {
  const bffOptions = { origin, redirectUri, stores, identityProvider, users, authorization, ...(clock ? { clock } : {}) };
  const bffHandler = createBffHandler(bffOptions);
  const resolveContext = createContextResolver({ stores, authorization, ...(clock ? { clock } : {}) });
  const database = databasePools ? createPriorityDatabase(databasePools) : createPostgresAuthContextAdapter(pool);
  const repository = createPostgresMvpRepository();
  const apiHandler = createMvpApiHandler({ origin, resolveContext, database, repository, securityAdministration, attachmentStorage, mapStorage, logger, ...(clock ? { clock } : {}) });
  return createApplicationRouter({ bffHandler, apiHandler });
}
