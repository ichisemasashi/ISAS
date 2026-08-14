const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIME = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

export const migrationContracts = Object.freeze({
  fields: { required: ["externalKey", "name", "fieldGroupId", "geometryWkt"], optional: ["cropName", "timezone"] },
  journals: { required: ["externalKey", "fieldExternalKey", "workerUserId", "workType", "workedOn", "startedAt", "endedAt"], optional: ["memo"] },
  pesticide_history: { required: ["fieldExternalKey", "cropName", "registrationNumber", "usageCount", "lastAppliedOn"], optional: [] },
});

function present(value) { return typeof value === "string" && value.trim().length > 0; }

function normalize(dataset, mapped) {
  const value = Object.fromEntries(Object.entries(mapped).map(([key, item]) => [key, typeof item === "string" ? item.trim() : item]));
  const errors = [];
  if (dataset === "fields") {
    if (!UUID.test(value.fieldGroupId || "")) errors.push("invalid_field_group_id");
    if (!/^(?:MULTI)?POLYGON\s*\(/i.test(value.geometryWkt || "")) errors.push("invalid_geometry_wkt");
    value.timezone ||= "Asia/Tokyo";
  } else if (dataset === "journals") {
    if (!UUID.test(value.workerUserId || "")) errors.push("invalid_worker_user_id");
    if (!DATE.test(value.workedOn || "")) errors.push("invalid_worked_on");
    if (!TIME.test(value.startedAt || "") || !TIME.test(value.endedAt || "")) errors.push("invalid_work_time");
    if (TIME.test(value.startedAt || "") && TIME.test(value.endedAt || "") && value.endedAt < value.startedAt) errors.push("end_before_start");
    value.memo ||= "";
  } else if (dataset === "pesticide_history") {
    const count = Number(value.usageCount);
    if (!Number.isInteger(count) || count < 0) errors.push("invalid_usage_count"); else value.usageCount = count;
    if (!DATE.test(value.lastAppliedOn || "")) errors.push("invalid_last_applied_on");
    if (DATE.test(value.lastAppliedOn || "")) value.seasonYear = Number(value.lastAppliedOn.slice(0, 4));
  }
  return { value, errors };
}

function duplicateKey(dataset, value) {
  if (dataset === "fields" || dataset === "journals") return value.externalKey;
  return `${value.fieldExternalKey}\u001f${value.cropName}\u001f${value.registrationNumber}\u001f${value.seasonYear}`;
}

export function mapMigrationRows(dataset, headers, rows, mapping) {
  const contract = migrationContracts[dataset];
  if (!contract || !mapping || typeof mapping !== "object" || Array.isArray(mapping)) throw new TypeError("invalid migration mapping");
  const allowed = new Set([...contract.required, ...contract.optional]);
  if (Object.keys(mapping).some((key) => !allowed.has(key))) throw new TypeError("unknown migration mapping field");
  for (const field of contract.required) if (!present(mapping[field]) || !headers.includes(mapping[field])) throw new TypeError(`missing mapping: ${field}`);
  for (const source of Object.values(mapping)) if (present(source) && !headers.includes(source)) throw new TypeError(`unknown source column: ${source}`);
  const seen = new Set();
  return rows.map((raw, index) => {
    const mapped = Object.fromEntries([...allowed].map((field) => [field, present(mapping[field]) ? raw[mapping[field]] : ""]));
    const missing = contract.required.filter((field) => !present(mapped[field])).map((field) => `required:${field}`);
    const normalized = normalize(dataset, mapped);
    const key = duplicateKey(dataset, normalized.value);
    const duplicate = key && seen.has(key);
    if (key) seen.add(key);
    const errors = [...missing, ...normalized.errors];
    return { lineNumber: index + 2, raw, normalized: normalized.value, duplicateKey: key || null,
      status: errors.length ? "invalid" : duplicate ? "duplicate" : "valid", errors };
  });
}
