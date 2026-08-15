#!/usr/bin/env python3
"""Re-evaluate immutable S2/S5/S7 load evidence against MVP SLO budgets."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LOGS = {
    "s2": ROOT / "spikes/results/S2_LOAD_2026-08-14_PG16_PostGIS.log",
    "s5": ROOT / "spikes/results/S5_2026-08-14_PG16.log",
    "s7": ROOT / "spikes/results/S7_INTEGRATION_2026-08-14_PG16_HTTP.log",
}


def metrics(text: str, section: str) -> dict[str, float]:
    match = re.search(rf"^{re.escape(section)}: PASS(?:\n|\s+)([^\n]*)", text, re.MULTILINE)
    if not match:
        raise ValueError(f"missing PASS evidence: {section}")
    return {key: float(value) for key, value in re.findall(r"([a-zA-Z][a-zA-Z0-9_]*)=([0-9.]+)(?:ms|s)?", match.group(1))}


def require(values: dict[str, float], key: str) -> float:
    if key not in values:
        raise ValueError(f"missing metric: {key}")
    return values[key]


def evaluate(logs: dict[str, str]) -> list[dict[str, object]]:
    s2_bbox = metrics(logs["s2"], "S2-bbox-1000")
    s2_knn = metrics(logs["s2"], "S2-knn-20")
    s2_isolation = metrics(logs["s2"], "S2-isolation")
    s5_same = metrics(logs["s5"], "S5-same-tenant")
    s5_integrity = metrics(logs["s5"], "S5-integrity")
    s7_push = metrics(logs["s7"], "S7-push")
    s7_pull = metrics(logs["s7"], "S7-pull")
    s7_day = metrics(logs["s7"], "S7-day-sync")
    s7_integrity = metrics(logs["s7"], "S7-integrity")

    checks = [
        ("S2 bbox 1,000件 p95", require(s2_bbox, "p95") <= 2000, require(s2_bbox, "p95"), "<= 2000 ms"),
        ("S2 bbox 到達rate", require(s2_bbox, "tps") >= 190, require(s2_bbox, "tps"), ">= 190 tps"),
        ("S2 bbox error", require(s2_bbox, "skipped") == 0 and require(s2_bbox, "failed") == 0, require(s2_bbox, "skipped") + require(s2_bbox, "failed"), "= 0"),
        ("S2 KNN p95", require(s2_knn, "p95") <= 500, require(s2_knn, "p95"), "<= 500 ms"),
        ("S2 KNN 到達rate", require(s2_knn, "tps") >= 190, require(s2_knn, "tps"), ">= 190 tps"),
        ("S2 tenant漏洩", require(s2_isolation, "leaked_other_tenant") == 0, require(s2_isolation, "leaked_other_tenant"), "= 0"),
        ("S5 同一tenant監査 p95", require(s5_same, "p95") <= 1000, require(s5_same, "p95"), "<= 1000 ms"),
        ("S5 同一tenant監査 rate", require(s5_same, "tps") >= 475, require(s5_same, "tps"), ">= 475 tps"),
        ("S5 監査chain整合", require(s5_integrity, "broken_prev") == 0 and require(s5_integrity, "broken_hash") == 0, require(s5_integrity, "broken_prev") + require(s5_integrity, "broken_hash"), "= 0"),
        ("S7 push p95", require(s7_push, "p95") <= 500, require(s7_push, "p95"), "<= 500 ms"),
        ("S7 pull p95", require(s7_pull, "p95") <= 500, require(s7_pull, "p95"), "<= 500 ms"),
        ("S7 1日分同期", require(s7_day, "elapsed") <= 300, require(s7_day, "elapsed"), "<= 300 s"),
        ("S7 冪等整合", require(s7_integrity, "duplicate_changes") == 0 and require(s7_integrity, "receipts") == require(s7_integrity, "changes"), require(s7_integrity, "duplicate_changes"), "duplicate=0, receipts=changes"),
    ]
    return [{"name": name, "passed": passed, "actual": actual, "budget": budget} for name, passed, actual, budget in checks]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        results = evaluate({name: path.read_text(encoding="utf-8") for name, path in DEFAULT_LOGS.items()})
    except (OSError, ValueError) as error:
        print(json.dumps({"passed": False, "error": str(error)}, ensure_ascii=False) if args.json else f"FAIL: {error}")
        return 1
    passed = all(item["passed"] for item in results)
    if args.json:
        print(json.dumps({"passed": passed, "checks": results}, ensure_ascii=False, indent=2))
    else:
        for item in results:
            print(f"{'PASS' if item['passed'] else 'FAIL'} {item['name']}: actual={item['actual']} budget={item['budget']}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
