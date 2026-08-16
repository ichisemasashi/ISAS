import importlib.util
import io
from pathlib import Path
import tempfile
import unittest


PATH = Path(__file__).with_name("run-rehearsal.py")
SPEC = importlib.util.spec_from_file_location("run_rehearsal", PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class RehearsalUnitTest(unittest.TestCase):
    def test_csv_row_count_handles_bom_blank_and_quoted_newline(self):
        value = "\ufeffid,memo\r\n1,ok\r\n\r\n2,\"two\nlines\"\r\n"
        self.assertEqual(MODULE.csv_row_count(value), 2)

    def test_manifest_requires_order_and_real_class(self):
        base = {"schema_version": 1, "evidence_class": "synthetic", "datasets": [],
                "expected_exports": {}, "restricted_scope_expected_exports": {}}
        with self.assertRaisesRegex(RuntimeError, "real evidence_class"):
            MODULE.validate_manifest(base)
        base["evidence_class"] = "real_anonymized"
        base["datasets"] = [{"dataset": name} for name in reversed(MODULE.ORDER)]
        with self.assertRaisesRegex(RuntimeError, "fields, journals"):
            MODULE.validate_manifest(base)

    def test_source_file_rejects_parent_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root.parent / "outside.csv"
            outside.write_text("id\n1\n")
            with self.assertRaisesRegex(RuntimeError, "direct file"):
                MODULE.source_file(root, "../outside.csv")
            outside.unlink()

    def test_count_reconciliation_rejects_bad_job(self):
        job = {"rowCount": 3, "validCount": 2, "duplicateCount": 0, "errorCount": 1}
        MODULE.expect_counts(job, {"rows": 3, "valid": 2, "duplicates": 0, "errors": 1}, "fields")
        with self.assertRaisesRegex(RuntimeError, "count mismatch"):
            MODULE.expect_counts(job, {"rows": 4, "valid": 2, "duplicates": 1, "errors": 1}, "fields")


if __name__ == "__main__":
    unittest.main()
