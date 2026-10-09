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


if __name__ == "__main__":
    unittest.main()
