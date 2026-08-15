#!/usr/bin/env bash
set -euo pipefail

export PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
PGHOST="${DB_HOST}"
PGPORT="${DB_PORT}"
PGDATABASE="${DB_NAME}"
PGUSER="${DB_USER}"
PGPASSWORD="${DB_PASSWORD}"

psql -X -v ON_ERROR_STOP=1 -v DB_ADMIN_USER="${PGUSER}" -f /isas/bootstrap-roles.sql

psql -X -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS public.isas_schema_migration (
  version text PRIMARY KEY,
  checksum_sha256 text NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON public.isas_schema_migration FROM PUBLIC, app_user, auth_role;
SQL

for migration in /isas/migrations/[0-9][0-9][0-9][0-9]_*.sql; do
  version="$(basename "${migration}" .sql)"
  checksum="$(sha256sum "${migration}" | awk '{print $1}')"
  applied_checksum="$(psql -X -Atq -v ON_ERROR_STOP=1 \
    -c "SELECT checksum_sha256 FROM public.isas_schema_migration WHERE version = '${version}'" || true)"
  if [[ -n "${applied_checksum}" ]]; then
    if [[ "${applied_checksum}" != "${checksum}" ]]; then
      echo "Migration checksum mismatch for ${version}" >&2
      exit 65
    fi
    echo "Already applied ${version}"
    continue
  fi
  echo "Applying ${version}"
  psql -X -v ON_ERROR_STOP=1 -f "${migration}"
  psql -X -v ON_ERROR_STOP=1 \
    -c "INSERT INTO public.isas_schema_migration(version, checksum_sha256) VALUES ('${version}', '${checksum}')"
done

echo "Verifying 0000_auth_context_v1_verify.sql (transaction rolls back fixtures)"
psql -X -v ON_ERROR_STOP=1 -f /isas/migrations/verify/0000_auth_context_v1_verify.sql

if [[ "${RUN_DESTRUCTIVE_FIXTURE_VERIFICATION:-false}" == "true" ]]; then
  if [[ "${ALLOW_DISPOSABLE_DATABASE:-false}" != "true" ]]; then
    echo "Full fixture verification requires ALLOW_DISPOSABLE_DATABASE=true" >&2
    exit 64
  fi
  for verification in /isas/migrations/verify/[0-9][0-9][0-9][0-9]_*.sql; do
    [[ "$(basename "${verification}")" == 0000_* ]] && continue
    echo "Disposable DB verification: $(basename "${verification}")"
    psql -X -v ON_ERROR_STOP=1 -f "${verification}"
  done
fi

echo "Verifying production AuthContext ownership, FORCE RLS, and audit triggers"
psql -X -v ON_ERROR_STOP=1 -v EXPECTED_POSTGIS_VERSION="${EXPECTED_POSTGIS_VERSION:-3.4.6}" \
  -f /isas/migrations/verify/production_auth_context_security.sql

unset PGPASSWORD DB_PASSWORD
echo "ISAS migrations and production security checks: PASS"
