import { randomUUID } from "node:crypto";

const CAPABILITY_BY_KIND = Object.freeze({ journal: "journal:write", pesticide: "pesticide:write", punch: "punch:write", stock: "inventory:write" });

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

function iso(value) { return value instanceof Date ? value.toISOString() : value; }

function validateMasterRelease(input) {
  if (!input || typeof input.version !== "string" || !input.version.trim() || input.version.length > 100
    || !Number.isFinite(Date.parse(input.validUntil)) || !Array.isArray(input.chemicals) || input.chemicals.length < 1 || input.chemicals.length > 1000) throw new TypeError("invalid pesticide master release");
  for (const item of input.chemicals) {
    if (!item || typeof item.registrationNumber !== "string" || !item.registrationNumber.trim()
      || typeof item.name !== "string" || !item.name.trim() || !Array.isArray(item.applicableCrops) || item.applicableCrops.length < 1
      || !Number.isFinite(item.dilutionMin) || item.dilutionMin <= 0 || !Number.isFinite(item.dilutionMax) || item.dilutionMax < item.dilutionMin
      || !Number.isInteger(item.maxUses) || item.maxUses < 1 || !Number.isInteger(item.preharvestDays) || item.preharvestDays < 0
      || (item.revokedOn != null && !/^\d{4}-\d{2}-\d{2}$/.test(item.revokedOn))) throw new TypeError("invalid agrochemical");
  }
}

function migrationJobDto(row, rows = undefined) {
  return {
    id: row.id, dataset: row.dataset, sourceName: row.source_name, sourceSha256: row.source_sha256,
    mapping: row.mapping, status: row.status, rowCount: Number(row.row_count), validCount: Number(row.valid_count),
    duplicateCount: Number(row.duplicate_count), errorCount: Number(row.error_count), version: Number(row.version),
    createdAt: iso(row.created_at), committedAt: iso(row.committed_at) || null,
    ...(rows ? { rows: rows.map((item) => ({ lineNumber: item.line_number, status: item.row_status,
      duplicateKey: item.duplicate_key, errors: item.errors, normalized: item.normalized_data, entityId: item.entity_id || null })) } : {}),
  };
}

async function inspectDatabaseDuplicate(client, dataset, value) {
  if (dataset === "fields") {
    const result = await client.query("SELECT field_id::text AS id FROM app.field WHERE tenant_id = app.current_tenant_id() AND external_key = $1 AND deleted_at IS NULL", [value.externalKey]);
    return result.rows[0] ? { status: "duplicate", errors: [] } : { status: "valid", errors: [] };
  }
  if (dataset === "journals") {
    const [duplicate, field] = await Promise.all([
      client.query("SELECT journal_id::text AS id FROM app.work_journal WHERE tenant_id = app.current_tenant_id() AND external_key = $1", [value.externalKey]),
      client.query("SELECT field_id::text AS id FROM app.field WHERE tenant_id = app.current_tenant_id() AND external_key = $1 AND deleted_at IS NULL", [value.fieldExternalKey]),
    ]);
    if (!field.rows[0]) return { status: "invalid", errors: ["unknown_field_external_key"] };
    return duplicate.rows[0] ? { status: "duplicate", errors: [] } : { status: "valid", errors: [] };
  }
  const reference = await client.query(`SELECT field.field_id::text AS field_id, chemical.chemical_id::text AS chemical_id,
      summary.summary_id::text AS summary_id
    FROM app.field field
    JOIN app.agrochemical chemical ON chemical.tenant_id = field.tenant_id AND chemical.registration_number = $2
    LEFT JOIN app.pesticide_usage_summary summary
      ON summary.tenant_id = field.tenant_id AND summary.field_id = field.field_id
     AND summary.crop_name = $3 AND summary.chemical_id = chemical.chemical_id AND summary.season_year = $4
    WHERE field.tenant_id = app.current_tenant_id() AND field.external_key = $1 AND field.deleted_at IS NULL
    ORDER BY chemical.created_at DESC LIMIT 1`, [value.fieldExternalKey, value.registrationNumber, value.cropName, value.seasonYear]);
  if (!reference.rows[0]) return { status: "invalid", errors: ["unknown_field_or_chemical"] };
  return reference.rows[0].summary_id ? { status: "duplicate", errors: [] } : { status: "valid", errors: [] };
}

function pesticideSafety({ chemical, cropName, dilution, appliedOn, plannedHarvestOn, usageCount, now = new Date() }) {
  const reasons = [];
  if (!chemical.current_chemical_id) reasons.push("master_entry_not_current");
  if (chemical.release_valid_until && Date.parse(chemical.release_valid_until) < now.getTime()) reasons.push("master_expired");
  if (chemical.revoked_on && chemical.revoked_on <= appliedOn) reasons.push("revoked");
  if (!chemical.applicable_crops.includes(cropName)) reasons.push("crop_not_applicable");
  if (dilution < Number(chemical.dilution_min) || dilution > Number(chemical.dilution_max)) reasons.push("dilution_out_of_range");
  if (usageCount + 1 > Number(chemical.max_uses)) reasons.push("maximum_uses_exceeded");
  if (plannedHarvestOn) {
    const interval = Math.floor((Date.parse(`${plannedHarvestOn}T00:00:00Z`) - Date.parse(`${appliedOn}T00:00:00Z`)) / 86400000);
    if (interval < Number(chemical.preharvest_days)) reasons.push("preharvest_interval_short");
  }
  return { status: reasons.length ? "warning" : "safe", reasons, checkedAt: new Date().toISOString(), usageCountBefore: usageCount };
}

