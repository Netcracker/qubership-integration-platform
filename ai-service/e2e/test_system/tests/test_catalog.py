import json
import tempfile
import unittest
from pathlib import Path

from test_system.catalog import CONTRACT, Catalog, inside


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        directory = self.root / "ai-service/e2e"
        directory.mkdir(parents=True)
        self.document = {
            "contract": CONTRACT,
            "profiles": {"core-regression": ["imports"]},
            "suites": [
                {
                    "id": "imports",
                    "usesLlm": False,
                    "command": ["python3", "test.py"],
                    "timeoutSeconds": 30,
                }
            ],
        }
        (directory / "suites.json").write_text(json.dumps(self.document))

    def test_missing_package_is_visible_and_does_not_disable_core(self):
        catalog = Catalog(self.root)
        self.assertIsNone(catalog.plan("core-regression")["blocked"])
        self.assertTrue(catalog.plan("chain-acceptance")["blocked"])
        self.assertFalse(
            next(p for p in catalog.describe()["profiles"] if p["id"] == "recovery")[
                "available"
            ]
        )

    def test_selection_cannot_inject_a_command_or_repeat_a_suite(self):
        catalog = Catalog(self.root)
        self.assertEqual(
            len(catalog.plan("core-regression", ["imports", "imports"])["suites"]), 1
        )
        with self.assertRaises(ValueError):
            catalog.plan("core-regression", ["imports; touch sentinel"])
        with self.assertRaises(ValueError):
            inside(self.root, "../../etc/passwd")

    def test_prepared_acceptance_selects_kimi_and_validates_model_selection(self):
        self.document["profiles"]["recovery-acceptance"] = ["imports"]
        (self.root / "ai-service/e2e/suites.json").write_text(json.dumps(self.document))
        catalog = Catalog(self.root)
        self.assertEqual(
            "kimi", catalog.plan("recovery-acceptance")["settings"]["model"]
        )
        self.assertEqual(
            "both",
            catalog.plan("recovery-acceptance", settings={"model": "both"})["settings"][
                "model"
            ],
        )
        with self.assertRaisesRegex(ValueError, "ordinary, kimi, or both"):
            catalog.plan(
                "recovery-acceptance", settings={"model": "arbitrary-provider"}
            )

    def test_urls_cannot_embed_credentials(self):
        with self.assertRaises(ValueError):
            Catalog(self.root).plan(
                "core-regression", settings={"base_url": "http://user:secret@host"}
            )

    def test_invalid_package_does_not_partially_register_suites(self):
        directory = self.root / ".private/rocky-skills/testing"
        directory.mkdir(parents=True)
        document = {**self.document, "profiles": {"chain-acceptance": ["missing"]}}
        document["suites"] = [{**self.document["suites"][0], "id": "chain-one"}]
        (directory / "suites.json").write_text(json.dumps(document))
        catalog = Catalog(self.root)
        self.assertNotIn("chain-one", catalog.suites)
        self.assertTrue(catalog.plan("chain-acceptance")["blocked"])

    def test_selecting_a_live_suite_preserves_all_profile_gates(self):
        self.document["suites"][0]["gate"] = True
        self.document["suites"].append(
            {
                "id": "chain",
                "usesLlm": True,
                "requiresCore": True,
                "timeoutSeconds": 30,
                "command": ["not-executed"],
            }
        )
        self.document["profiles"]["core-regression"].append("chain")
        (self.root / "ai-service/e2e/suites.json").write_text(json.dumps(self.document))
        selected = Catalog(self.root).plan("core-regression", ["chain"])
        self.assertEqual([s["id"] for s in selected["suites"]], ["imports", "chain"])


if __name__ == "__main__":
    unittest.main()
