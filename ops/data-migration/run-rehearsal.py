#!/usr/bin/env python3
"""Run an authenticated, isolated-staging CSV migration rehearsal."""
from __future__ import annotations

import csv
import hashlib
import http.cookiejar
import io
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


ORDER = ("fields", "journals", "pesticide_history")
EXPORT_NAME = {"fields": "fields", "journals": "journals", "pesticide_history": "pesticide-records"}


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value or "\0" in value:
        raise RuntimeError(f"{name} is required")
    return value


def load_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"{path} must contain a JSON object")
    return value


def csv_row_count(text: str) -> int:
    rows = list(csv.reader(io.StringIO(text.lstrip("\ufeff"))))
    if not rows:
        raise RuntimeError("CSV has no header")
    return sum(1 for row in rows[1:] if any(cell.strip() for cell in row))


def source_file(root: Path, relative: str) -> Path:
    candidate = (root / relative).resolve()
    if candidate.parent != root.resolve() or not candidate.is_file():
        raise RuntimeError(f"CSV must be a direct file under {root}: {relative}")
    return candidate


class Client:
    def __init__(self, base_url: str, cookie_file: Path):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "https" or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
            raise RuntimeError("ISAS_MIGRATION_BASE_URL must be an exact HTTPS origin")
        if not cookie_file.is_file():
            raise RuntimeError(f"cookie jar not found: {cookie_file}")
        jar = http.cookiejar.MozillaCookieJar(str(cookie_file))
        jar.load(ignore_discard=True, ignore_expires=False)
        self.base_url = base_url.rstrip("/")
        self.origin = self.base_url
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def request(self, path: str, *, method: str = "GET", body: dict | None = None,
                headers: dict[str, str] | None = None) -> tuple[int, bytes, dict[str, str]]:
        encoded = None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        request_headers = {"Accept": "application/json", **(headers or {})}
        if encoded is not None:
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self.base_url}{path}", data=encoded, method=method, headers=request_headers)
        try:
            response = self.opener.open(request, timeout=120)
            return response.status, response.read(), dict(response.headers.items())
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers.items())

    def json(self, path: str, **kwargs) -> tuple[int, dict]:
        status, raw, _ = self.request(path, **kwargs)
        try:
            value = json.loads(raw) if raw else {}
        except json.JSONDecodeError as error:
            raise RuntimeError(f"non-JSON response from {path}: HTTP {status}") from error
        return status, value

    def context(self, tenant_id: str) -> tuple[str, str]:
        status, session = self.json("/api/bff/session")
        if status != 200:
            raise RuntimeError(f"session bootstrap failed: HTTP {status}")
        if tenant_id not in {item.get("id") for item in session.get("tenants", [])}:
            raise RuntimeError(f"session is not assigned to rehearsal tenant {tenant_id}")
        csrf = session.get("csrfToken")
        status, context = self.json("/api/bff/contexts", method="POST", body={"tenantId": tenant_id},
                                    headers={"Origin": self.origin, "X-CSRF-Token": csrf})
        if status != 201:
            raise RuntimeError(f"context creation failed: HTTP {status}")
        return context["contextId"], csrf


def expect_counts(job: dict, expected: dict, phase: str) -> None:
    fields = {"rows": "rowCount", "valid": "validCount", "duplicates": "duplicateCount", "errors": "errorCount"}
    mismatches = [f"{name} expected={expected[name]} actual={job.get(api)}" for name, api in fields.items()
                  if not isinstance(expected.get(name), int) or job.get(api) != expected[name]]
    if mismatches:
        raise RuntimeError(f"{phase} count mismatch: " + "; ".join(mismatches))
    if expected["rows"] != expected["valid"] + expected["duplicates"] + expected["errors"]:
        raise RuntimeError(f"{phase} manifest does not reconcile rows")


