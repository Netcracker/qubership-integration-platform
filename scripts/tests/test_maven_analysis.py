import argparse
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import maven_analysis


class AnalysisBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "bundle"
        self.module = self.root / "consumer"
        (self.module / "target/classes").mkdir(parents=True)
        (self.module / "target/classes/Consumer.class").write_bytes(b"compiled consumer")
        (self.module / "target/classes/.gitkeep").touch()
        (self.module / "target/site/jacoco").mkdir(parents=True)
        (self.module / "target/site/jacoco/jacoco.xml").write_text("<report/>")
        (self.module / "target/surefire-reports").mkdir()
        (self.module / "target/surefire-reports/TEST-Consumer.xml").write_text("<testsuite/>")
        self.pom = self.root / "flattened.xml"
        self.pom.write_text("<project><dependencies><dependency>library</dependency></dependencies></project>")
        self.jar = self.root / "library.jar"
        self.jar.write_bytes(b"current library")
        self.context = {"commit": "current", "run": "12", "attempt": "1", "wrapper": "pinned"}
        environment = patch.dict(
            os.environ,
            {"MAVEN_ANALYSIS_DIRECTORY": str(self.bundle), "MAVEN_ANALYSIS_MODULE": "consumer"},
        )
        environment.start()
        self.addCleanup(environment.stop)
        for replacement in [
            patch.object(maven_analysis, "ROOT", self.root),
            patch.object(maven_analysis, "identity", return_value=self.context),
        ]:
            replacement.start()
            self.addCleanup(replacement.stop)
        maven_analysis.export_project(
            argparse.Namespace(
                group="ci.probe",
                name="library",
                version="1-SNAPSHOT",
                packaging="jar",
                artifact=str(self.jar),
                pom=str(self.pom),
            )
        )
        maven_analysis.seal_bundle()

    def test_restores_the_current_library_pom_and_classes_without_recompiling(self):
        shutil.rmtree(self.module / "target")
        with patch.object(maven_analysis.subprocess, "run") as execute:
            maven_analysis.restore_bundle(self.root)
        command = execute.call_args.args[0]
        self.assertIn(maven_analysis.INSTALL_GOAL, command)
        self.assertIn("-DgeneratePom=false", command)
        self.assertNotIn("verify", command)
        self.assertEqual((self.module / "target/classes/Consumer.class").read_bytes(), b"compiled consumer")
        self.assertTrue((self.module / "target/classes/.gitkeep").is_file())
        self.assertEqual((self.module / "target/site/jacoco/jacoco.xml").read_text(), "<report/>")
        self.assertEqual((self.module / "target/surefire-reports/TEST-Consumer.xml").read_text(), "<testsuite/>")
        copied_pom = next(
            argument.removeprefix("-DpomFile=") for argument in command if argument.startswith("-DpomFile=")
        )
        self.assertEqual(Path(copied_pom).read_text(encoding="utf-8"), self.pom.read_text())

    def test_a_missing_library_fails_before_an_old_cached_copy_can_be_used(self):
        (self.bundle / "reactor/ci.probe/library/1-SNAPSHOT/artifact.jar").unlink()
        with patch.object(maven_analysis.subprocess, "run") as execute:
            with self.assertRaises(FileNotFoundError):
                maven_analysis.restore_bundle(self.root)
        execute.assert_not_called()

    def test_changed_bytes_fail_before_import(self):
        (self.bundle / "reactor/ci.probe/library/1-SNAPSHOT/artifact.jar").write_bytes(b"old cached library")
        with patch.object(maven_analysis.subprocess, "run") as execute:
            with self.assertRaisesRegex(ValueError, "different digest"):
                maven_analysis.restore_bundle(self.root)
        execute.assert_not_called()

    def test_a_failed_analysis_can_reuse_the_build_from_an_earlier_attempt(self):
        with patch.object(maven_analysis, "identity", return_value=dict(self.context, attempt="2")):
            with patch.object(maven_analysis.subprocess, "run") as execute:
                maven_analysis.restore_bundle(self.root)
        execute.assert_called_once()

    def test_another_checkout_run_or_wrapper_cannot_supply_the_analysis(self):
        manifest_path = self.bundle / "manifest.json"
        for field in ["commit", "run", "wrapper"]:
            with self.subTest(field=field):
                manifest = json.loads(manifest_path.read_text())
                manifest["identity"] = dict(self.context, **{field: "different"})
                manifest_path.write_text(json.dumps(manifest))
                with patch.object(maven_analysis.subprocess, "run") as execute:
                    with self.assertRaisesRegex(ValueError, "another checkout"):
                        maven_analysis.restore_bundle(self.root)
                execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
