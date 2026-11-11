import copy
import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


class SchemaBuildTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="qip schema build ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.service = self.root / "ai-service"
        self.service.mkdir()
        self.source = self.root / "schema input"
        self.write_schemas(self.source)
        self.output = self.service / "target/qip-schemas-staging/qip-schemas"
        self.archive = self.root / "schemas.tar.gz"
        self.env = dict(os.environ)
        if os.name != "nt":
            binary = self.root / "bin"
            binary.mkdir()
            bash = binary / "bash"
            bash.write_text(
                "#!/bin/sh\necho 'Bash must not prepare schemas' >&2\nexit 97\n"
            )
            bash.chmod(0o755)
            self.env["PATH"] = str(binary) + os.pathsep + self.env["PATH"]
        self.write_pom()

    def write_schemas(self, directory):
        elements = directory / "element"
        elements.mkdir(parents=True)
        (elements / "http-trigger.schema.yaml").write_text(
            'title: "HTTP: Trigger"\n$ref: "../common-properties/value"\n',
            encoding="utf-8",
        )
        (elements / "old.schema.yaml").write_text("title: Old (deprecated)\n")
        (elements / "element.schema.yaml").write_text("title: Common element\n")
        common = directory / "common-properties"
        common.mkdir()
        (common / "value.schema.yaml").write_text("type: string\n")

    def write_pom(self):
        namespace = "http://maven.apache.org/POM/4.0.0"
        ns = {"m": namespace}
        original = ET.parse(Path(__file__).parents[3] / "pom.xml").getroot()
        plugin = next(
            item
            for item in original.findall("m:build/m:plugins/m:plugin", ns)
            if item.find("m:executions/m:execution/[m:id='fetch-qip-schemas']", ns)
            is not None
        )
        plugin = copy.deepcopy(plugin)
        executions = plugin.find("m:executions", ns)
        for execution in list(executions):
            if execution.findtext("m:id", namespaces=ns) != "fetch-qip-schemas":
                executions.remove(execution)
        for element in plugin.iter():
            element.tag = element.tag.rsplit("}", 1)[-1]
        scripts = self.service / "scripts"
        scripts.mkdir()
        for source in (Path(__file__).parents[3] / "scripts").iterdir():
            if source.name.startswith("fetch-qip-schemas."):
                shutil.copy2(source, scripts / source.name)
        project = ET.Element("project", xmlns=namespace)
        for name, value in (
            ("modelVersion", "4.0.0"),
            ("groupId", "test"),
            ("artifactId", "schema-build"),
            ("version", "1"),
            ("packaging", "pom"),
        ):
            ET.SubElement(project, name).text = value
        properties = ET.SubElement(project, "properties")
        for name, value in {
            "qip.schemas.sync.skip": "false",
            "qip.schemas.local.dir": str(self.source),
            "qip.schemas.download.url": self.archive.as_uri(),
        }.items():
            ET.SubElement(properties, name).text = value
        plugins = ET.SubElement(ET.SubElement(project, "build"), "plugins")
        plugins.append(plugin)
        ET.ElementTree(project).write(self.service / "pom.xml", encoding="utf-8")

    def build(self, **properties):
        maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
        self.assertIsNotNone(maven, "Maven must be installed for build-script tests")
        return subprocess.run(
            [
                maven,
                "-B",
                "-ntp",
                "-f",
                str(self.service / "pom.xml"),
                *[f"-D{name}={value}" for name, value in properties.items()],
                "generate-resources",
            ],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )

    def assert_schemas(self):
        self.assertEqual(
            'title: "HTTP: Trigger"\n$ref: "../common-properties/value"\n',
            (self.output / "element/http-trigger.schema.yaml").read_text(),
        )
        self.assertEqual(
            "type: string\n",
            (self.output / "common-properties/value.schema.yaml").read_text(),
        )
        self.assertEqual(
            {
                "elements": [
                    {
                        "type": "http-trigger",
                        "title": "HTTP: Trigger",
                        "deprecated": False,
                    },
                    {"type": "old", "title": "Old (deprecated)", "deprecated": True},
                ]
            },
            json.loads((self.output / "element-index.json").read_text()),
        )

    def test_local_schemas_and_index_build_without_bash(self):
        self.output.mkdir(parents=True)
        (self.output / "stale.yaml").write_text("stale")
        result = self.build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assert_schemas()
        self.assertFalse(
            (self.output / "stale.yaml").exists(), self.output / "stale.yaml"
        )

    def test_sibling_schemas_are_used_when_the_override_is_empty(self):
        sibling = self.root / "schemas/src/main/resources/conf-model"
        sibling.parent.mkdir(parents=True)
        shutil.move(self.source, sibling)
        result = self.build(**{"qip.schemas.local.dir": ""})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assert_schemas()

    def test_an_explicit_missing_source_does_not_fall_back_to_download(self):
        result = self.build(**{"qip.schemas.local.dir": str(self.root / "missing")})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Schema directory not found", result.stdout + result.stderr)

    def test_a_source_without_element_schemas_fails(self):
        shutil.rmtree(self.source / "element")
        result = self.build()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            "Element schema directory not found", result.stdout + result.stderr
        )

    def test_the_existing_skip_flag_leaves_staging_untouched(self):
        self.output.mkdir(parents=True)
        marker = self.output / "marker"
        marker.write_text("retained")
        result = self.build(**{"qip.schemas.sync.skip": "true"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("retained", marker.read_text())
        self.assertFalse(
            (self.output / "element-index.json").exists(),
            self.output / "element-index.json",
        )

    def test_archive_fallback_preserves_monorepo_and_legacy_layouts(self):
        for prefix in (
            "repo/schemas/src/main/resources/conf-model",
            "schemas/src/main/resources/conf-model",
            "repo/src/main/resources/qip-model",
            "src/main/resources/qip-model",
        ):
            with self.subTest(layout=prefix):
                with tarfile.open(self.archive, "w:gz") as archive:
                    archive.add(self.source, arcname=prefix)
                result = self.build(**{"qip.schemas.local.dir": ""})
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assert_schemas()

    def test_an_archive_cannot_write_outside_the_schema_tree(self):
        with tarfile.open(self.archive, "w:gz") as archive:
            entry = tarfile.TarInfo(
                "repo/schemas/src/main/resources/conf-model/../../escaped.yaml"
            )
            data = b"outside"
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
        result = self.build(**{"qip.schemas.local.dir": ""})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(
            (self.service / "target/escaped.yaml").exists(),
            self.service / "target/escaped.yaml",
        )

    def test_a_missing_archive_fails_instead_of_reusing_staging(self):
        self.output.mkdir(parents=True)
        index = self.output / "element-index.json"
        index.write_text('{"elements": [{"type": "stale"}]}')
        result = self.build(**{"qip.schemas.local.dir": ""})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(index.exists(), index)


if __name__ == "__main__":
    unittest.main()
