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
    secured.headers.set("Permissions-Policy", "camera=(), geolocation=(), microphone=()");
    secured.headers.set("Referrer-Policy", "no-referrer");
    secured.headers.set("X-Content-Type-Options", "nosniff");
    secured.headers.set("X-Frame-Options", "DENY");
    return secured;
  };
}

export function createIsasApplication({ origin, redirectUri, stores, identityProvider, users, authorization, securityAdministration, pool, clock }) {
  const bffOptions = { origin, redirectUri, stores, identityProvider, users, authorization, ...(clock ? { clock } : {}) };
  const bffHandler = createBffHandler(bffOptions);
  const resolveContext = createContextResolver({ stores, authorization, ...(clock ? { clock } : {}) });
  const database = createPostgresAuthContextAdapter(pool);
  const repository = createPostgresMvpRepository();
  const apiHandler = createMvpApiHandler({ origin, resolveContext, database, repository, securityAdministration, ...(clock ? { clock } : {}) });
  return createApplicationRouter({ bffHandler, apiHandler });
}
