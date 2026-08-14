#!/usr/bin/env python3
"""S7 real HTTP/TCP + PostgreSQL load without third-party Python packages."""
from __future__ import annotations

import concurrent.futures
import json
import math
import multiprocessing
import os
import queue
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DSN = os.environ.get("SPIKE_DSN", "postgresql://postgres:spike@127.0.0.1:55432/spike")
TENANT = "70000000-0000-7000-8000-000000000001"
PORTS = (18087, 18088)
RTT_MS = 100
BYTES_PER_SECOND = 10_000_000 / 8


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


class PsqlWorker:
    def __init__(self) -> None:
        env = {**os.environ, "PGPASSWORD": os.environ.get("PGPASSWORD", "spike")}
        self.process = subprocess.Popen(["psql", DSN, "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
                                        text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        bufsize=1, env=env)
        self.execute(f"SET ROLE app_user; SELECT set_config('app.tenant_id', {sql_literal(TENANT)}, false);")

    def execute(self, sql: str) -> list[str]:
        marker = f"__END_{uuid.uuid4().hex}__"
        assert self.process.stdin and self.process.stdout
        self.process.stdin.write(sql + f"\nSELECT '{marker}';\n")
        self.process.stdin.flush()
        rows = []
        while True:
            line = self.process.stdout.readline()
            if not line:
                error = self.process.stderr.read() if self.process.stderr else "psql closed"
                raise RuntimeError(error)
            value = line.rstrip("\n")
            if value == marker:
                return rows
            rows.append(value)

    def close(self) -> None:
        if self.process.stdin:
            self.process.stdin.close()
        self.process.terminate()


class WorkerPool:
    def __init__(self, size: int) -> None:
        self.workers = [PsqlWorker() for _ in range(size)]
        self.available: queue.Queue[PsqlWorker] = queue.Queue()
        for worker in self.workers:
            self.available.put(worker)

    def execute(self, sql: str) -> list[str]:
        worker = self.available.get()
        try:
            return worker.execute(sql)
        finally:
            self.available.put(worker)


def make_handler(pool: WorkerPool):
    class Handler(BaseHTTPRequestHandler):
        server_version = "ISAS-S7-Spike/1"

        def log_message(self, *_args) -> None:
            pass

        def link_delay(self, byte_count: int = 0) -> None:
            time.sleep(RTT_MS / 2000 + byte_count / BYTES_PER_SECOND)

        def send_json(self, status: int, body: dict) -> None:
            encoded = json.dumps(body, separators=(",", ":")).encode()
            self.link_delay(len(encoded))
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            self.link_delay(length)
            if self.path == "/sync/push":
                item = json.loads(body)
                payload = json.dumps(item.get("payload", {}), separators=(",", ":"))
                rows = pool.execute("SELECT result_status || '|' || result_seq::text FROM s7_push(" +
                    f"{sql_literal(TENANT)}::uuid,{sql_literal(item['eventUuid'])}::uuid,{sql_literal(item['bundleId'])}," +
                    f"{int(item.get('priority', 2))}::smallint,{sql_literal(payload)}::jsonb);")
                status, seq = rows[-1].split("|")
                self.send_json(200, {"status": status, "serverSeq": int(seq)})
            elif self.path == "/attachments":
                attachment_id = self.headers["X-Attachment-ID"]
                rows = pool.execute(f"SELECT s7_save_attachment({sql_literal(TENANT)}::uuid,{sql_literal(attachment_id)}::uuid,decode('{body.hex()}','hex'));")
                self.send_json(200, {"status": rows[-1], "bytes": length})
            else:
                self.send_json(404, {"error": "not_found"})

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/sync/pull":
                self.send_json(404, {"error": "not_found"}); return
            query = urllib.parse.parse_qs(parsed.query)
            cursor = int(query.get("cursor", ["0"])[0]); priority = int(query.get("priority", ["2"])[0])
            rows = pool.execute(f"SELECT server_seq || '|' || event_uuid::text FROM s7_change WHERE tenant_id={sql_literal(TENANT)}::uuid AND priority={priority} AND server_seq>{cursor} ORDER BY server_seq LIMIT 200;")
            changes = [{"serverSeq": int(row.split("|")[0]), "eventUuid": row.split("|")[1]} for row in rows]
            self.send_json(200, {"changes": changes, "nextCursor": changes[-1]["serverSeq"] if changes else cursor})
    return Handler


def server_process(port: int) -> None:
    pool = WorkerPool(8)
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(pool))
    server.serve_forever()


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(len(ordered) * q) - 1)]


def request(url: str, *, body: bytes | None = None, headers: dict[str, str] | None = None) -> tuple[float, dict]:
    started = time.perf_counter()
    with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers or {}), timeout=10) as response:
        result = json.loads(response.read())
    return (time.perf_counter() - started) * 1000, result


