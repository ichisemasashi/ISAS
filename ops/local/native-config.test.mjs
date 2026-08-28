import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const service = readFileSync(new URL("./native-service.sh", import.meta.url), "utf8");
const generator = readFileSync(new URL("./generate-native-config.mjs", import.meta.url), "utf8");
const launcher = readFileSync(new URL("./generate-launchd.mjs", import.meta.url), "utf8");

test("HTTPS ingressはloopbackだけへbindする", () => {
  assert.match(generator, /bind 127\.0\.0\.1/);
  assert.match(generator, /https:\/\/isas\.localhost:8443/);
});

test("5つの独立PgBouncer portと優先度roleを生成する", () => {
  for (const token of ["p0_user", "auth_role", "app_user", "p2_user", "ops_user", "6430", "6431", "6432", "6433", "6434"]) assert.match(generator, new RegExp(token));
});

test("秘密値をplistへ埋めずwrapper経由で起動する", () => {
  assert.match(launcher, /native-service\.sh/);
  assert.doesNotMatch(launcher, /POSTGRES_PASSWORD|KEYCLOAK_CLIENT_SECRET/);
  assert.match(service, /\. "\$ISAS_LOCAL_ENV"/);
});
