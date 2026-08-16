FROM postgres:16.14-bookworm

COPY apps/bff/migrations /isas/migrations
COPY infra/images/migration/bootstrap-roles.sql /isas/bootstrap-roles.sql
COPY infra/images/migration/run-migrations.sh /isas/run-migrations.sh
COPY infra/images/migration/purge-location.sh /isas/purge-location.sh

RUN chmod 0555 /isas/run-migrations.sh /isas/purge-location.sh \
    && chmod 0444 /isas/bootstrap-roles.sql \
    && find /isas/migrations -type d -exec chmod 0555 {} + \
    && find /isas/migrations -type f -name '*.sql' -exec chmod 0444 {} +

USER 999:999
ENTRYPOINT ["/isas/run-migrations.sh"]
