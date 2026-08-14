const CAPABILITY_BY_KIND = {
  journal: "journal:write",
  pesticide: "pesticide:write",
  punch: "punch:write",
};

function clone(value) {
  return structuredClone(value);
}

export function createMemoryMvpRepository({ tasks = [], fields = [], workInstructions = [], workJournals = [] } = {}) {
  const receipts = new Map();
  const changes = [];
  const rejections = [];
  const conflicts = [];
  let sequence = 0;
  const instructions = clone(workInstructions);
  const attachments = [];
  const journals = clone(workJournals);
  const revisions = [];

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
      const item = { ...clone(input), id: `instruction-${instructions.length + 1}`, tenantId: trusted.authContext.tenantId, version: 1, status: "issued", assignment: { id: `assignment-${instructions.length + 1}`, assigneeUserId: input.assigneeUserId, version: 1 } };
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
      if (invalid || lockedJournal) {
        const rejection = {
          id: `rejection-${rejections.length + 1}`,
          tenantId,
          bundleId: bundle.bundleId,
          eventUuids: bundle.events.map((event) => event.eventUuid),
          reason: lockedJournal ? "journal_locked" : CAPABILITY_BY_KIND[invalid.kind] ? "authorization_changed" : "unsupported_event",
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

  return { database, repository, state: { receipts, changes, rejections, conflicts, instructions, attachments, journals, revisions } };
}
