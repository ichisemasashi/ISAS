import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("run-production-load.py")
SPEC = importlib.util.spec_from_file_location("production_load", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class ProductionLoadTest(unittest.TestCase):
    def test_percentile_uses_nearest_rank(self):
        self.assertEqual(MODULE.percentile(list(range(1, 101)), .95), 95)

    def test_percentile_rejects_no_data(self):
        with self.assertRaisesRegex(RuntimeError, "no latency"):
            MODULE.percentile([], .95)

    def test_integer_rejects_out_of_range_configuration(self):
        import os
        old = os.environ.get("QUALITY_INTEGER_TEST")
        os.environ["QUALITY_INTEGER_TEST"] = "999"
        try:
            with self.assertRaisesRegex(RuntimeError, "between"):
                MODULE.integer("QUALITY_INTEGER_TEST", 5, 1, 10)
        finally:
            if old is None:
                os.environ.pop("QUALITY_INTEGER_TEST", None)
            else:
                os.environ["QUALITY_INTEGER_TEST"] = old


if __name__ == "__main__":
    unittest.main()
