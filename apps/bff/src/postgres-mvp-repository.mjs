import { randomUUID } from "node:crypto";

const CAPABILITY_BY_KIND = Object.freeze({ journal: "journal:write", pesticide: "pesticide:write", punch: "punch:write" });

function eventTimestamp(occurredAt) {
  const occurred = new Date(occurredAt);
  if (!Number.isFinite(occurred.getTime())) throw new TypeError("invalid occurredAt");
  const received = new Date();
  const earliest = received.getTime() - 400 * 24 * 60 * 60 * 1000;
  const latest = received.getTime() + 24 * 60 * 60 * 1000;
  return {
    eventTs: new Date(Math.max(earliest, Math.min(latest, occurred.getTime()))).toISOString(),
    clockSkewed: occurred.getTime() < earliest || occurred.getTime() > latest,
  };
}

function queueDto(row) {
  return {
    id: row.id,
    bundleId: row.bundle_id,
    eventUuids: row.event_uuids,
    reason: row.reason,
    recoveryAction: row.recovery_action,
    createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : row.created_at,
  };
}

export function createPostgresMvpRepository({ uuid = randomUUID } = {}) {
  return {
    async getToday(client) {
      const result = await client.query(`
        SELECT task_id::text AS id,
               to_char(scheduled_at AT TIME ZONE 'Asia/Tokyo', 'HH24:MI') AS time,
               field_name AS field, crop_name AS crop, work_name AS work, status
        FROM app.task
        WHERE scheduled_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Tokyo') AT TIME ZONE 'Asia/Tokyo'
          AND scheduled_at < (date_trunc('day', now() AT TIME ZONE 'Asia/Tokyo') + interval '1 day') AT TIME ZONE 'Asia/Tokyo'
          AND deleted_at IS NULL
        ORDER BY scheduled_at, task_id`);
      return { tasks: result.rows, serverTime: new Date().toISOString() };
    },

    async pushBundle(client, trusted, bundle) {
      const tenantId = trusted.authContext.tenantId;
      const allReceipts = await client.query(`
        SELECT event_uuid::text AS event_uuid, event_ts
        FROM app.event_receipt
        WHERE tenant_id = $1::uuid AND event_uuid = ANY($2::uuid[])`, [tenantId, bundle.events.map((event) => event.eventUuid)]);
      if (allReceipts.rows.length === bundle.events.length) {
        return { bundleId: bundle.bundleId, status: "duplicate", events: allReceipts.rows.map((row) => ({ eventUuid: row.event_uuid, eventTs: new Date(row.event_ts).toISOString() })) };
      }

      let rejectionReason;
      for (const event of bundle.events) {
        const required = CAPABILITY_BY_KIND[event.kind];
        if (!required) { rejectionReason = "unsupported_event"; break; }
        const allowed = await client.query("SELECT app.has_capability($1::text) AS allowed", [required]);
        if (!allowed.rows[0]?.allowed || event.membershipVersion !== trusted.membershipVersion || event.authorizationSnapshotId !== trusted.authorizationSnapshotId) {
          rejectionReason = "authorization_changed";
          break;
        }
      }
      if (rejectionReason) {
        const rejectionId = uuid();
        const inserted = await client.query(`
          INSERT INTO app.sync_rejection
            (tenant_id, rejection_id, bundle_id, event_uuids, reason, recovery_action, payload)
          VALUES ($1::uuid, $2::uuid, $3, $4::uuid[], $5, 'reauthenticate_or_request_manager_review', $6::jsonb)
          RETURNING rejection_id::text AS id, bundle_id, event_uuids::text[], reason, recovery_action, created_at`,
        [tenantId, rejectionId, bundle.bundleId, bundle.events.map((event) => event.eventUuid), rejectionReason, JSON.stringify(bundle)]);
        return { bundleId: bundle.bundleId, status: "rejected", rejection: queueDto(inserted.rows[0]) };
      }

      const accepted = [];
      let conflicted = false;
      for (const event of bundle.events) {
        const existing = allReceipts.rows.find((row) => row.event_uuid === event.eventUuid);
        if (existing) {
          accepted.push({ eventUuid: existing.event_uuid, eventTs: new Date(existing.event_ts).toISOString() });
          continue;
        }
        const { eventTs, clockSkewed } = eventTimestamp(event.occurredAt);
        const receipt = await client.query(`
          INSERT INTO app.event_receipt (tenant_id, event_uuid, event_ts)
          VALUES ($1::uuid, $2::uuid, $3::timestamptz)
          ON CONFLICT (tenant_id, event_uuid) DO UPDATE SET event_uuid = EXCLUDED.event_uuid
          RETURNING event_uuid::text AS event_uuid, event_ts`, [tenantId, event.eventUuid, eventTs]);
        const stableEventTs = receipt.rows[0].event_ts;
        await client.query(`
          INSERT INTO app.domain_event
            (tenant_id, event_uuid, event_ts, occurred_at, event_kind, scope_field_group_id, payload,
             authorization_snapshot_id, membership_version, actor_pseudonym, clock_skewed)
          VALUES ($1::uuid, $2::uuid, $3::timestamptz, $4::timestamptz, $5, $6::uuid, $7::jsonb, $8, $9, $10, $11)
          ON CONFLICT DO NOTHING`, [tenantId, event.eventUuid, stableEventTs, event.occurredAt, event.kind, event.scope || null, JSON.stringify(event.payload), event.authorizationSnapshotId, event.membershipVersion, trusted.actorPseudonym, clockSkewed]);

        if (event.kind === "journal" && event.payload.aggregateId && Number.isInteger(event.payload.baseVersion)) {
          const updated = await client.query(`
            UPDATE app.sync_document SET body = $3::jsonb, version = version + 1, updated_at = clock_timestamp()
            WHERE tenant_id = $1::uuid AND document_id = $2::uuid AND version = $4::bigint
            RETURNING version`, [tenantId, event.payload.aggregateId, JSON.stringify(event.payload), event.payload.baseVersion]);
          if (updated.rowCount === 0) {
            const current = await client.query("SELECT version, body FROM app.sync_document WHERE tenant_id = $1::uuid AND document_id = $2::uuid", [tenantId, event.payload.aggregateId]);
            if (current.rows[0]) {
              conflicted = true;
              await client.query(`
                INSERT INTO app.sync_conflict
                  (tenant_id, conflict_id, document_id, event_uuid, base_version, current_version, current_value, proposed_value)
                VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5, $6, $7::jsonb, $8::jsonb)`,
              [tenantId, uuid(), event.payload.aggregateId, event.eventUuid, event.payload.baseVersion, current.rows[0].version, JSON.stringify(current.rows[0].body), JSON.stringify(event.payload)]);
            } else {
              await client.query("INSERT INTO app.sync_document (tenant_id, document_id, field_group_id, body) VALUES ($1::uuid, $2::uuid, $3::uuid, $4::jsonb)", [tenantId, event.payload.aggregateId, event.scope || null, JSON.stringify(event.payload)]);
            }
          }
        }

        await client.query(`
          INSERT INTO app.sync_change (tenant_id, scope_field_group_id, priority, entity_type, operation, entity_id, event_uuid, data)
          VALUES ($1::uuid, $2::uuid, 1, $3, 'upsert', $4::uuid, $5::uuid, $6::jsonb)`,
        [tenantId, event.scope || null, event.kind, event.payload.aggregateId || null, event.eventUuid, JSON.stringify(event.payload)]);
        accepted.push({ eventUuid: event.eventUuid, eventTs: new Date(stableEventTs).toISOString() });
      }
      return { bundleId: bundle.bundleId, status: conflicted ? "conflict" : "accepted", events: accepted };
    },

    async pull(client, trusted, { scope, priority, cursor }) {
      const tenantScope = scope === "tenant";
      if (!tenantScope && !trusted.authContext.scopeFieldGroups.includes(scope)) {
        const error = new Error("scope revoked"); error.code = "scope_revoked"; error.scope = scope; throw error;
      }
      const after = cursor ? Number(cursor) : 0;
      if (!Number.isSafeInteger(after) || after < 0) throw new TypeError("invalid cursor");
      const upper = await client.query("SELECT coalesce(max(server_seq), 0)::text AS upper FROM app.sync_change WHERE tenant_id = app.current_tenant_id()");
      const result = await client.query(`
        SELECT server_seq::text, entity_type AS type, operation, entity_id::text, event_uuid::text, data
        FROM app.sync_change
        WHERE tenant_id = app.current_tenant_id()
          AND server_seq > $1::bigint AND server_seq <= $2::bigint
          AND (($3::boolean AND scope_field_group_id IS NULL) OR scope_field_group_id = $4::uuid)
          AND ($5::boolean = false OR priority = 0)
        ORDER BY server_seq LIMIT 501`, [after, upper.rows[0].upper, tenantScope, tenantScope ? null : scope, priority === "priority"]);
      const hasMore = result.rows.length > 500;
      const rows = result.rows.slice(0, 500);
      return {
        changes: rows.map((row) => ({ serverSeq: row.server_seq, type: row.type, operation: row.operation, entityId: row.entity_id, eventUuid: row.event_uuid, data: row.data })),
        nextCursor: rows.at(-1)?.server_seq || String(after),
        snapshotUpper: upper.rows[0].upper,
        hasMore,
      };
    },

    async getQueues(client) {
      const [rejections, conflicts] = await Promise.all([
        client.query(`SELECT rejection_id::text AS id, bundle_id, event_uuids::text[], reason, recovery_action, created_at FROM app.sync_rejection WHERE tenant_id = app.current_tenant_id() ORDER BY created_at DESC LIMIT 100`),
        client.query(`SELECT conflict_id::text AS id, document_id::text, event_uuid::text, base_version, current_version, current_value, proposed_value, status, created_at FROM app.sync_conflict WHERE tenant_id = app.current_tenant_id() AND status = 'pending' ORDER BY created_at LIMIT 100`),
      ]);
      return { rejections: rejections.rows.map(queueDto), conflicts: conflicts.rows };
    },

    async resolveConflict(client, _trusted, conflictId, resolution) {
      const result = await client.query(`
        UPDATE app.sync_conflict
        SET status = 'resolved', resolution = $2::jsonb, resolved_by = nullif(current_setting('app.user_id', true), '')::uuid, resolved_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND conflict_id = $1::uuid AND status = 'pending'
        RETURNING conflict_id::text AS id, status, resolution, resolved_at`, [conflictId, JSON.stringify(resolution)]);
      if (!result.rows[0]) return { id: conflictId, status: "not_found" };
      return result.rows[0];
    },
  };
}
