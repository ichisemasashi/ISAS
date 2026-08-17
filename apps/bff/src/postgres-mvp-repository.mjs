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
    || !Number.isFinite(payload.quantity) || payload.quantity === 0 || typeof payload.reason !== "string" || !payload.reason.trim()
    || (payload.lotId != null && !isUuid(payload.lotId))
    || (payload.unitCost != null && (!Number.isFinite(payload.unitCost) || payload.unitCost < 0))
    || (payload.currency != null && !/^[A-Z]{3}$/.test(payload.currency))
    || (payload.jgapAttributes != null && (typeof payload.jgapAttributes !== "object" || Array.isArray(payload.jgapAttributes)))) throw new TypeError("invalid stock event");
  const stockEventId = isUuid(payload.aggregateId) ? payload.aggregateId : uuid();
  const delta = payload.eventType === "withdrawal" ? -Math.abs(payload.quantity)
    : payload.eventType === "receipt" ? Math.abs(payload.quantity) : payload.quantity;
  // Serialize one material balance before the INSERT statement takes its MVCC snapshot.
  // This prevents two concurrent withdrawals from both missing the negative-balance transition.
  await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1::text || ':' || $2::text, 0))", [tenantId, payload.chemicalId]);
  await client.query(`INSERT INTO app.stock_event
    (tenant_id, stock_event_id, event_uuid, chemical_id, lot_id, event_type, quantity_delta, reason,
     unit_cost, currency, jgap_attributes, occurred_at, event_ts, actor_user_id)
    VALUES ($1::uuid, $2::uuid, $3::uuid, $4::uuid, $10::uuid, $5, $6, $7, $11, $12, $13::jsonb,
      $8::timestamptz, $9::timestamptz, app.current_user_id())
    ON CONFLICT (tenant_id, event_uuid) DO NOTHING`,
  [tenantId, stockEventId, event.eventUuid, payload.chemicalId, payload.eventType, delta, payload.reason, event.occurredAt, eventTs,
    payload.lotId || null, payload.unitCost ?? null, payload.currency || null, JSON.stringify(payload.jgapAttributes || {})]);
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
    priority: Number(row.priority), status: row.status, version: Number(row.version), progressPercent: Number(row.progress_percent || 0),
    cropPlanId: row.crop_plan_id || null, varietyName: row.variety_name || null,
    plannedAreaM2: row.planned_area_m2 == null ? null : Number(row.planned_area_m2),
    targetYieldKg: row.target_yield_kg == null ? null : Number(row.target_yield_kg),
    dependencies: row.dependencies || [], resources: row.resources || [], resourceConflicts: row.resource_conflicts || [],
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

    async getLocationBootstrap(client, _trusted, { locale }) {
      if (!/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(locale)) throw new TypeError("invalid locale");
      const [policies, consent, preference] = await Promise.all([
        client.query(`SELECT policy_version, purpose, locale, title, body, content_sha256, effective_from, effective_until
          FROM app.location_consent_policy WHERE tenant_id = app.current_tenant_id() AND purpose = 'work_evidence'
            AND locale IN ($1, 'ja') AND effective_from <= statement_timestamp()
            AND (effective_until IS NULL OR effective_until > statement_timestamp())
          ORDER BY (locale = $1) DESC, effective_from DESC`, [locale]),
        client.query(`SELECT consent_event_id::text AS id, action, purpose, policy_version, consent_text_sha256,
            locale, effective_at, expires_at FROM app.location_consent_current
          WHERE tenant_id = app.current_tenant_id() AND subject_user_id = app.current_user_id() AND purpose = 'work_evidence'`),
        client.query(`SELECT enabled, punch_linked, retention_days, locale, version, updated_at
          FROM app.location_tracking_preference WHERE tenant_id = app.current_tenant_id()
            AND user_id = app.current_user_id() AND purpose = 'work_evidence'`),
      ]);
      return { policies: policies.rows.map((row) => ({ policyVersion: row.policy_version, purpose: row.purpose,
          locale: row.locale, title: row.title, body: row.body, contentSha256: row.content_sha256,
          effectiveFrom: iso(row.effective_from), effectiveUntil: iso(row.effective_until) })),
        consent: consent.rows[0] ? { id: consent.rows[0].id, action: consent.rows[0].action,
          purpose: consent.rows[0].purpose, policyVersion: consent.rows[0].policy_version,
          consentTextSha256: consent.rows[0].consent_text_sha256, locale: consent.rows[0].locale,
          effectiveAt: iso(consent.rows[0].effective_at), expiresAt: iso(consent.rows[0].expires_at) } : null,
        preference: preference.rows[0] ? { enabled: preference.rows[0].enabled, punchLinked: preference.rows[0].punch_linked,
          retentionDays: Number(preference.rows[0].retention_days), locale: preference.rows[0].locale,
          version: Number(preference.rows[0].version), updatedAt: iso(preference.rows[0].updated_at) }
          : { enabled: false, punchLinked: true, retentionDays: 14, locale, version: 0 } };
    },

    async recordLocationConsent(client, _trusted, input) {
      if (!isUuid(input?.eventUuid) || !["granted", "withdrawn"].includes(input?.action)
        || typeof input.policyVersion !== "string" || !/^[0-9a-f]{64}$/.test(input.consentTextSha256 || "")
        || !/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(input.locale || "")
        || (input.expiresAt != null && !Number.isFinite(Date.parse(input.expiresAt)))) throw new TypeError("invalid location consent");
      if (input.action === "granted") {
        const policy = await client.query(`SELECT 1 FROM app.location_consent_policy
          WHERE tenant_id = app.current_tenant_id() AND policy_version = $1 AND purpose = 'work_evidence'
            AND locale = $2 AND content_sha256 = $3 AND effective_from <= statement_timestamp()
            AND (effective_until IS NULL OR effective_until > statement_timestamp())`,
        [input.policyVersion, input.locale, input.consentTextSha256]);
        if (!policy.rows[0]) throw new TypeError("unknown consent policy");
      }
      const consentId = uuid();
      const result = await client.query(`INSERT INTO app.location_consent_event
          (tenant_id, consent_event_id, event_uuid, subject_user_id, action, purpose, policy_version,
           consent_text_sha256, locale, expires_at, actor_user_id)
        VALUES (app.current_tenant_id(), $1::uuid, $2::uuid, app.current_user_id(), $3, 'work_evidence',
          $4, $5, $6, $7::timestamptz, app.current_user_id())
        RETURNING consent_event_id::text AS id, action, purpose, policy_version, consent_text_sha256,
          locale, effective_at, expires_at`,
      [consentId, input.eventUuid, input.action, input.policyVersion, input.consentTextSha256, input.locale, input.expiresAt || null]);
      const row = result.rows[0]; return { id: row.id, action: row.action, purpose: row.purpose,
        policyVersion: row.policy_version, consentTextSha256: row.consent_text_sha256, locale: row.locale,
        effectiveAt: iso(row.effective_at), expiresAt: iso(row.expires_at) };
    },

    async saveLocationPreference(client, _trusted, input) {
      if (typeof input?.enabled !== "boolean" || typeof input.punchLinked !== "boolean"
        || !Number.isInteger(input.retentionDays) || input.retentionDays < 1 || input.retentionDays > 30
        || !/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(input.locale || "")) throw new TypeError("invalid location preference");
      const result = await client.query(`INSERT INTO app.location_tracking_preference
          (tenant_id, user_id, purpose, enabled, punch_linked, retention_days, locale, updated_by)
        VALUES (app.current_tenant_id(), app.current_user_id(), 'work_evidence', $1, $2, $3, $4, app.current_user_id())
        ON CONFLICT (tenant_id, user_id, purpose) DO UPDATE SET enabled = EXCLUDED.enabled,
          punch_linked = EXCLUDED.punch_linked, retention_days = EXCLUDED.retention_days, locale = EXCLUDED.locale,
          version = app.location_tracking_preference.version + 1, updated_by = app.current_user_id(), updated_at = clock_timestamp()
        RETURNING enabled, punch_linked, retention_days, locale, version, updated_at`,
      [input.enabled, input.punchLinked, input.retentionDays, input.locale]);
      const row = result.rows[0]; return { enabled: row.enabled, punchLinked: row.punch_linked,
        retentionDays: Number(row.retention_days), locale: row.locale, version: Number(row.version), updatedAt: iso(row.updated_at) };
    },

    async appendLocationPoints(client, _trusted, input) {
      if (!isUuid(input?.collectionSessionId) || !Array.isArray(input.points) || input.points.length < 1 || input.points.length > 200) throw new TypeError("invalid location points");
      const consent = await client.query(`SELECT consent_event_id::text AS id FROM app.location_consent_current
        WHERE tenant_id = app.current_tenant_id() AND subject_user_id = app.current_user_id()
          AND purpose = 'work_evidence' AND action = 'granted'
          AND (expires_at IS NULL OR expires_at > statement_timestamp())`);
      if (!consent.rows[0]) throw new TypeError("valid location consent required");
      const accepted = [];
      for (const point of input.points) {
        if (!isUuid(point.eventUuid) || !Number.isFinite(point.longitude) || point.longitude < -180 || point.longitude > 180
          || !Number.isFinite(point.latitude) || point.latitude < -90 || point.latitude > 90
          || !Number.isFinite(point.accuracyM) || point.accuracyM <= 0 || !Number.isFinite(Date.parse(point.recordedAt))
          || (point.instructionId != null && !isUuid(point.instructionId)) || (point.fieldGroupId != null && !isUuid(point.fieldGroupId))) throw new TypeError("invalid location point");
        const inserted = await client.query(`INSERT INTO app.location_track_point
            (tenant_id, track_point_id, event_uuid, subject_user_id, instruction_id, field_group_id,
             collection_session_id, consent_event_id, geom, accuracy_m, recorded_at, expires_at)
          VALUES (app.current_tenant_id(), $1::uuid, $2::uuid, app.current_user_id(), $3::uuid, $4::uuid,
            $5::uuid, $6::uuid, ST_SetSRID(ST_MakePoint($7, $8), 4326)::geography, $9, $10::timestamptz,
            statement_timestamp() + interval '1 day') ON CONFLICT (tenant_id, event_uuid) DO NOTHING
          RETURNING event_uuid::text`, [uuid(), point.eventUuid, point.instructionId || null, point.fieldGroupId || null,
          input.collectionSessionId, consent.rows[0].id, point.longitude, point.latitude, point.accuracyM, point.recordedAt]);
        if (inserted.rows[0]) accepted.push(inserted.rows[0].event_uuid);
      }
      return { accepted: accepted.length, eventUuids: accepted };
    },

    async readLocationTracks(client, _trusted, input) {
      if (!isUuid(input?.subjectUserId) || !Number.isFinite(Date.parse(input?.from))
        || !Number.isFinite(Date.parse(input?.to)) || typeof input?.purpose !== "string") throw new TypeError("invalid location access");
      const result = await client.query(`SELECT * FROM app.read_location_tracks($1::uuid, $2::timestamptz, $3::timestamptz, $4)`,
        [input.subjectUserId, input.from, input.to, input.purpose]);
      return { points: result.rows.map((row) => ({ trackPointId: row.track_point_id, instructionId: row.instruction_id,
        fieldGroupId: row.field_group_id, longitude: row.longitude, latitude: row.latitude,
        accuracyM: Number(row.accuracy_m), recordedAt: iso(row.recorded_at), expiresAt: iso(row.expires_at) })) };
    },

    async getWorkActuals(client, _trusted, { from, to }) {
      if (!Number.isFinite(Date.parse(from)) || !Number.isFinite(Date.parse(to))) throw new TypeError("invalid actual range");
      const result = await client.query(`SELECT * FROM app.read_own_work_actuals($1::timestamptz, $2::timestamptz)`, [from, to]);
      return { actuals: result.rows.map((row) => ({ type: row.actual_type, instructionId: row.instruction_id,
        fieldId: row.field_id, fieldGroupId: row.field_group_id, date: row.actual_date,
        seconds: Number(row.seconds) })) };
    },

    async getTenantAnalytics(client) {
      await requireCapability(client, "analytics:read");
      const [plans, materials, freshness] = await Promise.all([
        client.query(`SELECT crop_plan_id::text, season_id::text, field_id::text, field_group_id::text,
            crop_name, variety_name, planned_area_m2, target_yield_kg, progress_percent,
            planned_work_seconds, actual_work_seconds, instruction_count, completed_instruction_count,
            actual_yield_kg, pesticide_amount, pesticide_application_count, freshest_at, missing_metrics
          FROM app.tenant_plan_actual ORDER BY crop_name, variety_name NULLS LAST, crop_plan_id`),
        client.query(`SELECT usage_type, chemical_id::text, material_name, quantity, unit, event_count, freshest_at
          FROM app.tenant_material_actual ORDER BY usage_type, material_name, chemical_id`),
        client.query(`SELECT source, freshest_at, freshness_status, age_seconds
          FROM app.tenant_analytics_freshness ORDER BY source`),
      ]);
      const planRows = plans.rows.map((row) => ({ cropPlanId: row.crop_plan_id, seasonId: row.season_id,
          fieldId: row.field_id, fieldGroupId: row.field_group_id, cropName: row.crop_name,
          varietyName: row.variety_name, plannedAreaM2: Number(row.planned_area_m2),
          targetYieldKg: row.target_yield_kg == null ? null : Number(row.target_yield_kg),
          progressPercent: Number(row.progress_percent), plannedWorkSeconds: Number(row.planned_work_seconds),
          actualWorkSeconds: Number(row.actual_work_seconds), instructionCount: Number(row.instruction_count),
          completedInstructionCount: Number(row.completed_instruction_count),
          actualYieldKg: row.actual_yield_kg == null ? null : Number(row.actual_yield_kg),
          pesticideAmount: row.pesticide_amount == null ? null : Number(row.pesticide_amount),
          pesticideApplicationCount: Number(row.pesticide_application_count), freshestAt: iso(row.freshest_at),
          missingMetrics: row.missing_metrics }));
      const materialRows = materials.rows.map((row) => ({ usageType: row.usage_type, chemicalId: row.chemical_id,
          materialName: row.material_name, quantity: Number(row.quantity), unit: row.unit,
          eventCount: Number(row.event_count), freshestAt: iso(row.freshest_at) }));
      const coverage = [
        ["plan_progress", (plan) => plan.instructionCount > 0],
        ["work_actual", (plan) => plan.actualWorkSeconds > 0],
        ["yield_actual", (plan) => plan.actualYieldKg != null],
        ["material_actual", (plan) => plan.pesticideAmount != null],
      ].map(([metric, predicate]) => {
        const coveredPlans = planRows.filter(predicate).length;
        const freshestAt = planRows.filter(predicate).map((plan) => plan.freshestAt).filter(Boolean).sort().at(-1) || null;
        return { metric, available: coveredPlans > 0, coveredPlans, totalPlans: planRows.length,
          percent: planRows.length ? Math.round(coveredPlans * 1000 / planRows.length) / 10 : null,
          inputMode: coveredPlans ? "manual" : "none", freshestAt };
      });
      const manualRecords = planRows.reduce((sum, plan) => sum + plan.instructionCount
        + (plan.actualYieldKg == null ? 0 : 1) + plan.pesticideApplicationCount, 0)
        + materialRows.reduce((sum, item) => sum + item.eventCount, 0);
      return { source: "operational_db", dwhRequired: false, generatedAt: new Date().toISOString(),
        plans: planRows, materials: materialRows,
        freshness: freshness.rows.map((row) => ({ source: row.source, freshestAt: iso(row.freshest_at),
          status: row.freshness_status, ageSeconds: row.age_seconds == null ? null : Number(row.age_seconds) })),
        sourceProfile: { manualRecords, machineRecords: 0, manualPercent: manualRecords ? 100 : null, machinePercent: manualRecords ? 0 : null }, coverage };
    },

    async recordHarvestActual(client, _trusted, input) {
      if (!isUuid(input?.eventUuid) || !isUuid(input?.cropPlanId) || !isUuid(input?.fieldId)
        || !isUuid(input?.fieldGroupId) || !/^\d{4}-\d{2}-\d{2}$/.test(input?.harvestedOn || "")
        || !Number.isFinite(input?.quantityKg) || input.quantityKg <= 0
        || (input.grade != null && (typeof input.grade !== "string" || !input.grade.length || input.grade.length > 80))
        || (input.note != null && (typeof input.note !== "string" || input.note.length > 1000))) throw new TypeError("invalid harvest actual");
      await requireCapability(client, "analytics:write");
      const harvestId = uuid();
      const result = await client.query(`WITH inserted AS (INSERT INTO app.harvest_actual_event
          (tenant_id, harvest_event_id, event_uuid, crop_plan_id, field_id, field_group_id,
           harvested_on, quantity_kg, grade, note, actor_user_id)
        VALUES (app.current_tenant_id(), $1::uuid, $2::uuid, $3::uuid, $4::uuid, $5::uuid,
          $6::date, $7, $8, $9, app.current_user_id())
        ON CONFLICT (tenant_id, event_uuid) DO NOTHING
        RETURNING harvest_event_id::text AS id, event_uuid::text, crop_plan_id::text, field_id::text,
          field_group_id::text, harvested_on, quantity_kg, grade, note, event_ts)
        SELECT * FROM inserted UNION ALL
        SELECT harvest_event_id::text, event_uuid::text, crop_plan_id::text, field_id::text,
          field_group_id::text, harvested_on, quantity_kg, grade, note, event_ts
        FROM app.harvest_actual_event WHERE tenant_id = app.current_tenant_id() AND event_uuid = $2::uuid
        LIMIT 1`,
      [harvestId, input.eventUuid, input.cropPlanId, input.fieldId, input.fieldGroupId,
        input.harvestedOn, input.quantityKg, input.grade || null, input.note || ""]);
      const row = result.rows[0]; return { id: row.id, eventUuid: row.event_uuid, cropPlanId: row.crop_plan_id,
        fieldId: row.field_id, fieldGroupId: row.field_group_id, harvestedOn: row.harvested_on,
        quantityKg: Number(row.quantity_kg), grade: row.grade, note: row.note, eventTs: iso(row.event_ts) };
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
          AND ($4::boolean = false OR (
            bbox_min_x <= $7 AND bbox_max_x >= $5
            AND bbox_min_y <= $8 AND bbox_max_y >= $6
            AND geom && ST_MakeEnvelope($5, $6, $7, $8, 4326)
          ))
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

    async authorizeOfflineMapPack(client, trusted, fieldGroupId) {
      if (!isUuid(fieldGroupId)) throw new TypeError("invalid field group");
      const result = await client.query(`WITH metric_bounds AS (
          SELECT ST_Extent(ST_Transform(geom, 3857))::box2d AS box
          FROM app.field
          WHERE tenant_id = app.current_tenant_id() AND field_group_id = $1::uuid
            AND deleted_at IS NULL AND app.can_read_scope(field_group_id)
        ), expanded AS (
          SELECT ST_Transform(ST_SetSRID(ST_Envelope(ST_Expand(box, 2000)), 3857), 4326) AS geometry
          FROM metric_bounds WHERE box IS NOT NULL
        )
        SELECT ST_XMin(geometry) AS west, ST_YMin(geometry) AS south,
               ST_XMax(geometry) AS east, ST_YMax(geometry) AS north
        FROM expanded`, [fieldGroupId]);
      if (!result.rows[0]) throw Object.assign(new Error("offline map scope is unavailable"), { code: "forbidden" });
      const row = result.rows[0];
      return { tenantId: trusted.authContext.tenantId, userId: trusted.userId, fieldGroupId,
        assignmentVersion: trusted.membershipVersion, bbox: [Number(row.west), Number(row.south), Number(row.east), Number(row.north)] };
    },

    async listWorkInstructions(client) {
      const result = await client.query(`
        SELECT instruction.instruction_id::text AS id, instruction.field_id::text AS field_id,
               instruction.field_group_id::text AS field_group_id, field.name AS field_name,
               field.crop_name, instruction.title, instruction.work_type, instruction.details,
               instruction.scheduled_start, instruction.scheduled_end, instruction.priority,
               instruction.status, instruction.version, instruction.progress_percent,
               instruction.crop_plan_id::text, plan.variety_name, plan.planned_area_m2, plan.target_yield_kg,
               coalesce((SELECT jsonb_agg(jsonb_build_object(
                 'predecessorInstructionId', dependency.predecessor_instruction_id::text,
                 'type', dependency.dependency_type, 'lagMinutes', dependency.lag_minutes))
                 FROM app.work_instruction_dependency dependency
                 WHERE dependency.tenant_id = instruction.tenant_id
                   AND dependency.successor_instruction_id = instruction.instruction_id), '[]'::jsonb) AS dependencies,
               coalesce((SELECT jsonb_agg(jsonb_build_object(
                 'id', resource.resource_id::text, 'name', resource.name, 'quantity', allocation.quantity))
                 FROM app.work_resource_allocation allocation
                 JOIN app.planning_resource resource USING (tenant_id, resource_id)
                 WHERE allocation.tenant_id = instruction.tenant_id
                   AND allocation.instruction_id = instruction.instruction_id), '[]'::jsonb) AS resources,
               coalesce((SELECT jsonb_agg(jsonb_build_object(
                 'resourceId', conflict.resource_id::text, 'resourceName', conflict.resource_name,
                 'conflictStart', conflict.conflict_start, 'conflictEnd', conflict.conflict_end))
                 FROM app.resource_conflict conflict
                 WHERE conflict.tenant_id = instruction.tenant_id
                   AND (conflict.left_instruction_id = instruction.instruction_id OR conflict.right_instruction_id = instruction.instruction_id)), '[]'::jsonb) AS resource_conflicts,
               assignment.assignment_id::text AS assignment_id,
               assignment.assignee_user_id::text AS assignee_user_id,
               assignment.version AS assignment_version
        FROM app.work_instruction instruction
        JOIN app.field field ON field.tenant_id = instruction.tenant_id AND field.field_id = instruction.field_id
        LEFT JOIN app.crop_plan plan ON plan.tenant_id = instruction.tenant_id AND plan.crop_plan_id = instruction.crop_plan_id
        LEFT JOIN app.work_assignment assignment
          ON assignment.tenant_id = instruction.tenant_id
         AND assignment.instruction_id = instruction.instruction_id
         AND assignment.unassigned_at IS NULL
        WHERE instruction.deleted_at IS NULL
          AND (assignment.assignee_user_id = app.current_user_id() OR app.has_capability('instruction:manage'))
        ORDER BY instruction.scheduled_start, instruction.instruction_id`);
      return { instructions: result.rows.map(workInstructionDto) };
    },

    async listPlanningTemplates(client) {
      const result = await client.query(`SELECT template.template_id::text AS id, template.name, template.crop_name,
          template.description, template.version, template.active,
          coalesce(jsonb_agg(jsonb_build_object('stepKey', step.step_key, 'title', step.title,
            'workType', step.work_type, 'details', step.details, 'startOffsetDays', step.start_offset_days,
            'durationMinutes', step.duration_minutes, 'priority', step.priority,
            'predecessorStepKey', step.predecessor_step_key, 'dependencyType', step.dependency_type,
            'lagMinutes', step.lag_minutes, 'requiredResourceType', step.required_resource_type,
            'requiredQuantity', step.required_quantity, 'sortOrder', step.sort_order)
            ORDER BY step.sort_order, step.step_key) FILTER (WHERE step.step_key IS NOT NULL), '[]'::jsonb) AS steps
        FROM app.work_plan_template template
        LEFT JOIN app.work_plan_template_step step USING (tenant_id, template_id)
        WHERE template.active
        GROUP BY template.tenant_id, template.template_id
        ORDER BY template.name`);
      return { templates: result.rows.map((row) => ({ id: row.id, name: row.name, cropName: row.crop_name || null,
        description: row.description, version: Number(row.version), active: row.active, steps: row.steps })) };
    },

    async expandPlanningTemplate(client, trusted, templateId, input) {
      if (!isUuid(templateId) || !isUuid(input?.cropPlanId) || !isUuid(input?.assigneeUserId)
        || !/^\d{4}-\d{2}-\d{2}$/.test(input?.baseDate || "") || !Number.isInteger(input?.expectedVersion)) throw new TypeError("invalid template expansion");
      await requireCapability(client, "planning:manage");
      await requireCapability(client, "instruction:manage");
      const template = await client.query(`SELECT template.template_id::text, template.version,
          plan.crop_plan_id::text, plan.field_id::text, plan.field_group_id::text
        FROM app.work_plan_template template
        JOIN app.crop_plan plan ON plan.tenant_id = template.tenant_id AND plan.crop_plan_id = $2::uuid
        WHERE template.tenant_id = app.current_tenant_id() AND template.template_id = $1::uuid
          AND template.active AND plan.deleted_at IS NULL FOR UPDATE OF template`, [templateId, input.cropPlanId]);
      if (!template.rows[0]) throw new TypeError("unknown template or crop plan");
      if (Number(template.rows[0].version) !== input.expectedVersion) throw Object.assign(new Error("version conflict"), { code: "version_conflict", currentVersion: Number(template.rows[0].version) });
      const steps = await client.query(`SELECT * FROM app.work_plan_template_step
        WHERE tenant_id = app.current_tenant_id() AND template_id = $1::uuid ORDER BY sort_order, step_key`, [templateId]);
      if (!steps.rows.length) throw new TypeError("template has no steps");
      if (steps.rows.some((step) => step.required_resource_type)) await requireCapability(client, "resource:manage");
      const ids = new Map(); const created = [];
      for (const step of steps.rows) {
        const instructionId = uuid(); const assignmentId = uuid(); ids.set(step.step_key, instructionId);
        const inserted = await client.query(`WITH instruction AS (
          INSERT INTO app.work_instruction
              (tenant_id, instruction_id, field_id, field_group_id, crop_plan_id, title, work_type, details,
               scheduled_start, scheduled_end, priority, created_by, updated_by)
            SELECT app.current_tenant_id(), $1::uuid, $2::uuid, $3::uuid, $4::uuid, $5, $6, $7,
              ($8::date + $9::integer)::timestamp AT TIME ZONE field.timezone,
              (($8::date + $9::integer)::timestamp AT TIME ZONE field.timezone) + make_interval(mins => $10::integer),
              $11, app.current_user_id(), app.current_user_id()
            FROM app.field field
            WHERE field.tenant_id = app.current_tenant_id() AND field.field_id = $2::uuid
            RETURNING *
          ), assignment AS (
            INSERT INTO app.work_assignment (tenant_id, assignment_id, instruction_id, field_group_id, assignee_user_id, assigned_by)
            SELECT tenant_id, $12::uuid, instruction_id, field_group_id, $13::uuid, app.current_user_id() FROM instruction RETURNING *
          ) SELECT instruction.*, assignment.assignment_id::text, assignment.assignee_user_id::text, assignment.version AS assignment_version,
              field.name AS field_name, field.crop_name
            FROM instruction CROSS JOIN assignment, app.field field
            WHERE field.tenant_id = instruction.tenant_id AND field.field_id = instruction.field_id`,
        [instructionId, template.rows[0].field_id, template.rows[0].field_group_id, input.cropPlanId,
          step.title, step.work_type, step.details, input.baseDate, step.start_offset_days, step.duration_minutes,
          step.priority, assignmentId, input.assigneeUserId]);
        created.push(workInstructionDto({ ...inserted.rows[0], id: instructionId, field_id: template.rows[0].field_id,
          field_group_id: template.rows[0].field_group_id, crop_plan_id: input.cropPlanId, dependencies: [], resources: [], resource_conflicts: [] }));
        if (step.required_resource_type) {
          await client.query(`INSERT INTO app.work_resource_allocation
              (tenant_id, allocation_id, instruction_id, resource_id, field_group_id, quantity,
               allocated_start, allocated_end, created_by, updated_by)
            SELECT instruction.tenant_id, $2::uuid, instruction.instruction_id, resource.resource_id,
              instruction.field_group_id, $3, instruction.scheduled_start, instruction.scheduled_end,
              app.current_user_id(), app.current_user_id()
            FROM app.work_instruction instruction
            JOIN LATERAL (SELECT resource_id FROM app.planning_resource
              WHERE tenant_id = instruction.tenant_id AND resource_type = $4 AND status = 'active'
                AND deleted_at IS NULL AND (field_group_id IS NULL OR field_group_id = instruction.field_group_id)
              ORDER BY resource_id LIMIT 1) resource ON true
            WHERE instruction.tenant_id = app.current_tenant_id() AND instruction.instruction_id = $1::uuid`,
          [instructionId, uuid(), Number(step.required_quantity), step.required_resource_type]);
        }
      }
      for (const step of steps.rows) if (step.predecessor_step_key) {
        const predecessor = ids.get(step.predecessor_step_key); const successor = ids.get(step.step_key);
        if (!predecessor) throw new TypeError("unknown predecessor step");
        await client.query(`INSERT INTO app.work_instruction_dependency
          (tenant_id, predecessor_instruction_id, successor_instruction_id, dependency_type, lag_minutes, created_by)
          VALUES (app.current_tenant_id(), $1::uuid, $2::uuid, $3, $4, app.current_user_id())`,
        [predecessor, successor, step.dependency_type, step.lag_minutes]);
      }
      const conflicts = await client.query(`SELECT count(*)::integer AS count FROM app.resource_conflict
        WHERE tenant_id = app.current_tenant_id()
          AND (left_instruction_id = ANY($1::uuid[]) OR right_instruction_id = ANY($1::uuid[]))`, [[...ids.values()]]);
      return { templateId, cropPlanId: input.cropPlanId, instructions: created, conflictCount: Number(conflicts.rows[0].count) };
    },

    async updateWorkProgress(client, trusted, instructionId, input) {
      if (!isUuid(instructionId) || !isUuid(input?.eventUuid) || !Number.isInteger(input?.progressPercent)
        || input.progressPercent < 0 || input.progressPercent > 100 || !Number.isInteger(input?.expectedVersion)
        || typeof input.note !== "string" || input.note.length > 1000) throw new TypeError("invalid progress");
      const locked = await client.query(`SELECT instruction_id::text, field_group_id::text, version
        FROM app.work_instruction WHERE tenant_id = app.current_tenant_id() AND instruction_id = $1::uuid
          AND deleted_at IS NULL FOR UPDATE`, [instructionId]);
      if (!locked.rows[0]) throw new TypeError("unknown instruction");
      if (Number(locked.rows[0].version) !== input.expectedVersion) throw Object.assign(new Error("version conflict"), { code: "version_conflict", currentVersion: Number(locked.rows[0].version) });
      const existing = await client.query(`SELECT progress_percent, event_ts FROM app.work_progress_event
        WHERE tenant_id = app.current_tenant_id() AND event_uuid = $1::uuid`, [input.eventUuid]);
      if (existing.rows[0]) return { id: instructionId, progressPercent: Number(existing.rows[0].progress_percent), status: null, version: input.expectedVersion, updatedAt: iso(existing.rows[0].event_ts) };
      await client.query(`INSERT INTO app.work_progress_event
        (tenant_id, progress_event_id, event_uuid, instruction_id, field_group_id, progress_percent, note, actor_user_id, occurred_at)
        VALUES (app.current_tenant_id(), $1::uuid, $2::uuid, $3::uuid, $4::uuid, $5, $6, app.current_user_id(), $7::timestamptz)`,
      [uuid(), input.eventUuid, instructionId, locked.rows[0].field_group_id, input.progressPercent, input.note, input.occurredAt || new Date().toISOString()]);
      const updated = await client.query(`UPDATE app.work_instruction SET progress_percent = $2::smallint,
          status = CASE WHEN $2::smallint = 100 THEN 'completed' WHEN $2::smallint > 0 THEN 'in_progress' ELSE 'issued' END,
          progress_updated_at = clock_timestamp(), updated_at = clock_timestamp(), updated_by = app.current_user_id(), version = version + 1
        WHERE tenant_id = app.current_tenant_id() AND instruction_id = $1::uuid
        RETURNING progress_percent, status, version, updated_at`, [instructionId, input.progressPercent]);
      return { id: instructionId, progressPercent: Number(updated.rows[0].progress_percent), status: updated.rows[0].status,
        version: Number(updated.rows[0].version), updatedAt: iso(updated.rows[0].updated_at) };
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
               instruction.field_group_id::text AS field_group_id, field.name AS field_name, field.crop_name,
               instruction.title, instruction.work_type,
               instruction.details, instruction.scheduled_start, instruction.scheduled_end,
               instruction.priority, instruction.status, instruction.version,
               assignment.assignment_id::text AS assignment_id,
               assignment.assignee_user_id::text AS assignee_user_id,
               assignment.version AS assignment_version
        FROM inserted_instruction instruction
        JOIN app.field field ON field.tenant_id = instruction.tenant_id AND field.field_id = instruction.field_id
        CROSS JOIN inserted_assignment assignment`,
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
        || !Number.isFinite(Date.parse(attachment.capturedAt)) || typeof attachment.objectKey !== "string") throw new TypeError("invalid attachment");
      await requireCapability(client, "journal:write");
      const result = await client.query(`
        WITH inserted AS (
          INSERT INTO app.journal_attachment
            (tenant_id, attachment_id, journal_id, worker_user_id, file_name, content_type,
             byte_size, sha256, content, captured_at, object_key, storage_status, retention_class)
          VALUES ($1::uuid, $2::uuid, $3::uuid, app.current_user_id(), $4, $5, $6, $7, NULL, $8::timestamptz, $9, 'pending', 'supporting')
          ON CONFLICT (tenant_id, attachment_id) DO NOTHING
          RETURNING attachment_id, journal_id, byte_size, sha256, object_key, storage_status
        )
        SELECT attachment_id::text AS id, journal_id::text AS journal_id, byte_size, sha256, object_key, storage_status FROM inserted
        UNION ALL
        SELECT attachment_id::text, journal_id::text, byte_size, sha256, object_key, storage_status FROM app.journal_attachment
        WHERE tenant_id = $1::uuid AND attachment_id = $2::uuid
        LIMIT 1`,
      [trusted.authContext.tenantId, attachment.attachmentId, attachment.journalId, attachment.fileName, attachment.contentType, attachment.bytes.length, attachment.sha256, attachment.capturedAt, attachment.objectKey]);
      if (result.rows[0]?.sha256 !== attachment.sha256 || result.rows[0]?.object_key !== attachment.objectKey) {
        const error = new Error("attachment id reused"); error.code = "idempotency_conflict"; throw error;
      }
      return { id: result.rows[0].id, journalId: result.rows[0].journal_id, byteSize: Number(result.rows[0].byte_size), sha256: result.rows[0].sha256, objectKey: result.rows[0].object_key, storageStatus: result.rows[0].storage_status };
    },

    async markJournalAttachmentReady(client, _trusted, attachmentId, sha256) {
      const result = await client.query(`UPDATE app.journal_attachment
        SET storage_status = 'ready', ready_at = coalesce(ready_at, clock_timestamp()), last_storage_check_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND attachment_id = $1::uuid AND sha256 = $2
          AND storage_status IN ('pending', 'ready')
        RETURNING attachment_id::text AS id, journal_id::text AS journal_id, byte_size, sha256, object_key, storage_status`, [attachmentId, sha256]);
      if (!result.rows[0]) { const error = new Error("attachment finalization conflict"); error.code = "idempotency_conflict"; throw error; }
      return { id: result.rows[0].id, journalId: result.rows[0].journal_id, byteSize: Number(result.rows[0].byte_size), sha256: result.rows[0].sha256, objectKey: result.rows[0].object_key, storageStatus: result.rows[0].storage_status };
    },

    async getJournalAttachment(client, _trusted, attachmentId) {
      if (!isUuid(attachmentId)) throw new TypeError("invalid attachment");
      const result = await client.query(`SELECT attachment_id::text AS id, journal_id::text AS journal_id,
          file_name, content_type, byte_size, sha256, object_key, storage_status
        FROM app.journal_attachment
        WHERE tenant_id = app.current_tenant_id() AND attachment_id = $1::uuid`, [attachmentId]);
      if (!result.rows[0]) throw new TypeError("unknown attachment");
      const row = result.rows[0];
      return { id: row.id, journalId: row.journal_id, fileName: row.file_name, contentType: row.content_type,
        byteSize: Number(row.byte_size), sha256: row.sha256, objectKey: row.object_key, storageStatus: row.storage_status };
    },

    async listAttachmentStorageRecords(client) {
      await requireCapability(client, "security:manage");
      const result = await client.query(`SELECT attachment_id::text AS id, object_key, storage_status, created_at
        FROM app.journal_attachment
        WHERE tenant_id = app.current_tenant_id() AND object_key IS NOT NULL AND storage_status IN ('pending', 'ready')`);
      return result.rows.map((row) => ({ id: row.id, objectKey: row.object_key, storageStatus: row.storage_status, createdAt: iso(row.created_at) }));
    },

    async applyAttachmentReconciliation(client, _trusted, { readyAttachmentIds, missingAttachmentIds }) {
      await requireCapability(client, "security:manage");
      const ready = readyAttachmentIds.filter(isUuid);
      const missing = missingAttachmentIds.filter(isUuid);
      if (ready.length) await client.query(`UPDATE app.journal_attachment SET storage_status = 'ready',
          ready_at = coalesce(ready_at, clock_timestamp()), last_storage_check_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND attachment_id = ANY($1::uuid[]) AND storage_status = 'pending'`, [ready]);
      if (missing.length) await client.query(`UPDATE app.journal_attachment SET storage_status = 'quarantined',
          last_storage_check_at = clock_timestamp()
        WHERE tenant_id = app.current_tenant_id() AND attachment_id = ANY($1::uuid[]) AND storage_status = 'pending'`, [missing]);
      return { finalized: ready.length, quarantined: missing.length };
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
          AND attachment.storage_status IN ('legacy', 'ready')
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

    async requestPesticideMasterReview(client, trusted, input) {
      await requireCapability(client, "pesticide:manage");
      if (!input?.release || typeof input.reason !== "string" || input.reason.trim().length < 10
        || typeof input.ticketRef !== "string" || !input.ticketRef.trim()) throw new TypeError("invalid pesticide review");
      validateMasterRelease(input.release);
      const tenantId = trusted.authContext.tenantId;
      const current = await client.query(`SELECT jsonb_build_object('id', release_id, 'version', version,
          'validUntil', valid_until, 'publishedAt', published_at) AS value
        FROM app.pesticide_master_release WHERE tenant_id = $1::uuid ORDER BY published_at DESC LIMIT 1`, [tenantId]);
      const requestId = uuid();
      const saved = await client.query(`INSERT INTO app.pesticide_master_review_request
        (tenant_id, request_id, proposed_release, before_release, reason, ticket_ref, requested_by)
        VALUES ($1::uuid,$2::uuid,$3::jsonb,$4::jsonb,$5,$6,app.current_user_id())
        RETURNING request_id::text AS id, proposed_release, before_release, status, reason, ticket_ref,
          requested_by::text, requested_at`,
      [tenantId, requestId, input.release, current.rows[0]?.value || null, input.reason.trim(), input.ticketRef.trim()]);
      const row = saved.rows[0];
      return { id: row.id, proposedRelease: row.proposed_release, beforeRelease: row.before_release, status: row.status,
        reason: row.reason, ticketRef: row.ticket_ref, requestedBy: row.requested_by, requestedAt: iso(row.requested_at) };
    },

    async listPesticideMasterReviews(client) {
      await requireCapability(client, "pesticide:manage");
      const result = await client.query(`SELECT request_id::text AS id, proposed_release, before_release, status,
          reason, ticket_ref, requested_by::text, requested_at, decided_by::text, decided_at, decision_note,
          release_id::text FROM app.pesticide_master_review_request
        WHERE tenant_id = app.current_tenant_id() ORDER BY requested_at DESC LIMIT 200`);
      return { reviews: result.rows.map((row) => ({ id: row.id, proposedRelease: row.proposed_release,
        beforeRelease: row.before_release, status: row.status, reason: row.reason, ticketRef: row.ticket_ref,
        requestedBy: row.requested_by, requestedAt: iso(row.requested_at), decidedBy: row.decided_by || null,
        decidedAt: iso(row.decided_at) || null, decisionNote: row.decision_note || null, releaseId: row.release_id || null })) };
    },

    async decidePesticideMasterReview(client, trusted, reviewId, input) {
      await requireCapability(client, "pesticide:manage");
      if (!isUuid(reviewId) || !["approve", "reject"].includes(input?.decision) || !input.note?.trim()) throw new TypeError("invalid pesticide review decision");
      const locked = await client.query(`SELECT proposed_release, requested_by::text FROM app.pesticide_master_review_request
        WHERE tenant_id = app.current_tenant_id() AND request_id = $1::uuid AND status = 'pending' FOR UPDATE`, [reviewId]);
      if (!locked.rows[0]) throw new TypeError("unknown pesticide review");
      if (locked.rows[0].requested_by === trusted.userId) throw Object.assign(new Error("requester cannot approve"), { code: "forbidden" });
      if (input.decision === "reject") {
        await client.query(`UPDATE app.pesticide_master_review_request SET status='rejected', decided_by=app.current_user_id(),
          decided_at=clock_timestamp(), decision_note=$2 WHERE tenant_id=app.current_tenant_id() AND request_id=$1::uuid`, [reviewId, input.note.trim()]);
        return { id: reviewId, status: "rejected" };
      }
      const release = await repository.publishPesticideMaster(client, trusted, locked.rows[0].proposed_release);
      await client.query(`UPDATE app.pesticide_master_review_request SET status='published', decided_by=app.current_user_id(),
        decided_at=clock_timestamp(), decision_note=$2, release_id=$3::uuid
        WHERE tenant_id=app.current_tenant_id() AND request_id=$1::uuid`, [reviewId, input.note.trim(), release.id]);
      return { id: reviewId, status: "published", release };
    },

    async listInventory(client) {
      const [balances, alerts, incoming, lots, counts] = await Promise.all([
        client.query(`SELECT chemical.chemical_id::text AS chemical_id, chemical.name, chemical.registration_number,
            coalesce(balance.quantity, 0) AS quantity, balance.updated_at,
            policy.reorder_point, policy.target_level, policy.safety_stock, policy.allow_negative
          FROM app.agrochemical chemical
          JOIN app.pesticide_master_release release
            ON release.tenant_id = chemical.tenant_id AND release.release_id = chemical.release_id
          LEFT JOIN app.stock_balance balance
            ON balance.tenant_id = chemical.tenant_id AND balance.chemical_id = chemical.chemical_id
          LEFT JOIN app.inventory_policy policy
            ON policy.tenant_id = chemical.tenant_id AND policy.chemical_id = chemical.chemical_id
           AND policy.status = 'active' AND policy.deleted_at IS NULL
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
        client.query(`SELECT incoming.purchase_order_line_id::text, incoming.purchase_order_id::text,
            incoming.order_number, incoming.supplier_name, incoming.chemical_id::text,
            chemical.name, incoming.incoming_quantity, incoming.unit, incoming.unit_cost,
            incoming.currency, incoming.expected_on
          FROM app.incoming_stock incoming JOIN app.agrochemical chemical USING (tenant_id,chemical_id)
          ORDER BY incoming.expected_on NULLS LAST,incoming.order_number`),
        client.query(`SELECT balance.lot_id::text,balance.chemical_id::text,chemical.name,balance.lot_number,
            balance.supplier_name,balance.received_on,balance.expires_on,balance.unit,balance.unit_cost,
            balance.currency,balance.status,balance.quantity,balance.quantity*balance.unit_cost AS inventory_value,
            balance.updated_at
          FROM app.inventory_lot_balance balance JOIN app.agrochemical chemical USING (tenant_id,chemical_id)
          ORDER BY balance.expires_on NULLS LAST,balance.lot_number`),
        client.query(`SELECT session.count_session_id::text AS id,session.location_name,session.counted_at,
            session.status,session.version,count(line.count_line_id)::integer AS line_count,
            coalesce(sum(abs(line.variance)),0) AS absolute_variance
          FROM app.inventory_count_session session LEFT JOIN app.inventory_count_line line USING (tenant_id,count_session_id)
          GROUP BY session.tenant_id,session.count_session_id ORDER BY session.counted_at DESC LIMIT 50`),
      ]);
      return {
        balances: balances.rows.map((row) => ({ chemicalId: row.chemical_id, name: row.name, registrationNumber: row.registration_number, quantity: Number(row.quantity), updatedAt: iso(row.updated_at) || null,
          policy: row.reorder_point == null ? null : { reorderPoint: Number(row.reorder_point), targetLevel: Number(row.target_level), safetyStock: Number(row.safety_stock), allowNegative: row.allow_negative },
          belowReorderPoint: row.reorder_point != null && Number(row.quantity) <= Number(row.reorder_point) })),
        alerts: alerts.rows.map((row) => ({ id: row.id, chemicalId: row.chemical_id, name: row.name, negativeQuantity: Number(row.negative_quantity), triggeringEventId: row.triggering_event_id, status: row.status, createdAt: iso(row.created_at) })),
        incoming: incoming.rows.map((row) => ({ purchaseOrderLineId: row.purchase_order_line_id, purchaseOrderId: row.purchase_order_id,
          orderNumber: row.order_number, supplierName: row.supplier_name, chemicalId: row.chemical_id, name: row.name,
          incomingQuantity: Number(row.incoming_quantity), unit: row.unit, unitCost: Number(row.unit_cost), currency: row.currency, expectedOn: row.expected_on })),
        lots: lots.rows.map((row) => ({ id: row.lot_id, chemicalId: row.chemical_id, name: row.name, lotNumber: row.lot_number,
          supplierName: row.supplier_name, receivedOn: row.received_on, expiresOn: row.expires_on || null, unit: row.unit,
          unitCost: Number(row.unit_cost), currency: row.currency, status: row.status, quantity: Number(row.quantity),
          inventoryValue: Number(row.inventory_value), updatedAt: iso(row.updated_at) || null })),
        counts: counts.rows.map((row) => ({ id: row.id, locationName: row.location_name, countedAt: iso(row.counted_at),
          status: row.status, version: Number(row.version), lineCount: Number(row.line_count), absoluteVariance: Number(row.absolute_variance) })),
      };
    },

    async createPurchaseOrder(client, trusted, input) {
      await requireCapability(client, "inventory:adjust");
      if (!input || typeof input.orderNumber !== "string" || !input.orderNumber.trim() || typeof input.supplierName !== "string" || !input.supplierName.trim()
        || !/^\d{4}-\d{2}-\d{2}$/.test(input.orderedOn || "") || (input.expectedOn != null && !/^\d{4}-\d{2}-\d{2}$/.test(input.expectedOn))
        || (input.currency != null && !/^[A-Z]{3}$/.test(input.currency)) || !Array.isArray(input.lines) || !input.lines.length || input.lines.length > 500) throw new TypeError("invalid purchase order");
      for (const line of input.lines) if (!isUuid(line.chemicalId) || !Number.isFinite(line.orderedQuantity) || line.orderedQuantity <= 0
        || !Number.isFinite(line.unitCost) || line.unitCost < 0 || typeof line.unit !== "string" || !line.unit.trim()) throw new TypeError("invalid purchase order line");
      const orderId = uuid();
      const order = await client.query(`INSERT INTO app.purchase_order
        (tenant_id,purchase_order_id,order_number,supplier_name,ordered_on,expected_on,currency,note,created_by,updated_by)
        VALUES(app.current_tenant_id(),$1::uuid,$2,$3,$4::date,$5::date,$6,$7,app.current_user_id(),app.current_user_id())
        RETURNING purchase_order_id::text AS id,order_number,supplier_name,ordered_on,expected_on,status,currency,note,version`,
      [orderId,input.orderNumber.trim(),input.supplierName.trim(),input.orderedOn,input.expectedOn || null,input.currency || "JPY",input.note || ""]);
      const lines = [];
      for (const line of input.lines) {
        const result = await client.query(`INSERT INTO app.purchase_order_line
          (tenant_id,purchase_order_line_id,purchase_order_id,chemical_id,ordered_quantity,unit,unit_cost,expected_on)
          VALUES(app.current_tenant_id(),$1::uuid,$2::uuid,$3::uuid,$4,$5,$6,$7::date)
          RETURNING purchase_order_line_id::text AS id,chemical_id::text,ordered_quantity,received_quantity,unit,unit_cost,expected_on,version`,
        [uuid(),orderId,line.chemicalId,line.orderedQuantity,line.unit.trim(),line.unitCost,line.expectedOn || input.expectedOn || null]);
        lines.push({ id: result.rows[0].id, chemicalId: result.rows[0].chemical_id, orderedQuantity: Number(result.rows[0].ordered_quantity),
          receivedQuantity: 0, unit: result.rows[0].unit, unitCost: Number(result.rows[0].unit_cost), expectedOn: result.rows[0].expected_on, version: Number(result.rows[0].version) });
      }
      return { id: order.rows[0].id, orderNumber: order.rows[0].order_number, supplierName: order.rows[0].supplier_name,
        orderedOn: order.rows[0].ordered_on, expectedOn: order.rows[0].expected_on, status: order.rows[0].status,
        currency: order.rows[0].currency, note: order.rows[0].note, version: Number(order.rows[0].version), lines };
    },

    async receiveInventoryLot(client, trusted, input) {
      await requireCapability(client, "inventory:adjust"); await requireCapability(client, "inventory:write");
      if (!isUuid(input?.purchaseOrderLineId) || !isUuid(input?.eventUuid) || !isUuid(input?.lotId)
        || typeof input.lotNumber !== "string" || !input.lotNumber.trim() || !/^\d{4}-\d{2}-\d{2}$/.test(input.receivedOn || "")
        || (input.expiresOn != null && !/^\d{4}-\d{2}-\d{2}$/.test(input.expiresOn)) || !Number.isFinite(input.quantity) || input.quantity <= 0) throw new TypeError("invalid inventory receipt");
      const duplicate = await client.query(`SELECT event.stock_event_id::text,lot.lot_id::text,lot.chemical_id::text,lot.lot_number,
          lot.expires_on,lot.unit,lot.unit_cost,lot.currency,purchase.status
        FROM app.stock_event event JOIN app.stock_lot lot USING(tenant_id,lot_id)
        LEFT JOIN app.purchase_order_line line USING(tenant_id,purchase_order_line_id)
        LEFT JOIN app.purchase_order purchase USING(tenant_id,purchase_order_id)
        WHERE event.tenant_id=app.current_tenant_id() AND event.event_uuid=$1::uuid`,[input.eventUuid]);
      if(duplicate.rows[0]) return {lot:{id:duplicate.rows[0].lot_id,chemicalId:duplicate.rows[0].chemical_id,lotNumber:duplicate.rows[0].lot_number,
        expiresOn:duplicate.rows[0].expires_on||null,unit:duplicate.rows[0].unit,unitCost:Number(duplicate.rows[0].unit_cost),currency:duplicate.rows[0].currency},
        purchaseOrderStatus:duplicate.rows[0].status,stockEventId:duplicate.rows[0].stock_event_id,duplicate:true};
      const locked = await client.query(`SELECT line.*,purchase.supplier_name,purchase.currency,purchase.order_number
        FROM app.purchase_order_line line JOIN app.purchase_order purchase USING(tenant_id,purchase_order_id)
        WHERE line.tenant_id=app.current_tenant_id() AND line.purchase_order_line_id=$1::uuid FOR UPDATE OF line,purchase`, [input.purchaseOrderLineId]);
      const line = locked.rows[0];
      if (!line || Number(line.received_quantity)+input.quantity>Number(line.ordered_quantity)) throw new TypeError("receipt exceeds order");
      await client.query(`INSERT INTO app.stock_lot
        (tenant_id,lot_id,chemical_id,purchase_order_line_id,lot_number,supplier_name,received_on,expires_on,
         initial_quantity,unit,unit_cost,currency,created_by,updated_by)
        VALUES(app.current_tenant_id(),$1::uuid,$2::uuid,$3::uuid,$4,$5,$6::date,$7::date,$8,$9,$10,$11,app.current_user_id(),app.current_user_id())`,
      [input.lotId,line.chemical_id,input.purchaseOrderLineId,input.lotNumber.trim(),line.supplier_name,input.receivedOn,input.expiresOn || null,input.quantity,line.unit,line.unit_cost,line.currency]);
      const stockEventId=uuid();
      await client.query(`INSERT INTO app.stock_event
        (tenant_id,stock_event_id,event_uuid,chemical_id,lot_id,event_type,quantity_delta,reason,unit_cost,currency,jgap_attributes,occurred_at,event_ts,actor_user_id)
        VALUES(app.current_tenant_id(),$1::uuid,$2::uuid,$3::uuid,$4::uuid,'receipt',$5,$6,$7,$8,$9::jsonb,$10::timestamptz,statement_timestamp(),app.current_user_id())`,
      [stockEventId,input.eventUuid,line.chemical_id,input.lotId,input.quantity,input.reason || `発注 ${line.order_number} 入荷`,line.unit_cost,line.currency,JSON.stringify(input.jgapAttributes || {}),`${input.receivedOn}T00:00:00Z`]);
      await client.query(`UPDATE app.purchase_order_line SET received_quantity=received_quantity+$2,version=version+1
        WHERE tenant_id=app.current_tenant_id() AND purchase_order_line_id=$1::uuid`,[input.purchaseOrderLineId,input.quantity]);
      const status=await client.query(`UPDATE app.purchase_order purchase SET status=CASE WHEN NOT EXISTS(
          SELECT 1 FROM app.purchase_order_line line WHERE line.tenant_id=purchase.tenant_id AND line.purchase_order_id=purchase.purchase_order_id AND line.received_quantity<line.ordered_quantity
        ) THEN 'received' ELSE 'partially_received' END,version=version+1,updated_at=clock_timestamp(),updated_by=app.current_user_id()
        WHERE tenant_id=app.current_tenant_id() AND purchase_order_id=$1::uuid RETURNING status`,[line.purchase_order_id]);
      return { lot: { id: input.lotId, chemicalId: line.chemical_id, lotNumber: input.lotNumber, quantity: input.quantity,
        expiresOn: input.expiresOn || null, unit: line.unit, unitCost: Number(line.unit_cost), currency: line.currency }, purchaseOrderStatus: status.rows[0].status, stockEventId };
    },

    async createInventoryCount(client, trusted, input) {
      await requireCapability(client,"inventory:adjust");
      if (!input || typeof input.locationName!=="string" || !input.locationName.trim() || !Number.isFinite(Date.parse(input.countedAt))
        || !Array.isArray(input.lines) || !input.lines.length || input.lines.length>1000) throw new TypeError("invalid inventory count");
      const sessionId=uuid();
      await client.query(`INSERT INTO app.inventory_count_session
        (tenant_id,count_session_id,location_name,counted_at,note,created_by)
        VALUES(app.current_tenant_id(),$1::uuid,$2,$3::timestamptz,$4,app.current_user_id())`,[sessionId,input.locationName.trim(),input.countedAt,input.note||""]);
      const lines=[];
      for(const line of input.lines){
        if(!isUuid(line.chemicalId)||(line.lotId!=null&&!isUuid(line.lotId))||!Number.isFinite(line.countedQuantity)||line.countedQuantity<0||typeof line.unit!=="string") throw new TypeError("invalid inventory count line");
        const balance=await client.query(line.lotId
          ? "SELECT quantity FROM app.inventory_lot_balance WHERE tenant_id=app.current_tenant_id() AND chemical_id=$1::uuid AND lot_id=$2::uuid"
          : "SELECT quantity FROM app.stock_balance WHERE tenant_id=app.current_tenant_id() AND chemical_id=$1::uuid",[line.chemicalId,...(line.lotId?[line.lotId]:[])]);
        const systemQuantity=Number(balance.rows[0]?.quantity||0); const lineId=uuid();
        const inserted=await client.query(`INSERT INTO app.inventory_count_line
          (tenant_id,count_session_id,count_line_id,chemical_id,lot_id,system_quantity,counted_quantity,unit,reason,jgap_attributes)
          VALUES(app.current_tenant_id(),$1::uuid,$2::uuid,$3::uuid,$4::uuid,$5,$6,$7,$8,$9::jsonb)
          RETURNING count_line_id::text AS id,chemical_id::text,lot_id::text,system_quantity,counted_quantity,variance,unit,reason`,
        [sessionId,lineId,line.chemicalId,line.lotId||null,systemQuantity,line.countedQuantity,line.unit,line.reason||"",JSON.stringify(line.jgapAttributes||{})]);
        lines.push({...inserted.rows[0],systemQuantity:Number(inserted.rows[0].system_quantity),countedQuantity:Number(inserted.rows[0].counted_quantity),variance:Number(inserted.rows[0].variance)});
      }
      return {id:sessionId,locationName:input.locationName,countedAt:input.countedAt,status:"draft",version:1,lines};
    },

    async postInventoryCount(client, trusted, sessionId, input) {
      await requireCapability(client,"inventory:adjust"); await requireCapability(client,"inventory:write");
      if(!isUuid(sessionId)||!Number.isInteger(input?.expectedVersion)||typeof input?.eventUuids!=="object") throw new TypeError("invalid count posting");
      const session=await client.query(`SELECT count_session_id::text,created_by::text,version,status,location_name,counted_at
        FROM app.inventory_count_session WHERE tenant_id=app.current_tenant_id() AND count_session_id=$1::uuid FOR UPDATE`,[sessionId]);
      if(!session.rows[0]||session.rows[0].status!=="draft") throw new TypeError("count not postable");
      if(session.rows[0].created_by===trusted.userId) throw Object.assign(new Error("independent count review required"),{code:"forbidden"});
      if(Number(session.rows[0].version)!==input.expectedVersion) throw Object.assign(new Error("version conflict"),{code:"version_conflict",currentVersion:Number(session.rows[0].version)});
      const lines=await client.query(`SELECT * FROM app.inventory_count_line WHERE tenant_id=app.current_tenant_id() AND count_session_id=$1::uuid ORDER BY count_line_id`,[sessionId]);
      for(const line of lines.rows){
        if(Number(line.variance)===0) continue; const eventUuid=input.eventUuids[line.count_line_id]; if(!isUuid(eventUuid)) throw new TypeError("missing count event UUID");
        await client.query(`INSERT INTO app.stock_event
          (tenant_id,stock_event_id,event_uuid,chemical_id,lot_id,event_type,quantity_delta,reason,jgap_attributes,occurred_at,event_ts,actor_user_id)
          VALUES(app.current_tenant_id(),$1::uuid,$2::uuid,$3::uuid,$4::uuid,'adjustment',$5,$6,$7::jsonb,$8::timestamptz,statement_timestamp(),app.current_user_id())`,
        [uuid(),eventUuid,line.chemical_id,line.lot_id,Number(line.variance),line.reason||`棚卸し ${session.rows[0].location_name}`,JSON.stringify(line.jgap_attributes||{}),session.rows[0].counted_at]);
      }
      const posted=await client.query(`UPDATE app.inventory_count_session SET status='posted',reviewed_by=app.current_user_id(),posted_by=app.current_user_id(),
        posted_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 WHERE tenant_id=app.current_tenant_id() AND count_session_id=$1::uuid
        RETURNING status,version,posted_at`,[sessionId]);
      return {id:sessionId,status:posted.rows[0].status,version:Number(posted.rows[0].version),postedAt:iso(posted.rows[0].posted_at),adjustmentCount:lines.rows.filter((line)=>Number(line.variance)!==0).length};
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
      } else if (dataset === "jgap-inventory") {
        headers = ["受入・使用日時","農薬名","登録番号","ロット番号","有効期限","仕入先","区分","数量","単位","単価","通貨","理由","記録者ID","JGAP属性"];
        fileName = `jgap-inventory-${stamp}.csv`;
        result = await client.query(`SELECT event.event_ts::text AS "受入・使用日時",chemical.name AS "農薬名",
            chemical.registration_number AS "登録番号",coalesce(lot.lot_number,'') AS "ロット番号",
            coalesce(lot.expires_on::text,'') AS "有効期限",coalesce(lot.supplier_name,'') AS "仕入先",
            event.event_type AS "区分",event.quantity_delta::text AS "数量",coalesce(lot.unit,'') AS "単位",
            coalesce(event.unit_cost,lot.unit_cost)::text AS "単価",coalesce(event.currency,lot.currency,'') AS "通貨",
            event.reason AS "理由",event.actor_user_id::text AS "記録者ID",event.jgap_attributes::text AS "JGAP属性"
          FROM app.stock_event event JOIN app.agrochemical chemical USING(tenant_id,chemical_id)
          LEFT JOIN app.stock_lot lot USING(tenant_id,lot_id)
          WHERE event.tenant_id=app.current_tenant_id()
            AND ($1::date IS NULL OR event.event_ts::date >= $1::date)
            AND ($2::date IS NULL OR event.event_ts::date <= $2::date)
          ORDER BY event.event_ts,event.stock_event_id LIMIT 100001`,[from,to]);
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