def psql_scalar(sql: str) -> str:
    env = {**os.environ, "PGPASSWORD": os.environ.get("PGPASSWORD", "spike")}
    return subprocess.run(["psql", DSN, "-XAt", "-v", "ON_ERROR_STOP=1", "-c", sql], check=True, text=True, capture_output=True, env=env).stdout.strip().splitlines()[-1]


def main() -> None:
    servers = [multiprocessing.Process(target=server_process, args=(port,), daemon=True) for port in PORTS]
    for server in servers: server.start()
    try:
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                request(f"http://127.0.0.1:{PORTS[0]}/sync/pull?cursor=0&priority=0"); break
            except Exception: time.sleep(.1)
        print("S7 integrated load — 2 HTTP processes / 16 persistent PostgreSQL connections / FORCE RLS")
        print(f"link profile: actual loopback HTTP/TCP plus emulated {RTT_MS}ms RTT and 10Mbps serialization")

        unique = [str(uuid.uuid4()) for _ in range(1000)]
        event_ids = unique + unique[:200]
        payloads = [(index, event_id, json.dumps({"eventUuid": event_id, "bundleId": f"bundle-{event_id}",
                    "priority": 0 if index % 20 == 0 else 2, "payload": {"memo": "x" * 1024}}).encode()) for index, event_id in enumerate(event_ids)]
        push_latencies, statuses, errors = [], [], []
        def push(item):
            index, _event_id, body = item
            return request(f"http://127.0.0.1:{PORTS[index % 2]}/sync/push", body=body, headers={"Content-Type": "application/json"})
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as executor:
            futures = [executor.submit(push, item) for item in payloads]
            for future in concurrent.futures.as_completed(futures):
                try:
                    latency, result = future.result(); push_latencies.append(latency); statuses.append(result["status"])
                except Exception as error: errors.append(str(error))
        if errors or statuses.count("accepted") != 1000 or statuses.count("duplicate") != 200 or percentile(push_latencies, .95) > 500:
            raise RuntimeError(f"push failed errors={errors[:3]} accepted={statuses.count('accepted')} duplicate={statuses.count('duplicate')} p95={percentile(push_latencies,.95):.2f}")
        print(f"S7-push: PASS requests=1200 accepted=1000 duplicate=200 p50={percentile(push_latencies,.5):.2f}ms p95={percentile(push_latencies,.95):.2f}ms")

        pull_latencies = []
        for priority in (0, 2):
            cursor = 0
            while True:
                latency, result = request(f"http://127.0.0.1:{PORTS[cursor % 2]}/sync/pull?cursor={cursor}&priority={priority}")
                pull_latencies.append(latency)
                if not result["changes"]: break
                cursor = result["nextCursor"]
        if percentile(pull_latencies, .95) > 500: raise RuntimeError("pull p95 exceeded 500ms")
        print(f"S7-pull: PASS pages={len(pull_latencies)} p50={percentile(pull_latencies,.5):.2f}ms p95={percentile(pull_latencies,.95):.2f}ms")

        day_started = time.perf_counter()
        day_events = [json.dumps({"eventUuid": str(uuid.uuid4()), "bundleId": f"day-{index}", "priority": 2, "payload": {"memo": "d" * 1024}}).encode() for index in range(50)]
        photos = [(str(uuid.uuid4()), os.urandom(100_000)) for _ in range(10)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
            event_futures = [executor.submit(request, f"http://127.0.0.1:{PORTS[index % 2]}/sync/push", body=body, headers={"Content-Type":"application/json"}) for index, body in enumerate(day_events)]
            photo_futures = [executor.submit(request, f"http://127.0.0.1:{PORTS[index % 2]}/attachments", body=body, headers={"X-Attachment-ID": photo_id}) for index, (photo_id, body) in enumerate(photos)]
            for future in event_futures + photo_futures: future.result()
        day_seconds = time.perf_counter() - day_started
        if day_seconds > 300: raise RuntimeError("one-day synchronization exceeded five minutes")
        print(f"S7-day-sync: PASS records=50 photos=10 photo_bytes=1000000 elapsed={day_seconds:.3f}s budget=300s")

        counts = psql_scalar(f"SET ROLE app_user; SELECT set_config('app.tenant_id','{TENANT}',false); SELECT (SELECT count(*) FROM s7_event_receipt)||'|'||(SELECT count(*) FROM s7_change)||'|'||(SELECT count(*) FROM s7_attachment);")
        receipts, changes, attachments = map(int, counts.split("|"))
        if receipts != 1050 or changes != 1050 or attachments != 10: raise RuntimeError(f"integrity counts {counts}")
        print(f"S7-integrity: PASS receipts={receipts} changes={changes} attachments={attachments} duplicate_changes=0")
    finally:
        for server in servers:
            server.terminate(); server.join(timeout=3)


if __name__ == "__main__":
    main()
