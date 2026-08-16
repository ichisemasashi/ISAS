import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { OTLPMetricExporter } from "@opentelemetry/exporter-metrics-otlp-proto";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { PeriodicExportingMetricReader } from "@opentelemetry/sdk-metrics";
import { NodeSDK } from "@opentelemetry/sdk-node";

const LOOPBACK_OTLP = /^http:\/\/(?:127\.0\.0\.1|localhost):4318$/;
const LOCAL_COMPOSE_OTLP = /^http:\/\/otel-collector:4318$/;
const SENSITIVE_ATTRIBUTES = ["http.url", "url.full", "url.query", "db.statement", "db.query.text"];

export function telemetryConfig(env = process.env) {
  const disabled = env.OTEL_SDK_DISABLED === "true";
  const endpoint = env.OTEL_EXPORTER_OTLP_ENDPOINT || "http://127.0.0.1:4318";
  const localIntegration = env.ISAS_ENV_PROFILE === "local-integration";
  const allowedCollector = LOOPBACK_OTLP.test(endpoint) || (localIntegration && LOCAL_COMPOSE_OTLP.test(endpoint));
  if (!disabled && env.NODE_ENV === "production" && !allowedCollector) {
    throw new Error("Production telemetry must use the task-local ADOT collector");
  }
  if (!disabled && (!/^https?:\/\//.test(endpoint) || endpoint.includes("\0"))) throw new Error("OTLP endpoint is invalid");
  return Object.freeze({ disabled, endpoint });
}

function redactSpan(span) {
  for (const attribute of SENSITIVE_ATTRIBUTES) span.deleteAttribute(attribute);
}

export async function startTelemetry(env = process.env) {
  const config = telemetryConfig(env);
  if (config.disabled) return Object.freeze({ async shutdown() {} });
  const common = { concurrencyLimit: 1 };
  const sdk = new NodeSDK({
    traceExporter: new OTLPTraceExporter({ ...common, url: `${config.endpoint}/v1/traces` }),
    metricReader: new PeriodicExportingMetricReader({
      exporter: new OTLPMetricExporter({ ...common, url: `${config.endpoint}/v1/metrics` }),
      exportIntervalMillis: 60000,
    }),
    instrumentations: [getNodeAutoInstrumentations({
      "@opentelemetry/instrumentation-fs": { enabled: false },
      "@opentelemetry/instrumentation-dns": { enabled: false },
      "@opentelemetry/instrumentation-net": { enabled: false },
      "@opentelemetry/instrumentation-http": { requestHook: redactSpan },
      "@opentelemetry/instrumentation-pg": { enhancedDatabaseReporting: false, requestHook: redactSpan },
    })],
  });
  sdk.start();
  return Object.freeze({ shutdown: () => sdk.shutdown() });
}
