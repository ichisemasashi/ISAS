import csv
import tempfile
import unittest
from pathlib import Path

from analyze_ut import InputError, analyze_directory


PARTICIPANT_FIELDS = ["participant_id", "cohort", "consent_record_id"]
TASK_FIELDS = ["participant_id", "task_id", "success", "duration_seconds", "tap_count", "intervention_count", "warning_missed", "offline_saved_understood", "unsynced_understood"]
SUS_FIELDS = ["participant_id"] + ["q%d" % number for number in range(1, 11)]
FINDING_FIELDS = ["finding_id", "severity", "status"]


def write_rows(directory, name, fields, rows):
    with (directory / name).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def read_rows(directory, name):
    with (directory / name).open(encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def passing_round(directory: Path) -> None:
    cohorts = ["worker", "worker", "older_worker", "older_worker", "technical_intern", "technical_intern"]
    participants = [{"participant_id": "P%02d" % (index + 1), "cohort": cohort, "consent_record_id": "CONSENT-%02d" % (index + 1)} for index, cohort in enumerate(cohorts)]
    tasks = []
    for participant in participants:
        for number in range(1, 7):
            tasks.append({
                "participant_id": participant["participant_id"],
                "task_id": "UT-%02d" % number,
                "success": "true",
                "duration_seconds": "25" if number == 3 else "50" if number == 4 else "20",
                "tap_count": "3",
                "intervention_count": "0",
                "warning_missed": "false",
                "offline_saved_understood": "true" if number == 5 else "false",
                "unsynced_understood": "true" if number == 5 else "false",
            })
    sus = [{"participant_id": participant["participant_id"], **{"q%d" % number: "5" if number % 2 else "1" for number in range(1, 11)}} for participant in participants]
    write_rows(directory, "participants.csv", PARTICIPANT_FIELDS, participants)
    write_rows(directory, "tasks.csv", TASK_FIELDS, tasks)
    write_rows(directory, "sus.csv", SUS_FIELDS, sus)
    write_rows(directory, "findings.csv", FINDING_FIELDS, [])


class AnalyzeUtTest(unittest.TestCase):
    def test_passing_round(self):
        with tempfile.TemporaryDirectory() as value:
            directory = Path(value)
            passing_round(directory)
            result = analyze_directory(directory)
            self.assertEqual("PASS", result["status"])
            self.assertEqual(100.0, result["per_task"]["UT-03"]["success_rate"])
            self.assertEqual(100.0, result["sus_scores"]["P01"])

    def test_failed_task_time_understanding_sus_and_finding_fail_gates(self):
        with tempfile.TemporaryDirectory() as value:
            directory = Path(value)
            passing_round(directory)
            task_rows = read_rows(directory, "tasks.csv")
            for row in task_rows:
                if row["participant_id"] == "P01" and row["task_id"] == "UT-01":
                    row["success"] = "false"
                if row["task_id"] == "UT-03":
                    row["duration_seconds"] = "31"
                if row["task_id"] == "UT-04":
                    row["duration_seconds"] = "61"
                if row["participant_id"] == "P01" and row["task_id"] == "UT-05":
                    row["unsynced_understood"] = "false"
            write_rows(directory, "tasks.csv", TASK_FIELDS, task_rows)
            sus_rows = read_rows(directory, "sus.csv")
            for row in sus_rows:
                for number in range(1, 11):
                    row["q%d" % number] = "1" if number % 2 else "5"
            write_rows(directory, "sus.csv", SUS_FIELDS, sus_rows)
            write_rows(directory, "findings.csv", FINDING_FIELDS, [{"finding_id": "F-1", "severity": "1", "status": "open"}])
            result = analyze_directory(directory)
            self.assertEqual("NOT_PASS", result["status"])
            failed = {item["name"] for item in result["gates"] if not item["passed"]}
            self.assertTrue({"主要タスク成功率", "日誌時間中央値", "農薬記録時間中央値", "オフライン保存・未同期理解", "SUS平均", "未解決Severity 1"}.issubset(failed))

    def test_duplicate_task_is_rejected(self):
        with tempfile.TemporaryDirectory() as value:
            directory = Path(value)
            passing_round(directory)
            rows = read_rows(directory, "tasks.csv")
            rows.append(dict(rows[0]))
            write_rows(directory, "tasks.csv", TASK_FIELDS, rows)
            with self.assertRaises(InputError):
                analyze_directory(directory)


if __name__ == "__main__":
    unittest.main()
