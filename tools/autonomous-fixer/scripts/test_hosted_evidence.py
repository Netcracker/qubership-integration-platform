import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import hosted_evidence


class HostedEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.hosted = self.root / "hosted"
        self.hosted.mkdir()
        self.archive = self.root / "archive"

    def file(self, relative, data):
        path = self.hosted / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def test_stages_product_evidence_and_retains_provider_cost(self):
        evidence = b"browser verified folder navigation\n"
        self.file("result/report.json", b'{"status":"author-reported-fixed"}\n')
        self.file("result/provider-usage.json", b'{"reported_cost_usd":0.02}\n')
        self.file(
            "result/config.json",
            b'{"task_id":"qip-878","workflow":"fix-issue","environment":{"OPENROUTER_API_KEY":"private"}}\n',
        )
        self.file("result/step-0001-author/process.json", b"{}\n")
        self.file("result/step-0001-author/profile.json", b"{}\n")
        self.file("result/step-0001-author/response-raw.txt", b"Outcome: fixed\n")
        self.file("result/step-0001-author/stdout.jsonl", b"full native stream\n")
        self.file("result/evidence/browser/baseline.txt", evidence)
        self.file("preparation/stand.log", b"kind stand ready\n")
        self.file("preparation/extra.log", b"outside intended logs\n")
        hosted_evidence.stage(self.hosted, self.archive)
        manifest = json.loads((self.archive / "manifest.json").read_text())
        files = {file["path"]: file for file in manifest["files"]}
        self.assertEqual(manifest["reported_status"], "author-reported-fixed")
        self.assertEqual(
            files["result/evidence/browser/baseline.txt"]["sha256"],
            hashlib.sha256(evidence).hexdigest(),
        )
        self.assertEqual(
            json.loads((self.archive / "result/provider-usage.json").read_text())[
                "reported_cost_usd"
            ],
            0.02,
        )
        self.assertEqual(
            set(json.loads((self.archive / "result/config.json").read_text())),
            {"task_id", "workflow"},
        )
        self.assertIn("result/step-0001-author/process.json", files)
        self.assertIn("preparation/stand.log", files)
        self.assertNotIn("preparation/extra.log", files)
        self.assertNotIn("result/step-0001-author/stdout.jsonl", files)

    def test_stages_nonzero_exit_and_provider_error_without_copying_native_output(self):
        step = "result/step-0001-author"
        self.file(
            f"{step}/process.json", b'{"ExitCode":1,"Classification":"process_error"}'
        )
        self.file(f"{step}/stderr.log", b"")
        self.file(
            f"{step}/stdout.jsonl",
            b"\n".join(
                (
                    b'{"type":"text","part":{"text":"Outcome: fixed"}}',
                    (
                        b'{"type":"error","error":{"type":"provider.rate-limit","status":429,'
                        b'"message":"private token","response":{"body":"private response"}}}'
                    ),
                )
            ),
        )
        self.file(f"{step}/response-raw.txt", b"Outcome: fixed\n")
        self.file(
            "result/report.json",
            b'{"status":"execution-failed","error":"exit status 1"}',
        )

        hosted_evidence.stage(self.hosted, self.archive)
        diagnostic_path = f"{step}/runtime-failure.json"
        diagnostic = (self.archive / diagnostic_path).read_text()
        self.assertEqual(
            json.loads(diagnostic),
            {
                "exit_code": 1,
                "classification": "process_error",
                "author_reported_outcome": "fixed",
                "stderr": {"bytes": 0},
                "stdout": {
                    "bytes": (self.hosted / step / "stdout.jsonl").stat().st_size,
                    "last_error": {"type": "provider.rate-limit", "http_status": 429},
                },
            },
        )
        manifest = json.loads((self.archive / "manifest.json").read_text())
        self.assertEqual(manifest["reported_status"], "execution-failed")
        files = {entry["path"] for entry in manifest["files"]}
        self.assertIn(diagnostic_path, files)
        self.assertNotIn(f"{step}/stderr.log", files)
        self.assertNotIn(f"{step}/stdout.jsonl", files)

    def test_reports_stderr_failures_and_ignores_successful_processes(self):
        failed = "result/step-0001-author"
        succeeded = "result/step-0002-author"
        stderr = b"Error: connect ECONNREFUSED 127.0.0.1:12345\nprivate key on another line\n"
        self.file(
            f"{failed}/process.json", b'{"ExitCode":1,"Classification":"process_error"}'
        )
        self.file(f"{failed}/stderr.log", stderr)
        self.file(
            f"{succeeded}/process.json",
            b'{"ExitCode":0,"Classification":"completed_unverified"}',
        )
        self.file(f"{succeeded}/stderr.log", stderr)

        hosted_evidence.stage(self.hosted, self.archive)
        diagnostic = json.loads(
            (self.archive / failed / "runtime-failure.json").read_text()
        )
        self.assertEqual(
            diagnostic["stderr"],
            {"bytes": len(stderr), "signals": ["connection-refused"]},
        )
        self.assertEqual(diagnostic["stdout"], {"capture": "unavailable"})
        self.assertFalse((self.archive / succeeded / "runtime-failure.json").exists())

    def test_records_missing_session_outcome_from_terminal_stdout_event(self):
        step = "result/step-0001-author"
        self.file(
            f"{step}/process.json", b'{"ExitCode":1,"Classification":"process_error"}'
        )
        self.file(
            f"{step}/stdout.jsonl",
            b"\n".join(
                (
                    b'{"type":"error","error":{"type":"provider.timeout"}}',
                    b'{"type":"error","error":{"type":"unknown","message":"Session ended without a recorded outcome"}}',
                )
            ),
        )

        hosted_evidence.stage(self.hosted, self.archive)
        diagnostic = json.loads(
            (self.archive / step / "runtime-failure.json").read_text()
        )
        self.assertEqual(
            diagnostic["stdout"]["last_error"],
            {"type": "unknown", "reason": "missing-session-outcome"},
        )
        self.assertNotIn("reason", diagnostic)

    def test_checks_only_bounded_native_output_and_omits_unrecognized_error_fields(
        self,
    ):
        step = "result/step-0001-author"
        self.file(
            f"{step}/process.json", b'{"ExitCode":1,"Classification":"process_error"}'
        )
        self.file(
            f"{step}/stdout.jsonl",
            b"x" * 512
            + b"\n"
            + b"\n".join(
                (
                    b'{"type":"text","part":{"text":"provider.rate-limit"}}',
                    (
                        b'{"type":"error","error":{"type":"repository-token",'
                        b'"message":"repository password","status":987}}'
                    ),
                )
            ),
        )
        outside = self.root / "private"
        outside.write_text("Error: connect ECONNREFUSED with secret")
        (self.hosted / step / "stderr.log").symlink_to(outside)

        with patch.object(hosted_evidence, "DIAGNOSTIC_TAIL_BYTES", 256):
            hosted_evidence.stage(self.hosted, self.archive)
        diagnostic = json.loads(
            (self.archive / step / "runtime-failure.json").read_text()
        )
        self.assertEqual(diagnostic["stderr"], {"capture": "unavailable"})
        self.assertTrue(diagnostic["stdout"]["truncated"])
        self.assertEqual(diagnostic["stdout"]["last_error"], {"type": "unknown"})
        self.assertEqual(diagnostic["reason"], "unknown")

    def test_omits_links_auth_homes_databases_and_named_pipes(self):
        outside = self.root / "outside"
        outside.mkdir()
        secret = outside / "secret.txt"
        secret.write_text("private")
        folder = self.hosted / "result/evidence"
        folder.mkdir(parents=True)
        (folder / "file-link.txt").symlink_to(secret)
        (folder / "directory-link").symlink_to(outside, target_is_directory=True)
        (self.hosted / "preparation").symlink_to(outside, target_is_directory=True)
        os.link(secret, folder / "hard-link.txt")
        os.mkfifo(folder / "pipe.txt")
        self.file("result/author-home/credentials.json", b"private")
        self.file("result/evidence/author-home/tokens.txt", b"private")
        self.file("result/evidence/opencode.db", b"sqlite")
        self.file("result/evidence/renamed.txt", b"SQLite format 3\x00" + bytes(4096))
        hosted_evidence.stage(self.hosted, self.archive)
        self.assertEqual(
            sorted(file.name for file in self.archive.iterdir()), ["manifest.json"]
        )
        manifest = json.loads((self.archive / "manifest.json").read_text())
        self.assertEqual(manifest["omissions"]["non_regular_or_linked"]["count"], 4)
        self.assertEqual(manifest["omissions"]["unsafe_directory"]["count"], 1)
        self.assertEqual(manifest["omissions"]["private_path"]["count"], 2)
        self.assertEqual(manifest["omissions"]["sqlite"]["count"], 1)

    def test_rejects_symlinked_source_and_destination_parents(self):
        link = self.root / "linked"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError):
            hosted_evidence.stage(link / "hosted", self.archive)
        with self.assertRaises(OSError):
            hosted_evidence.stage(self.hosted, link / "archive-two")
        self.assertFalse((self.root / "archive-two").exists())

    def test_bounds_files_report_and_total_bytes(self):
        self.file("result/report.json", b"unterminated report")
        self.file("result/evidence/huge.txt", b"a" * 65)
        for name in ("first.txt", "second.txt"):
            self.file(f"result/evidence/{name}", b"b" * 40)
        with patch.multiple(hosted_evidence, MAX_FILE_BYTES=64, MAX_ARCHIVE_BYTES=60):
            hosted_evidence.stage(self.hosted, self.archive)
        manifest = json.loads((self.archive / "manifest.json").read_text())
        self.assertIsNone(manifest["reported_status"])
        self.assertEqual(len(manifest["files"]), 1)
        self.assertEqual(manifest["omissions"]["size_limit"]["count"], 2)
        self.assertEqual(manifest["omissions"]["invalid_json"]["count"], 1)


if __name__ == "__main__":
    unittest.main()
