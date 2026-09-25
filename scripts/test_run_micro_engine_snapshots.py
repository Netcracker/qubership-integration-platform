"""Tests for the snapshot launcher's Maven argument and exit-code forwarding."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


class SnapshotRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.runner = scripts / "run-micro-engine-snapshots.py"
        shutil.copyfile(Path(__file__).with_name(self.runner.name), self.runner)
        wrapper = self.root / "mvnw"
        wrapper.write_text(textwrap.dedent("""\
            #!/usr/bin/env python3
            import json
            from pathlib import Path
            import sys
            args = sys.argv[1:]
            with Path('commands.jsonl').open('a') as output:
                output.write(json.dumps({'args': args, 'cwd': str(Path.cwd())}) + '\\n')
            status = next((arg.split('=', 1)[1] for arg in args if arg.startswith('-DexitCode=')), '0')
            sys.exit(int(status))
            """), encoding="utf-8")
        wrapper.chmod(0o755)

    def run_maven(self, *arguments):
        return subprocess.run([sys.executable, str(self.runner), *arguments], cwd=self.root / "scripts",
                              text=True, capture_output=True, check=False)

    def commands(self):
        return [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]

    def test_runs_one_maven_test_lifecycle_with_six_workers_by_default(self):
        result = self.run_maven()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([{
            "args": [
                "-q", "-B", "-Dstyle.color=never", "-pl", "micro-engine", "-PsnapshotTests",
                "-Dgpg.skip=true", "-Dsnapshot.workers=6", "test"
            ],
            "cwd": str(self.root),
        }], self.commands())

    def test_forwards_filters_and_options_without_splitting_arguments(self):
        result = self.run_maven("--workers", "3", "--", "-Dsnapshot.target=kafka-sender",
                                "-Dsnapshot.scenario=publishes-gzip-compressed-records",
                                "-s", "/path with spaces/settings.xml", "-nsu")

        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertEqual(1, len(commands))
        self.assertEqual([
            "-Dsnapshot.workers=3", "-Dsnapshot.target=kafka-sender",
            "-Dsnapshot.scenario=publishes-gzip-compressed-records", "-s",
            "/path with spaces/settings.xml", "-nsu", "test",
        ], commands[0]["args"][7:])

    def test_preserves_maven_failure_exit_code(self):
        result = self.run_maven("--", "-DexitCode=7")

        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(1, len(self.commands()))

    def test_rejects_invalid_worker_count_before_starting_maven(self):
        for worker_count in ("0", "-1", "two"):
            with self.subTest(worker_count=worker_count):
                result = self.run_maven("--workers", worker_count)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("--workers", result.stderr)
                self.assertFalse((self.root / "commands.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
