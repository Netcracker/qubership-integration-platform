"""Tests for the release version scripts.

Each test copies the tracked POMs and scripts into a throwaway Git repository,
changes what the case needs there, and runs the script under test with bash.

Run: python3 scripts/test_version_scripts.py
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
COPIED = ["*pom.xml", "scripts/*.sh", ".github/maven-settings.xml"]
BASH = shutil.which("bash")


class RepositoryCopy(unittest.TestCase):
    """A throwaway Git repository holding the tracked POMs and scripts."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        listed = subprocess.run(
            ["git", "ls-files", "--", *COPIED],
            cwd=REPOSITORY,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.split()
        for name in listed:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / name, target)
        self.environment = {**os.environ, "LC_ALL": "C.UTF-8"}
        self.git("init", "--quiet")
        self.git("add", "--all")
        self.git("commit", "--quiet", "--message", "copy")

    def git(self, *arguments):
        """Run git in the copy and return its output."""
        return subprocess.run(
            [
                "git",
                "-c",
                "user.name=test",
                "-c",
                "user.email=test@example.com",
                "-c",
                "core.autocrlf=false",
                *arguments,
            ],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout

    def read(self, name):
        """Return a file of the copy with its line endings as they are."""
        with open(self.root / name, encoding="utf-8", newline="") as file:
            return file.read()

    def write(self, name, text):
        """Write a file of the copy without translating line endings."""
        with open(self.root / name, "w", encoding="utf-8", newline="") as file:
            file.write(text)

    def value(self, name, pattern):
        """Return group 1 of the first match of a regular expression in a file."""
        match = re.search(pattern, self.read(name))
        self.assertIsNotNone(match, f"{pattern} matches nothing in {name}")
        return match.group(1)

    def substitute(self, name, pattern, replacement):
        """Replace the first match of a regular expression, and fail when nothing matches."""
        changed, count = re.subn(pattern, replacement, self.read(name), count=1)
        self.assertEqual(count, 1, f"{pattern} matches nothing in {name}")
        self.write(name, changed)

    def run_script(self, script, *arguments, **environment):
        """Run scripts/<script> with bash in the copy."""
        return subprocess.run(
            [BASH, f"scripts/{script}", *arguments],
            cwd=self.root,
            capture_output=True,
            text=True,
            env={**self.environment, **environment},
            check=False,
        )

    def assertFailsWith(self, result, message):  # pylint: disable=invalid-name
        """Assert that a script exited non-zero and printed the message."""
        output = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn(message, output)


class VersionInvariantsTest(RepositoryCopy):
    """check-version-invariants.sh holds on the tree and names each drift."""

    def setUp(self):
        super().setUp()
        self.platform = self.value("pom.xml", r"<revision>([^<]+)</revision>")

    def check(self):
        """Run the invariant checks in the copy."""
        return self.run_script("check-version-invariants.sh")

    def test_holds_on_the_tree(self):
        """The committed tree satisfies every invariant."""
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"Version invariants hold: platform {self.platform}", result.stdout)

    def test_root_revision_must_be_semver(self):
        """A root <revision> that is not X.Y.Z stops the check."""
        self.substitute("pom.xml", r"<revision>[^<]+</revision>", "<revision>1.5</revision>")
        self.assertFailsWith(self.check(), "root pom <revision> is not X.Y.Z (got '1.5')")

    def test_parent_version_follows_the_platform(self):
        """A parent POM whose own version drifted is named."""
        self.substitute(
            "parents/base/pom.xml",
            r"<version>[^<]+-SNAPSHOT</version>",
            "<version>0.0.1-SNAPSHOT</version>",
        )
        self.assertFailsWith(
            self.check(),
            f"parents/base/pom.xml <version> is '0.0.1-SNAPSHOT', "
            f"expected '{self.platform}-SNAPSHOT'",
        )

    def test_child_pin_follows_the_platform(self):
        """A child pinning another parent version is named."""
        self.substitute(
            "engine/pom.xml",
            r"(<artifactId>qip-parent-spring</artifactId>\s*<version>)[^<]+",
            r"\g<1>0.0.1-SNAPSHOT",
        )
        self.assertFailsWith(
            self.check(),
            f"engine/pom.xml pins parent '0.0.1-SNAPSHOT', expected '{self.platform}-SNAPSHOT'",
        )

    def test_library_pin_follows_the_library(self):
        """A library consumer pinning another revision is named."""
        self.substitute(
            "runtime-catalog/pom.xml",
            r"<qip\.integration-build-pipeline\.revision>[^<]+<",
            "<qip.integration-build-pipeline.revision>0.0.1<",
        )
        self.assertFailsWith(self.check(), "runtime-catalog/pom.xml pins the library at '0.0.1'")

    def test_checkstyle_pin_follows_checkstyle(self):
        """A parent pinning another checkstyle revision is named."""
        self.substitute(
            "parents/spring/pom.xml",
            r"<qip\.checkstyle\.version>[^<]+<",
            "<qip.checkstyle.version>0.0.1<",
        )
        self.assertFailsWith(
            self.check(), "parents/spring/pom.xml pins qip-checkstyle at '0.0.1'"
        )

    def test_bom_entry_follows_the_module(self):
        """A BOM entry whose number drifted from the module's revision is named."""
        engine = self.value("engine/pom.xml", r"<revision>([^<]+)</revision>")
        self.substitute(
            "bom/pom.xml",
            r"(<artifactId>qip-engine</artifactId>\s*<version>)[0-9.]+",
            r"\g<1>0.0.1",
        )
        self.assertFailsWith(
            self.check(),
            f"bom/pom.xml lists qip-engine at '0.0.1${{changelist}}', "
            f"expected '{engine}${{changelist}}'",
        )

    def test_bom_entry_keeps_the_changelist(self):
        """A BOM entry without ${changelist} would publish a release in a snapshot BOM."""
        engine = self.value("engine/pom.xml", r"<revision>([^<]+)</revision>")
        self.substitute(
            "bom/pom.xml",
            r"(<artifactId>qip-engine</artifactId>\s*<version>[0-9.]+)\$\{changelist\}",
            r"\g<1>",
        )
        self.assertFailsWith(
            self.check(),
            f"bom/pom.xml lists qip-engine at '{engine}', expected '{engine}${{changelist}}'",
        )

    def test_checkstyle_entry_is_bare(self):
        """checkstyle has an empty changelist, so its entry carries no suffix."""
        checkstyle = self.value("checkstyle/pom.xml", r"<revision>([^<]+)</revision>")
        self.substitute(
            "bom/pom.xml",
            r"(<artifactId>qip-checkstyle</artifactId>\s*<version>[0-9.]+)",
            r"\g<1>${changelist}",
        )
        self.assertFailsWith(
            self.check(),
            f"bom/pom.xml lists qip-checkstyle at '{checkstyle}${{changelist}}', "
            f"expected '{checkstyle}'",
        )

    def test_bom_lists_no_unknown_artifact(self):
        """An entry for no module in QIP_BOM_ENTRIES is named."""
        self.substitute(
            "bom/pom.xml",
            r"(\s*)</dependencies>(\s*)</dependencyManagement>",
            r"\1<dependency><groupId>org.qubership.integration.platform</groupId>"
            r"<artifactId>qip-unknown</artifactId><version>1.0.0</version></dependency>"
            r"\1</dependencies>\2</dependencyManagement>",
        )
        self.assertFailsWith(
            self.check(),
            "bom/pom.xml lists qip-unknown, which is no module in QIP_BOM_ENTRIES",
        )

    def test_bom_lists_every_module(self):
        """A module in QIP_BOM_ENTRIES with no BOM entry is named."""
        self.substitute(
            "bom/pom.xml",
            r"\s*<dependency>\s*<groupId>[^<]+</groupId>\s*"
            r"<artifactId>qip-schemas</artifactId>\s*<version>[^<]+</version>\s*</dependency>",
            "",
        )
        self.assertFailsWith(self.check(), "bom/pom.xml has no entry for qip-schemas (schemas)")

    def test_parents_share_their_common_properties(self):
        """A property both parents declare must have one value."""
        lombok = self.value("parents/base/pom.xml", r"<lombok\.version>([^<]+)<")
        self.substitute(
            "parents/spring/pom.xml",
            r"<lombok\.version>[^<]+<",
            "<lombok.version>0.0.1<",
        )
        self.assertFailsWith(
            self.check(),
            f"lombok.version is '{lombok}' in parents/base/pom.xml "
            f"but '0.0.1' in parents/spring/pom.xml",
        )


