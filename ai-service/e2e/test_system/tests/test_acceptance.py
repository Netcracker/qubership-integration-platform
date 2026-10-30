import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_system.acceptance import COMPONENTS, main, preparation, summarize
from test_system.catalog import digest
from test_system.runs import write_json


class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def save(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        write_json(path, value)
        return path

    def prepared(self):
        launcher = self.root / "prepared/acceptance.py"
        launcher.parent.mkdir()
        launcher.write_text(
            "import json, sys\nassert sys.argv[1:] == ['check']\n"
            "print(json.dumps(dict(outcome='READY', limitsChanged=False, paidRequestsInPreparation=0)))\n"
        )
        source = self.root / "ai-service/src/fixture.txt"
        source.parent.mkdir(parents=True)
        source.write_text("tested source")
        candidate = self.save(
            "prepared/candidate.json",
            {
                "limitsChanged": False,
                "files": {"ai-service/src/fixture.txt": digest(source)},
            },
        )
        self.save(
            ".dev/recovery-acceptance.json",
            {
                "launcher": str(launcher),
                "launcherSha256": digest(launcher),
                "candidateSha256": digest(candidate),
                "waveRoot": "wave",
            },
        )
        return launcher, source

    def matrix(self, outcome="GO"):
        self.save(
            "native/acceptance/summary.json",
            {
                "outcome": outcome,
                "helperRestoreFailures": [],
                "results": [
                    {
                        "model": "kimi",
                        "scope": "CONTROLLED_REGISTERED_COMPONENTS",
                        "outcome": "PASS",
                    },
                    *[
                        {
                            "model": "kimi",
                            "scope": "FULL_PRODUCT_PIPELINE",
                            "case": case,
                            "outcome": "PASS",
                        }
                        for case in ("parcel-credit", "parcel-credit-controlled")
                    ],
                ],
            },
        )
        for component in COMPONENTS:
            self.save(
                "native/acceptance/kimi/components/" + component + "/result.json",
                {
                    "outcome": "PASS",
                    "realProvider": True,
                    "nativeCaptureAccepted": True,
                },
            )
        for case in ("parcel-credit", "parcel-credit-controlled"):
            self.save(
                "native/product/kimi/" + case + "/wave-result.json",
                {
                    "outcome": "PASS",
                    "localScript": {"outcome": "PASS_LOCAL_HARNESS"},
                    "recovery": (
                        "OBSERVED" if case.endswith("controlled") else "NOT_EXERCISED"
                    ),
                },
            )

    def test_check_dispatches_only_the_unpaid_preflight(self):
        self.prepared()
        output = self.root / "task"
        output.mkdir()
        with patch.dict(
            "os.environ", QIP_SERVICE_ROOT=str(self.root), QIP_TEST_TASK_DIR=str(output)
        ), patch("sys.argv", ["acceptance", "check"]):
            self.assertEqual(0, main())
        result = json.loads((output / "result.json").read_text())
        self.assertEqual("PASS", result["status"])
        self.assertEqual("prepared-acceptance-readiness", result["verifiedBoundary"])
        self.assertFalse((output / "native/acceptance").exists())

    def test_changed_candidate_source_and_launcher_are_rejected(self):
        launcher, source = self.prepared()
        preparation(self.root)
        source.write_text("changed source")
        with self.assertRaisesRegex(ValueError, "differs from the workspace"):
            preparation(self.root)
        launcher.write_text("changed launcher")
        with self.assertRaisesRegex(ValueError, "launcher changed"):
            preparation(self.root)

    def test_new_source_files_require_a_new_candidate(self):
        _, source = self.prepared()
        source.with_name("new-source.txt").write_text("new source")
        with self.assertRaisesRegex(ValueError, "file inventory differs"):
            preparation(self.root)

    def test_complete_live_matrix_keeps_controlled_failures_and_scopes(self):
        self.matrix()
        result = summarize(self.root, ("kimi",), 0)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(3, len(result["failures"]))
        self.assertTrue(
            all(e["recovery"]["status"] == "RECOVERED" for e in result["failures"])
        )
        self.assertEqual(
            "NOT_PROVEN_BY_COMPONENT_CHECKS",
            result["newMechanismsFullPipelineRecovery"],
        )
        self.assertEqual("OPEN", result["sourceMeaningWithoutNativeProof"])
        self.assertEqual("FAIL", summarize(self.root, ("kimi",), 1)["status"])

    def test_go_cannot_hide_an_incomplete_matrix_or_a_scripted_component(self):
        self.matrix()
        with self.assertRaisesRegex(ValueError, "matrix is incomplete"):
            summarize(self.root, ("ordinary", "kimi"), 0)
        self.save(
            "native/acceptance/kimi/components/read-loop/result.json",
            {"outcome": "PASS", "realProvider": False},
        )
        with self.assertRaisesRegex(ValueError, "real-provider"):
            summarize(self.root, ("kimi",), 0)

    def test_cleanup_failure_is_visible_even_when_every_case_passed(self):
        self.matrix("NO-GO")
        path = self.root / "native/acceptance/summary.json"
        document = json.loads(path.read_text())
        document["helperRestoreFailures"] = ["catalog-helper"]
        write_json(path, document)
        result = summarize(self.root, ("kimi",), 0)
        self.assertEqual("FAIL", result["status"])
        self.assertEqual("infrastructure", result["failures"][-1]["category"])
        self.assertIn("catalog-helper", result["failures"][-1]["error"]["message"])

    def test_natural_construction_success_is_not_controlled_recovery(self):
        self.matrix()
        self.save(
            "native/product/kimi/parcel-credit-controlled/wave-result.json",
            {
                "outcome": "PASS",
                "localScript": {"outcome": "PASS_LOCAL_HARNESS"},
                "recovery": "NOT_EXERCISED",
            },
        )
        with self.assertRaisesRegex(ValueError, "did not prove recovery"):
            summarize(self.root, ("kimi",), 0)

    def test_original_failure_and_failed_recovery_remain_separate(self):
        self.matrix("NO-GO")
        path = self.root / "native/acceptance/summary.json"
        document = json.loads(path.read_text())
        document["results"][-1]["outcome"] = "FAIL"
        write_json(path, document)
        self.save(
            "native/product/kimi/parcel-credit-controlled/wave-result.json",
            {"outcome": "FAIL", "reasons": ["Correction was not admitted"]},
        )
        self.save(
            "native/product/kimi/parcel-credit-controlled/durable.json",
            {
                "attempts": [
                    {
                        "outcome": "FAILED",
                        "failureCode": "HTTP_CAPTURE",
                        "failureEvidence": "Invalid exposure type",
                    }
                ]
            },
        )
        result = summarize(self.root, ("kimi",), 1)
        self.assertEqual("FAIL", result["status"])
        episode = next(
            e
            for e in result["failures"]
            if e["caseId"] == "kimi/parcel-credit-controlled"
        )
        self.assertEqual("Invalid exposure type", episode["error"]["message"])
        self.assertEqual("FAILED", episode["recovery"]["status"])
        self.assertEqual("UNKNOWN", episode["recovery"]["diagnosis"]["status"])


if __name__ == "__main__":
    unittest.main()
