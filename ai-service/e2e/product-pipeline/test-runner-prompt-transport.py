#!/usr/bin/env python3
"""Check exact initial prompt bytes through the real runner without network calls."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

DIR = Path(__file__).resolve().parent
CURL = """#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[1:]
assert args[args.index("-X") + 1] == "POST"
payload = pathlib.Path(args[args.index("-d") + 1][1:]).read_bytes()
pathlib.Path(os.environ["RUNNER_REQUEST_FILE"]).write_bytes(payload)
sys.exit(73)
"""


class RunnerPromptTransportTest(unittest.TestCase):
    def run_prompt(self, prompt, unique_name=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "curl").write_text(CURL)
            (root / "curl").chmod(0o755)
            scenario = {
                "prompt": prompt,
                "pipeline": "create-chain-v1",
                "profileId": "create-chain",
                "profileVersion": "2",
                "terminalState": "CHAIN_MATERIALIZED",
                "retainCatalogChain": True,
            }
            if unique_name:
                scenario["uniqueChainNamePrefix"] = "TransportFixture"
            (root / "scenarios.json").write_text(
                json.dumps({"prompt-transport": scenario})
            )
            result = subprocess.run(
                [
                    "bash",
                    str(DIR / "run-product-scenario.sh"),
                    "--scenario",
                    "prompt-transport",
                    "--rep",
                    "1",
                    "--base-url",
                    "http://fixture",
                    "--evaluator-url",
                    "http://fixture",
                    "--report",
                    str(root / "report.json"),
                    "--scenarios-file",
                    str(root / "scenarios.json"),
                    "--conversation-id",
                    "transport-fixture",
                    "--strict-recovery",
                ],
                env={
                    **{
                        key: value
                        for key, value in os.environ.items()
                        if key in {"PATH", "HOME", "LANG", "LC_ALL", "TMPDIR"}
                    },
                    "PATH": f"{root}:{os.environ['PATH']}",
                    "RUNNER_REQUEST_FILE": str(root / "request.json"),
                    "PRODUCT_PIPELINE_STUB_MODE": "0",
                },
                capture_output=True,
                text=True,
                timeout=15,
                check=False,
            )
            if not prompt:
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("missing prompt", result.stderr)
                self.assertFalse((root / "request.json").exists())
                return
            self.assertEqual(73, result.returncode, result.stderr)
            expected = prompt
            if unique_name:
                name = (root / "unique-chain-name.txt").read_text().removesuffix("\n")
                expected = prompt.replace("{{UNIQUE_CHAIN_NAME}}", name)
            body = json.loads((root / "request.json").read_bytes())
            interactions = [
                json.loads(line)
                for line in (root / "interactions.jsonl").read_text().splitlines()
            ]
            self.assertEqual(expected.encode("utf-8"), body["message"].encode("utf-8"))
            self.assertEqual([{"kind": "initial", "text": expected}], interactions)
            self.assertEqual("transport-fixture", body["conversationId"])
            self.assertNotIn("decision", body)

    def test_exact_source_bytes_reach_the_http_payload_and_interaction_log(self):
        for prompt in (
            "plain",
            "one final LF\n",
            "several final LFs\n\n\n",
            "first\n\nthird\n",
            "first\r\nsecond\r\n",
            "literal space \t\n",
            "literal space \t",
            "original sentinel.",
            ".\n\n",
            "quotes: \"double\" 'single' $dollar `ticks` \\slash café 中文 😀\n",
        ):
            with self.subTest(prompt=repr(prompt)):
                self.run_prompt(prompt)

    def test_unique_name_substitution_preserves_other_bytes(self):
        self.run_prompt(
            'Name {{UNIQUE_CHAIN_NAME}}\nKeep "literal " \t\n\n', unique_name=True
        )

    def test_missing_prompt_is_still_rejected_before_submission(self):
        for prompt in ("", None):
            with self.subTest(prompt=prompt):
                self.run_prompt(prompt)


if __name__ == "__main__":
    unittest.main()
