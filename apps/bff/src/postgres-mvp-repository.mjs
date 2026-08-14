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

function same(left, right) { return JSON.stringify(left) === JSON.stringify(right); }

function mergeFields(base, current, proposed) {
  const merged = { ...current };
  const conflicts = [];
  for (const [field, value] of Object.entries(proposed)) {
    const serverChanged = !same(current[field], base[field]);
    const deviceChanged = !same(value, base[field]);
    if (serverChanged && deviceChanged && !same(current[field], value)) conflicts.push(field);
    else if (deviceChanged) merged[field] = value;
  }
  return { merged, conflicts };
}

function isUuid(value) { return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value); }

function validateInstruction(input) {
  if (!input || !isUuid(input.fieldId) || !isUuid(input.assigneeUserId)
    || typeof input.title !== "string" || !input.title.trim() || input.title.length > 200
    || typeof input.workType !== "string" || !input.workType.trim() || input.workType.length > 100
    || !Number.isFinite(Date.parse(input.scheduledStart)) || !Number.isFinite(Date.parse(input.scheduledEnd))
    || Date.parse(input.scheduledEnd) < Date.parse(input.scheduledStart)
    || (input.priority !== undefined && (!Number.isInteger(input.priority) || input.priority < 0 || input.priority > 2))) throw new TypeError("invalid instruction");
}

async function requireCapability(client, capability) {
  const allowed = await client.query("SELECT app.has_capability($1::text) AS allowed", [capability]);
  if (!allowed.rows[0]?.allowed) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
}

function workInstructionDto(row) {
  return {
    id: row.id, fieldId: row.field_id, fieldGroupId: row.field_group_id,
    fieldName: row.field_name || null, cropName: row.crop_name || null,
    title: row.title, workType: row.work_type, details: row.details,
    scheduledStart: new Date(row.scheduled_start).toISOString(), scheduledEnd: new Date(row.scheduled_end).toISOString(),
    priority: Number(row.priority), status: row.status, version: Number(row.version),
    assignment: row.assignment_id ? { id: row.assignment_id, assigneeUserId: row.assignee_user_id, version: Number(row.assignment_version) } : null,
  };
}