def import_dataset(client: Client, context_id: str, csrf: str, item: dict, csv_path: Path) -> dict:
    dataset = item.get("dataset")
    text = csv_path.read_text(encoding="utf-8-sig")
    expected = item.get("expected", {})
    if csv_row_count(text) != expected.get("rows"):
        raise RuntimeError(f"{dataset} local CSV row count differs from manifest")
    digest = hashlib.sha256(text.encode()).hexdigest()
    key = f"rehearsal-{dataset}-{digest}"
    headers = {"Origin": client.origin, "X-CSRF-Token": csrf, "X-ISAS-Context": context_id, "Idempotency-Key": key}
    body = {"dataset": dataset, "sourceName": csv_path.name, "csv": text, "mapping": item.get("mapping")}
    status, first = client.json("/api/v1/migration-jobs", method="POST", body=body, headers=headers)
    if status != 201:
        raise RuntimeError(f"{dataset} validation failed: HTTP {status} {first.get('code', '')}")
    expect_counts(first, expected, f"{dataset} validation")
    status, replay = client.json("/api/v1/migration-jobs", method="POST", body=body, headers=headers)
    if status != 201 or replay.get("id") != first.get("id"):
        raise RuntimeError(f"{dataset} idempotent replay did not return the same job")
    if first.get("status") == "committed":
        committed = first
    else:
        if first.get("status") != "validated" or expected.get("errors") != 0:
            raise RuntimeError(f"{dataset} is not committable; correct the source CSV and rerun")
        status, committed = client.json(f"/api/v1/migration-jobs/{urllib.parse.quote(first['id'])}/commit",
                                        method="POST", body={"expectedVersion": first["version"]},
                                        headers={"Origin": client.origin, "X-CSRF-Token": csrf, "X-ISAS-Context": context_id})
        if status != 200 or committed.get("status") != "committed":
            raise RuntimeError(f"{dataset} commit failed: HTTP {status}")
    committed_expected = item.get("expected_committed")
    if committed.get("validCount") != committed_expected:
        raise RuntimeError(f"{dataset} committed expected={committed_expected} actual={committed.get('validCount')}")
    if committed.get("rowCount") != committed.get("validCount") + committed.get("duplicateCount") + committed.get("errorCount"):
        raise RuntimeError(f"{dataset} committed counts do not reconcile")
    return {"dataset": dataset, "source_sha256": digest, "source_rows": expected["rows"],
            "job_id": committed["id"], "status": "pass", "validated": expected,
            "committed": committed["validCount"], "duplicates_at_commit": committed["duplicateCount"] - expected["duplicates"],
            "idempotent_replay": "pass"}


def export_counts(client: Client, context_id: str) -> dict[str, int]:
    result = {}
    for name in EXPORT_NAME.values():
        status, raw, _ = client.request(f"/api/v1/exports/{name}.csv", headers={"X-ISAS-Context": context_id, "Accept": "text/csv"})
        if status != 200:
            raise RuntimeError(f"{name} export failed: HTTP {status}")
        result[name] = csv_row_count(raw.decode("utf-8-sig"))
    return result


def validate_manifest(manifest: dict) -> None:
    if manifest.get("schema_version") != 1 or manifest.get("evidence_class") not in ("real_anonymized", "production_export"):
        raise RuntimeError("manifest must declare schema_version 1 and a real evidence_class")
    datasets = manifest.get("datasets")
    if not isinstance(datasets, list) or tuple(item.get("dataset") for item in datasets) != ORDER:
        raise RuntimeError("datasets must appear in fields, journals, pesticide_history order")
    if not isinstance(manifest.get("expected_exports"), dict) or not isinstance(manifest.get("restricted_scope_expected_exports"), dict):
        raise RuntimeError("exact full and restricted export counts are required")


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: run-rehearsal.py <manifest.json> <evidence.json>", file=sys.stderr)
        return 2
    manifest_path, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
    manifest = load_json(manifest_path)
    validate_manifest(manifest)
    root = manifest_path.parent
    base_url = required("ISAS_MIGRATION_BASE_URL")
    tenant_id = required("ISAS_MIGRATION_TENANT_ID")
    if tenant_id != manifest.get("tenant_id") or manifest.get("environment") != "staging":
        raise RuntimeError("runner is restricted to the manifest's isolated staging tenant")
    client = Client(base_url, Path(required("ISAS_MIGRATION_COOKIE_FILE")))
    context_id, csrf = client.context(tenant_id)
    started = time.time()
    imports = [import_dataset(client, context_id, csrf, item, source_file(root, item.get("file", ""))) for item in manifest["datasets"]]
    full_exports = export_counts(client, context_id)
    if full_exports != manifest["expected_exports"]:
        raise RuntimeError(f"full export mismatch expected={manifest['expected_exports']} actual={full_exports}")
    restricted = Client(base_url, Path(required("ISAS_MIGRATION_RESTRICTED_COOKIE_FILE")))
    restricted_context, _ = restricted.context(tenant_id)
    restricted_exports = export_counts(restricted, restricted_context)
    if restricted_exports != manifest["restricted_scope_expected_exports"]:
        raise RuntimeError(f"RLS scope export mismatch expected={manifest['restricted_scope_expected_exports']} actual={restricted_exports}")
    evidence = {"schema_version": 1, "status": "PARTIAL", "evidence_class": manifest["evidence_class"],
                "round_id": manifest.get("round_id"), "measured_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "source_commit": required("ISAS_MIGRATION_SOURCE_COMMIT"), "deployment_id": required("ISAS_MIGRATION_DEPLOYMENT_ID"),
                "environment": {"kind": "staging", "base_origin": base_url, "tenant_id": tenant_id},
                "imports": imports, "exports": full_exports,
                "rls_scope": {"status": "pass", "restricted_exports": restricted_exports},
                "approvals": [], "duration_seconds": round(time.time() - started, 3)}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"migration rehearsal: PARTIAL; add independent approvals then run the acceptance gate: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"migration rehearsal: BLOCKED\n- {error}", file=sys.stderr)
        raise SystemExit(1)