async function projectPesticideUsage(client, tenantId, trusted, event, eventTs, uuid) {
  const payload = event.payload;
  if (!isUuid(payload.fieldId) || !isUuid(payload.chemicalId) || typeof payload.cropName !== "string" || !payload.cropName.trim()
    || !Number.isFinite(payload.dilution) || payload.dilution <= 0 || !Number.isFinite(payload.amount) || payload.amount <= 0
    || typeof payload.targetPest !== "string" || !payload.targetPest.trim() || typeof payload.workerName !== "string" || !payload.workerName.trim()
    || typeof payload.equipment !== "string" || !payload.equipment.trim()
    || (payload.plannedHarvestOn != null && !/^\d{4}-\d{2}-\d{2}$/.test(payload.plannedHarvestOn))) throw new TypeError("invalid pesticide usage");
  const reference = await client.query(`
    WITH latest_release AS (
      SELECT release_id, valid_until FROM app.pesticide_master_release
      WHERE tenant_id = $1::uuid ORDER BY published_at DESC LIMIT 1
    )
    SELECT field.field_group_id::text, field.timezone,
           cached.chemical_id::text, cached.registration_number,
           current.chemical_id::text AS current_chemical_id, release.valid_until AS release_valid_until,
           coalesce(current.applicable_crops, cached.applicable_crops) AS applicable_crops,
           coalesce(current.dilution_min, cached.dilution_min) AS dilution_min,
           coalesce(current.dilution_max, cached.dilution_max) AS dilution_max,
           coalesce(current.max_uses, cached.max_uses) AS max_uses,
           coalesce(current.preharvest_days, cached.preharvest_days) AS preharvest_days,
           coalesce(current.revoked_on, cached.revoked_on) AS revoked_on,
           ($4::timestamptz AT TIME ZONE field.timezone)::date::text AS applied_on
    FROM app.field field
    JOIN app.agrochemical cached ON cached.tenant_id = field.tenant_id AND cached.chemical_id = $3::uuid
    LEFT JOIN latest_release release ON true
    LEFT JOIN app.agrochemical current
      ON current.tenant_id = cached.tenant_id AND current.release_id = release.release_id
     AND current.registration_number = cached.registration_number
    WHERE field.tenant_id = $1::uuid AND field.field_id = $2::uuid AND field.deleted_at IS NULL`,
  [tenantId, payload.fieldId, payload.chemicalId, event.occurredAt]);
  if (!reference.rows[0]) throw new TypeError("unknown pesticide or field");
  const chemical = reference.rows[0];
  const count = await client.query(`SELECT count(*)::integer AS usage_count
    FROM app.pesticide_usage usage JOIN app.agrochemical used
      ON used.tenant_id = usage.tenant_id AND used.chemical_id = usage.chemical_id
    WHERE usage.tenant_id = $1::uuid AND usage.field_id = $2::uuid AND usage.crop_name = $3
      AND used.registration_number = $4
      AND extract(year FROM usage.applied_on) = extract(year FROM $5::date)`,
  [tenantId, payload.fieldId, payload.cropName, chemical.registration_number, chemical.applied_on]);
  const serverSafety = pesticideSafety({ chemical, cropName: payload.cropName, dilution: payload.dilution,
    appliedOn: chemical.applied_on, plannedHarvestOn: payload.plannedHarvestOn, usageCount: Number(count.rows[0].usage_count) });
  if (typeof payload.safetyDecision?.status === "string" && payload.safetyDecision.status !== serverSafety.status) {
    serverSafety.reasons.push("client_server_mismatch");
    serverSafety.status = "warning";
  }
  const usageId = isUuid(payload.aggregateId) ? payload.aggregateId : uuid();
  await client.query(`INSERT INTO app.pesticide_usage
    (tenant_id, usage_id, event_uuid, field_id, field_group_id, crop_name, chemical_id, applied_on,
     dilution, amount, target_pest, worker_name, equipment, planned_harvest_on, client_safety,
     server_safety, occurred_at, event_ts, actor_user_id)
    VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5::uuid, $6, $7::uuid, $8::date,
      $9, $10, $11, $12, $13, $14::date, $15::jsonb, $16::jsonb, $17::timestamptz, $18::timestamptz, app.current_user_id())
    ON CONFLICT (tenant_id, event_uuid) DO NOTHING`,
  [tenantId, usageId, event.eventUuid, payload.fieldId, chemical.field_group_id, payload.cropName, payload.chemicalId,
    chemical.applied_on, payload.dilution, payload.amount, payload.targetPest, payload.workerName, payload.equipment,
    payload.plannedHarvestOn || null, JSON.stringify(payload.safetyDecision || {}), JSON.stringify(serverSafety), event.occurredAt, eventTs]);
  if (serverSafety.reasons.length) {
    await client.query(`INSERT INTO app.pesticide_safety_alert
      (tenant_id, alert_id, usage_id, field_group_id, reasons, client_safety, server_safety)
      VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5::text[], $6::jsonb, $7::jsonb)`,
    [tenantId, uuid(), usageId, chemical.field_group_id, serverSafety.reasons, JSON.stringify(payload.safetyDecision || {}), JSON.stringify(serverSafety)]);
  }
  return { entityId: usageId, serverSafety };
}