function localTime(value) {
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Tokyo", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

function derivePunchSuggestion(rows) {
  const start = rows.find((row) => row.action === "start") || rows.find((row) => row.action === "resume");
  const finish = [...rows].reverse().find((row) => row.action === "finish");
  return {
    startedAt: start ? localTime(start.occurred_at) : null,
    endedAt: finish ? localTime(finish.occurred_at) : null,
    warning: !start ? "missing_start" : start && !finish ? "missing_finish" : null,
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

    async searchFields(client, trusted, { bbox, query, limit, cursor }) {
      const result = await client.query(`
        SELECT field_id::text AS id, field_group_id::text AS field_group_id,
               name, crop_name, status, gis_area_sqm, version,
               ST_AsGeoJSON(geom, 7, 0)::json AS geometry
        FROM app.field
        WHERE tenant_id = $1::uuid
          AND deleted_at IS NULL
          AND ($2::uuid IS NULL OR field_id > $2::uuid)
          AND ($3::text = '' OR lower(name) LIKE lower($3::text) || '%')
          AND ($4::boolean = false OR geom && ST_MakeEnvelope($5, $6, $7, $8, 4326))
        ORDER BY field_id
        LIMIT $9`, [trusted.authContext.tenantId, cursor, query, Boolean(bbox), bbox?.[0] || 0, bbox?.[1] || 0, bbox?.[2] || 0, bbox?.[3] || 0, limit + 1]);
      const rows = result.rows.slice(0, limit);
      return {
        type: "FeatureCollection",
        features: rows.map((row) => ({
          type: "Feature", id: row.id, geometry: row.geometry,
          properties: {
            id: row.id, fieldGroupId: row.field_group_id, name: row.name,
            cropName: row.crop_name, status: row.status,
            areaSqm: Number(row.gis_area_sqm), version: Number(row.version),
          },
        })),
        nextCursor: result.rows.length > limit ? rows.at(-1).id : null,
      };
    },

    async listWorkInstructions(client) {
      const result = await client.query(`
        SELECT instruction.instruction_id::text AS id, instruction.field_id::text AS field_id,
               instruction.field_group_id::text AS field_group_id, field.name AS field_name,
               field.crop_name, instruction.title, instruction.work_type, instruction.details,
               instruction.scheduled_start, instruction.scheduled_end, instruction.priority,
               instruction.status, instruction.version,
               assignment.assignment_id::text AS assignment_id,
               assignment.assignee_user_id::text AS assignee_user_id,
               assignment.version AS assignment_version
        FROM app.work_instruction instruction
        JOIN app.field field ON field.tenant_id = instruction.tenant_id AND field.field_id = instruction.field_id
        LEFT JOIN app.work_assignment assignment
          ON assignment.tenant_id = instruction.tenant_id
         AND assignment.instruction_id = instruction.instruction_id
         AND assignment.unassigned_at IS NULL
        WHERE instruction.deleted_at IS NULL
          AND (assignment.assignee_user_id = app.current_user_id() OR app.has_capability('instruction:manage'))
        ORDER BY instruction.scheduled_start, instruction.instruction_id`);
      return { instructions: result.rows.map(workInstructionDto) };
    },

    async createWorkInstruction(client, trusted, input) {
      validateInstruction(input);
      await requireCapability(client, "instruction:manage");
      const tenantId = trusted.authContext.tenantId;
      const instructionId = uuid();
      const assignmentId = uuid();
      const result = await client.query(`
        WITH inserted_instruction AS (
          INSERT INTO app.work_instruction
            (tenant_id, instruction_id, field_id, field_group_id, title, work_type, details,
             scheduled_start, scheduled_end, priority, created_by, updated_by)
          SELECT $1::uuid, $2::uuid, field_id, field_group_id, $4, $5, $6,
                 $7::timestamptz, $8::timestamptz, $9::smallint, app.current_user_id(), app.current_user_id()
          FROM app.field WHERE tenant_id = $1::uuid AND field_id = $3::uuid AND deleted_at IS NULL
          RETURNING *
        ), inserted_assignment AS (
          INSERT INTO app.work_assignment
            (tenant_id, assignment_id, instruction_id, field_group_id, assignee_user_id, assigned_by)
          SELECT tenant_id, $10::uuid, instruction_id, field_group_id, $11::uuid, app.current_user_id()
          FROM inserted_instruction
          RETURNING *
        )
        SELECT instruction.instruction_id::text AS id, instruction.field_id::text AS field_id,
               instruction.field_group_id::text AS field_group_id, instruction.title, instruction.work_type,
               instruction.details, instruction.scheduled_start, instruction.scheduled_end,
               instruction.priority, instruction.status, instruction.version,
               assignment.assignment_id::text AS assignment_id,
               assignment.assignee_user_id::text AS assignee_user_id,
               assignment.version AS assignment_version
        FROM inserted_instruction instruction CROSS JOIN inserted_assignment assignment`,
      [tenantId, instructionId, input.fieldId, input.title, input.workType, input.details || "", input.scheduledStart, input.scheduledEnd, input.priority ?? 1, assignmentId, input.assigneeUserId]);
      if (!result.rows[0]) throw new TypeError("unknown field");
      return workInstructionDto(result.rows[0]);
    },

    async reassignWorkInstruction(client, trusted, instructionId, input) {
      if (!isUuid(instructionId) || !isUuid(input.assigneeUserId) || !Number.isInteger(input.expectedVersion) || input.expectedVersion < 1) throw new TypeError("invalid assignment");
      await requireCapability(client, "instruction:manage");
      const tenantId = trusted.authContext.tenantId;
      const locked = await client.query(`
        SELECT instruction_id::text AS id, field_group_id::text AS field_group_id, version
        FROM app.work_instruction
        WHERE tenant_id = $1::uuid AND instruction_id = $2::uuid AND deleted_at IS NULL
        FOR UPDATE`, [tenantId, instructionId]);
      if (!locked.rows[0]) throw new TypeError("unknown instruction");
      if (Number(locked.rows[0].version) !== input.expectedVersion) {
        const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = Number(locked.rows[0].version); throw error;
      }
      await client.query(`UPDATE app.work_assignment SET unassigned_at = clock_timestamp(), version = version + 1
        WHERE tenant_id = $1::uuid AND instruction_id = $2::uuid AND unassigned_at IS NULL`, [tenantId, instructionId]);
      const assignmentId = uuid();
      await client.query(`INSERT INTO app.work_assignment
        (tenant_id, assignment_id, instruction_id, field_group_id, assignee_user_id, assigned_by)
        VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5::uuid, app.current_user_id())`,
      [tenantId, assignmentId, instructionId, locked.rows[0].field_group_id, input.assigneeUserId]);
      const updated = await client.query(`UPDATE app.work_instruction
        SET version = version + 1, updated_by = app.current_user_id(), updated_at = clock_timestamp()
        WHERE tenant_id = $1::uuid AND instruction_id = $2::uuid AND version = $3
        RETURNING version`, [tenantId, instructionId, input.expectedVersion]);
      return { id: instructionId, assignmentId, assigneeUserId: input.assigneeUserId, version: Number(updated.rows[0].version) };
    },

    async getJournalBootstrap(client, _trusted, { instructionId, fieldId, journalId }) {
      if (instructionId && !isUuid(instructionId)) throw new TypeError("invalid instruction");
      if (fieldId && !isUuid(fieldId)) throw new TypeError("invalid field");
      if (journalId && !isUuid(journalId)) throw new TypeError("invalid journal");
      const instruction = instructionId ? await client.query(`
        SELECT instruction.instruction_id::text AS id, instruction.field_id::text AS field_id,
               instruction.field_group_id::text AS field_group_id, field.name AS field_name,
               instruction.work_type, instruction.details, instruction.scheduled_start, instruction.scheduled_end
        FROM app.work_instruction instruction
        JOIN app.field field ON field.tenant_id = instruction.tenant_id AND field.field_id = instruction.field_id
        JOIN app.work_assignment assignment ON assignment.tenant_id = instruction.tenant_id
          AND assignment.instruction_id = instruction.instruction_id AND assignment.unassigned_at IS NULL
        WHERE instruction.tenant_id = app.current_tenant_id() AND instruction.instruction_id = $1::uuid
          AND (assignment.assignee_user_id = app.current_user_id() OR app.has_capability('instruction:manage'))`, [instructionId]) : { rows: [] };
      const selectedFieldId = fieldId || instruction.rows[0]?.field_id || null;
      const selectedScope = instruction.rows[0]?.field_group_id || null;
      const [punches, templates, previous] = await Promise.all([
        client.query(`SELECT action, occurred_at FROM app.work_punch
          WHERE tenant_id = app.current_tenant_id() AND user_id = app.current_user_id()
            AND occurred_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Tokyo') AT TIME ZONE 'Asia/Tokyo'
          ORDER BY occurred_at`),
        client.query(`SELECT template_id::text AS id, name, work_type, defaults, version FROM app.journal_template
          WHERE tenant_id = app.current_tenant_id() AND active
            AND ($1::uuid IS NULL OR field_group_id IS NULL OR field_group_id = $1::uuid)
          ORDER BY sort_order, name LIMIT 20`, [selectedScope]),
        client.query(`SELECT journal_id::text AS id, instruction_id::text AS instruction_id,
            field_id::text AS field_id, field_group_id::text AS field_group_id, body, status, version, updated_at
          FROM app.work_journal
          WHERE tenant_id = app.current_tenant_id() AND worker_user_id = app.current_user_id()
            AND ($1::uuid IS NULL OR journal_id = $1::uuid)
            AND ($2::uuid IS NULL OR field_id = $2::uuid)
          ORDER BY updated_at DESC LIMIT 1`, [journalId || null, selectedFieldId]),
      ]);
      return {
        instruction: instruction.rows[0] ? {
          id: instruction.rows[0].id, fieldId: instruction.rows[0].field_id, fieldGroupId: instruction.rows[0].field_group_id,
          fieldName: instruction.rows[0].field_name, workType: instruction.rows[0].work_type, details: instruction.rows[0].details,
          scheduledStart: new Date(instruction.rows[0].scheduled_start).toISOString(), scheduledEnd: new Date(instruction.rows[0].scheduled_end).toISOString(),
        } : null,
        punchSuggestion: derivePunchSuggestion(punches.rows),
        templates: templates.rows.map((row) => ({ id: row.id, name: row.name, workType: row.work_type, defaults: row.defaults, version: Number(row.version) })),
        previous: previous.rows[0] ? { id: previous.rows[0].id, instructionId: previous.rows[0].instruction_id, fieldId: previous.rows[0].field_id, fieldGroupId: previous.rows[0].field_group_id, body: previous.rows[0].body, status: previous.rows[0].status, version: Number(previous.rows[0].version), updatedAt: new Date(previous.rows[0].updated_at).toISOString() } : null,
      };
    },

    async saveJournalAttachment(client, trusted, attachment) {
      if (!isUuid(attachment.attachmentId) || !isUuid(attachment.journalId)
        || typeof attachment.fileName !== "string" || !attachment.fileName || attachment.fileName.length > 255
        || !Number.isFinite(Date.parse(attachment.capturedAt))) throw new TypeError("invalid attachment");
      await requireCapability(client, "journal:write");
      const result = await client.query(`
        WITH inserted AS (
          INSERT INTO app.journal_attachment
            (tenant_id, attachment_id, journal_id, worker_user_id, file_name, content_type,
             byte_size, sha256, content, captured_at)
          VALUES ($1::uuid, $2::uuid, $3::uuid, app.current_user_id(), $4, $5, $6, $7, $8::bytea, $9::timestamptz)
          ON CONFLICT (tenant_id, attachment_id) DO NOTHING
          RETURNING attachment_id, journal_id, byte_size, sha256
        )
        SELECT attachment_id::text AS id, journal_id::text AS journal_id, byte_size, sha256 FROM inserted
        UNION ALL
        SELECT attachment_id::text, journal_id::text, byte_size, sha256 FROM app.journal_attachment
        WHERE tenant_id = $1::uuid AND attachment_id = $2::uuid
        LIMIT 1`,
      [trusted.authContext.tenantId, attachment.attachmentId, attachment.journalId, attachment.fileName, attachment.contentType, attachment.bytes.length, attachment.sha256, attachment.bytes, attachment.capturedAt]);
      if (result.rows[0]?.sha256 !== attachment.sha256) { const error = new Error("attachment id reused"); error.code = "idempotency_conflict"; throw error; }
      return { id: result.rows[0].id, journalId: result.rows[0].journal_id, byteSize: Number(result.rows[0].byte_size), sha256: result.rows[0].sha256 };
    },

    async listJournals(client) {
      const result = await client.query(`
        SELECT journal.journal_id::text AS id, journal.instruction_id::text AS instruction_id,
               journal.field_id::text AS field_id, field.name AS field_name,
               journal.worker_user_id::text AS worker_user_id, journal.body, journal.status,
               journal.version, journal.submitted_at, journal.updated_at,
               coalesce(jsonb_agg(jsonb_build_object('id', attachment.attachment_id::text,
                 'fileName', attachment.file_name, 'contentType', attachment.content_type))
                 FILTER (WHERE attachment.attachment_id IS NOT NULL), '[]'::jsonb) AS attachments
        FROM app.work_journal journal
        LEFT JOIN app.field field ON field.tenant_id = journal.tenant_id AND field.field_id = journal.field_id
        LEFT JOIN app.journal_attachment attachment ON attachment.tenant_id = journal.tenant_id AND attachment.journal_id = journal.journal_id
        WHERE journal.tenant_id = app.current_tenant_id()
          AND (journal.worker_user_id = app.current_user_id() OR app.has_capability('journal:review'))
        GROUP BY journal.tenant_id, journal.journal_id, field.name
        ORDER BY journal.updated_at DESC LIMIT 100`);
      return { journals: result.rows.map((row) => ({
        id: row.id, instructionId: row.instruction_id, fieldId: row.field_id, fieldName: row.field_name,
        workerUserId: row.worker_user_id, body: row.body, status: row.status, version: Number(row.version),
        submittedAt: new Date(row.submitted_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString(), attachments: row.attachments,
      })) };
    },

    async reviewJournal(client, _trusted, journalId, input) {
      if (!isUuid(journalId) || !["approve", "return"].includes(input.action)
        || !Number.isInteger(input.expectedVersion) || input.expectedVersion < 1
        || (input.action === "return" && (typeof input.reason !== "string" || !input.reason.trim()))) throw new TypeError("invalid review");
      await requireCapability(client, "journal:review");
      const locked = await client.query(`SELECT journal_id::text AS id, worker_user_id::text AS worker_user_id,
          body, status, version FROM app.work_journal
        WHERE tenant_id = app.current_tenant_id() AND journal_id = $1::uuid FOR UPDATE`, [journalId]);
      if (!locked.rows[0]) throw new TypeError("unknown journal");
      if (Number(locked.rows[0].version) !== input.expectedVersion) {
        const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = Number(locked.rows[0].version); throw error;
      }
      if (!["submitted", "corrected"].includes(locked.rows[0].status)) throw new TypeError("journal is not reviewable");
      const nextStatus = input.action === "approve" ? "approved" : "returned";
      const revisionId = uuid();
      await client.query(`INSERT INTO app.journal_revision
        (tenant_id, revision_id, journal_id, worker_user_id, action, from_status, to_status,
         reason, body_snapshot, actor_user_id)
        VALUES (app.current_tenant_id(), $1::uuid, $2::uuid, $3::uuid, $4, $5, $6, $7, $8::jsonb, app.current_user_id())`,
      [revisionId, journalId, locked.rows[0].worker_user_id, nextStatus, locked.rows[0].status, nextStatus, input.reason || null, JSON.stringify(locked.rows[0].body)]);
      const updated = await client.query(`UPDATE app.work_journal SET status = $2, version = version + 1, updated_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND journal_id = $1::uuid AND version = $3
        RETURNING status, version, updated_at`, [journalId, nextStatus, input.expectedVersion]);
      await client.query(`INSERT INTO app.sync_change (tenant_id, priority, entity_type, operation, entity_id, data)
        VALUES (app.current_tenant_id(), 0, 'journal_review', 'upsert', $1::uuid,
          jsonb_build_object('journalId', $1::text, 'status', $2::text, 'reason', $3::text, 'version', $4::bigint))`,
      [journalId, nextStatus, input.reason || null, updated.rows[0].version]);
      return { id: journalId, status: updated.rows[0].status, version: Number(updated.rows[0].version), updatedAt: new Date(updated.rows[0].updated_at).toISOString() };
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
        if (event.kind === "journal" && isUuid(event.payload.aggregateId)) {
          const currentJournal = await client.query("SELECT status FROM app.work_journal WHERE tenant_id = $1::uuid AND journal_id = $2::uuid", [tenantId, event.payload.aggregateId]);
          if (currentJournal.rows[0]?.status === "approved") { rejectionReason = "journal_locked"; break; }
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

        if (event.kind === "punch") {
          if (!["start", "break", "resume", "finish"].includes(event.payload.action)) throw new TypeError("invalid punch");
          await client.query(`INSERT INTO app.work_punch
            (tenant_id, punch_id, event_uuid, user_id, instruction_id, field_group_id, action, occurred_at, event_ts)
            VALUES ($1::uuid, $2::uuid, $3::uuid, app.current_user_id(), $4::uuid, $5::uuid, $6, $7::timestamptz, $8::timestamptz)
            ON CONFLICT (tenant_id, event_uuid) DO NOTHING`,
          [tenantId, uuid(), event.eventUuid, isUuid(event.payload.instructionId) ? event.payload.instructionId : null, event.scope || null, event.payload.action, event.occurredAt, stableEventTs]);
        }

        if (event.kind === "journal" && isUuid(event.payload.aggregateId) && Number.isInteger(event.payload.baseVersion)) {
          const proposed = event.payload.changes && typeof event.payload.changes === "object" ? event.payload.changes : event.payload;
          let journalBody = proposed;
          const currentJournal = await client.query(`SELECT worker_user_id::text AS worker_user_id, body, status
            FROM app.work_journal WHERE tenant_id = $1::uuid AND journal_id = $2::uuid FOR UPDATE`, [tenantId, event.payload.aggregateId]);
          const updated = await client.query(`
            UPDATE app.sync_document SET body = $3::jsonb, version = version + 1, updated_at = clock_timestamp()
            WHERE tenant_id = $1::uuid AND document_id = $2::uuid AND version = $4::bigint
            RETURNING version`, [tenantId, event.payload.aggregateId, JSON.stringify(proposed), event.payload.baseVersion]);
          if (updated.rowCount === 0) {
            const current = await client.query("SELECT version, body FROM app.sync_document WHERE tenant_id = $1::uuid AND document_id = $2::uuid", [tenantId, event.payload.aggregateId]);
            if (current.rows[0]) {
              const base = event.payload.baseValue && typeof event.payload.baseValue === "object" ? event.payload.baseValue : {};
              const fieldMerge = mergeFields(base, current.rows[0].body, proposed);
              journalBody = fieldMerge.merged;
              await client.query("UPDATE app.sync_document SET body = $3::jsonb, version = version + 1, updated_at = clock_timestamp() WHERE tenant_id = $1::uuid AND document_id = $2::uuid AND version = $4", [tenantId, event.payload.aggregateId, JSON.stringify(fieldMerge.merged), current.rows[0].version]);
              if (fieldMerge.conflicts.length) {
                conflicted = true;
                const currentConflict = Object.fromEntries(fieldMerge.conflicts.map((field) => [field, current.rows[0].body[field]]));
                const proposedConflict = Object.fromEntries(fieldMerge.conflicts.map((field) => [field, proposed[field]]));
                await client.query(`
                  INSERT INTO app.sync_conflict
                    (tenant_id, conflict_id, document_id, event_uuid, base_version, current_version, current_value, proposed_value, conflicting_fields)
                  VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5, $6, $7::jsonb, $8::jsonb, $9::text[])`,
                [tenantId, uuid(), event.payload.aggregateId, event.eventUuid, event.payload.baseVersion, current.rows[0].version, JSON.stringify(currentConflict), JSON.stringify(proposedConflict), fieldMerge.conflicts]);
              }
            } else {
              await client.query("INSERT INTO app.sync_document (tenant_id, document_id, field_group_id, body) VALUES ($1::uuid, $2::uuid, $3::uuid, $4::jsonb)", [tenantId, event.payload.aggregateId, event.scope || null, JSON.stringify(proposed)]);
            }
          }
          await client.query(`INSERT INTO app.work_journal
            (tenant_id, journal_id, instruction_id, field_id, field_group_id, worker_user_id, body)
            VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5::uuid, app.current_user_id(), $6::jsonb)
            ON CONFLICT (tenant_id, journal_id) DO UPDATE
              SET body = EXCLUDED.body, version = app.work_journal.version + 1,
                  status = CASE WHEN app.work_journal.status = 'returned' THEN 'corrected' ELSE 'submitted' END,
                  updated_at = clock_timestamp()
              WHERE app.work_journal.worker_user_id = app.current_user_id()
                AND app.work_journal.status IN ('submitted', 'returned', 'corrected')`,
          [tenantId, event.payload.aggregateId, isUuid(event.payload.instructionId) ? event.payload.instructionId : null,
            isUuid(event.payload.fieldId) ? event.payload.fieldId : null, event.scope || null, JSON.stringify(journalBody)]);
          if (currentJournal.rows[0]?.status === "returned") {
            await client.query(`INSERT INTO app.journal_revision
              (tenant_id, revision_id, journal_id, worker_user_id, action, from_status, to_status,
               reason, body_snapshot, actor_user_id)
              VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, 'corrected', 'returned', 'corrected',
                $5, $6::jsonb, app.current_user_id())`,
            [tenantId, uuid(), event.payload.aggregateId, currentJournal.rows[0].worker_user_id,
              typeof event.payload.correctionReason === "string" ? event.payload.correctionReason : null, JSON.stringify(journalBody)]);
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
        client.query(`SELECT conflict_id::text AS id, document_id::text AS document_id, event_uuid::text AS event_uuid, base_version, current_version, current_value, proposed_value, conflicting_fields, status, created_at FROM app.sync_conflict WHERE tenant_id = app.current_tenant_id() AND status = 'pending' ORDER BY created_at LIMIT 100`),
      ]);
      return {
        rejections: rejections.rows.map(queueDto),
        conflicts: conflicts.rows.map((row) => ({
          id: row.id, documentId: row.document_id, eventUuid: row.event_uuid,
          baseVersion: Number(row.base_version), currentVersion: Number(row.current_version),
          currentValue: row.current_value, proposedValue: row.proposed_value,
          conflictingFields: row.conflicting_fields, status: row.status,
          createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : row.created_at,
        })),
      };
    },

    async resolveConflict(client, _trusted, conflictId, resolution) {
      if (resolution.choice !== "server" && resolution.choice !== "device") throw new TypeError("invalid conflict choice");
      const capability = await client.query("SELECT app.has_capability('conflict:resolve') AS allowed");
      if (!capability.rows[0]?.allowed) {
        const error = new Error("conflict resolution requires current capability");
        error.code = "forbidden";
        throw error;
      }
      const conflict = await client.query(`
        SELECT document_id::text, current_value, proposed_value, conflicting_fields
        FROM app.sync_conflict
        WHERE tenant_id = app.current_tenant_id() AND conflict_id = $1::uuid AND status = 'pending'
        FOR UPDATE`, [conflictId]);
      if (!conflict.rows[0]) return { id: conflictId, status: "not_found" };
      const selected = resolution.choice === "server" ? conflict.rows[0].current_value : conflict.rows[0].proposed_value;
      await client.query(`
        UPDATE app.sync_document SET body = body || $2::jsonb, version = version + 1, updated_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND document_id = $1::uuid`, [conflict.rows[0].document_id, JSON.stringify(selected)]);
      const result = await client.query(`
        UPDATE app.sync_conflict
        SET status = 'resolved', resolution = $2::jsonb, resolved_by = nullif(current_setting('app.user_id', true), '')::uuid, resolved_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND conflict_id = $1::uuid AND status = 'pending'
        RETURNING conflict_id::text AS id, status, resolution, resolved_at`, [conflictId, JSON.stringify({ choice: resolution.choice, value: selected })]);
      await client.query(`
        INSERT INTO app.sync_change (tenant_id, priority, entity_type, operation, entity_id, data)
        VALUES (app.current_tenant_id(), 1, 'journal', 'upsert', $1::uuid, $2::jsonb)`, [conflict.rows[0].document_id, JSON.stringify(selected)]);
      return result.rows[0];
    },
  };
}

export const postgresMvpContract = Object.freeze({ mergeFields, derivePunchSuggestion });
