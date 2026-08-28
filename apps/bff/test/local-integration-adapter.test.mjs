import assert from "node:assert/strict";
import test from "node:test";
import { validateLocalEnvironment } from "../runtime-adapters/local-integration.mjs";

function fixture(overrides = {}) {
  return {
    config: { deploymentProfile: "local-integration", origin: "https://isas.localhost:8443" },
    env: {
      KEYCLOAK_ISSUER: "https://isas.localhost:8443/oidc/realms/isas-local",
      ISAS_LOCAL_RUNTIME_ROOT: "/Users/test/ISAS/.local",
      LOCAL_OBJECT_ROOT: "/Users/test/ISAS/.local/objects",
      LOCAL_SESSION_KEY_FILE: "/Users/test/ISAS/.local/secrets/session.key",
      LOCAL_OBJECT_KEY_FILE: "/Users/test/ISAS/.local/secrets/object.key",
      LOCAL_OFFLINE_RECOVERY_KEY_FILE: "/Users/test/ISAS/.local/secrets/offline-recovery.key"
    },
    ...overrides
  };
}

test("local adapter accepts only isolated endpoints and mounts", () => {
  assert.equal(validateLocalEnvironment(fixture()), true);
  const invalid = fixture(); invalid.env = { ...invalid.env, LOCAL_OBJECT_ROOT: "/tmp/objects" };
  assert.throws(() => validateLocalEnvironment(invalid), /isolated runtime root/);
});

test("local adapter cannot run in production profile", () => {
  const invalid = fixture(); invalid.config = { ...invalid.config, deploymentProfile: "production" };
  assert.throws(() => validateLocalEnvironment(invalid), /cannot run outside/);
});
