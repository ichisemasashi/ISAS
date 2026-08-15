import unittest

from check_slo import evaluate


S2 = """S2-bbox-1000: PASS samples=100 tps=199.9 p95=80.71ms skipped=0 failed=0
S2-knn-20: PASS samples=100 tps=194.5 p95=54.41ms skipped=0 failed=0
S2-isolation: PASS visible=10000 leaked_other_tenant=0
"""
S5 = """S5-same-tenant: PASS
samples=100 tps=504.7 p95=6.60ms skipped=0 failed=0
S5-integrity: PASS
rows=100 broken_prev=0 broken_hash=0
"""
S7 = """S7-push: PASS
requests=1200 accepted=1000 duplicate=200 p95=135.73ms
S7-pull: PASS
pages=8 p95=78.05ms
S7-day-sync: PASS
records=50 elapsed=0.632s budget=300s
S7-integrity: PASS
receipts=1050 changes=1050 duplicate_changes=0
"""


class SloGateTest(unittest.TestCase):
    def test_accepts_complete_evidence_within_budgets(self):
        self.assertTrue(all(item["passed"] for item in evaluate({"s2": S2, "s5": S5, "s7": S7})))

    def test_fails_when_a_latency_budget_is_exceeded(self):
        results = evaluate({"s2": S2.replace("p95=80.71ms", "p95=2000.01ms"), "s5": S5, "s7": S7})
        self.assertFalse(next(item for item in results if item["name"] == "S2 bbox 1,000件 p95")["passed"])

    def test_rejects_missing_evidence(self):
        with self.assertRaisesRegex(ValueError, "missing PASS evidence"):
            evaluate({"s2": S2, "s5": S5, "s7": S7.replace("S7-integrity: PASS", "S7-integrity: FAIL")})


if __name__ == "__main__":
    unittest.main()
