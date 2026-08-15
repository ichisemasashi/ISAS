import { randomUUID } from "node:crypto";

function one(result) {
  return result.rows?.length === 1 ? result.rows[0] : null;
}

function camel(value) {
  if (Array.isArray(value)) return value.map(camel);
  if (!value || typeof value !== "object" || value instanceof Date) return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key.replace(/_([a-z])/g, (_all, letter) => letter.toUpperCase()), camel(item)]));
}

function tenant(trusted) {
  const tenantId = trusted?.authContext?.tenantId;
  if (!trusted?.userId || !tenantId) throw Object.assign(new Error("Missing trusted authorization context"), { code: "forbidden" });
  return tenantId;
}

export function createPostgresSecurityAdministration({ pool }) {
  if (!pool?.query) throw new Error("Security administration pool is required");
  return Object.freeze({
    async snapshot(trusted) {
      const row = one(await pool.query(
        "SELECT app_private.security_admin_snapshot($1::uuid,$2::uuid) AS snapshot",
        [trusted.userId, tenant(trusted)],
      ));
      return camel(row?.snapshot || {});
    },
    async requestChange(trusted, input) {
      const row = one(await pool.query(
        "SELECT app_private.create_security_change_request($1::uuid,$2::uuid,$3::uuid,$4::text,$5::uuid,$6::text,$7::text,$8::jsonb) AS result",
        [randomUUID(), trusted.userId, tenant(trusted), input.changeType, input.targetUserId, input.reason, input.ticketRef, input.proposedState],
      ));
      return row.result;
    },
    async decideChange(trusted, requestId, input) {
      const row = one(await pool.query(
        "SELECT app_private.decide_security_change_request($1::uuid,$2::uuid,$3::boolean,$4::text) AS result",
        [requestId, trusted.userId, input.decision === "approve", input.note],
      ));
      return row.result;
    },
    async createPrivacyRequest(trusted, input) {
      const row = one(await pool.query(
        "SELECT app_private.create_privacy_request($1::uuid,$2::uuid,$3::uuid,$4::uuid,$5::text,$6::jsonb,$7::timestamptz,$8::text) AS result",
        [randomUUID(), trusted.userId, tenant(trusted), input.subjectUserId || null, input.requestType, input.details, input.dueAt, input.note],
      ));
      return row.result;
    },
    async transitionPrivacyRequest(trusted, requestId, input) {
      const row = one(await pool.query(
        "SELECT app_private.transition_privacy_request($1::uuid,$2::uuid,$3::text,$4::text,$5::jsonb) AS result",
        [requestId, trusted.userId, input.action, input.note, input.evidenceRef || null],
      ));
      return row.result;
    },
  });
}
