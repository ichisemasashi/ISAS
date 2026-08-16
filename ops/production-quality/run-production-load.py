#!/usr/bin/env python3
"""Run destructive, synthetic-tenant-only production acceptance through the real TLS ingress."""
from __future__ import annotations

import concurrent.futures
import http.cookiejar
import json
import math
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value or "\0" in value:
        raise RuntimeError(f"{name} is required")
    return value


def integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.environ.get(name, str(default))
    if not raw.isdigit() or not minimum <= int(raw) <= maximum:
        raise RuntimeError(f"{name} must be between {minimum} and {maximum}")
    return int(raw)


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise RuntimeError("no latency samples")
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1)]


class Client:
    def __init__(self, base_url: str, cookie_file: Path):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "https" or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
            raise RuntimeError("ISAS_ACCEPTANCE_BASE_URL must be an exact HTTPS origin")
        if not cookie_file.is_file():
            raise RuntimeError("ISAS_ACCEPTANCE_COOKIE_FILE must point to a Netscape cookie jar")
        jar = http.cookiejar.MozillaCookieJar(str(cookie_file))
        jar.load(ignore_discard=True, ignore_expires=False)
        self.base_url = base_url.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        self.origin = self.base_url

    def request(self, path: str, *, method: str = "GET", body: dict | None = None, headers: dict[str, str] | None = None) -> tuple[float, int, dict, dict[str, str]]:
        encoded = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        request_headers = {"Accept": "application/json", **(headers or {})}
        if encoded is not None:
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self.base_url}{path}", data=encoded, method=method, headers=request_headers)
        started = time.perf_counter()
        try:
            response = self.opener.open(request, timeout=30)
            raw = response.read()
            status = response.status
            response_headers = dict(response.headers.items())
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
            response_headers = dict(error.headers.items())
        latency = (time.perf_counter() - started) * 1000
        value = json.loads(raw) if raw else {}
        return latency, status, value, response_headers


def tls_probe(base_url: str) -> dict:
    parsed = urllib.parse.urlparse(base_url)
    port = parsed.port or 443
    context = ssl.create_default_context()
    with socket.create_connection((parsed.hostname, port), timeout=10) as tcp:
        with context.wrap_socket(tcp, server_hostname=parsed.hostname) as channel:
            certificate = channel.getpeercert()
            return {
                "status": "pass",
                "version": channel.version(),
                "cipher": channel.cipher()[0],
                "certificate_not_after": certificate.get("notAfter"),
                "hostname_verified": True,
            }


def database_probe(dsn_env: str, expected_major: str = "16") -> dict:
    dsn = required(dsn_env)
    sql = "SELECT current_user, current_setting('server_version'), postgis_version(), ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()"
    completed = subprocess.run(["psql", dsn, "-XAt", "-v", "ON_ERROR_STOP=1", "-F", "|", "-c", sql], check=True, text=True, capture_output=True)
    role, version, postgis, tls = completed.stdout.strip().split("|")[-4:]
    if not version.startswith(f"{expected_major}.") or tls != "t":
        raise RuntimeError(f"{dsn_env} is not PostgreSQL {expected_major} over TLS")
    return {"status": "pass", "role": role, "postgres_version": version, "postgis_version": postgis, "tls": True}


def bootstrap(client: Client, tenant_id: str) -> tuple[str, str, str, str]:
    _, status, session, _ = client.request("/api/bff/session")
    if status != 200:
        raise RuntimeError(f"authenticated session bootstrap failed: {status}")
    if tenant_id not in {tenant.get("id") for tenant in session.get("tenants", [])}:
        raise RuntimeError("synthetic acceptance tenant is not assigned to the test user")
    csrf = session["csrfToken"]
    _, status, context, _ = client.request("/api/bff/contexts", method="POST", body={"tenantId": tenant_id}, headers={"Origin": client.origin, "X-CSRF-Token": csrf})
    if status != 201:
        raise RuntimeError(f"context creation failed: {status}")
    return context["contextId"], csrf, context["membershipVersion"], context["authorizationSnapshotId"]


