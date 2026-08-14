const CAPABILITY_BY_KIND = {
  journal: "journal:write",
  pesticide: "pesticide:write",
  punch: "punch:write",
};

function clone(value) {
  return structuredClone(value);
}

export function createMemoryMvpRepository({ tasks = [], fields = [], workInstructions = [] } = {}) {
  const receipts = new Map();
  const changes = [];
  const rejections = [];
  const conflicts = [];
  let sequence = 0;
  const instructions = clone(workInstructions);

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
      if (invalid) {
        const rejection = {
          id: `rejection-${rejections.length + 1}`,
          tenantId,
          bundleId: bundle.bundleId,
          eventUuids: bundle.events.map((event) => event.eventUuid),
          reason: CAPABILITY_BY_KIND[invalid.kind] ? "authorization_changed" : "unsupported_event",
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

  return { database, repository, state: { receipts, changes, rejections, conflicts, instructions } };
}
