import { createMvpApiHandler } from "./api-handler.mjs";
import { createBffHandler, createContextResolver } from "./handler.mjs";
import { createPostgresAuthContextAdapter } from "./postgres-auth-context.mjs";
import { createPostgresMvpRepository } from "./postgres-mvp-repository.mjs";

export function createApplicationRouter({ bffHandler, apiHandler }) {
  return (request) => {
    const path = new URL(request.url).pathname;
    if (path.startsWith("/api/bff/")) return bffHandler(request);
    if (path.startsWith("/api/v1/")) return apiHandler(request);
    return new Response(JSON.stringify({ type: "not_found", status: 404 }), { status: 404, headers: { "Content-Type": "application/problem+json" } });
  };
}

export function createIsasApplication({ origin, redirectUri, stores, identityProvider, users, authorization, pool, clock }) {
  const bffOptions = { origin, redirectUri, stores, identityProvider, users, authorization, ...(clock ? { clock } : {}) };
  const bffHandler = createBffHandler(bffOptions);
  const resolveContext = createContextResolver({ stores, authorization, ...(clock ? { clock } : {}) });
  const database = createPostgresAuthContextAdapter(pool);
  const repository = createPostgresMvpRepository();
  const apiHandler = createMvpApiHandler({ origin, resolveContext, database, repository });
  return createApplicationRouter({ bffHandler, apiHandler });
}