def run_s7(client: Client, context_id: str, csrf: str, membership_version: str, snapshot_id: str, requests: int, concurrency: int) -> dict:
    unique_count = requests - max(1, requests // 10)
    event_ids = [str(uuid.uuid4()) for _ in range(unique_count)]
    event_ids.extend(event_ids[:requests - unique_count])

    def push(index_event: tuple[int, str]):
        index, event_id = index_event
        body = {"bundles": [{"bundleId": f"quality-{event_id}", "events": [{
            "eventUuid": event_id, "kind": "punch", "occurredAt": "2026-08-16T00:00:00Z",
            "membershipVersion": membership_version, "authorizationSnapshotId": snapshot_id,
            "payload": {"action": "quality_probe", "synthetic": True, "sequence": index},
        }]}]}
        return client.request("/api/v1/sync/push", method="POST", body=body, headers={"Origin": client.origin, "X-CSRF-Token": csrf, "X-ISAS-Context": context_id})

    latencies, accepted, duplicates, failures = [], 0, 0, []
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        for latency, status, body, _ in executor.map(push, enumerate(event_ids)):
            latencies.append(latency)
            if status != 200:
                failures.append(status)
                continue
            result = body.get("results", [{}])[0].get("status")
            accepted += result == "accepted"
            duplicates += result == "duplicate"
    if failures or accepted != unique_count or duplicates != requests - unique_count:
        raise RuntimeError(f"S7 push integrity failed: accepted={accepted} duplicate={duplicates} failures={len(failures)}")
    return {"status": "pass", "requests": requests, "accepted": accepted, "duplicates": duplicates,
            "p50_ms": round(percentile(latencies, .5), 2), "p95_ms": round(percentile(latencies, .95), 2), "max_ms": round(max(latencies), 2)}


def run_pool_saturation(client: Client, context_id: str, scope: str, samples: int, p2_concurrency: int) -> dict:
    headers = {"X-ISAS-Context": context_id}
    stop_at = time.monotonic() + integer("ISAS_ACCEPTANCE_SATURATION_SECONDS", 60, 15, 600)
    p2_results: list[int] = []

    def saturate() -> None:
        while time.monotonic() < stop_at:
            _, status, _, _ = client.request("/api/v1/migration-jobs", headers=headers)
            p2_results.append(status)

    with concurrent.futures.ThreadPoolExecutor(max_workers=p2_concurrency + 16) as executor:
        blockers = [executor.submit(saturate) for _ in range(p2_concurrency)]
        time.sleep(1)
        futures = [executor.submit(client.request, f"/api/v1/sync/pull?scope={urllib.parse.quote(scope)}&priority=priority", headers=headers) for _ in range(samples)]
        p0 = [future.result() for future in futures]
        for blocker in blockers:
            blocker.result()
    latencies = [item[0] for item in p0]
    within = sum(item[1] == 200 and item[0] <= 500 for item in p0)
    availability = within / samples
    if availability < .999:
        raise RuntimeError(f"P0 SLO failed under P2 saturation: {availability:.6f}")
    if not p2_results or any(status not in (200, 429, 503) for status in p2_results):
        raise RuntimeError("P2 saturation did not exercise a controlled endpoint")
    return {"status": "pass", "p2_concurrency": p2_concurrency, "p2_requests": len(p2_results),
            "p0_samples": samples, "p0_within_500ms": within, "p0_availability": round(availability, 6),
            "p0_p50_ms": round(percentile(latencies, .5), 2), "p0_p95_ms": round(percentile(latencies, .95), 2),
            "p0_p99_ms": round(percentile(latencies, .99), 2), "p0_max_ms": round(max(latencies), 2)}


def main() -> int:
    output = Path(sys.argv[1]) if len(sys.argv) == 2 else None
    if output is None:
        print("usage: run-production-load.py <output.json>", file=sys.stderr)
        return 2
    base_url = required("ISAS_ACCEPTANCE_BASE_URL")
    client = Client(base_url, Path(required("ISAS_ACCEPTANCE_COOKIE_FILE")))
    tenant_id = required("ISAS_ACCEPTANCE_TENANT_ID")
    scope = required("ISAS_ACCEPTANCE_SCOPE")
    started = time.time()
    context_id, csrf, membership_version, snapshot_id = bootstrap(client, tenant_id)
    _, live_status, _, live_headers = client.request("/health/live")
    _, ready_status, _, _ = client.request("/health/ready")
    if live_status != 200 or ready_status != 200:
        raise RuntimeError(f"BFF health failed: live={live_status} ready={ready_status}")
    if "strict-transport-security" not in {key.lower() for key in live_headers}:
        raise RuntimeError("TLS ingress did not return Strict-Transport-Security")
    evidence = {
        "schema_version": 1,
        "status": "PARTIAL",
        "measured_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source_commit": required("ISAS_ACCEPTANCE_SOURCE_COMMIT"),
        "deployment_id": required("ISAS_ACCEPTANCE_DEPLOYMENT_ID"),
        "environment": {
            "base_origin": base_url, "tls": tls_probe(base_url), "bff_live": "pass", "bff_ready": "pass",
            "p0_database": database_probe("ISAS_ACCEPTANCE_DB_P0_URL"),
            "p2_database": database_probe("ISAS_ACCEPTANCE_DB_P2_URL"),
            "network": {"kind": "actual", "proxy_or_vpn": os.environ.get("ISAS_ACCEPTANCE_NETWORK_LABEL", "direct-staging")},
        },
        "s7": run_s7(client, context_id, csrf, membership_version, snapshot_id,
                      integer("ISAS_ACCEPTANCE_S7_REQUESTS", 1000, 1000, 100000), integer("ISAS_ACCEPTANCE_S7_CONCURRENCY", 16, 1, 128)),
        "pool_saturation": run_pool_saturation(client, context_id, scope,
                                                integer("ISAS_ACCEPTANCE_P0_SAMPLES", 1000, 1000, 100000), integer("ISAS_ACCEPTANCE_P2_CONCURRENCY", 32, 2, 128)),
        "duration_seconds": round(time.time() - started, 3),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
    print(f"production load: PASS evidence={output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"production load: BLOCKED - {error}", file=sys.stderr)
        raise SystemExit(1)
