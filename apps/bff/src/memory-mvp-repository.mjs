const CAPABILITY_BY_KIND = {
  journal: "journal:write",
  pesticide: "pesticide:write",
  punch: "punch:write",
  stock: "inventory:write",
};

function clone(value) {
  return structuredClone(value);
}

export function createMemoryMvpRepository({ tasks = [], fields = [], workInstructions = [], workJournals = [], pesticideRelease = null, agrochemicals = [], stockEvents = [] } = {}) {
  const receipts = new Map();
  const changes = [];
  const rejections = [];
  const conflicts = [];
  let sequence = 0;
  const instructions = clone(workInstructions);
  const attachments = [];
  const journals = clone(workJournals);
  const revisions = [];
  let currentPesticideRelease = clone(pesticideRelease);
  const chemicals = clone(agrochemicals);
  const inventoryEvents = clone(stockEvents);
  const pesticideUsages = [];
  const pesticideAlerts = [];
  const stockAlerts = [];
  const migrationJobs = [];

  const database = {
    async transaction(_trusted, operation) { return operation({}); },
  };

  const repository = {
    async getToday(_client, trusted) {
      return { tasks: clone(tasks.filter((task) => !task.tenantId || task.tenantId === trusted.authContext.tenantId)), serverTime: new Date().toISOString() };
    },

    async searchFields(_client, trusted, { query, limit, cursor }) {
      const visible = fields.filter((field) => (!field.tenantId || field.tenantId === trusted.authContext.tenantId)
        && (!query || field.properties.name.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
        && (!cursor || field.id > cursor)).sort((a, b) => a.id.localeCompare(b.id)).slice(0, limit + 1);
      const page = visible.slice(0, limit);
      return { type: "FeatureCollection", features: clone(page), nextCursor: visible.length > limit ? page.at(-1).id : null };
    },

    async listWorkInstructions(_client, trusted) {
      const manager = trusted.authContext.capabilities.includes("instruction:manage");
      return { instructions: clone(instructions.filter((item) => item.tenantId === trusted.authContext.tenantId && (manager || item.assignment?.assigneeUserId === trusted.userId))) };
    },

    async createWorkInstruction(_client, trusted, input) {
      if (!trusted.authContext.capabilities.includes("instruction:manage")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      const field = fields.find((candidate) => candidate.id === input.fieldId && (!candidate.tenantId || candidate.tenantId === trusted.authContext.tenantId));
      const item = { ...clone(input), fieldName: field?.properties.name || null, cropName: field?.properties.cropName || null, id: `instruction-${instructions.length + 1}`, tenantId: trusted.authContext.tenantId, version: 1, status: "issued", assignment: { id: `assignment-${instructions.length + 1}`, assigneeUserId: input.assigneeUserId, version: 1 } };
      instructions.push(item); return clone(item);
    },

    async reassignWorkInstruction(_client, trusted, instructionId, input) {
      if (!trusted.authContext.capabilities.includes("instruction:manage")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      const item = instructions.find((row) => row.id === instructionId && row.tenantId === trusted.authContext.tenantId);
      if (!item) throw new TypeError("unknown instruction");
      if (item.version !== input.expectedVersion) { const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = item.version; throw error; }
      item.version += 1; item.assignment = { id: `assignment-${instructions.length + item.version}`, assigneeUserId: input.assigneeUserId, version: 1 };
      return { id: item.id, assignmentId: item.assignment.id, assigneeUserId: input.assigneeUserId, version: item.version };
    },

    async getJournalBootstrap() {
      return { instruction: null, punchSuggestion: { startedAt: null, endedAt: null, warning: "missing_start" }, templates: [], previous: null };
    },

    async saveJournalAttachment(_client, trusted, attachment) {
      if (!trusted.authContext.capabilities.includes("journal:write")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      const existing = attachments.find((item) => item.id === attachment.attachmentId && item.tenantId === trusted.authContext.tenantId);
      if (existing) return clone(existing);
      const item = { id: attachment.attachmentId, journalId: attachment.journalId, tenantId: trusted.authContext.tenantId, byteSize: attachment.bytes.length, sha256: attachment.sha256 };
      attachments.push(item); return clone(item);
    },

    async listJournals(_client, trusted) {
      const reviewer = trusted.authContext.capabilities.includes("journal:review");
      return { journals: clone(journals.filter((item) => item.tenantId === trusted.authContext.tenantId && (reviewer || item.workerUserId === trusted.userId))) };
    },

    async reviewJournal(_client, trusted, journalId, input) {
      if (!trusted.authContext.capabilities.includes("journal:review")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      if (!["approve", "return"].includes(input.action) || !Number.isInteger(input.expectedVersion) || (input.action === "return" && (typeof input.reason !== "string" || !input.reason.trim()))) throw new TypeError("invalid review");
      const item = journals.find((row) => row.id === journalId && row.tenantId === trusted.authContext.tenantId);
      if (!item) throw new TypeError("unknown journal");
      if (item.version !== input.expectedVersion) { const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = item.version; throw error; }
      item.status = input.action === "approve" ? "approved" : "returned"; item.returnReason = input.action === "return" ? input.reason : null; item.version += 1;
      return { id: item.id, status: item.status, version: item.version, updatedAt: new Date().toISOString() };
    },

    async getPesticideBootstrap(_client, trusted, { fieldId }) {
      const field = fields.find((item) => item.id === fieldId && (!item.tenantId || item.tenantId === trusted.authContext.tenantId));
      if (!field) throw new TypeError("unknown field");
      const tenantChemicals = chemicals.filter((item) => !item.tenantId || item.tenantId === trusted.authContext.tenantId);
      return {
        field: { id: field.id, fieldGroupId: field.properties.fieldGroupId || null, name: field.properties.name, cropName: field.properties.cropName, timezone: field.properties.timezone || "Asia/Tokyo" },
        release: clone(currentPesticideRelease), chemicals: clone(tenantChemicals),
        usage: [],
        inventory: tenantChemicals.map((chemical) => ({ chemicalId: chemical.id,
          quantity: inventoryEvents.filter((item) => item.chemicalId === chemical.id).reduce((sum, item) => sum + item.quantityDelta, 0), updatedAt: null })),
      };
    },

    async publishPesticideMaster(_client, trusted, input) {
      if (!trusted.authContext.capabilities.includes("pesticide:manage")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      if (input.expectedVersion !== undefined && (currentPesticideRelease?.version || null) !== input.expectedVersion) {
        const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = currentPesticideRelease?.version || null; throw error;
      }
      currentPesticideRelease = { id: `release-${Date.now()}`, version: input.version, validUntil: input.validUntil, publishedAt: new Date().toISOString(), syncedAt: new Date().toISOString() };
      chemicals.splice(0, chemicals.length, ...input.chemicals.map((item, index) => ({ ...clone(item), id: item.id || `chemical-${index + 1}`, tenantId: trusted.authContext.tenantId })));
      return { ...clone(currentPesticideRelease), chemicalCount: chemicals.length };
    },

    async listInventory(_client, trusted) {
      const balances = chemicals.filter((item) => !item.tenantId || item.tenantId === trusted.authContext.tenantId).map((chemical) => ({
        chemicalId: chemical.id, name: chemical.name, registrationNumber: chemical.registrationNumber,
        quantity: inventoryEvents.filter((item) => item.chemicalId === chemical.id).reduce((sum, item) => sum + item.quantityDelta, 0), updatedAt: null,
      }));
      return { balances, alerts: clone(stockAlerts.filter((item) => item.tenantId === trusted.authContext.tenantId && item.status === "pending")) };
    },

    async createMigrationJob(_client, trusted, input) {
      if (!trusted.authContext.capabilities.includes("migration:manage")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      const existing = migrationJobs.find((item) => item.tenantId === trusted.authContext.tenantId && item.idempotencyKey === input.idempotencyKey);
      if (existing) {
        if (existing.sourceSha256 !== input.sourceSha256) { const error = new Error("idempotency conflict"); error.code = "idempotency_conflict"; throw error; }
        return clone(existing);
      }
      const rows = input.rows.map((row) => clone(row));
      const job = { id: `0198a6c0-0000-7000-8000-${String(migrationJobs.length + 1).padStart(12, "0")}`,
        tenantId: trusted.authContext.tenantId, idempotencyKey: input.idempotencyKey, dataset: input.dataset,
        sourceName: input.sourceName, sourceSha256: input.sourceSha256, mapping: clone(input.mapping),
        status: rows.some((row) => row.status === "invalid") ? "needs_review" : "validated",
        rowCount: rows.length, validCount: rows.filter((row) => row.status === "valid").length,
        duplicateCount: rows.filter((row) => row.status === "duplicate").length,
        errorCount: rows.filter((row) => row.status === "invalid").length, version: 1,
        createdAt: new Date().toISOString(), committedAt: null, rows };
      migrationJobs.push(job); return clone(job);
    },

    async listMigrationJobs(_client, trusted) {
      if (!trusted.authContext.capabilities.includes("migration:manage")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      return { jobs: clone(migrationJobs.filter((item) => item.tenantId === trusted.authContext.tenantId).map(({ rows: _rows, ...item }) => item)) };
    },

    async commitMigrationJob(_client, trusted, jobId, input) {
      if (!trusted.authContext.capabilities.includes("migration:manage")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      const job = migrationJobs.find((item) => item.id === jobId && item.tenantId === trusted.authContext.tenantId);
      if (!job || job.status !== "validated") throw new TypeError("migration job is not committable");
      if (job.version !== input.expectedVersion) { const error = new Error("version conflict"); error.code = "version_conflict"; error.currentVersion = job.version; throw error; }
      job.status = "committed"; job.version += 2; job.committedAt = new Date().toISOString();
      for (const row of job.rows) if (row.status === "valid") row.status = "committed";
      return clone(job);
    },

    async exportCsv(_client, trusted, dataset) {
      if (!trusted.authContext.capabilities.includes("export:read")) { const error = new Error("forbidden"); error.code = "forbidden"; throw error; }
      if (dataset === "fields") return { fileName: "fields.csv", headers: ["圃場コード", "圃場名", "作物"], rows: fields.filter((item) => !item.tenantId || item.tenantId === trusted.authContext.tenantId).map((item) => ({ "圃場コード": item.externalKey || item.id, "圃場名": item.properties.name, "作物": item.properties.cropName || "" })) };
      if (dataset === "journals") return { fileName: "work-journals.csv", headers: ["記録コード", "圃場名", "作業種別", "メモ"], rows: journals.filter((item) => item.tenantId === trusted.authContext.tenantId).map((item) => ({ "記録コード": item.externalKey || item.id, "圃場名": item.fieldName || "", "作業種別": item.body.workType || "", "メモ": item.body.memo || "" })) };
      if (dataset === "pesticide-records") return { fileName: "pesticide-records.csv", headers: ["散布日", "作物", "薬剤名"], rows: pesticideUsages.filter((item) => item.tenantId === trusted.authContext.tenantId).map((item) => ({ "散布日": item.appliedOn || "", "作物": item.cropName || "", "薬剤名": chemicals.find((chemical) => chemical.id === item.chemicalId)?.name || "" })) };
      throw new TypeError("invalid export dataset");
    },

    async pushBundle(_client, trusted, bundle) {
      const tenantId = trusted.authContext.tenantId;
      const duplicate = bundle.events.every((event) => receipts.has(`${tenantId}:${event.eventUuid}`));
      if (duplicate) {
        return { bundleId: bundle.bundleId, status: "duplicate", events: bundle.events.map((event) => clone(receipts.get(`${tenantId}:${event.eventUuid}`))) };
      }

      const invalid = bundle.events.find((event) => {
        const required = CAPABILITY_BY_KIND[event.kind];
        return !required || !trusted.authContext.capabilities.includes(required)
          || event.membershipVersion !== trusted.membershipVersion
          || event.authorizationSnapshotId !== trusted.authorizationSnapshotId;
      });
      const lockedJournal = bundle.events.find((event) => event.kind === "journal" && journals.some((journal) => journal.id === event.payload.aggregateId && journal.tenantId === tenantId && journal.status === "approved"));
      const unauthorizedAdjustment = bundle.events.find((event) => event.kind === "stock" && event.payload.eventType === "adjustment" && !trusted.authContext.capabilities.includes("inventory:adjust"));
      if (invalid || lockedJournal || unauthorizedAdjustment) {
        const rejection = {
          id: `rejection-${rejections.length + 1}`,
          tenantId,
          bundleId: bundle.bundleId,
          eventUuids: bundle.events.map((event) => event.eventUuid),
          reason: lockedJournal ? "journal_locked" : unauthorizedAdjustment || CAPABILITY_BY_KIND[invalid.kind] ? "authorization_changed" : "unsupported_event",
          recoveryAction: "reauthenticate_or_request_manager_review",
          createdAt: new Date().toISOString(),
        };
        rejections.push(rejection);
        return { bundleId: bundle.bundleId, status: "rejected", rejection: clone(rejection) };
      }

      const accepted = [];
      for (const event of bundle.events) {
        const key = `${tenantId}:${event.eventUuid}`;
        const existing = receipts.get(key);
        if (existing) {
          accepted.push(clone(existing));
          continue;
        }
        const eventTs = new Date().toISOString();
        const receipt = { eventUuid: event.eventUuid, eventTs };
        receipts.set(key, receipt);
        const change = { serverSeq: String(++sequence), scope: event.scope || "tenant", priority: "normal", type: event.kind, operation: "upsert", data: clone(event.payload), eventUuid: event.eventUuid };
        changes.push(change);
        if (event.kind === "journal" && event.payload.aggregateId) {
          const journal = journals.find((item) => item.id === event.payload.aggregateId && item.tenantId === tenantId);
          if (journal?.status === "returned") {
            revisions.push({ journalId: journal.id, action: "corrected", reason: event.payload.correctionReason || null, body: clone(event.payload.changes || event.payload) });
            journal.status = "corrected"; journal.version += 1; journal.body = clone(event.payload.changes || event.payload);
          }
        }
        if (event.kind === "pesticide") pesticideUsages.push({ ...clone(event.payload), eventUuid: event.eventUuid, tenantId });
        if (event.kind === "stock") {
          const delta = event.payload.eventType === "withdrawal" ? -Math.abs(event.payload.quantity)
            : event.payload.eventType === "receipt" ? Math.abs(event.payload.quantity) : event.payload.quantity;
          const stockEvent = { id: event.payload.aggregateId || event.eventUuid, eventUuid: event.eventUuid, tenantId,
            chemicalId: event.payload.chemicalId, eventType: event.payload.eventType, quantityDelta: delta, reason: event.payload.reason };
          inventoryEvents.push(stockEvent);
          const balance = inventoryEvents.filter((item) => item.tenantId === tenantId && item.chemicalId === event.payload.chemicalId).reduce((sum, item) => sum + item.quantityDelta, 0);
          if (balance < 0) {
            const existingAlert = stockAlerts.find((item) => item.tenantId === tenantId && item.chemicalId === event.payload.chemicalId && item.status === "pending");
            if (existingAlert) existingAlert.negativeQuantity = balance;
            else stockAlerts.push({ id: `stock-alert-${stockAlerts.length + 1}`, tenantId, chemicalId: event.payload.chemicalId, negativeQuantity: balance, triggeringEventId: stockEvent.id, status: "pending", createdAt: new Date().toISOString() });
          }
          if (event.payload.eventType === "adjustment" && event.payload.alertId) {
            const alert = stockAlerts.find((item) => item.id === event.payload.alertId && item.tenantId === tenantId);
            if (alert) alert.status = "resolved";
          }
        }
        accepted.push(receipt);
      }
      return { bundleId: bundle.bundleId, status: "accepted", events: accepted };
    },

    async pull(_client, trusted, { scope, priority, cursor }) {
      if (scope !== "tenant" && !trusted.authContext.scopeFieldGroups.includes(scope)) {
        const error = new Error("scope revoked");
        error.code = "scope_revoked";
        error.scope = scope;
        throw error;
      }
      const after = cursor ? Number(cursor) : 0;
      const visible = changes.filter((change) => Number(change.serverSeq) > after && change.scope === scope && (priority === "normal" || change.priority === "priority"));
      return { changes: clone(visible), nextCursor: visible.at(-1)?.serverSeq || String(after), hasMore: false };
    },

    async getQueues(_client, trusted) {
      const tenantId = trusted.authContext.tenantId;
      return {
        rejections: clone(rejections.filter((item) => item.tenantId === tenantId)),
        conflicts: clone(conflicts.filter((item) => item.tenantId === tenantId && item.status === "pending")),
        pesticideAlerts: clone(pesticideAlerts.filter((item) => item.tenantId === tenantId && item.status === "pending")),
        stockAlerts: clone(stockAlerts.filter((item) => item.tenantId === tenantId && item.status === "pending")),
      };
    },

    async resolveConflict(_client, trusted, conflictId, resolution) {
      if (!trusted.authContext.capabilities.includes("conflict:resolve")) {
        const error = new Error("forbidden");
        error.code = "forbidden";
        throw error;
      }
      const conflict = conflicts.find((item) => item.id === conflictId && item.tenantId === trusted.authContext.tenantId);
      if (!conflict) return { id: conflictId, status: "not_found" };
      conflict.status = "resolved";
      conflict.resolution = clone(resolution);
      return clone(conflict);
    },
  };

  return { database, repository, state: { receipts, changes, rejections, conflicts, instructions, attachments, journals, revisions, chemicals, pesticideUsages, pesticideAlerts, inventoryEvents, stockAlerts, migrationJobs } };
}
