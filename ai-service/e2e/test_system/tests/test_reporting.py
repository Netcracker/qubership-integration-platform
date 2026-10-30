import json
import tempfile
import unittest
from pathlib import Path

from test_system.reporting import inspect_reports, redact


class ReportingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.suite = {"id": "imports", "feature": "import", "format": "junit"}

    def test_failure_keeps_actual_error_and_does_not_invent_root_cause(self):
        report = self.root / "tests.xml"
        report.write_text(
            (
                '<testsuite><testcase classname="Import" name="version"><failure type="Asser'
                'tionError" message="wrong version">expected 2, got 1</failure></testcase></'
                "testsuite>"
            )
        )
        result = inspect_reports(self.suite, self.root, [report], 1)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("expected 2", result["failures"][0]["error"]["message"])
        self.assertEqual(result["failures"][0]["diagnosis"]["status"], "UNKNOWN")
        self.assertEqual(result["failures"][0]["recovery"]["status"], "NOT_OBSERVED")

    def test_jest_failure_keeps_the_native_assertion(self):
        report = self.root / "jest.json"
        report.write_text(
            json.dumps(
                {
                    "numTotalTests": 2,
                    "numPendingTests": 0,
                    "success": False,
                    "testResults": [
                        {
                            "assertionResults": [
                                {
                                    "status": "failed",
                                    "fullName": "import card version",
                                    "failureMessages": ["Expected v2, received v1"],
                                }
                            ]
                        }
                    ],
                }
            )
        )
        result = inspect_reports(
            {**self.suite, "format": "jest"}, self.root, [report], 1
        )
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["counts"]["tests"], 2)
        self.assertIn("received v1", result["failures"][0]["error"]["message"])

    def test_zero_exit_without_required_or_executed_tests_cannot_pass(self):
        with self.assertRaises(ValueError):
            inspect_reports(self.suite, self.root, [], 0)
        report = self.root / "empty.xml"
        report.write_text(
            '<testsuite><testcase name="not run"><skipped/></testcase></testsuite>'
        )
        with self.assertRaises(ValueError):
            inspect_reports(self.suite, self.root, [report], 0)

    def test_recovered_failure_is_retained_on_a_passing_run(self):
        report = self.root / "result.json"
        (self.root / "continuation.json").write_text("{}")
        report.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "status": "PASS",
                    "failures": [
                        {
                            "error": {"message": "invalid field"},
                            "recovery": {
                                "status": "RECOVERED",
                                "attempts": [{"number": 1}],
                                "evidence": ["continuation.json"],
                                "diagnosis": {"status": "UNKNOWN"},
                            },
                        }
                    ],
                }
            )
        )
        result = inspect_reports(
            {**self.suite, "format": "result"}, self.root, [report], 0
        )
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(len(result["failures"]), 1)

    def test_unproven_recovery_cannot_pass(self):
        report = self.root / "result.json"
        report.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "status": "PASS",
                    "failures": [
                        {
                            "error": {"message": "invalid field"},
                            "recovery": {"status": "RECOVERED", "attempts": []},
                        }
                    ],
                }
            )
        )
        with self.assertRaises(ValueError):
            inspect_reports({**self.suite, "format": "result"}, self.root, [report], 0)

    def test_partially_skipped_required_suite_is_blocked(self):
        report = self.root / "tests.xml"
        report.write_text(
            (
                '<testsuite><testcase name="passed"/><testcase name="not run"><skipped messa'
                'ge="Missing evidence"/></testcase></testsuite>'
            )
        )
        result = inspect_reports(self.suite, self.root, [report], 0)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertEqual(
            result["failures"][0]["error"]["code"], "REQUIRED_TEST_NOT_RUN"
        )

    def test_a_confirmed_diagnosis_without_evidence_is_rejected(self):
        report = self.root / "result.json"
        report.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "status": "FAIL",
                    "failures": [
                        {
                            "error": {"message": "invalid field"},
                            "diagnosis": {"status": "CONFIRMED"},
                        }
                    ],
                }
            )
        )
        with self.assertRaises(ValueError):
            inspect_reports({**self.suite, "format": "result"}, self.root, [report], 1)

    def test_common_credentials_are_removed_from_captured_output(self):
        text = redact("Authorization: Bearer abc\nAPI_KEY=xyz\npassword=secret")
        for secret in ("abc", "xyz", "secret"):
            self.assertNotIn(secret, text)


if __name__ == "__main__":
    unittest.main()
