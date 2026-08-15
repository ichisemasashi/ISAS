#!/usr/bin/env node
import { startTelemetry } from "../src/telemetry.mjs";

const command = process.argv[2] || "start";
const telemetry = command === "start" ? await startTelemetry() : { async shutdown() {} };
const [{ startProductionRuntime, createJsonLogger }, { loadRuntimeConfig, publicRuntimeConfig }] = await Promise.all([
  import("../src/runtime.mjs"), import("../src/runtime-config.mjs"),
]);
const logger = createJsonLogger();

if (command === "check-config") {
  try {
    logger.info("bff_config_valid", publicRuntimeConfig(loadRuntimeConfig(process.env)));
  } catch (error) {
    logger.error("bff_config_invalid", { message: error.message });
    process.exitCode = 78;
  }
} else if (command === "start") {
  let service;
  let stopping = false;
  const stop = async (reason, exitCode = 0) => {
    if (stopping) return;
    stopping = true;
    process.exitCode = exitCode;
    try {
      await service?.shutdown(reason);
      await telemetry.shutdown();
    } catch (error) {
      logger.error("bff_shutdown_failed", { reason, message: error.message });
      process.exitCode = 1;
    }
  };

  process.once("SIGTERM", () => { void stop("SIGTERM"); });
  process.once("SIGINT", () => { void stop("SIGINT"); });
  process.once("SIGHUP", () => { void stop("SIGHUP", 75); });
  process.once("uncaughtException", (error) => {
    logger.error("bff_uncaught_exception", { message: error.message });
    void stop("uncaughtException", 1);
  });
  process.once("unhandledRejection", (error) => {
    logger.error("bff_unhandled_rejection", { message: error instanceof Error ? error.message : "unknown" });
    void stop("unhandledRejection", 1);
  });

  try {
    service = await startProductionRuntime({ logger });
  } catch (error) {
    logger.error("bff_start_failed", { message: error.message });
    await telemetry.shutdown().catch(() => undefined);
    process.exitCode = 1;
  }
} else {
  process.stderr.write("usage: node bin/server.mjs [start|check-config]\n");
  process.exitCode = 64;
}