async function projectStockEvent(client, tenantId, event, eventTs, uuid) {
  const payload = event.payload;
  if (!isUuid(payload.chemicalId) || !["receipt", "withdrawal", "adjustment"].includes(payload.eventType)
    || !Number.isFinite(payload.quantity) || payload.quantity === 0 || typeof payload.reason !== "string" || !payload.reason.trim()) throw new TypeError("invalid stock event");
  const stockEventId = isUuid(payload.aggregateId) ? payload.aggregateId : uuid();
  const delta = payload.eventType === "withdrawal" ? -Math.abs(payload.quantity)
    : payload.eventType === "receipt" ? Math.abs(payload.quantity) : payload.quantity;
  // Serialize one material balance before the INSERT statement takes its MVCC snapshot.
  // This prevents two concurrent withdrawals from both missing the negative-balance transition.
  await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1::text || ':' || $2::text, 0))", [tenantId, payload.chemicalId]);
  await client.query(`INSERT INTO app.stock_event
    (tenant_id, stock_event_id, event_uuid, chemical_id, event_type, quantity_delta, reason, occurred_at, event_ts, actor_user_id)
    VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $5, $6, $7, $8::timestamptz, $9::timestamptz, app.current_user_id())
    ON CONFLICT (tenant_id, event_uuid) DO NOTHING`,
  [tenantId, stockEventId, event.eventUuid, payload.chemicalId, payload.eventType, delta, payload.reason, event.occurredAt, eventTs]);
  if (payload.eventType === "adjustment" && isUuid(payload.alertId)) {
    await client.query(`UPDATE app.stock_alert
      SET status = 'resolved', resolved_by = app.current_user_id(), resolved_at = clock_timestamp(), resolution_event_id = $3::uuid
      WHERE tenant_id = $1::uuid AND alert_id = $2::uuid AND status = 'pending'`, [tenantId, payload.alertId, stockEventId]);
  }
  return { entityId: stockEventId, quantityDelta: delta };
}

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
               latest_return.reason AS return_reason,
               coalesce(jsonb_agg(jsonb_build_object('id', attachment.attachment_id::text,
                 'fileName', attachment.file_name, 'contentType', attachment.content_type))
                 FILTER (WHERE attachment.attachment_id IS NOT NULL), '[]'::jsonb) AS attachments
        FROM app.work_journal journal
        LEFT JOIN app.field field ON field.tenant_id = journal.tenant_id AND field.field_id = journal.field_id
        LEFT JOIN app.journal_attachment attachment ON attachment.tenant_id = journal.tenant_id AND attachment.journal_id = journal.journal_id
        LEFT JOIN LATERAL (
          SELECT revision.reason FROM app.journal_revision revision
          WHERE revision.tenant_id = journal.tenant_id AND revision.journal_id = journal.journal_id
            AND revision.action = 'returned'
          ORDER BY revision.created_at DESC LIMIT 1
        ) latest_return ON true
        WHERE journal.tenant_id = app.current_tenant_id()
          AND (journal.worker_user_id = app.current_user_id() OR app.has_capability('journal:review'))
        GROUP BY journal.tenant_id, journal.journal_id, field.name, latest_return.reason
        ORDER BY journal.updated_at DESC LIMIT 100`);
      return { journals: result.rows.map((row) => ({
        id: row.id, instructionId: row.instruction_id, fieldId: row.field_id, fieldName: row.field_name,
        workerUserId: row.worker_user_id, body: row.body, status: row.status, version: Number(row.version),
        returnReason: row.return_reason, submittedAt: new Date(row.submitted_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString(), attachments: row.attachments,
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

    async getPesticideBootstrap(client, trusted, { fieldId }) {
      if (!isUuid(fieldId)) throw new TypeError("invalid field");
      const tenantId = trusted.authContext.tenantId;
      const field = await client.query(`SELECT field_id::text AS id, field_group_id::text AS field_group_id,
          name, crop_name, timezone FROM app.field
        WHERE tenant_id = $1::uuid AND field_id = $2::uuid AND deleted_at IS NULL`, [tenantId, fieldId]);
      if (!field.rows[0]) throw new TypeError("unknown field");
      const release = await client.query(`SELECT release_id::text AS id, version, valid_until, published_at
        FROM app.pesticide_master_release WHERE tenant_id = $1::uuid
        ORDER BY published_at DESC LIMIT 1`, [tenantId]);
      if (!release.rows[0]) return { field: field.rows[0], release: null, chemicals: [], usage: [], inventory: [] };
      const [chemicals, usage, inventory] = await Promise.all([
        client.query(`SELECT chemical_id::text AS id, registration_number, name, active_ingredient,
            applicable_crops, dilution_min, dilution_max, max_uses, preharvest_days, revoked_on
          FROM app.agrochemical WHERE tenant_id = $1::uuid AND release_id = $2::uuid ORDER BY name`, [tenantId, release.rows[0].id]),
        client.query(`WITH combined AS (
            SELECT chemical_id, count(*)::integer AS usage_count, max(applied_on) AS last_applied_on
            FROM app.pesticide_usage
            WHERE tenant_id = $1::uuid AND field_id = $2::uuid AND crop_name = $3
              AND extract(year FROM applied_on) = extract(year FROM current_date)
            GROUP BY chemical_id
            UNION ALL
            SELECT chemical_id, usage_count, last_applied_on
            FROM app.pesticide_usage_summary
            WHERE tenant_id = $1::uuid AND field_id = $2::uuid AND crop_name = $3
              AND season_year = extract(year FROM current_date)::integer
          )
          SELECT chemical_id::text AS chemical_id, sum(usage_count)::integer AS usage_count,
                 max(last_applied_on)::text AS last_applied_on
          FROM combined GROUP BY chemical_id`, [tenantId, fieldId, field.rows[0].crop_name]),
        client.query(`SELECT balance.chemical_id::text AS chemical_id, balance.quantity, balance.updated_at
          FROM app.stock_balance balance
          JOIN app.agrochemical chemical ON chemical.tenant_id = balance.tenant_id AND chemical.chemical_id = balance.chemical_id
          WHERE balance.tenant_id = $1::uuid AND chemical.release_id = $2::uuid`, [tenantId, release.rows[0].id]),
      ]);
      return {
        field: { id: field.rows[0].id, fieldGroupId: field.rows[0].field_group_id, name: field.rows[0].name, cropName: field.rows[0].crop_name, timezone: field.rows[0].timezone },
        release: { id: release.rows[0].id, version: release.rows[0].version, validUntil: iso(release.rows[0].valid_until), publishedAt: iso(release.rows[0].published_at), syncedAt: new Date().toISOString() },
        chemicals: chemicals.rows.map((row) => ({ id: row.id, registrationNumber: row.registration_number, name: row.name,
          activeIngredient: row.active_ingredient, applicableCrops: row.applicable_crops, dilutionMin: Number(row.dilution_min),
          dilutionMax: Number(row.dilution_max), maxUses: Number(row.max_uses), preharvestDays: Number(row.preharvest_days), revokedOn: row.revoked_on || null })),
        usage: usage.rows.map((row) => ({ chemicalId: row.chemical_id, usageCount: Number(row.usage_count), lastAppliedOn: row.last_applied_on })),
        inventory: inventory.rows.map((row) => ({ chemicalId: row.chemical_id, quantity: Number(row.quantity), updatedAt: iso(row.updated_at) })),
      };
    },

    async publishPesticideMaster(client, trusted, input) {
      validateMasterRelease(input);
      await requireCapability(client, "pesticide:manage");
      const tenantId = trusted.authContext.tenantId;
      const current = await client.query(`SELECT version FROM app.pesticide_master_release
        WHERE tenant_id = $1::uuid ORDER BY published_at DESC LIMIT 1 FOR UPDATE`, [tenantId]);
      if (input.expectedVersion !== undefined && (current.rows[0]?.version || null) !== input.expectedVersion) {
        const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = current.rows[0]?.version || null; throw error;
      }
      const releaseId = uuid();
      const published = await client.query(`INSERT INTO app.pesticide_master_release
        (tenant_id, release_id, version, valid_until, published_by)
        VALUES ($1::uuid, $2::uuid, $3, $4::timestamptz, app.current_user_id())
        RETURNING release_id::text AS id, version, valid_until, published_at`, [tenantId, releaseId, input.version, input.validUntil]);
      for (const item of input.chemicals) {
        const existingChemical = await client.query(`SELECT chemical_id::text AS id FROM app.agrochemical
          WHERE tenant_id = $1::uuid AND registration_number = $2 ORDER BY created_at DESC LIMIT 1`, [tenantId, item.registrationNumber]);
        const chemicalId = existingChemical.rows[0]?.id || (isUuid(item.id) ? item.id : uuid());
        await client.query(`INSERT INTO app.agrochemical
          (tenant_id, chemical_id, release_id, registration_number, name, active_ingredient,
           applicable_crops, dilution_min, dilution_max, max_uses, preharvest_days, revoked_on)
          VALUES ($1::uuid, $2::uuid, $3::uuid, $4, $5, $6, $7::text[], $8, $9, $10, $11, $12::date)
          ON CONFLICT (tenant_id, chemical_id) DO UPDATE SET
            release_id = EXCLUDED.release_id, registration_number = EXCLUDED.registration_number,
            name = EXCLUDED.name, active_ingredient = EXCLUDED.active_ingredient,
            applicable_crops = EXCLUDED.applicable_crops, dilution_min = EXCLUDED.dilution_min,
            dilution_max = EXCLUDED.dilution_max, max_uses = EXCLUDED.max_uses,
            preharvest_days = EXCLUDED.preharvest_days, revoked_on = EXCLUDED.revoked_on`,
        [tenantId, chemicalId, releaseId, item.registrationNumber, item.name,
          item.activeIngredient || "", item.applicableCrops, item.dilutionMin, item.dilutionMax,
          item.maxUses, item.preharvestDays, item.revokedOn || null]);
      }
      await client.query(`INSERT INTO app.sync_change (tenant_id, priority, entity_type, operation, entity_id, data)
        VALUES ($1::uuid, 0, 'pesticide_master', 'upsert', $2::uuid,
          jsonb_build_object('version', $3::text, 'validUntil', $4::timestamptz))`, [tenantId, releaseId, input.version, input.validUntil]);
      return { id: published.rows[0].id, version: published.rows[0].version, validUntil: iso(published.rows[0].valid_until), publishedAt: iso(published.rows[0].published_at), chemicalCount: input.chemicals.length };
    },

    async listInventory(client) {
      const [balances, alerts] = await Promise.all([
        client.query(`SELECT chemical.chemical_id::text AS chemical_id, chemical.name, chemical.registration_number,
            coalesce(balance.quantity, 0) AS quantity, balance.updated_at
          FROM app.agrochemical chemical
          JOIN app.pesticide_master_release release
            ON release.tenant_id = chemical.tenant_id AND release.release_id = chemical.release_id
          LEFT JOIN app.stock_balance balance
            ON balance.tenant_id = chemical.tenant_id AND balance.chemical_id = chemical.chemical_id
          WHERE chemical.tenant_id = app.current_tenant_id()
            AND release.release_id = (SELECT release_id FROM app.pesticide_master_release
              WHERE tenant_id = app.current_tenant_id() ORDER BY published_at DESC LIMIT 1)
          ORDER BY chemical.name`),
        client.query(`SELECT alert.alert_id::text AS id, alert.chemical_id::text AS chemical_id,
            chemical.name, alert.negative_quantity, alert.triggering_event_id::text,
            alert.status, alert.created_at
          FROM app.stock_alert alert JOIN app.agrochemical chemical
            ON chemical.tenant_id = alert.tenant_id AND chemical.chemical_id = alert.chemical_id
          WHERE alert.tenant_id = app.current_tenant_id() AND alert.status = 'pending'
          ORDER BY alert.created_at`),
      ]);
      return {
        balances: balances.rows.map((row) => ({ chemicalId: row.chemical_id, name: row.name, registrationNumber: row.registration_number, quantity: Number(row.quantity), updatedAt: iso(row.updated_at) || null })),
        alerts: alerts.rows.map((row) => ({ id: row.id, chemicalId: row.chemical_id, name: row.name, negativeQuantity: Number(row.negative_quantity), triggeringEventId: row.triggering_event_id, status: row.status, createdAt: iso(row.created_at) })),
      };
    },

    async createMigrationJob(client, trusted, input) {
      await requireCapability(client, "migration:manage");
      if (!input || !["fields", "journals", "pesticide_history"].includes(input.dataset)
        || typeof input.idempotencyKey !== "string" || typeof input.sourceName !== "string" || !input.sourceName.trim()
        || typeof input.sourceSha256 !== "string" || !/^[0-9a-f]{64}$/.test(input.sourceSha256)
        || !Array.isArray(input.rows) || input.rows.length > 50000) throw new TypeError("invalid migration job");
      const tenantId = trusted.authContext.tenantId;
      const existing = await client.query(`SELECT job_id::text AS id, dataset, source_name, source_sha256, mapping,
          status, row_count, valid_count, duplicate_count, error_count, version, created_at, committed_at
        FROM app.migration_job WHERE tenant_id = $1::uuid AND idempotency_key = $2`, [tenantId, input.idempotencyKey]);
      if (existing.rows[0]) {
        if (existing.rows[0].source_sha256 !== input.sourceSha256) { const error = new Error("idempotency conflict"); error.code = "idempotency_conflict"; throw error; }
        const savedRows = await client.query(`SELECT line_number, row_status, duplicate_key, errors,
            normalized_data, entity_id::text AS entity_id FROM app.migration_row
          WHERE tenant_id = $1::uuid AND job_id = $2::uuid ORDER BY line_number LIMIT 200`, [tenantId, existing.rows[0].id]);
        return migrationJobDto(existing.rows[0], savedRows.rows);
      }

      const inspected = [];
      for (const row of input.rows) {
        if (!row || !Number.isInteger(row.lineNumber) || !["valid", "duplicate", "invalid"].includes(row.status)) throw new TypeError("invalid migration row");
        if (row.status !== "valid") { inspected.push(row); continue; }
        const databaseResult = await inspectDatabaseDuplicate(client, input.dataset, row.normalized);
        inspected.push({ ...row, status: databaseResult.status, errors: [...row.errors, ...databaseResult.errors] });
      }
      const counts = {
        valid: inspected.filter((row) => row.status === "valid").length,
        duplicate: inspected.filter((row) => row.status === "duplicate").length,
        error: inspected.filter((row) => row.status === "invalid").length,
      };
      const jobId = uuid();
      const status = counts.error ? "needs_review" : "validated";
      const inserted = await client.query(`INSERT INTO app.migration_job
        (tenant_id, job_id, idempotency_key, dataset, source_name, source_sha256, mapping, status,
         row_count, valid_count, duplicate_count, error_count, created_by)
        VALUES ($1::uuid, $2::uuid, $3, $4, $5, $6, $7::jsonb, $8, $9, $10, $11, $12, app.current_user_id())
        RETURNING job_id::text AS id, dataset, source_name, source_sha256, mapping, status,
          row_count, valid_count, duplicate_count, error_count, version, created_at, committed_at`,
      [tenantId, jobId, input.idempotencyKey, input.dataset, input.sourceName, input.sourceSha256,
        JSON.stringify(input.mapping), status, inspected.length, counts.valid, counts.duplicate, counts.error]);
      for (const row of inspected) {
        await client.query(`INSERT INTO app.migration_row
          (tenant_id, job_id, line_number, raw_data, normalized_data, row_status, duplicate_key, errors)
          VALUES ($1::uuid, $2::uuid, $3, $4::jsonb, $5::jsonb, $6, $7, $8::text[])`,
        [tenantId, jobId, row.lineNumber, JSON.stringify(row.raw), JSON.stringify(row.normalized), row.status, row.duplicateKey, row.errors]);
      }
      return migrationJobDto(inserted.rows[0], inspected.slice(0, 200).map((row) => ({ line_number: row.lineNumber,
        row_status: row.status, duplicate_key: row.duplicateKey, errors: row.errors, normalized_data: row.normalized, entity_id: null })));
    },

    async listMigrationJobs(client) {
      await requireCapability(client, "migration:manage");
      const result = await client.query(`SELECT job_id::text AS id, dataset, source_name, source_sha256, mapping,
          status, row_count, valid_count, duplicate_count, error_count, version, created_at, committed_at
        FROM app.migration_job WHERE tenant_id = app.current_tenant_id() ORDER BY created_at DESC LIMIT 100`);
      return { jobs: result.rows.map((row) => migrationJobDto(row)) };
    },

    async commitMigrationJob(client, trusted, jobId, input) {
      if (!isUuid(jobId) || !Number.isInteger(input.expectedVersion) || input.expectedVersion < 1) throw new TypeError("invalid migration commit");
      await requireCapability(client, "migration:manage");
      const tenantId = trusted.authContext.tenantId;
      const locked = await client.query(`SELECT job_id::text AS id, dataset, source_name, source_sha256, mapping,
          status, row_count, valid_count, duplicate_count, error_count, version, created_at, committed_at
        FROM app.migration_job WHERE tenant_id = $1::uuid AND job_id = $2::uuid FOR UPDATE`, [tenantId, jobId]);
      const job = locked.rows[0];
      if (!job) throw new TypeError("unknown migration job");
      if (Number(job.version) !== input.expectedVersion) { const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = Number(job.version); throw error; }
      if (job.status === "committed") return migrationJobDto(job);
      if (job.status !== "validated" || Number(job.error_count) > 0) throw new TypeError("migration job is not committable");
      await client.query("UPDATE app.migration_job SET status = 'committing', version = version + 1 WHERE tenant_id = $1::uuid AND job_id = $2::uuid", [tenantId, jobId]);
      const sourceRows = await client.query(`SELECT line_number, normalized_data FROM app.migration_row
        WHERE tenant_id = $1::uuid AND job_id = $2::uuid AND row_status = 'valid' ORDER BY line_number`, [tenantId, jobId]);
      let committed = 0;
      let concurrentDuplicates = 0;
      for (const source of sourceRows.rows) {
        const value = source.normalized_data;
        let inserted;
        if (job.dataset === "fields") {
          const entityId = uuid();
          inserted = await client.query(`INSERT INTO app.field
            (tenant_id, field_id, field_group_id, external_key, name, crop_name, timezone, geom, import_job_id, import_source_row)
            VALUES ($1::uuid, $2::uuid, $3::uuid, $4, $5, $6, $7,
              ST_Multi(ST_GeomFromText($8, 4326)), $9::uuid, $10)
            ON CONFLICT (tenant_id, external_key) WHERE external_key IS NOT NULL AND deleted_at IS NULL DO NOTHING
            RETURNING field_id::text AS id`, [tenantId, entityId, value.fieldGroupId, value.externalKey, value.name,
            value.cropName || null, value.timezone, value.geometryWkt, jobId, source.line_number]);
        } else if (job.dataset === "journals") {
          const entityId = uuid();
          inserted = await client.query(`INSERT INTO app.work_journal
            (tenant_id, journal_id, field_id, field_group_id, worker_user_id, external_key, body,
             status, import_job_id, import_source_row)
            SELECT $1::uuid, $2::uuid, field_id, field_group_id, $3::uuid, $4,
              jsonb_build_object('field', name, 'workType', $5::text, 'workedOn', $6::text,
                'startedAt', $7::text, 'endedAt', $8::text, 'memo', $9::text),
              'approved', $10::uuid, $11
            FROM app.field WHERE tenant_id = $1::uuid AND external_key = $12 AND deleted_at IS NULL
            ON CONFLICT (tenant_id, external_key) WHERE external_key IS NOT NULL DO NOTHING
            RETURNING journal_id::text AS id`, [tenantId, entityId, value.workerUserId, value.externalKey, value.workType,
            value.workedOn, value.startedAt, value.endedAt, value.memo, jobId, source.line_number, value.fieldExternalKey]);
        } else {
          const entityId = uuid();
          inserted = await client.query(`INSERT INTO app.pesticide_usage_summary
            (tenant_id, summary_id, field_id, field_group_id, crop_name, chemical_id, season_year,
             usage_count, last_applied_on, import_job_id, import_source_row)
            SELECT $1::uuid, $2::uuid, field.field_id, field.field_group_id, $3, chemical.chemical_id,
              $4, $5, $6::date, $7::uuid, $8
            FROM app.field field JOIN app.agrochemical chemical
              ON chemical.tenant_id = field.tenant_id AND chemical.registration_number = $9
            WHERE field.tenant_id = $1::uuid AND field.external_key = $10 AND field.deleted_at IS NULL
            ORDER BY chemical.created_at DESC LIMIT 1
            ON CONFLICT (tenant_id, field_id, crop_name, chemical_id, season_year) DO NOTHING
            RETURNING summary_id::text AS id`, [tenantId, entityId, value.cropName, value.seasonYear,
            value.usageCount, value.lastAppliedOn, jobId, source.line_number, value.registrationNumber, value.fieldExternalKey]);
        }
        const entityId = inserted.rows[0]?.id;
        if (entityId) {
          committed += 1;
          await client.query(`UPDATE app.migration_row SET row_status = 'committed', entity_id = $4::uuid
            WHERE tenant_id = $1::uuid AND job_id = $2::uuid AND line_number = $3`, [tenantId, jobId, source.line_number, entityId]);
          await client.query(`INSERT INTO app.sync_change
            (tenant_id, priority, entity_type, operation, entity_id, data)
            VALUES ($1::uuid, 1, $2, 'upsert', $3::uuid, jsonb_build_object('importJobId', $4::text, 'sourceRow', $5::integer))`,
          [tenantId, job.dataset === "fields" ? "field" : job.dataset === "journals" ? "journal" : "pesticide_history", entityId, jobId, source.line_number]);
        } else {
          concurrentDuplicates += 1;
          await client.query(`UPDATE app.migration_row SET row_status = 'duplicate', errors = array_append(errors, 'duplicate_at_commit')
            WHERE tenant_id = $1::uuid AND job_id = $2::uuid AND line_number = $3`, [tenantId, jobId, source.line_number]);
        }
      }
      const completed = await client.query(`UPDATE app.migration_job
        SET status = 'committed', committed_at = clock_timestamp(), version = version + 1,
            valid_count = $3, duplicate_count = duplicate_count + $4
        WHERE tenant_id = $1::uuid AND job_id = $2::uuid
        RETURNING job_id::text AS id, dataset, source_name, source_sha256, mapping, status,
          row_count, valid_count, duplicate_count, error_count, version, created_at, committed_at`,
      [tenantId, jobId, committed, concurrentDuplicates]);
      return migrationJobDto(completed.rows[0]);
    },

    async exportCsv(client, _trusted, dataset, { from, to }) {
      await requireCapability(client, "export:read");
      const stamp = new Date().toISOString().slice(0, 10).replaceAll("-", "");
      let result;
      let headers;
      let fileName;
      if (dataset === "fields") {
        headers = ["圃場コード", "圃場名", "作物", "状態", "面積㎡", "タイムゾーン", "境界WKT"];
        fileName = `fields-${stamp}.csv`;
        result = await client.query(`SELECT coalesce(external_key, field_id::text) AS "圃場コード",
            name AS "圃場名", coalesce(crop_name, '') AS "作物", status AS "状態",
            gis_area_sqm::text AS "面積㎡", timezone AS "タイムゾーン", ST_AsText(geom) AS "境界WKT"
          FROM app.field WHERE tenant_id = app.current_tenant_id() AND deleted_at IS NULL
          ORDER BY name, field_id LIMIT 100001`);
      } else if (dataset === "journals") {
        headers = ["記録コード", "圃場コード", "圃場名", "作業者ID", "作業日", "作業種別", "開始", "終了", "メモ", "状態", "提出日時"];
        fileName = `work-journals-${stamp}.csv`;
        result = await client.query(`SELECT coalesce(journal.external_key, journal.journal_id::text) AS "記録コード",
            coalesce(field.external_key, field.field_id::text) AS "圃場コード", field.name AS "圃場名",
            journal.worker_user_id::text AS "作業者ID",
            CASE WHEN journal.body->>'workedOn' ~ '^\d{4}-\d{2}-\d{2}$' THEN journal.body->>'workedOn'
              ELSE (journal.submitted_at AT TIME ZONE field.timezone)::date::text END AS "作業日",
            coalesce(journal.body->>'workType', '') AS "作業種別",
            coalesce(journal.body->>'startedAt', '') AS "開始", coalesce(journal.body->>'endedAt', '') AS "終了",
            coalesce(journal.body->>'memo', '') AS "メモ", journal.status AS "状態",
            journal.submitted_at::text AS "提出日時"
          FROM app.work_journal journal JOIN app.field field
            ON field.tenant_id = journal.tenant_id AND field.field_id = journal.field_id
          WHERE journal.tenant_id = app.current_tenant_id()
            AND ($1::date IS NULL OR (CASE WHEN journal.body->>'workedOn' ~ '^\d{4}-\d{2}-\d{2}$' THEN (journal.body->>'workedOn')::date
              ELSE (journal.submitted_at AT TIME ZONE field.timezone)::date END) >= $1::date)
            AND ($2::date IS NULL OR (CASE WHEN journal.body->>'workedOn' ~ '^\d{4}-\d{2}-\d{2}$' THEN (journal.body->>'workedOn')::date
              ELSE (journal.submitted_at AT TIME ZONE field.timezone)::date END) <= $2::date)
          ORDER BY "作業日", journal.journal_id LIMIT 100001`, [from, to]);
      } else if (dataset === "pesticide-records") {
        headers = ["散布日", "圃場コード", "圃場名", "作物", "登録番号", "薬剤名", "希釈倍率", "散布量", "対象病害虫", "作業者", "使用器具", "収穫予定日", "サーバ判定"];
        fileName = `pesticide-records-${stamp}.csv`;
        result = await client.query(`SELECT usage.applied_on::text AS "散布日",
            coalesce(field.external_key, field.field_id::text) AS "圃場コード", field.name AS "圃場名",
            usage.crop_name AS "作物", chemical.registration_number AS "登録番号", chemical.name AS "薬剤名",
            usage.dilution::text AS "希釈倍率", usage.amount::text AS "散布量",
            usage.target_pest AS "対象病害虫", usage.worker_name AS "作業者", usage.equipment AS "使用器具",
            coalesce(usage.planned_harvest_on::text, '') AS "収穫予定日", usage.server_safety->>'status' AS "サーバ判定"
          FROM app.pesticide_usage usage
          JOIN app.field field ON field.tenant_id = usage.tenant_id AND field.field_id = usage.field_id
          JOIN app.agrochemical chemical ON chemical.tenant_id = usage.tenant_id AND chemical.chemical_id = usage.chemical_id
          WHERE usage.tenant_id = app.current_tenant_id()
            AND ($1::date IS NULL OR usage.applied_on >= $1::date)
            AND ($2::date IS NULL OR usage.applied_on <= $2::date)
          ORDER BY usage.applied_on, usage.usage_id LIMIT 100001`, [from, to]);
      } else throw new TypeError("invalid export dataset");
      if (result.rows.length > 100000) throw new RangeError("export_too_large");
      return { fileName, headers, rows: result.rows };
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
        if (event.kind === "stock" && event.payload.eventType === "adjustment") {
          const adjustment = await client.query("SELECT app.has_capability('inventory:adjust') AS allowed");
          if (!adjustment.rows[0]?.allowed) { rejectionReason = "authorization_changed"; break; }
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

        let projectedEntityId = isUuid(event.payload.aggregateId) ? event.payload.aggregateId : null;
        let projectedData = event.payload;

        if (event.kind === "punch") {
          if (!["start", "break", "resume", "finish"].includes(event.payload.action)) throw new TypeError("invalid punch");
          await client.query(`INSERT INTO app.work_punch
            (tenant_id, punch_id, event_uuid, user_id, instruction_id, field_group_id, action, occurred_at, event_ts)
            VALUES ($1::uuid, $2::uuid, $3::uuid, app.current_user_id(), $4::uuid, $5::uuid, $6, $7::timestamptz, $8::timestamptz)
            ON CONFLICT (tenant_id, event_uuid) DO NOTHING`,
          [tenantId, uuid(), event.eventUuid, isUuid(event.payload.instructionId) ? event.payload.instructionId : null, event.scope || null, event.payload.action, event.occurredAt, stableEventTs]);
        }

        if (event.kind === "pesticide") {
          const projection = await projectPesticideUsage(client, tenantId, trusted, event, stableEventTs, uuid);
          projectedEntityId = projection.entityId;
          projectedData = { ...event.payload, serverSafety: projection.serverSafety };
        }

        if (event.kind === "stock") {
          const projection = await projectStockEvent(client, tenantId, event, stableEventTs, uuid);
          projectedEntityId = projection.entityId;
          projectedData = { ...event.payload, quantityDelta: projection.quantityDelta };
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
        [tenantId, event.scope || null, event.kind, projectedEntityId, event.eventUuid, JSON.stringify(projectedData)]);
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
      const [rejections, conflicts, pesticideAlerts, stockAlerts] = await Promise.all([
        client.query(`SELECT rejection_id::text AS id, bundle_id, event_uuids::text[], reason, recovery_action, created_at FROM app.sync_rejection WHERE tenant_id = app.current_tenant_id() ORDER BY created_at DESC LIMIT 100`),
        client.query(`SELECT conflict_id::text AS id, document_id::text AS document_id, event_uuid::text AS event_uuid, base_version, current_version, current_value, proposed_value, conflicting_fields, status, created_at FROM app.sync_conflict WHERE tenant_id = app.current_tenant_id() AND status = 'pending' ORDER BY created_at LIMIT 100`),
        client.query(`SELECT alert_id::text AS id, usage_id::text AS usage_id, reasons, client_safety, server_safety, status, created_at
          FROM app.pesticide_safety_alert WHERE tenant_id = app.current_tenant_id() AND status = 'pending' ORDER BY created_at LIMIT 100`),
        client.query(`SELECT alert_id::text AS id, chemical_id::text AS chemical_id, triggering_event_id::text AS triggering_event_id,
            negative_quantity, status, created_at FROM app.stock_alert
          WHERE tenant_id = app.current_tenant_id() AND status = 'pending' ORDER BY created_at LIMIT 100`),
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
        pesticideAlerts: pesticideAlerts.rows.map((row) => ({ id: row.id, usageId: row.usage_id, reasons: row.reasons,
          clientSafety: row.client_safety, serverSafety: row.server_safety, status: row.status, createdAt: iso(row.created_at) })),
        stockAlerts: stockAlerts.rows.map((row) => ({ id: row.id, chemicalId: row.chemical_id,
          triggeringEventId: row.triggering_event_id, negativeQuantity: Number(row.negative_quantity), status: row.status, createdAt: iso(row.created_at) })),
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

export const postgresMvpContract = Object.freeze({ mergeFields, derivePunchSuggestion, pesticideSafety });
