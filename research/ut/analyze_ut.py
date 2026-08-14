#!/usr/bin/env python3
"""ISAS Phase 1 実ユーザーUTの匿名CSVを検証・集計する。"""

import argparse
import csv
import json
import statistics
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


COHORTS = ("worker", "older_worker", "technical_intern")
TASKS = tuple("UT-%02d" % number for number in range(1, 7))
CORE_TASKS = TASKS[:5]
RESOLVED_FINDING_STATUSES = {"resolved", "closed", "verified"}


class InputError(ValueError):
    pass


def read_csv(directory: Path, name: str) -> List[Dict[str, str]]:
    path = directory / name
    if not path.is_file():
        raise InputError("missing input: %s" % path)
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def require_columns(rows: Sequence[Dict[str, str]], required: Iterable[str], name: str) -> None:
    if not rows:
        return
    columns = set(rows[0].keys()) if rows else set()
    missing = set(required) - columns
    if missing:
        raise InputError("%s missing columns: %s" % (name, ", ".join(sorted(missing))))


def parse_bool(value: str, field: str) -> bool:
    normalized = value.strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise InputError("%s must be true or false, got %r" % (field, value))


def parse_nonnegative_int(value: str, field: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise InputError("%s must be an integer, got %r" % (field, value)) from error
    if parsed < 0:
        raise InputError("%s must be non-negative" % field)
    return parsed


def parse_positive_float(value: str, field: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise InputError("%s must be numeric, got %r" % (field, value)) from error
    if parsed <= 0:
        raise InputError("%s must be greater than zero" % field)
    return parsed


def sus_score(row: Dict[str, str]) -> float:
    contribution = 0
    for number in range(1, 11):
        field = "q%d" % number
        score = parse_nonnegative_int(row[field], "%s.%s" % (row["participant_id"], field))
        if not 1 <= score <= 5:
            raise InputError("%s.%s must be between 1 and 5" % (row["participant_id"], field))
        contribution += score - 1 if number % 2 else 5 - score
    return contribution * 2.5


def percentile_nearest_rank(values: Sequence[float], percentile: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, int((percentile * len(ordered) + 0.999999999)))
    return ordered[min(rank, len(ordered)) - 1]


def gate(name: str, passed: bool, actual: str, threshold: str) -> Dict[str, object]:
    return {"name": name, "passed": passed, "actual": actual, "threshold": threshold}


def analyze_directory(directory: Path) -> Dict[str, object]:
    participants = read_csv(directory, "participants.csv")
    tasks = read_csv(directory, "tasks.csv")
    sus_rows = read_csv(directory, "sus.csv")
    findings = read_csv(directory, "findings.csv")

    require_columns(participants, ("participant_id", "cohort", "consent_record_id"), "participants.csv")
    require_columns(tasks, ("participant_id", "task_id", "success", "duration_seconds", "tap_count", "intervention_count", "warning_missed", "offline_saved_understood", "unsynced_understood"), "tasks.csv")
    require_columns(sus_rows, ("participant_id",) + tuple("q%d" % number for number in range(1, 11)), "sus.csv")
    require_columns(findings, ("finding_id", "severity", "status"), "findings.csv")

    participant_ids = [row["participant_id"].strip() for row in participants]
    if not participant_ids or any(not value for value in participant_ids):
        raise InputError("participants.csv requires non-empty participant_id values")
    if len(participant_ids) != len(set(participant_ids)):
        raise InputError("participants.csv contains duplicate participant_id")
    known = set(participant_ids)

    cohort_counts = {cohort: 0 for cohort in COHORTS}
    for row in participants:
        cohort = row["cohort"].strip()
        if cohort not in COHORTS:
            raise InputError("unknown cohort %r; use %s" % (cohort, ", ".join(COHORTS)))
        if not row["consent_record_id"].strip():
            raise InputError("%s has no consent_record_id" % row["participant_id"])
        cohort_counts[cohort] += 1

    task_index: Dict[Tuple[str, str], Dict[str, object]] = {}
    task_metrics: Dict[str, List[Dict[str, object]]] = {task: [] for task in TASKS}
    for row in tasks:
        participant_id = row["participant_id"].strip()
        task_id = row["task_id"].strip().upper()
        if participant_id not in known:
            raise InputError("tasks.csv references unknown participant %r" % participant_id)
        if task_id not in TASKS:
            raise InputError("unknown task_id %r" % task_id)
        key = (participant_id, task_id)
        if key in task_index:
            raise InputError("duplicate task row for %s %s" % key)
        parsed = {
            "participant_id": participant_id,
            "task_id": task_id,
            "success": parse_bool(row["success"], "%s.%s.success" % key),
            "duration_seconds": parse_positive_float(row["duration_seconds"], "%s.%s.duration_seconds" % key),
            "tap_count": parse_nonnegative_int(row["tap_count"], "%s.%s.tap_count" % key),
            "intervention_count": parse_nonnegative_int(row["intervention_count"], "%s.%s.intervention_count" % key),
            "warning_missed": parse_bool(row["warning_missed"], "%s.%s.warning_missed" % key),
            "offline_saved_understood": parse_bool(row["offline_saved_understood"], "%s.%s.offline_saved_understood" % key),
            "unsynced_understood": parse_bool(row["unsynced_understood"], "%s.%s.unsynced_understood" % key),
        }
        task_index[key] = parsed
        task_metrics[task_id].append(parsed)

    missing_tasks = [(participant_id, task_id) for participant_id in participant_ids for task_id in TASKS if (participant_id, task_id) not in task_index]

    sus_by_participant: Dict[str, float] = {}
    for row in sus_rows:
        participant_id = row["participant_id"].strip()
        if participant_id not in known:
            raise InputError("sus.csv references unknown participant %r" % participant_id)
        if participant_id in sus_by_participant:
            raise InputError("sus.csv contains duplicate participant %r" % participant_id)
        sus_by_participant[participant_id] = sus_score(row)
    missing_sus = sorted(known - set(sus_by_participant))

    core_rows = [row for task_id in CORE_TASKS for row in task_metrics[task_id]]
    core_successes = sum(1 for row in core_rows if row["success"])
    core_success_rate = (100.0 * core_successes / len(core_rows)) if core_rows else 0.0
    core_task_rates = {
        task_id: (100.0 * sum(1 for row in task_metrics[task_id] if row["success"]) / len(task_metrics[task_id])) if task_metrics[task_id] else 0.0
        for task_id in CORE_TASKS
    }
    journal_times = [float(row["duration_seconds"]) for row in task_metrics["UT-03"] if row["success"]]
    pesticide_times = [float(row["duration_seconds"]) for row in task_metrics["UT-04"] if row["success"]]
    warning_misses = sum(1 for row in task_metrics["UT-04"] if row["warning_missed"])
    offline_rows = task_metrics["UT-05"]
    offline_understood = sum(1 for row in offline_rows if row["offline_saved_understood"] and row["unsynced_understood"])
    sus_mean = statistics.mean(sus_by_participant.values()) if sus_by_participant else 0.0
    open_severity_1 = sum(1 for row in findings if row["severity"].strip() == "1" and row["status"].strip().lower() not in RESOLVED_FINDING_STATUSES)

    complete = not missing_tasks and not missing_sus
    gates = [
        gate("対象者構成", 6 <= len(participants) <= 9 and all(cohort_counts[value] >= 2 for value in COHORTS), "%d名 (%s)" % (len(participants), ", ".join("%s=%d" % item for item in cohort_counts.items())), "合計6〜9名・各群2名以上"),
        gate("記録完全性", complete, "不足task=%d / 不足SUS=%d" % (len(missing_tasks), len(missing_sus)), "全参加者がUT-01〜06・SUS回答"),
        gate("主要タスク成功率", complete and core_success_rate >= 90.0 and all(value >= 90.0 for value in core_task_rates.values()), "総合%.1f%% (%d/%d)・最低タスク%.1f%%" % (core_success_rate, core_successes, len(core_rows), min(core_task_rates.values())), "総合・UT-01〜05各90%以上"),
        gate("日誌時間中央値", bool(journal_times) and statistics.median(journal_times) <= 30.0, "%.1f秒" % statistics.median(journal_times) if journal_times else "データなし", "30秒以内"),
        gate("農薬記録時間中央値", bool(pesticide_times) and statistics.median(pesticide_times) <= 60.0, "%.1f秒" % statistics.median(pesticide_times) if pesticide_times else "データなし", "60秒以内"),
        gate("農薬警告見落とし", complete and warning_misses == 0, "%d件" % warning_misses, "0件"),
        gate("オフライン保存・未同期理解", complete and len(offline_rows) == len(participants) and offline_understood == len(participants), "%d/%d名" % (offline_understood, len(participants)), "全員"),
        gate("SUS平均", complete and sus_mean >= 75.0, "%.1f" % sus_mean, "75以上"),
        gate("未解決Severity 1", open_severity_1 == 0, "%d件" % open_severity_1, "0件"),
    ]

    per_task = {}
    for task_id, rows in task_metrics.items():
        successes = sum(1 for row in rows if row["success"])
        durations = [float(row["duration_seconds"]) for row in rows if row["success"]]
        per_task[task_id] = {
            "attempted": len(rows),
            "successes": successes,
            "success_rate": (100.0 * successes / len(rows)) if rows else 0.0,
            "median_seconds": statistics.median(durations) if durations else None,
            "p90_seconds": percentile_nearest_rank(durations, 0.90),
        }

    return {
        "status": "PASS" if all(bool(item["passed"]) for item in gates) else "NOT_PASS",
        "participant_count": len(participants),
        "cohort_counts": cohort_counts,
        "gates": gates,
        "per_task": per_task,
        "sus_scores": sus_by_participant,
        "missing_tasks": [list(item) for item in missing_tasks],
        "missing_sus": missing_sus,
    }


def markdown_report(result: Dict[str, object]) -> str:
    lines = [
        "# ISAS 実ユーザーUT集計",
        "",
        "**総合判定：%s**" % result["status"],
        "",
        "## 合格ゲート",
        "",
        "| 判定 | 指標 | 実測 | 基準 |",
        "|---|---|---:|---|",
    ]
    for item in result["gates"]:  # type: ignore[union-attr]
        lines.append("| %s | %s | %s | %s |" % ("PASS" if item["passed"] else "FAIL", item["name"], item["actual"], item["threshold"]))
    lines.extend(["", "## タスク別", "", "| タスク | 成功 | 成功率 | 中央値 | p90 |", "|---|---:|---:|---:|---:|"])
    for task_id, item in result["per_task"].items():  # type: ignore[union-attr]
        median = "—" if item["median_seconds"] is None else "%.1f秒" % item["median_seconds"]
        p90 = "—" if item["p90_seconds"] is None else "%.1f秒" % item["p90_seconds"]
        lines.append("| %s | %d/%d | %.1f%% | %s | %s |" % (task_id, item["successes"], item["attempted"], item["success_rate"], median, p90))
    lines.extend(["", "このレポートは匿名CSVの機械集計であり、同意原本・録画・個人情報を含まない。", ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="participants.csv等を含むラウンドディレクトリ")
    parser.add_argument("--output", type=Path, help="Markdownレポート出力先。省略時は標準出力")
    parser.add_argument("--json", action="store_true", help="MarkdownではなくJSONを出力")
    args = parser.parse_args()
    try:
        result = analyze_directory(args.directory)
    except InputError as error:
        parser.error(str(error))
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n" if args.json else markdown_report(result)
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
