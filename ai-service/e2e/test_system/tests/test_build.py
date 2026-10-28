import os
import shutil
import tempfile
import unittest
from pathlib import Path
from subprocess import run


class BuildEntryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.script = self.root / "ai-service/scripts/build.sh"
        self.script.parent.mkdir(parents=True)
        shutil.copy(Path(__file__).parents[3] / "scripts/build.sh", self.script)
        self.pom = self.root / "ai-service/pom.xml"
        self.pom.parent.mkdir(parents=True, exist_ok=True)
        self.pom.touch()
        self.packages = self.root / ".private"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.arguments = self.root / "maven-arguments.txt"
        self.mvn = self.bin / "mvn"
        self.mvn.write_text(
            "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$MAVEN_ARGUMENTS_FILE\"\n"
            "exit \"${MAVEN_EXIT_CODE:-0}\"\n"
        )
        self.mvn.chmod(0o755)
        self.env = {
            **os.environ,
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            "MAVEN_ARGUMENTS_FILE": str(self.arguments),
        }

    def build(self, *arguments, exit_code=0):
        return run(
            [str(self.script), *arguments],
            env={**self.env, "MAVEN_EXIT_CODE": str(exit_code)},
            capture_output=True,
            text=True,
            check=False,
        )

    def test_missing_rocky_packages_select_the_base_build(self):
        result = self.build("-DskipTests")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            [
                "-f",
                str(self.pom),
                "package",
                "-Dgpg.skip=true",
                "-DskipTests",
            ],
            self.arguments.read_text().splitlines(),
        )

    def test_both_package_paths_select_the_profile_build(self):
        (self.packages / "rocky-skills").mkdir(parents=True)
        (self.packages / "rocky-knowledge").mkdir()

        result = self.build()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-Pwith-rocky-skills", self.arguments.read_text().splitlines())

    def test_each_partial_package_selects_the_profile_build(self):
        for package in ("rocky-skills", "rocky-knowledge"):
            with self.subTest(package=package):
                shutil.rmtree(self.packages, ignore_errors=True)
                (self.packages / package).mkdir(parents=True)

                result = self.build()

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(
                    "-Pwith-rocky-skills", self.arguments.read_text().splitlines()
                )

    def test_invalid_package_entry_selects_the_profile_build(self):
        self.packages.mkdir()
        (self.packages / "rocky-skills").write_text("invalid package")

        result = self.build()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-Pwith-rocky-skills", self.arguments.read_text().splitlines())

    def test_dangling_package_symlink_selects_the_profile_build(self):
        self.packages.mkdir()
        (self.packages / "rocky-knowledge").symlink_to(self.root / "missing-package")

        result = self.build()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-Pwith-rocky-skills", self.arguments.read_text().splitlines())

    def test_maven_failure_is_returned_unchanged(self):
        result = self.build(exit_code=37)

        self.assertEqual(37, result.returncode)

    def test_private_build_failure_does_not_retry_as_base(self):
        (self.packages / "rocky-skills").mkdir(parents=True)

        result = self.build(exit_code=37)

        self.assertEqual(37, result.returncode)
        self.assertIn("-Pwith-rocky-skills", self.arguments.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
