import copy
import json
import runpy
import subprocess
import tempfile
import unittest
from pathlib import Path

build_report = runpy.run_path(
    str(Path(__file__).with_name("build-report-from-evidence.py"))
)["build_report"]


class ProductEvidenceReportTest(unittest.TestCase):
    def evidence(self):
        package = {
            "packageKey": "fixture@1.0.0",
            "knowledgeVersion": "1.0.0",
            "schemaVersion": "1.0.0",
            "packageChecksum": "sha256:fixture",
            "certificationStatus": "CERTIFIED",
            "certificationDigest": "sha256:certificate",
        }
        return {
            "runId": "run",
            "currentState": "CHAIN_MATERIALIZED",
            "runManifest": {
                "runtimeSelection": "product",
                "profileId": "create-chain",
                "profileVersion": "2",
                "knowledgePackage": package,
            },
            "knowledgeContext": {
                "packageRef": package,
                "objectIds": [],
                "contentChars": 0,
            },
            "attempts": [
                {
                    "runRevision": revision,
                    "outcome": outcome,
                    "outputs": [
                        {"kind": "PLAN_VALIDATION_RESULT", "artifactId": artifact_id}
                    ],
                }
                for revision, outcome, artifact_id in (
                    (23, "FAILED", "rejected"),
                    (27, "SUCCEEDED", "accepted"),
                )
            ],
            "decodedArtifacts": {
                "PLAN_VALIDATION_RESULT#rejected": {
                    "findings": [{"code": "MISSING_PROPERTY", "blocker": True}]
                },
                "PLAN_VALIDATION_RESULT#accepted": {"findings": []},
            },
        }

    def report(self, evidence):
        return build_report(
            evidence,
            scenario_id="product-create-chain-dual-trigger-greetings",
            rep=1,
            required_facts=[],
            forbidden_facts=[],
            expected_terminal_state="CHAIN_MATERIALIZED",
        )

    def test_reports_repaired_validation_independently_of_history_order(self):
        for reverse_attempts in (False, True):
            for reverse_decoded in (False, True):
                with self.subTest(
                    reverse_attempts=reverse_attempts, reverse_decoded=reverse_decoded
                ):
                    evidence = self.evidence()
                    if reverse_attempts:
                        evidence["attempts"].reverse()
                    if reverse_decoded:
                        evidence["decodedArtifacts"] = dict(
                            reversed(list(evidence["decodedArtifacts"].items()))
                        )
                    report = self.report(evidence)
                    self.assertEqual("PASS", report["validationVerdict"])
                    self.assertEqual([], report["validationFindings"])

    def test_reports_a_later_failed_validation(self):
        evidence = self.evidence()
        evidence["attempts"][0]["runRevision"] = 29
        self.assertEqual("FAIL", self.report(evidence)["validationVerdict"])

    def test_rejects_missing_current_validation_payload(self):
        evidence = self.evidence()
        del evidence["decodedArtifacts"]["PLAN_VALIDATION_RESULT#accepted"]
        with self.assertRaisesRegex(ValueError, "missing PLAN_VALIDATION_RESULT"):
            self.report(evidence)

    def test_rejects_conflicting_validation_references_at_the_same_revision(self):
        evidence = self.evidence()
        evidence["attempts"][0]["runRevision"] = 27
        with self.assertRaisesRegex(ValueError, "conflicting.*PLAN_VALIDATION_RESULT"):
            self.report(evidence)

    def test_duplicate_output_references_select_the_same_validation(self):
        evidence = self.evidence()
        evidence["attempts"].append(copy.deepcopy(evidence["attempts"][-1]))
        self.assertEqual("PASS", self.report(evidence)["validationVerdict"])

    def test_reports_validation_without_attempt_output_references(self):
        evidence = self.evidence()
        evidence["attempts"] = []
        evidence["decodedArtifacts"].pop("PLAN_VALIDATION_RESULT#rejected")
        self.assertEqual("PASS", self.report(evidence)["validationVerdict"])


class ProductEvidenceTest(unittest.TestCase):
    def report(self):
        return {
            "scenarioId": "product-create-chain-error-handling",
            "runtimeMode": "product",
            "profileId": "create-chain",
            "profileVersion": "2",
            "validationVerdict": "PASS",
            "terminalState": "CHAIN_MATERIALIZED",
            "expectedTerminalState": "CHAIN_MATERIALIZED",
            "materializedChainId": "test-chain",
            "approvedPlanContentHash": "approved-plan",
            "reconcileMatches": True,
            "hasGeneratedBundle": False,
            "hasPublicationReceipt": False,
            "hasCatalogMutation": False,
            "hasDeploymentArtifact": False,
            "knowledgePackage": {
                "packageKey": "CIP@1.0.0",
                "knowledgeVersion": "1.0.0",
                "schemaVersion": "1.0.0",
                "packageChecksum": "sha256:package",
                "certificationStatus": "CERTIFIED",
                "certificationDigest": "sha256:certification",
            },
            "knowledgeContext": {
                "packageChecksum": "sha256:package",
                "objectIds": ["CIP:PAT-000007"],
                "contentChars": 120,
            },
            "materializedElementTypes": ["try-catch-finally-2", "try-2", "catch-2"],
            "committedArtifactKinds": [
                "IMPLEMENTATION_PLAN",
                "PLAN_VALIDATION_RESULT",
                "APPROVAL_RECORD",
                "MATERIALIZATION_RESULT",
                "CATALOG_CHAIN_SNAPSHOT",
                "RECONCILE_RESULT",
            ],
        }

    def check(self, report):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(report))
            return subprocess.run(
                [
                    "bash",
                    str(Path(__file__).with_name("assert-product-run.sh")),
                    str(path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_accepts_certified_knowledge_without_a_specific_object(self):
        result = self.check(self.report())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rejects_missing_or_untrusted_evidence(self):
        mutations = [
            ("knowledgeContext", "objectIds", []),
            ("knowledgeContext", "objectIds", [""]),
            ("knowledgeContext", "objectIds", [42]),
            ("knowledgeContext", "contentChars", 0),
            ("knowledgeContext", "packageChecksum", "sha256:other"),
            ("knowledgePackage", "certificationStatus", "UNTRUSTED"),
        ]
        for section, key, value in mutations:
            with self.subTest(section=section, key=key, value=value):
                report = copy.deepcopy(self.report())
                report[section][key] = value
                result = self.check(report)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rejects_missing_error_scope_elements(self):
        for element_type in self.report()["materializedElementTypes"]:
            with self.subTest(element_type=element_type):
                report = self.report()
                report["materializedElementTypes"].remove(element_type)
                result = self.check(report)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
