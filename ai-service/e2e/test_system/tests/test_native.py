import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_system.native import main, summarize


class NativeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "native").mkdir()

    def save(self, path, value):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value))

    def test_recovery_passes_the_selected_external_corpus_explicitly(self):
        package = self.root / "external-testing"
        with patch.dict(
            "os.environ",
            QIP_TEST_TASK_DIR=str(self.root),
            QIP_SERVICE_ROOT=str(self.root),
            QIP_SKILLS_TESTING_ROOT=str(package),
        ), patch(
            "sys.argv",
            [
                "native",
                "recovery",
                "--base-url",
                "http://localhost:8094",
                "--image",
                "existing-image",
            ],
        ), patch(
            "test_system.native.subprocess.call", return_value=0
        ) as dispatch, patch(
            "test_system.native.summarize", return_value={"status": "PASS"}
        ):
            self.assertEqual(0, main())
        command = dispatch.call_args.args[0]
        self.assertEqual(
            str(package / "product-pipeline/fixtures/recovery/cases.json"),
            command[command.index("--cases") + 1],
        )

    def test_native_failure_verdict_wins_over_a_zero_exit_code(self):
        self.save(
            "native/summary.json",
            {
                "totalRuns": 1,
                "verdict": "FAIL",
                "results": [{"caseId": "mapping", "passed": False}],
            },
        )
        result = summarize("executor", self.root, 0)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["failures"][0]["caseId"], "mapping")

    def test_a_generator_proposal_is_not_an_accepted_golden(self):
        self.save(
            "native/generators.json",
            {
                "rows": [
                    {
                        "skill": "script",
                        "element": "literal",
                        "result": "PROPOSED",
                        "comment": "No accepted golden",
                    }
                ]
            },
        )
        result = summarize("generators", self.root, 0)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["failures"][0]["error"]["code"], "PROPOSED")

    def test_not_exercised_recovery_cannot_pass(self):
        self.save(
            "native/campaign.json",
            [
                {
                    "caseId": "property",
                    "outcome": "NOT_EXERCISED",
                    "reasons": ["Fault was not applied"],
                }
            ],
        )
        self.save("native/property/evidence.json", {"events": []})
        result = summarize("recovery", self.root, 0)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["failures"][0]["recovery"]["status"], "FAILED")

    def test_live_quality_rejects_stub_reports(self):
        self.save(
            "native/summary.json",
            {"totalRuns": 1, "expectedRuns": 1, "verdict": "PASS"},
        )
        self.save("native/runs/create/rep-1/report.json", {"stub": True})
        with self.assertRaises(ValueError):
            summarize("quality", self.root, 0)

    def test_recovered_failure_keeps_rejected_attempt_and_native_proof(self):
        self.save(
            "native/property/proof-verdict.json",
            {"caseId": "property", "outcome": "COMPLETION_PASS"},
        )
        self.save(
            "native/campaign.json",
            [{"caseId": "property", "outcome": "COMPLETION_PASS"}],
        )
        self.save("native/property/evidence.json", {"events": []})
        self.save(
            "native/property/durable.json",
            {
                "attempts": [
                    {"outcome": "FAILED", "failureEvidence": "Missing method"},
                    {"outcome": "SUCCEEDED"},
                ]
            },
        )
        result = summarize("recovery", self.root, 0)
        self.assertEqual(result["status"], "PASS")
        episode = result["failures"][0]
        self.assertEqual(episode["error"]["message"], "Missing method")
        self.assertEqual(episode["recovery"]["status"], "RECOVERED")
        self.assertEqual(len(episode["recovery"]["attempts"]), 2)


if __name__ == "__main__":
    unittest.main()