class SetPlatformVersionTest(RepositoryCopy):
    """set-platform-version.sh moves every copy of the platform version."""

    def test_moves_every_copy(self):
        """The root revision, the parents, and every pin move together."""
        platform = self.value("pom.xml", r"<revision>([^<]+)</revision>")
        boot = self.value(
            "parents/spring/pom.xml",
            r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)<",
        )
        major, minor, _ = platform.split(".")
        target = f"{major}.{int(minor) + 1}.0"
        result = self.run_script("set-platform-version.sh", target)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            set(result.stdout.split()),
            {
                "pom.xml",
                "parents/pom.xml",
                "parents/base/pom.xml",
                "parents/spring/pom.xml",
                "parents/quarkus/pom.xml",
                "bom/pom.xml",
                "checkstyle/pom.xml",
                "schemas/pom.xml",
                "integration-build-pipeline/pom.xml",
                "integration-build-maven-plugin/pom.xml",
                "runtime-catalog/pom.xml",
                "sessions-management/pom.xml",
                "engine/pom.xml",
                "micro-engine/pom.xml",
            },
        )
        check = self.run_script("check-version-invariants.sh")
        self.assertEqual(check.returncode, 0, check.stdout + check.stderr)
        self.assertIn(f"platform {target}", check.stdout)
        self.assertEqual(
            self.value(
                "parents/spring/pom.xml",
                r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)<",
            ),
            boot,
        )

    def test_rejects_a_version_that_is_not_semver(self):
        """Only X.Y.Z is a platform version."""
        self.assertFailsWith(
            self.run_script("set-platform-version.sh", "1.6"),
            "platform version must be X.Y.Z (got '1.6')",
        )


