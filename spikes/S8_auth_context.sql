-- S8 is now a thin executable wrapper around the production migration.
-- The authoritative DDL and assertions live under apps/bff/migrations.
\set ON_ERROR_STOP on

SELECT version();
SELECT extversion AS postgis_version FROM pg_extension WHERE extname = 'postgis';

\ir ../apps/bff/migrations/0000_auth_context_v1.sql
\ir ../apps/bff/migrations/verify/0000_auth_context_v1_verify.sql

\echo ''
\echo 'S8: production AuthContext migration verification PASS'
