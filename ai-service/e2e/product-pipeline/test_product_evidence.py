import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


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
                "IMPLEMENTATION_PLAN", "PLAN_VALIDATION_RESULT", "APPROVAL_RECORD",
                "MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT", "RECONCILE_RESULT",
            ],
        }

    def check(self, report):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(report))
            return subprocess.run(
                ["bash", str(Path(__file__).with_name("assert-product-run.sh")), str(path)],
                capture_output=True, text=True,
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
