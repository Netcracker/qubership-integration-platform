import json
import tempfile
import unittest
from pathlib import Path

from check_element_test_coverage import (
    load_product_coverage,
    load_regression_coverage,
    load_supported_elements,
    main,
)


class ElementTestCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.matrix = self.root / "matrix.json"
        self.scenarios = self.root / "scenarios.json"
        self.suites = self.root / "suites"
        (self.suites / "skill-a").mkdir(parents=True)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_fixture(self, include_product: bool = True, known_failure: bool = False):
        self.matrix.write_text(
            json.dumps(
                {
                    "elements": [
                        {"elementType": "file-write", "status": "SUPPORTED"},
                        {"elementType": "split-2", "status": "SUPPORTED"},
                        {"elementType": "legacy", "status": "DEPRECATED"},
                    ]
                }
            ),
            encoding="utf-8",
        )
        self.scenarios.write_text(
            json.dumps(
                {
                    "sync-split": {
                        "status": "active" if include_product else "inactive",
                        "catalog": {"requiredTypes": ["split-2"]},
                        "liveTest": {
                            "knownFailure": "fixture failure" if known_failure else ""
                        },
                    }
                }
            ),
            encoding="utf-8",
        )
        (self.suites / "skill-a" / "file.yaml").write_text(
            "id: file-case\nskillId: skill-a\nelementType: file-write\n",
            encoding="utf-8",
        )

    def test_loads_supported_and_both_live_test_lanes(self):
        self.write_fixture()
        self.assertEqual(load_supported_elements(self.matrix), {"file-write", "split-2"})
        self.assertEqual(
            load_regression_coverage(self.suites)["file-write"],
            ["regression:skill-a/file-case"],
        )
        self.assertEqual(
            load_product_coverage(self.scenarios)["split-2"],
            ["product:sync-split"],
        )

    def test_returns_failure_when_supported_element_is_missing(self):
        self.write_fixture(include_product=False)
        result = main(
            [
                "--matrix",
                str(self.matrix),
                "--scenarios",
                str(self.scenarios),
                "--regression-suites",
                str(self.suites),
            ]
        )
        self.assertEqual(result, 1)

    def test_strict_mode_fails_when_only_test_is_a_known_failure(self):
        self.write_fixture(known_failure=True)
        result = main(
            [
                "--matrix",
                str(self.matrix),
                "--scenarios",
                str(self.scenarios),
                "--regression-suites",
                str(self.suites),
                "--fail-on-known-failure",
            ]
        )
        self.assertEqual(result, 1)


if __name__ == "__main__":
    unittest.main()
