#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const dataRoot = process.env.ISAS_NATIVE_DATA_ROOT || resolve(process.env.HOME, "Library/Application Support/ISAS/local-integration");
const secretDir = resolve(dataRoot, "secrets");
const tlsDir = resolve(dataRoot, "tls");
const objectDir = resolve(dataRoot, "objects");
const envFile = resolve(secretDir, "runtime.env");

function secret(bytes = 32) { return randomBytes(bytes).toString("base64url"); }
function base32Secret(bytes = 20) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const value of randomBytes(bytes)) bits += value.toString(2).padStart(8, "0");
  let encoded = "";
  for (let offset = 0; offset < bits.length; offset += 5) encoded += alphabet[Number.parseInt(bits.slice(offset, offset + 5).padEnd(5, "0"), 2)];
  return encoded;
}
function writeSecretFile(path, value) {
  writeFileSync(path, `${value}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  chmodSync(path, 0o600);
}

for (const path of [secretDir, tlsDir, objectDir]) mkdirSync(path, { recursive: true, mode: 0o700 });
chmodSync(secretDir, 0o700);

if (!existsSync(envFile)) {
  const values = {
    POSTGRES_PASSWORD: secret(),
    KEYCLOAK_ADMIN: "local-admin",
    KEYCLOAK_ADMIN_PASSWORD: secret(),
    KEYCLOAK_DB_PASSWORD: secret(),
    KEYCLOAK_CLIENT_SECRET: secret(),
    LOCAL_OPERATOR_PASSWORD: secret(18),
    LOCAL_OPERATOR_TOTP_SECRET: base32Secret(),
    ISAS_DB_P0_PASSWORD: secret(),
    ISAS_DB_AUTH_P1_PASSWORD: secret(),
    ISAS_DB_P1_PASSWORD: secret(),
    ISAS_DB_P2_PASSWORD: secret(),
    ISAS_DB_OPS_PASSWORD: secret(),
    ACTOR_PSEUDONYM_KEY: secret(48),
    LOCAL_SESSION_KEY_FILE: resolve(secretDir, "session.key"),
    LOCAL_OBJECT_KEY_FILE: resolve(secretDir, "object.key"),
    LOCAL_OFFLINE_RECOVERY_KEY_FILE: resolve(secretDir, "offline-recovery.key"),
    KEYCLOAK_ISSUER: "https://isas.localhost:8443/oidc/realms/isas-local",
    KEYCLOAK_CLIENT_ID: "isas-bff"
  };
  writeFileSync(envFile, `${Object.entries(values).map(([key, value]) => `${key}=${value}`).join("\n")}\n`, { mode: 0o600, flag: "wx" });
  chmodSync(envFile, 0o600);
  writeSecretFile(resolve(secretDir, "session.key"), secret());
  writeSecretFile(resolve(secretDir, "object.key"), secret());
  writeSecretFile(resolve(secretDir, "offline-recovery.key"), secret());
}

const existingEnvironment = readFileSync(envFile, "utf8");
if (!/^LOCAL_OPERATOR_PASSWORD=/m.test(existingEnvironment)) appendFileSync(envFile, `LOCAL_OPERATOR_PASSWORD=${secret(18)}\n`, { mode: 0o600 });
if (!/^LOCAL_OPERATOR_TOTP_SECRET=/m.test(existingEnvironment)) appendFileSync(envFile, `LOCAL_OPERATOR_TOTP_SECRET=${base32Secret()}\n`, { mode: 0o600 });

const certificate = resolve(tlsDir, "isas.localhost.pem");
const certificateKey = resolve(tlsDir, "isas.localhost-key.pem");
const rootCa = resolve(tlsDir, "rootCA.pem");
if (!existsSync(certificate) || !existsSync(certificateKey) || !existsSync(rootCa)) {
  let caRoot;
  try { caRoot = execFileSync("mkcert", ["-CAROOT"], { encoding: "utf8" }).trim(); }
  catch { throw new Error("mkcertが必要です。Homebrewで `brew install mkcert` と `mkcert -install` を実行してください"); }
  execFileSync("mkcert", ["-cert-file", certificate, "-key-file", certificateKey, "isas.localhost", "127.0.0.1", "::1"], { stdio: "inherit" });
  writeFileSync(rootCa, execFileSync("/bin/cat", [resolve(caRoot, "rootCA.pem")]), { mode: 0o600 });
  chmodSync(certificateKey, 0o600);
  chmodSync(rootCa, 0o600);
}

process.stdout.write("local bootstrap: ready (secret values are not displayed)\n");
