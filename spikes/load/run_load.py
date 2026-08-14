#!/usr/bin/env python3
"""Dependency-free pgbench runner and evidence summarizer for load spikes."""
from __future__ import annotations

import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DSN = os.environ.get("SPIKE_DSN", "postgresql://postgres:spike@127.0.0.1:55432/spike")


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise RuntimeError("pgbench produced no transaction latency samples")
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1)]


def psql(sql: str) -> str:
    env = {**os.environ, "PGPASSWORD": os.environ.get("PGPASSWORD", "spike")}
    command = ["psql", DSN, "-X", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql]
    return subprocess.run(command, check=True, text=True, capture_output=True, env=env).stdout.strip()


def pgbench_case(name: str, script: Path, *, clients: int, jobs: int, seconds: int, rate: int, p95_budget_ms: float, min_rate_ratio: float = 0.95) -> dict[str, float]:
    env = {**os.environ, "PGPASSWORD": os.environ.get("PGPASSWORD", "spike")}
    with tempfile.TemporaryDirectory(prefix=f"isas-{name}-") as directory:
        prefix = str(Path(directory) / "latency-")
        command = ["pgbench", DSN, "-n", "-c", str(clients), "-j", str(jobs), "-T", str(seconds),
                   "-R", str(rate), "-L", str(int(p95_budget_ms)), "-P", "5", "-l", "--log-prefix", prefix, "-f", str(script)]
        completed = subprocess.run(command, check=True, text=True, capture_output=True, env=env)
        output = completed.stdout + completed.stderr
        latencies = []
        for log in Path(directory).glob("latency-*"):
            for line in log.read_text().splitlines():
                parts = line.split()
                if len(parts) >= 3 and parts[2].isdigit():
                    latencies.append(int(parts[2]) / 1000)
        tps_match = re.search(r"tps = ([0-9.]+)", output)
        skipped_match = re.search(r"number of transactions skipped: (\d+)", output)
        failed_match = re.search(r"number of failed transactions: (\d+)", output)
        tps = float(tps_match.group(1)) if tps_match else 0.0
        skipped = int(skipped_match.group(1)) if skipped_match else 0
        failed = int(failed_match.group(1)) if failed_match else 0
        result = {"samples": float(len(latencies)), "tps": tps, "p50_ms": percentile(latencies, .5),
                  "p95_ms": percentile(latencies, .95), "p99_ms": percentile(latencies, .99),
                  "skipped": float(skipped), "failed": float(failed)}
        passed = result["p95_ms"] <= p95_budget_ms and tps >= rate * min_rate_ratio and skipped == 0 and failed == 0
        print(f"{name}: {'PASS' if passed else 'FAIL'} samples={len(latencies)} tps={tps:.1f} "
              f"p50={result['p50_ms']:.2f}ms p95={result['p95_ms']:.2f}ms p99={result['p99_ms']:.2f}ms skipped={skipped} failed={failed}")
        if not passed:
            print(output)
            raise RuntimeError(f"{name} failed its acceptance profile")
        return result


def run_s5() -> None:
    print("S5 audit hash-chain concurrency — provisional maximum-tenant profile")
    print("profile: PostgreSQL over TCP, 32 clients, offered 500 writes/s, 15s, p95 <= 1000ms")
    same = pgbench_case("S5-same-tenant", ROOT / "load/S5_same_tenant.sql", clients=32, jobs=8, seconds=15, rate=500, p95_budget_ms=1000)
    multi = pgbench_case("S5-multi-tenant-diagnostic", ROOT / "load/S5_multi_tenant.sql", clients=32, jobs=8, seconds=15, rate=500, p95_budget_ms=1000)
    integrity = psql("""
      WITH checked AS (
        SELECT tenant_id, period_start, seq, prev_hash, row_hash, payload,
          lag(row_hash) OVER (PARTITION BY tenant_id, period_start ORDER BY seq) AS expected_prev
        FROM audit_chain_log
      )
      SELECT count(*) FILTER (WHERE prev_hash IS DISTINCT FROM expected_prev) || '|' ||
             count(*) FILTER (WHERE row_hash IS DISTINCT FROM public.digest(
               tenant_id::text || '|' || period_start::text || '|' || seq::text || '|' ||
               coalesce(encode(prev_hash, 'hex'), '') || '|' || payload::text, 'sha256')) || '|' || count(*)
      FROM checked;
    """)
    broken_prev, broken_hash, rows = map(int, integrity.split("|"))
    if broken_prev or broken_hash:
        raise RuntimeError(f"S5 chain integrity failed: prev={broken_prev}, hash={broken_hash}")
    print(f"S5-integrity: PASS rows={rows} broken_prev=0 broken_hash=0")
    improvement = same["p95_ms"] / multi["p95_ms"] if multi["p95_ms"] else 0
    print(f"S5 diagnostic same/multi p95 ratio: {improvement:.2f}x")


def main() -> None:
    target = sys.argv[1].upper() if len(sys.argv) > 1 else "S5"
    if target == "S5":
        run_s5()
    else:
        raise SystemExit(f"unknown load target: {target}")


if __name__ == "__main__":
    main()
