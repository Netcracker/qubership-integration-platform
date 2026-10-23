import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from test_system.catalog import CONTRACT, Catalog
from test_system.runs import RunStore, create_run, execute_run


class RunsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "ai-service/e2e").mkdir(parents=True)
        self.store = RunStore(self.root / "results")
        mock = patch(
            "test_system.runs.git_identity",
            return_value={"commit": "candidate", "dirty": False},
        )
        mock.start()
        self.addCleanup(mock.stop)

    def catalog(self, suites):
        for suite in suites:
            suite.setdefault("usesLlm", False)
            suite.setdefault("timeoutSeconds", 10)
        (self.root / "ai-service/e2e/suites.json").write_text(
            json.dumps(
                {
                    "contract": CONTRACT,
                    "profiles": {"core-regression": [s["id"] for s in suites]},
                    "suites": suites,
                }
            )
        )
        return Catalog(self.root)

    def test_real_subprocess_reports_native_failure_and_retains_logs(self):
        command = [
            sys.executable,
            "-c",
            "from pathlib import Path; Path('{task_dir}/native.xml').write_text('<testsuite><testcase name=\"wrong-version\"><failure>expected 2 got 1</failure></testcase></testsuite>'); print('native rejection'); raise SystemExit(1)",
        ]
        catalog = self.catalog(
            [
                {
                    "id": "imports",
                    "format": "junit",
                    "command": command,
                    "reports": "{task_dir}/native.xml",
                }
            ]
        )
        run = create_run(catalog, self.store, "core-regression")
        execute_run(self.store, run["id"])
        result = self.store.read(run["id"])
        self.assertEqual(result["status"], "FAIL")
        episode = result["tasks"][0]["failures"][0]
        self.assertEqual(episode["id"], "F001")
        self.assertIn("expected 2", episode["error"]["message"])
        self.assertIn(
            "native rejection",
            (self.store.directory(run["id"]) / result["tasks"][0]["log"]).read_text(),
        )

    def test_missing_report_is_error_and_core_failure_blocks_llm_task(self):
        catalog = self.catalog(
            [
                {
                    "id": "imports",
                    "gate": True,
                    "format": "junit",
                    "reports": "{task_dir}/missing.xml",
                    "command": [sys.executable, "-c", "pass"],
                },
                {
                    "id": "chain",
                    "requiresCore": True,
                    "usesLlm": True,
                    "command": [
                        sys.executable,
                        "-c",
                        "raise RuntimeError('must not run')",
                    ],
                },
            ]
        )
        run = create_run(catalog, self.store, "core-regression", allow_llm=True)
        execute_run(self.store, run["id"])
        result = self.store.read(run["id"])
        self.assertEqual(result["status"], "ERROR")
        self.assertEqual(result["tasks"][1]["status"], "NOT_RUN")

    def test_non_llm_tasks_disable_an_inherited_live_recovery_provider(self):
        catalog = self.catalog(
            [
                {
                    "id": "offline",
                    "command": [
                        sys.executable,
                        "-c",
                        "import os; assert os.environ['QIP_RECOVERY_LIVE'] == 'false'; assert 'LLM_API_KEY' not in os.environ",
                    ],
                }
            ]
        )
        with patch.dict(
            "os.environ", QIP_RECOVERY_LIVE="true", LLM_API_KEY="local-fixture"
        ):
            run = create_run(catalog, self.store, "core-regression")
            execute_run(self.store, run["id"])
        self.assertEqual("PASS", self.store.read(run["id"])["status"])

    def test_llm_is_blocked_without_explicit_execution_setting(self):
        catalog = self.catalog(
            [{"id": "chain", "usesLlm": True, "command": ["must-not-run"]}]
        )
        run = create_run(catalog, self.store, "core-regression")
        self.assertEqual(run["status"], "BLOCKED")

    def test_exited_worker_cannot_leave_a_run_successful_or_running(self):
        catalog = self.catalog(
            [{"id": "one", "command": [sys.executable, "-c", "pass"]}]
        )
        run = create_run(catalog, self.store, "core-regression")
        with self.store.edit(run["id"]) as document:
            document.update(status="RUNNING", workerPid=99999999)
        with patch("test_system.runs.os.kill", side_effect=ProcessLookupError):
            result = self.store.read(run["id"])
        self.assertEqual(result["status"], "ERROR")
        self.assertEqual(result["tasks"][0]["status"], "NOT_RUN")

    def test_cancel_stops_a_running_process_and_keeps_partial_report(self):
        catalog = self.catalog(
            [
                {
                    "id": "wait",
                    "command": [
                        sys.executable,
                        "-c",
                        "import time; print('started',flush=True); time.sleep(60)",
                    ],
                }
            ]
        )
        run = create_run(catalog, self.store, "core-regression")
        worker = threading.Thread(target=execute_run, args=(self.store, run["id"]))
        worker.start()
        for _ in range(100):
            if self.store.read(run["id"])["tasks"][0]["status"] == "RUNNING":
                break
            time.sleep(0.02)
        self.store.cancel(run["id"])
        worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(self.store.read(run["id"])["status"], "CANCELLED")

    def test_diagnosis_updates_preserve_previous_diagnosis_and_verdict(self):
        catalog = self.catalog(
            [{"id": "broken", "command": [sys.executable, "-c", "raise SystemExit(1)"]}]
        )
        run = create_run(catalog, self.store, "core-regression")
        execute_run(self.store, run["id"])
        evidence = self.store.directory(run["id"]) / "reproduction.txt"
        evidence.write_text("Reproduced the mismatch using the saved input.")
        self.store.diagnose(
            run["id"],
            "F001",
            "failure",
            {
                "status": "CONFIRMED",
                "summary": "Wrong version",
                "evidence": ["reproduction.txt"],
            },
        )
        result = self.store.read(run["id"])
        self.assertEqual(result["status"], "FAIL")
        episode = result["tasks"][0]["failures"][0]
        self.assertEqual(
            episode["diagnosisHistory"][0]["previous"]["status"], "UNKNOWN"
        )
        with self.assertRaises(ValueError):
            self.store.diagnose(
                run["id"],
                "F001",
                "recovery",
                {"status": "CONFIRMED", "evidence": ["../../outside"]},
            )


if __name__ == "__main__":
    unittest.main()