class SyncBomEntryTest(RepositoryCopy):
    """sync-bom-entry.sh moves one BOM entry and keeps its form."""

    ENGINE_REVISION = r"<revision>([^<]+)</revision>"
    ENGINE_ENTRY = r"<artifactId>qip-engine</artifactId>\s*<version>([^<]+)<"

    def release(self, module, version):
        """Write a module's released <revision> and move its BOM entry, as the release job does."""
        self.substitute(
            f"{module}/pom.xml", r"<revision>[^<]+</revision>", f"<revision>{version}</revision>"
        )
        return self.run_script("sync-bom-entry.sh", module, version)

    def test_moves_the_entry_and_keeps_the_changelist(self):
        """The entry takes the new number and keeps ${changelist}; other entries stay."""
        catalog = self.value(
            "bom/pom.xml", r"<artifactId>qip-runtime-catalog</artifactId>\s*<version>([^<]+)<"
        )
        result = self.release("engine", "9.9.9")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), "bom/pom.xml")
        self.assertEqual(self.value("bom/pom.xml", self.ENGINE_ENTRY), "9.9.9${changelist}")
        self.assertEqual(
            self.value(
                "bom/pom.xml", r"<artifactId>qip-runtime-catalog</artifactId>\s*<version>([^<]+)<"
            ),
            catalog,
        )
        check = self.run_script("check-version-invariants.sh")
        self.assertEqual(check.returncode, 0, check.stdout + check.stderr)

    def test_checkstyle_entry_stays_bare(self):
        """checkstyle's entry has no suffix before the sync and none after it."""
        result = self.run_script("sync-bom-entry.sh", "checkstyle", "9.9.9")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            self.value("bom/pom.xml", r"<artifactId>qip-checkstyle</artifactId>\s*<version>([^<]+)<"),
            "9.9.9",
        )

    def test_fails_for_a_module_the_bom_does_not_list(self):
        """The BOM lists no entry for itself."""
        self.assertFailsWith(
            self.run_script("sync-bom-entry.sh", "bom", "9.9.9"),
            "bom/pom.xml has no entry for qip-bom (bom)",
        )

    def test_a_rerun_with_the_same_version_changes_nothing(self):
        """Recovery re-applies the bump, so a second sync must be a no-op."""
        self.release("engine", "9.9.9")
        before = self.read("bom/pom.xml")
        result = self.run_script("sync-bom-entry.sh", "engine", "9.9.9")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read("bom/pom.xml"), before)

    def test_works_on_a_crlf_checkout(self):
        """POMs with CRLF line endings read and write like LF ones."""
        for name in ["bom/pom.xml", "engine/pom.xml"]:
            text = self.read(name).replace("\r\n", "\n").replace("\n", "\r\n")
            self.write(name, text)
        result = self.release("engine", "9.9.9")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        bom = self.read("bom/pom.xml")
        self.assertIn("<version>9.9.9${changelist}</version>\r\n", bom)
        self.assertNotIn("\r\r", bom)
        check = self.run_script("check-version-invariants.sh")
        self.assertEqual(check.returncode, 0, check.stdout + check.stderr)

    def test_parallel_releases_merge_without_a_conflict(self):
        """Two wave jobs move two entries on two branches; the rebase merges them."""
        self.git("checkout", "--quiet", "-b", "engine-release")
        self.release("engine", "9.9.9")
        self.git("commit", "--quiet", "--all", "--message", "engine")
        self.git("checkout", "--quiet", "-")
        self.git("checkout", "--quiet", "-b", "catalog-release")
        self.release("runtime-catalog", "8.8.8")
        self.git("commit", "--quiet", "--all", "--message", "catalog")
        self.git("rebase", "--quiet", "engine-release")
        self.assertEqual(self.value("bom/pom.xml", self.ENGINE_ENTRY), "9.9.9${changelist}")
        self.assertEqual(
            self.value(
                "bom/pom.xml", r"<artifactId>qip-runtime-catalog</artifactId>\s*<version>([^<]+)<"
            ),
            "8.8.8${changelist}",
        )


