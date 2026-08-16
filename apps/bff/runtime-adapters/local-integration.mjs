import { createKeycloakOidc } from "../src/keycloak-oidc.mjs";
import { createLocalEnvelopeCrypto, readLocalKey } from "../src/local-envelope-crypto.mjs";
import { createLocalMapStorage } from "../src/local-map-storage.mjs";
import { createLocalObjectStorage } from "../src/local-object-storage.mjs";
import { createLocalRevocationService } from "../src/local-revocation-service.mjs";
import { createPostgresIdentityAdapters } from "../src/postgres-identity.mjs";
import { createPostgresLocalStores } from "../src/postgres-local-stores.mjs";
import { createPostgresSecurityAdministration } from "../src/security-administration.mjs";

function required(env, name) {
  const value = env[name];
  if (!value || value.includes("\0")) throw new Error(`${name} is required`);
  return value;
}

export function validateLocalEnvironment({ config, env }) {
  if (config.deploymentProfile !== "local-integration" || config.origin !== "https://isas.localhost:8443") throw new Error("local adapter cannot run outside local-integration");
  if (env.KEYCLOAK_ISSUER !== "https://isas.localhost:8443/oidc/realms/isas-local") throw new Error("Keycloak issuer is outside the local allowlist");
  if (env.LOCAL_OBJECT_ROOT !== "/var/lib/isas/objects") throw new Error("local object root is outside the isolated volume");
  for (const name of ["LOCAL_SESSION_KEY_FILE", "LOCAL_OBJECT_KEY_FILE", "LOCAL_OFFLINE_RECOVERY_KEY_FILE"]) {
    if (!required(env, name).startsWith("/run/isas/secrets/")) throw new Error(`${name} is outside the secret mount`);
  }
  return true;
}

export async function createRuntimeAdapters({ config, pools, logger, env = process.env }) {
  validateLocalEnvironment({ config, env });
  const sessionCrypto = createLocalEnvelopeCrypto({ key: readLocalKey(env.LOCAL_SESSION_KEY_FILE) });
  const objectCrypto = createLocalEnvelopeCrypto({ key: readLocalKey(env.LOCAL_OBJECT_KEY_FILE) });
  const stores = createPostgresLocalStores({ pool: pools.ops, crypto: sessionCrypto });
  const postgres = createPostgresIdentityAdapters({ pool: pools.authP1, jurisdiction: "jp", shardId: config.deploymentId, pseudonymKey: required(env, "ACTOR_PSEUDONYM_KEY") });
  const securityAdministration = createPostgresSecurityAdministration({ pool: pools.authP1 });
  const attachmentStorage = createLocalObjectStorage({ root: env.LOCAL_OBJECT_ROOT, crypto: objectCrypto, origin: config.origin });
  const mapStorage = createLocalMapStorage({ root: env.LOCAL_OBJECT_ROOT });
  const identityProvider = createKeycloakOidc({ issuer: env.KEYCLOAK_ISSUER, clientId: required(env, "KEYCLOAK_CLIENT_ID"), clientSecret: required(env, "KEYCLOAK_CLIENT_SECRET"), crypto: sessionCrypto });
  const revocations = createLocalRevocationService({ pool: pools.ops, outbox: postgres.revocationOutbox, stores, identityProvider, crypto: sessionCrypto, logger });
  let lastReadinessAt = 0;
  async function dependenciesCheck() {
    await Promise.all([stores.startupCheck(), revocations.startupCheck(), identityProvider.startupCheck(), attachmentStorage.startupCheck(), mapStorage.startupCheck()]);
    lastReadinessAt = Date.now();
  }
  return Object.freeze({
    stores: Object.freeze({ loginAttempts: stores.loginAttempts, sessions: stores.sessions, contexts: stores.contexts }),
    identityProvider,
    users: postgres.users,
    authorization: postgres.authorization,
    securityAdministration,
    attachmentStorage,
    mapStorage,
    revocations,
    async startupCheck() { await dependenciesCheck(); revocations.start(); },
    async readinessCheck() { if (Date.now() - lastReadinessAt >= 30000) await dependenciesCheck(); },
    async close() { await revocations.close(); }
  });
}
