#!/usr/bin/env node
import { chmodSync, copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, symlinkSync, unlinkSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");
const dataRoot = process.env.ISAS_NATIVE_DATA_ROOT || resolve(process.env.HOME, "Library/Application Support/ISAS/local-integration");
const stateRoot = resolve(dataRoot, "state");
const env = Object.fromEntries(readFileSync(resolve(dataRoot, "secrets/runtime.env"), "utf8").split(/\r?\n/).filter(Boolean).map((line) => {
  const offset = line.indexOf("=");
  return [line.slice(0, offset), line.slice(offset + 1)];
}));

for (const name of ["ISAS_DB_P0_PASSWORD", "ISAS_DB_AUTH_P1_PASSWORD", "ISAS_DB_P1_PASSWORD", "ISAS_DB_P2_PASSWORD", "ISAS_DB_OPS_PASSWORD"]) {
  if (!env[name]) throw new Error(`${name} is missing`);
}

const pools = [
  ["p0", 6430, "p0_user", env.ISAS_DB_P0_PASSWORD, 8, 40],
  ["auth-p1", 6431, "auth_role", env.ISAS_DB_AUTH_P1_PASSWORD, 12, 60],
  ["p1", 6432, "app_user", env.ISAS_DB_P1_PASSWORD, 16, 80],
  ["p2", 6433, "p2_user", env.ISAS_DB_P2_PASSWORD, 8, 40],
  ["ops", 6434, "ops_user", env.ISAS_DB_OPS_PASSWORD, 4, 20],
];
const poolRoot = resolve(stateRoot, "pgbouncer");
mkdirSync(poolRoot, { recursive: true, mode: 0o700 });
for (const [name, port, role, password, size, clients] of pools) {
  const auth = resolve(poolRoot, `${name}.users`);
  writeFileSync(auth, `"${role}" "${password}"\n`, { mode: 0o600 });
  chmodSync(auth, 0o600);
  const config = `[databases]
isas = host=127.0.0.1 port=55433 dbname=isas user=${role} password=${password}

[pgbouncer]
listen_addr = 127.0.0.1
listen_port = ${port}
unix_socket_dir = ${poolRoot}
auth_type = scram-sha-256
auth_file = ${auth}
pool_mode = transaction
max_client_conn = ${clients}
default_pool_size = ${size}
server_reset_query = DISCARD ALL
ignore_startup_parameters = statement_timeout
pidfile = ${resolve(poolRoot, `${name}.pid`)}
  logfile = ${resolve(dataRoot, `log/pgbouncer-${name}.internal.log`)}
`;
  writeFileSync(resolve(poolRoot, `${name}.ini`), config, { mode: 0o600 });
}

const webRoot = resolve(dataRoot, "artifacts/current/apps/web/dist");
const tlsRoot = resolve(dataRoot, "tls");
const caddyRuntime = resolve("/tmp", `isas-local-caddy-${process.getuid()}`);
mkdirSync(caddyRuntime, { recursive: true, mode: 0o700 });
function replaceLink(name, target) {
  const path = resolve(caddyRuntime, name);
  if (existsSync(path)) {
    if (!lstatSync(path).isSymbolicLink()) throw new Error(`refusing to replace non-symlink Caddy runtime path: ${path}`);
    unlinkSync(path);
  }
  symlinkSync(target, path);
}
replaceLink("certificate.pem", resolve(tlsRoot, "isas.localhost.pem"));
replaceLink("certificate-key.pem", resolve(tlsRoot, "isas.localhost-key.pem"));
replaceLink("web", webRoot);
const caddy = `{
  admin off
  auto_https off
}

https://isas.localhost:8443 {
  bind 127.0.0.1
  tls ${resolve(caddyRuntime, "certificate.pem")} ${resolve(caddyRuntime, "certificate-key.pem")}
  encode zstd gzip
  handle /health/live {
    respond "ok" 200
  }
  handle /health/ready {
    reverse_proxy 127.0.0.1:3000
  }
  handle /oidc/* {
    reverse_proxy 127.0.0.1:18080
  }
  handle /api/* {
    reverse_proxy 127.0.0.1:3000
  }
  handle {
    root * ${resolve(caddyRuntime, "web")}
    try_files {path} /index.html
    file_server
  }
  header {
    -Server
    X-Content-Type-Options nosniff
    X-Frame-Options DENY
    Referrer-Policy no-referrer
    Strict-Transport-Security "max-age=31536000"
    Content-Security-Policy "default-src 'self'; connect-src 'self' https://cyberjapandata.gsi.go.jp; img-src 'self' data: blob: https://cyberjapandata.gsi.go.jp; style-src 'self' 'unsafe-inline'; script-src 'self'; worker-src 'self' blob:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    Cross-Origin-Resource-Policy same-origin
    Permissions-Policy "camera=(self), geolocation=(self), microphone=()"
  }
}
`;
writeFileSync(resolve(stateRoot, "Caddyfile"), caddy, { mode: 0o600 });

const keycloakImport = resolve(dataRoot, "components/keycloak/data/import");
mkdirSync(keycloakImport, { recursive: true, mode: 0o700 });
copyFileSync(resolve(root, "infra/local/keycloak/isas-local-realm.json"), resolve(keycloakImport, "isas-local-realm.json"));
process.stdout.write("native local configuration: five pools, edge, and Keycloak import ready\n");
