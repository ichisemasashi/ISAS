import assert from "node:assert/strict";
import { test } from "node:test";
import { telemetryConfig } from "../src/telemetry.mjs";

test("production telemetry is restricted to the task-local ADOT collector", () => {
  assert.equal(telemetryConfig({ NODE_ENV: "production" }).endpoint, "http://127.0.0.1:4318");
  assert.equal(telemetryConfig({ NODE_ENV: "production", ISAS_ENV_PROFILE: "local-integration", OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector:4318" }).endpoint, "http://otel-collector:4318");
  assert.throws(() => telemetryConfig({ NODE_ENV: "production", OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector:4318" }), /task-local/);
  assert.throws(() => telemetryConfig({ NODE_ENV: "production", OTEL_EXPORTER_OTLP_ENDPOINT: "https://telemetry.example" }), /task-local/);
  assert.equal(telemetryConfig({ NODE_ENV: "production", OTEL_SDK_DISABLED: "true", OTEL_EXPORTER_OTLP_ENDPOINT: "https://telemetry.example" }).disabled, true);
});