class ComputeReleaseVersionTest(RepositoryCopy):
    """compute-release-version.sh reads the restored root revision and the BOM."""

    def compute(self, **environment):
        """Run the script and return what it wrote to GITHUB_OUTPUT."""
        result = self.run_script(
            "compute-release-version.sh", GITHUB_OUTPUT="github-output", **environment
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.read("github-output")

    def test_platform(self):
        """A minor wave bumps the root revision and tags a bare vX.Y.Z."""
        major, minor, _ = self.value("pom.xml", r"<revision>([^<]+)</revision>").split(".")
        output = self.compute(ECOSYSTEM="platform", RELEASE_TYPE="minor")
        self.assertIn(f"release-version={major}.{int(minor) + 1}.0", output)
        self.assertIn(f"release-tag=v{major}.{int(minor) + 1}.0", output)

    def test_bom(self):
        """The BOM releases like a module, tagged bom-vX.Y.Z."""
        major, minor, patch = self.value(
            "bom/pom.xml", r"<revision>([^<]+)</revision>"
        ).split(".")
        output = self.compute(ECOSYSTEM="maven", MODULE="bom", RELEASE_TYPE="patch")
        self.assertIn(f"release-version={major}.{minor}.{int(patch) + 1}", output)
        self.assertIn(f"release-tag=bom-v{major}.{minor}.{int(patch) + 1}", output)


if __name__ == "__main__":
    unittest.main()
