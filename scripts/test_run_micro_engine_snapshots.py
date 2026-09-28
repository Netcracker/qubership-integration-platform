"""Tests for the snapshot launcher's Maven argument and exit-code forwarding."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

MAVEN_OPTIONS = (
    "-q -B -Dstyle.color=never -pl micro-engine -PsnapshotTests -Dgpg.skip=true"
).split()


class SnapshotRunnerTest(unittest.TestCase):
    """Check launcher behavior using isolated Maven stand-ins."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.runner = scripts / "run-micro-engine-snapshots.py"
        shutil.copyfile(Path(__file__).with_name(self.runner.name), self.runner)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment = {**os.environ, "PATH": str(self.bin)}
        self.wrapper = self.root / "mvnw"
        self.write_maven(self.wrapper)

    def write_maven(self, executable, default_exit_code=0):
        """Create a Maven stand-in that records arguments and accepts an exit code."""
        executable.write_text(
            textwrap.dedent(
                f"""\
            #!{sys.executable}
            import json
            import sys
            from pathlib import Path
            args = sys.argv[1:]
            with Path('commands.jsonl').open('a') as output:
                output.write(json.dumps({{'args': args, 'cwd': str(Path.cwd())}}) + '\\n')
            status = next((arg.split('=', 1)[1] for arg in args if arg.startswith('-DexitCode=')),
                          '{default_exit_code}')
            sys.exit(int(status))
            """
            ),
            encoding="utf-8",
        )
        executable.chmod(0o755)

    def run_maven(self, *arguments):
        """Run the launcher with a PATH restricted to the test executables."""
        return subprocess.run(
            [sys.executable, str(self.runner), *arguments],
            cwd=self.root / "scripts",
            env=self.environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def commands(self):
        """Read the arguments and working directory recorded by each Maven call."""
        return [
            json.loads(line)
            for line in (self.root / "commands.jsonl").read_text().splitlines()
        ]

    def test_runs_one_maven_test_lifecycle_with_six_workers_by_default(self):
        """The default command uses six workers and the repository root."""
        result = self.run_maven()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            [
                {
                    "args": [
                        *MAVEN_OPTIONS,
                        "-Dsnapshot.workers=6",
                        "test",
                    ],
                    "cwd": str(self.root),
                }
            ],
            self.commands(),
        )

    def test_forwards_filters_and_options_without_splitting_arguments(self):
        """Filters and paths containing spaces reach Maven as separate arguments."""
        result = self.run_maven(
            "--workers",
            "3",
            "--",
            "-Dsnapshot.target=kafka-sender",
            "-Dsnapshot.scenario=publishes-gzip-compressed-records",
            "-s",
            "/path with spaces/settings.xml",
            "-nsu",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertEqual(1, len(commands))
        self.assertEqual(
            [
                "-Dsnapshot.workers=3",
                "-Dsnapshot.target=kafka-sender",
                "-Dsnapshot.scenario=publishes-gzip-compressed-records",
                "-s",
                "/path with spaces/settings.xml",
                "-nsu",
                "test",
            ],
            commands[0]["args"][len(MAVEN_OPTIONS):],
        )

    def test_preserves_maven_failure_exit_code(self):
        """A wrapper failure becomes the launcher exit code."""
        result = self.run_maven("--", "-DexitCode=7")

        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(1, len(self.commands()))

    def test_uses_maven_from_path_when_wrapper_is_missing(self):
        """Maven from PATH receives the same options when the wrapper is absent."""
        self.wrapper.unlink()
        self.write_maven(self.bin / "mvn")

        result = self.run_maven(
            "--workers",
            "2",
            "--",
            "-Dsnapshot.target=kafka-sender",
            "-Dsnapshot.scenario=publishes-gzip-compressed-records",
            "-s",
            "/path with spaces/settings.xml",
            "-nsu",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            [
                {
                    "args": [
                        *MAVEN_OPTIONS,
                        "-Dsnapshot.workers=2",
                        "-Dsnapshot.target=kafka-sender",
                        "-Dsnapshot.scenario=publishes-gzip-compressed-records",
                        "-s",
                        "/path with spaces/settings.xml",
                        "-nsu",
                        "test",
                    ],
                    "cwd": str(self.root),
                }
            ],
            self.commands(),
        )

    def test_preserves_path_maven_failure_exit_code(self):
        """A failure from Maven on PATH becomes the launcher exit code."""
        self.wrapper.unlink()
        self.write_maven(self.bin / "mvn")

        result = self.run_maven("--", "-DexitCode=7")

        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(1, len(self.commands()))

    def test_prefers_wrapper_over_maven_from_path(self):
        """The wrapper takes precedence when both Maven executables exist."""
        self.write_maven(self.bin / "mvn", default_exit_code=13)

        result = self.run_maven()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(self.commands()))

    def test_reports_error_when_wrapper_and_path_maven_are_missing(self):
        """Missing Maven executables produce a diagnostic and a failing exit code."""
        self.wrapper.unlink()

        result = self.run_maven()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot start Maven", result.stderr)
        self.assertIn("mvn", result.stderr)
        self.assertFalse((self.root / "commands.jsonl").exists())

    def test_rejects_invalid_worker_count_before_starting_maven(self):
        """Zero, negative, and nonnumeric worker counts prevent Maven from starting."""
        for worker_count in ("0", "-1", "two"):
            with self.subTest(worker_count=worker_count):
                result = self.run_maven("--workers", worker_count)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("--workers", result.stderr)
                self.assertFalse((self.root / "commands.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
