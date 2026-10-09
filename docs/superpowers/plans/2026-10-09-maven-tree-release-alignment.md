# Align the Release Workflows with the New Maven Tree: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every release path (module release, BOM cascade, `release-all` wave, platform drop, snapshot publish)
work on the `parents/` + `bom/` POM tree, signed, with version invariants that hold on the branch.

**Architecture:** The version model lives in POM files and is moved by shell scripts under `scripts/`, which the
GitHub workflows call. A new sourced `scripts/pom.sh` holds the POM readers and writers; `modules.sh` holds the file
lists. Two new scripts move a BOM entry and publish the parents. The workflows lose the Central profile and gain a BOM
cascade that calls the reusable `_maven-module-release.yaml` directly.

**Tech Stack:** Bash (GNU sed, AWK, grep with `-P`), GitHub Actions, Maven 3.9 with flatten-maven-plugin and
maven-gpg-plugin, Python 3 `unittest` for the script tests, Docker for actionlint and super-linter.

**Spec:** `docs/superpowers/specs/2026-10-09-maven-tree-release-alignment-design.md`

## Global Constraints

- Nothing is deployed to Maven Central. The `central` and `github` Maven profiles no longer exist; no workflow passes
  `-P`.
- Every workflow deploy is signed. `-Dgpg.skip=true` appears only on PR builds and on the local
  `mvn -f parents/pom.xml install` steps.
- The parents keep a literal `<version>`; only `set-platform-version.sh` and `publish-parents.sh` change it.
- A BOM entry is `<revision>${changelist}`, or the bare `<revision>` for checkstyle, whose `<changelist>` is empty.
- Actions stay pinned by commit SHA with a version comment, reusing the SHAs already in `.github/workflows/`.
- Shell scripts are formatted like the existing ones (4-space indent, `> /dev/null` with spaces: shfmt), carry
  `set -euo pipefail`, and have the executable bit (`git update-index --chmod=+x`), which super-linter's BASH_EXEC
  rule requires.
- Every script and test must work on a CRLF checkout (`core.autocrlf=true` on Windows) as well as on LF.
- Edit `.apm/` sources, never `AGENTS.md`, `.claude/rules/`, `.cursor/rules/`, or `.codex/`; regenerate them with
  `apm compile`.
- Load the `english-developer-style` skill before writing any comment, doc line, or commit message.
- Commit messages follow Conventional Commits. Append the `Co-Authored-By` trailer that the session's attribution
  instructions give to every commit message below; the commands omit it, because the repository's secret scanner
  rejects a file that quotes the address.

## Review Focus

1. **A CRLF checkout.** On Windows the POMs can carry `\r\n`. Every reader must return the value without `\r`, and
   every writer must keep the file's line endings. Task 4 adds a test that converts POMs to CRLF and runs the sync and
   the invariants.
2. **Parallel module releases in one wave.** Each job rewrites its own entry in `bom/pom.xml`, and
   `commit-and-push.sh` rebases them onto each other. A reasonable person expects no conflict. Task 4 adds a test that
   syncs two modules on two branches and merges them.
3. **A recovery rerun.** A rerun re-applies the bump with the same version, so `sync-bom-entry.sh` must be idempotent:
   a second run with the same version changes nothing and succeeds. Task 4 adds that test.
4. **A drop without credentials.** `publish-parents.sh` must fail before it touches `parents/` when `GH_TOKEN` is
   missing, so a misconfigured job never leaves rewritten versions behind. Task 5 adds that test.
5. **Overlapping standalone cascades.** GitHub keeps one running and one pending run per concurrency group, and a
   third run cancels the pending one. A cancelled BOM release loses nothing, because every module bump already moved
   its BOM entry, and the next BOM release publishes it. This needs no code; Task 7 states it in the cascade comment.

---

### Task 1: Restore the platform revision and write the BOM entries as project versions

**Files:**
- Modify: `pom.xml` (root, lines 13-16)
- Modify: `bom/pom.xml` (the eight `<dependency>` entries)

**Interfaces:**
- Produces: root `<revision>` (the platform version, `X.Y.Z`) and `<changelist>-SNAPSHOT</changelist>`; BOM entries
  in the form `<version>X.Y.Z${changelist}</version>`, checkstyle's as `<version>X.Y.Z</version>`.

- [ ] **Step 1: Write the failing check**

Deploy the BOM as a release would, into a local directory, and look at what it publishes:

```bash
REPO_DIR="$(mktemp -d)"
URL="file://$(cygpath -m "$REPO_DIR" 2> /dev/null || echo "$REPO_DIR")"
mvn -B -q -pl bom deploy -Drevision=1.5.2 -Dchangelist= -Dgpg.skip=true \
    -DaltDeploymentRepository="local::$URL"
grep -c 'SNAPSHOT' "$REPO_DIR/org/qubership/integration/platform/qip-bom/1.5.2/qip-bom-1.5.2.pom"
```

- [ ] **Step 2: Run it to verify it fails**

Expected: the count is `7`. The released BOM still points every module except checkstyle at `1.5.1-SNAPSHOT`.

- [ ] **Step 3: Edit the root POM**

In `pom.xml`, replace

```xml
  <artifactId>qip-monorepo</artifactId>
  <packaging>pom</packaging>

  <name>QIP Monorepo Aggregator</name>
```

with

```xml
  <artifactId>qip-monorepo</artifactId>
  <version>${revision}${changelist}</version>
  <packaging>pom</packaging>

  <name>QIP Monorepo Aggregator</name>

  <properties>
    <!-- The platform version: the last released drop. The parents carry it with -SNAPSHOT appended. -->
    <revision>1.5.1</revision>
    <changelist>-SNAPSHOT</changelist>
  </properties>
```

- [ ] **Step 4: Edit the BOM entries**

In `bom/pom.xml`, change the `<version>` of every entry except `qip-checkstyle` from `1.5.1-SNAPSHOT` to
`1.5.1${changelist}`. Leave `qip-checkstyle` at `0.0.3`. Put this comment directly above `<dependencyManagement>`:

```xml
  <!-- Each entry is its module's project version: the module's <revision> followed by ${changelist}, which this
       POM resolves to -SNAPSHOT in the working tree and to nothing in a release. checkstyle has an empty
       changelist, so its entry is the bare <revision>. scripts/sync-bom-entry.sh moves an entry on release. -->
```

- [ ] **Step 5: Run the check to verify it passes**

Run Step 1 again, then:

```bash
grep -A1 '<artifactId>qip-engine</artifactId>' \
    "$REPO_DIR/org/qubership/integration/platform/qip-bom/1.5.2/qip-bom-1.5.2.pom"
mvn -B -q -o validate
```

Expected: the SNAPSHOT count is `0`; the engine entry reads `<version>1.5.1</version>`; `validate` passes for the
whole reactor.

- [ ] **Step 6: Commit**

```bash
git add pom.xml bom/pom.xml
git commit -m "build: restore the platform revision and list the BOM entries as project versions" \
    -m "The root POM carries the platform version in <revision> again, as on main. Each BOM entry is the module's <revision> followed by \${changelist}, so it resolves to the module's project version in the working tree and to the released version in a BOM release."
```

---

### Task 2: POM helpers, the new topology, the invariant checks, and their tests

**Files:**
- Create: `scripts/pom.sh`
- Create: `scripts/test_version_scripts.py`
- Modify: `scripts/modules.sh` (whole file)
- Modify: `scripts/check-version-invariants.sh` (whole file)
- Modify: `.github/workflows/version-invariants.yaml`

**Interfaces:**
- Produces, in `scripts/pom.sh` (sourced; run from the repository root):
  - `pom_prop <pom> <tag>`: the first value of `<tag>`, or empty. Never fails.
  - `pom_props <pom>`: `name=value` per line for every entry of the `<properties>` block.
  - `pom_artifact <pom>`: the POM's own `artifactId`, the first one outside `<parent>`.
  - `pom_own_version <pom>`: the first `<version>` outside `<parent>`. Only meaningful for POMs that declare one.
  - `pom_parent_version <pom>`: the `<version>` inside `<parent>`.
  - `pom_set_own_version <pom> <version>` and `pom_set_parent_version <pom> <version>`.
  - `bom_artifacts`: every `artifactId` in `bom/pom.xml`'s `dependencyManagement`, one per line.
  - `bom_entry <artifactId>`: the version text of that BOM entry (for example `1.5.1${changelist}`), or empty.
  - `bom_set_entry <artifactId> <X.Y.Z>`: replaces the `X.Y.Z` of that entry and keeps any suffix; returns 1 when the
    BOM has no such entry.
- Produces, in `scripts/modules.sh`: `QIP_PARENT_POMS`, `QIP_PARENT_CHILDREN` (POM paths), `QIP_LIBRARY_PROPERTY`,
  `QIP_LIBRARY_CONSUMERS`, `QIP_CHECKSTYLE_PROPERTY`, `QIP_CHECKSTYLE_CONSUMERS`, `QIP_BOM_ENTRIES`, `QIP_BOM_MODULES`.
- Produces, in `scripts/test_version_scripts.py`: the base class `RepositoryCopy` with `read`, `value`, `substitute`,
  `run_script`, `git`, and `assertFailsWith`, which Tasks 3-5 extend.

- [ ] **Step 1: Write the failing tests**

Create `scripts/test_version_scripts.py`:

```python
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


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 scripts/test_version_scripts.py`
Expected: FAIL. `test_holds_on_the_tree` fails because the current script reads `parent/pom.xml`; the drift tests
fail on missing messages.

- [ ] **Step 3: Write `scripts/pom.sh`**

```bash
#!/usr/bin/env bash
#
# POM readers and writers, sourced by the version scripts. Every function runs
# from the repository root and reads or writes one POM with grep, sed, or awk.
#
# The readers stop a value at the next "<", so a CRLF checkout returns no "\r".
# The writers replace text inside one line and keep its line ending.
#
# Usage: . "$ROOT/scripts/pom.sh"
#
# Running this file does nothing. It keeps the executable bit because
# super-linter's BASH_EXEC rule requires it of every .sh in the repo.

pom_prop() { # <pom> <tag> -> the first value of <tag>, or nothing
    grep -oP "(?<=<$2>)[^<]+" "$1" | head -1 || true
}

pom_props() { # <pom> -> name=value per entry of the <properties> block
    sed -n '/<properties>/,/<\/properties>/p' "$1" |
        grep -oP '<\K[A-Za-z0-9._-]+>[^<]*(?=</)' |
        sed 's/>/=/' || true
}

pom_artifact() { # <pom> -> the POM's own artifactId
    sed '/<parent>/,/<\/parent>/d' "$1" | grep -oP '(?<=<artifactId>)[^<]+' | head -1 || true
}

pom_own_version() { # <pom> -> the first <version> outside <parent>
    sed '/<parent>/,/<\/parent>/d' "$1" | grep -oP '(?<=<version>)[^<]+' | head -1 || true
}

pom_parent_version() { # <pom> -> the <version> inside <parent>
    sed -n '/<parent>/,/<\/parent>/p' "$1" | grep -oP '(?<=<version>)[^<]+' | head -1 || true
}

pom_set_own_version() { # <pom> <version>
    local tmp
    tmp="$(mktemp)"
    awk -v ver="$2" '
        /<parent>/ { in_parent = 1 }
        !in_parent && !done && /<version>[^<]*<\/version>/ {
            sub(/<version>[^<]*<\/version>/, "<version>" ver "</version>")
            done = 1
        }
        /<\/parent>/ { in_parent = 0 }
        { print }
    ' "$1" > "$tmp"
    mv "$tmp" "$1"
}

pom_set_parent_version() { # <pom> <version>
    sed -i -E "/<parent>/,/<\/parent>/s#<version>[^<]+</version>#<version>$2</version>#" "$1"
}

bom_artifacts() { # -> every artifactId in the BOM's dependencyManagement
    sed -n '/<dependencyManagement>/,/<\/dependencyManagement>/p' bom/pom.xml |
        grep -oP '(?<=<artifactId>)[^<]+' || true
}

bom_entry() { # <artifactId> -> the version text of its BOM entry, or nothing
    awk -v id="$1" '
        /<dependencyManagement>/ { inside = 1 }
        inside && /<artifactId>/ {
            artifact = $0
            sub(/.*<artifactId>/, "", artifact)
            sub(/<\/artifactId>.*/, "", artifact)
        }
        inside && artifact == id && /<version>/ {
            version = $0
            sub(/.*<version>/, "", version)
            sub(/<\/version>.*/, "", version)
            print version
            exit
        }
    ' bom/pom.xml
}

bom_set_entry() { # <artifactId> <X.Y.Z> -> 1 when the BOM lists no such artifact
    local tmp
    tmp="$(mktemp)"
    if awk -v id="$1" -v ver="$2" '
        /<dependencyManagement>/ { inside = 1 }
        inside && /<artifactId>/ {
            artifact = $0
            sub(/.*<artifactId>/, "", artifact)
            sub(/<\/artifactId>.*/, "", artifact)
        }
        inside && !done && artifact == id && /<version>/ {
            sub(/<version>[0-9]+\.[0-9]+\.[0-9]+/, "<version>" ver)
            done = 1
        }
        { print }
        END { exit done ? 0 : 1 }
    ' bom/pom.xml > "$tmp"; then
        mv "$tmp" bom/pom.xml
    else
        rm -f "$tmp"
        return 1
    fi
}
```

Then: `git add scripts/pom.sh && git update-index --chmod=+x scripts/pom.sh`.

- [ ] **Step 4: Rewrite `scripts/modules.sh`**

Replace everything from `# Modules whose POM pins qip-monorepo-parent by a literal version.` to the end of the file
with:

```bash
# The parent POMs that declare their own literal <version>, which is the platform
# version with -SNAPSHOT appended. parents/quarkus/pom.xml inherits its version
# from its pin on the base parent, so it is a child below and not listed here.
QIP_PARENT_POMS=(parents/pom.xml parents/base/pom.xml parents/spring/pom.xml)

# POMs whose <parent><version> pins a qip-parent-* POM by a literal version.
QIP_PARENT_CHILDREN=(
    pom.xml
    bom/pom.xml
    checkstyle/pom.xml
    schemas/pom.xml
    integration-build-pipeline/pom.xml
    integration-build-maven-plugin/pom.xml
    runtime-catalog/pom.xml
    sessions-management/pom.xml
    engine/pom.xml
    micro-engine/pom.xml
    parents/quarkus/pom.xml
)

# POMs that pin the released qip-integration-build-pipeline by this property. The
# dependency appends ${changelist} in each of them, so the pin names the release
# and a reactor build resolves the development coordinate from source.
QIP_LIBRARY_PROPERTY=qip.integration-build-pipeline.revision
QIP_LIBRARY_CONSUMERS=(runtime-catalog/pom.xml integration-build-maven-plugin/pom.xml)

# POMs that pin the released qip-checkstyle by this property.
QIP_CHECKSTYLE_PROPERTY=qip.checkstyle.version
QIP_CHECKSTYLE_CONSUMERS=(parents/base/pom.xml parents/spring/pom.xml)

# Module directories the BOM (bom/pom.xml) lists, one entry each.
QIP_BOM_ENTRIES=(
    schemas
    checkstyle
    micro-engine
    integration-build-pipeline
    integration-build-maven-plugin
    engine
    runtime-catalog
    sessions-management
)

# Modules release-all releases and the release manifest reports, in output order.
# schemas is one row for two artifacts: the npm package and the Maven artifact
# carry the same version. bom is released after every Maven module in the wave.
QIP_BOM_MODULES=(
    engine
    micro-engine
    runtime-catalog
    sessions-management
    integration-build-pipeline
    integration-build-maven-plugin
    schemas
    bom
    testing-service
    ui
    vscode-extension
)
```

- [ ] **Step 5: Rewrite `scripts/check-version-invariants.sh`**

```bash
#!/usr/bin/env bash
#
# Fail when POM versions that must agree have drifted apart. Several files carry
# the platform version, the in-repo library pins, and the BOM entries, and the
# release scripts write them, so a missed rewrite is silent until a release breaks.
#
# Invariants. Each one is written atomically by a single script in a single
# commit, so it holds at every point a human can observe:
#   1. the root pom <revision> is X.Y.Z: the platform version P
#   2. every POM in QIP_PARENT_POMS has the <version> P-SNAPSHOT
#   3. every POM in QIP_PARENT_CHILDREN pins its parent at P-SNAPSHOT
#   4. every library consumer's pin == integration-build-pipeline's <revision>,
#      so a reactor build resolves the library from source (the dependency
#      appends ${changelist}, so the pin names the release and the build the
#      development coordinate)
#   5. every qip-checkstyle pin == checkstyle's <revision>. No changelist there
#      on purpose: checkstyle is a plugin dependency, never in a module's
#      reactor, so its pin always names a published release.
#   6. every BOM entry is its module's project version: the module's <revision>
#      followed by ${changelist}, or the bare <revision> when the module's
#      <changelist> is empty (checkstyle). The BOM lists exactly the modules in
#      QIP_BOM_ENTRIES.
#   7. every property both parents/base and parents/spring declare has one value.
#      The Spring parent extends spring-boot-starter-parent, so it copies these
#      properties instead of inheriting them.
#
# Deliberately NOT checked: that the modules share one major.minor. That is a
# postcondition of a full release-all wave, not a property at rest — a partial
# minor or major wave (`modules: engine,ui`) leaves them apart on purpose, and
# release-all asserts it for itself once the wave is done.
#
# Usage: scripts/check-version-invariants.sh

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"
. "$ROOT/scripts/pom.sh"

failures=0

fail() {
    echo "::error::$1"
    failures=$((failures + 1))
}

platform="$(pom_prop pom.xml revision)"
[[ "$platform" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    fail "root pom <revision> is not X.Y.Z (got '$platform')"
    exit 1
}
expected_parent="${platform}-SNAPSHOT"

for pom in "${QIP_PARENT_POMS[@]}"; do
    own="$(pom_own_version "$pom")"
    [ "$own" = "$expected_parent" ] ||
        fail "$pom <version> is '$own', expected '$expected_parent' (root <revision> $platform)"
done

for child in "${QIP_PARENT_CHILDREN[@]}"; do
    pinned="$(pom_parent_version "$child")"
    [ "$pinned" = "$expected_parent" ] ||
        fail "$child pins parent '$pinned', expected '$expected_parent'"
done

library_version="$(pom_prop integration-build-pipeline/pom.xml revision)"
for consumer in "${QIP_LIBRARY_CONSUMERS[@]}"; do
    pin="$(pom_prop "$consumer" "$QIP_LIBRARY_PROPERTY")"
    [ "$pin" = "$library_version" ] ||
        fail "$consumer pins the library at '$pin', but integration-build-pipeline is on '$library_version' — a reactor build would then resolve the released jar instead of the working tree"
done

checkstyle_version="$(pom_prop checkstyle/pom.xml revision)"
for consumer in "${QIP_CHECKSTYLE_CONSUMERS[@]}"; do
    checkstyle_pin="$(pom_prop "$consumer" "$QIP_CHECKSTYLE_PROPERTY")"
    [ "$checkstyle_pin" = "$checkstyle_version" ] ||
        fail "$consumer pins qip-checkstyle at '$checkstyle_pin', but checkstyle is on '$checkstyle_version'"
done

known_artifacts=" "
for module in "${QIP_BOM_ENTRIES[@]}"; do
    artifact="$(pom_artifact "$module/pom.xml")"
    known_artifacts+="$artifact "
    revision="$(pom_prop "$module/pom.xml" revision)"
    expected="$revision"
    if [ -n "$(pom_prop "$module/pom.xml" changelist)" ]; then
        # The BOM resolves its own ${changelist}; the entry carries it unexpanded.
        # shellcheck disable=SC2016
        expected+='${changelist}'
    fi
    entry="$(bom_entry "$artifact")"
    if [ -z "$entry" ]; then
        fail "bom/pom.xml has no entry for $artifact ($module)"
    elif [ "$entry" != "$expected" ]; then
        fail "bom/pom.xml lists $artifact at '$entry', expected '$expected' ($module <revision> $revision)"
    fi
done
for artifact in $(bom_artifacts); do
    [[ "$known_artifacts" == *" $artifact "* ]] ||
        fail "bom/pom.xml lists $artifact, which is no module in QIP_BOM_ENTRIES"
done

while IFS='=' read -r name base_value spring_value; do
    [ "$base_value" = "$spring_value" ] ||
        fail "$name is '$base_value' in parents/base/pom.xml but '$spring_value' in parents/spring/pom.xml"
done < <(LC_ALL=C join -t '=' \
    <(pom_props parents/base/pom.xml | LC_ALL=C sort) \
    <(pom_props parents/spring/pom.xml | LC_ALL=C sort))

if [ "$failures" -gt 0 ]; then
    echo "$failures version invariant(s) broken."
    exit 1
fi

echo "Version invariants hold: platform $platform, library $library_version, checkstyle $checkstyle_version."
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `python3 scripts/test_version_scripts.py VersionInvariantsTest -v`
Expected: 12 tests, all PASS.

Run: `LC_ALL=C.UTF-8 bash scripts/check-version-invariants.sh`
Expected: `Version invariants hold: platform 1.5.1, library 1.5.1, checkstyle 0.0.3.`

- [ ] **Step 7: Run the tests in the Version Invariants workflow**

In `.github/workflows/version-invariants.yaml`, replace the header comment

```yaml
# The platform version lives in six POMs and is written by three release scripts.
# This gate catches a drift on the PR that introduces it, not on the release that
# trips over it.
```

with

```yaml
# The platform version, the library pins, and the BOM entries live in many POMs
# and are written by the release scripts. This gate catches a drift on the PR
# that introduces it, not on the release that trips over it, and tests the
# scripts that write them.
```

and append a step after `Check versions`:

```yaml
      - name: Test the version scripts
        run: python3 scripts/test_version_scripts.py
```

- [ ] **Step 8: Commit**

```bash
git add scripts/pom.sh scripts/modules.sh scripts/check-version-invariants.sh \
    scripts/test_version_scripts.py .github/workflows/version-invariants.yaml
git commit -m "build: check the version invariants of the parents and the BOM" \
    -m "check-version-invariants.sh read parent/pom.xml and the root <revision>, so it failed on this branch. It now checks the three parent POMs, every parent pin, the renamed library and checkstyle pins, every BOM entry, and the properties both parents declare. The POM readers move into scripts/pom.sh, and scripts/test_version_scripts.py tests each check; Version Invariants runs it."
```

---

### Task 3: Write the platform version into the new parents

**Files:**
- Modify: `scripts/set-platform-version.sh` (whole file)
- Test: `scripts/test_version_scripts.py` (add a class)

**Interfaces:**
- Consumes: `pom_set_own_version`, `pom_own_version`, `pom_set_parent_version`, `pom_parent_version` (Task 2);
  `QIP_PARENT_POMS`, `QIP_PARENT_CHILDREN`.
- Produces: `scripts/set-platform-version.sh <X.Y.Z>` prints every changed file, one per line, sorted.

- [ ] **Step 1: Write the failing tests**

Add above `if __name__ == "__main__":`:

```python
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 scripts/test_version_scripts.py SetPlatformVersionTest -v`
Expected: `test_moves_every_copy` FAILS (the script rewrites `parent/pom.xml`, which does not exist).

- [ ] **Step 3: Rewrite `scripts/set-platform-version.sh`**

```bash
#!/usr/bin/env bash
#
# Write the platform version into every POM that carries it, and print the files
# it changed (one per line) so a caller can commit exactly those.
#
# The platform version lives in three places that must agree:
#   * the root aggregator pom <revision>, the source of truth
#   * the own <version> of every POM in QIP_PARENT_POMS, as X.Y.Z-SNAPSHOT
#   * <parent><version> in every POM in QIP_PARENT_CHILDREN, as X.Y.Z-SNAPSHOT
#
# The parents keep a literal <version> on purpose. Maven 3 compares a child's
# literal parent version with the parent's raw ${revision}${changelist}, finds no
# match, and resolves the parent from the repository instead of the relativePath.
# That is why the bump is this coordinated rewrite rather than one shared
# property.
#
# Every rewrite is verified — a sed that matches nothing is silent otherwise,
# and the caller commits and releases on whatever this script leaves behind.
#
# Usage: scripts/set-platform-version.sh 1.3.0

set -euo pipefail

VERSION="${1:?version (X.Y.Z) required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::platform version must be X.Y.Z (got '$VERSION')" >&2
    exit 1
}

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"
. "$ROOT/scripts/pom.sh"

PARENT_VERSION="${VERSION}-SNAPSHOT"

sed -i -E "s#<revision>[0-9]+\.[0-9]+\.[0-9]+</revision>#<revision>${VERSION}</revision>#" pom.xml
[ "$(pom_prop pom.xml revision)" = "$VERSION" ] || {
    echo "::error::Failed to write <revision>${VERSION}</revision> in pom.xml"
    exit 1
}

for pom in "${QIP_PARENT_POMS[@]}"; do
    pom_set_own_version "$pom" "$PARENT_VERSION"
    [ "$(pom_own_version "$pom")" = "$PARENT_VERSION" ] || {
        echo "::error::Failed to write <version>${PARENT_VERSION}</version> in $pom"
        exit 1
    }
done

for child in "${QIP_PARENT_CHILDREN[@]}"; do
    pom_set_parent_version "$child" "$PARENT_VERSION"
    [ "$(pom_parent_version "$child")" = "$PARENT_VERSION" ] || {
        echo "::error::Failed to write the parent version ${PARENT_VERSION} in $child"
        exit 1
    }
done

printf '%s\n' pom.xml "${QIP_PARENT_POMS[@]}" "${QIP_PARENT_CHILDREN[@]}" | sort -u
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 scripts/test_version_scripts.py -v`
Expected: every test PASSES.

- [ ] **Step 5: Commit**

```bash
git add scripts/set-platform-version.sh scripts/test_version_scripts.py
git commit -m "build: write the platform version into the new parent POMs" \
    -m "set-platform-version.sh rewrote parent/pom.xml. It now writes the root <revision>, the own version of the three parent POMs that declare one, and the parent pin of every child, including parents/quarkus."
```

---

### Task 4: Move a module's BOM entry on release

**Files:**
- Create: `scripts/sync-bom-entry.sh`
- Modify: `scripts/compute-release-version.sh:24-25` (comment only)
- Test: `scripts/test_version_scripts.py` (add two classes)

**Interfaces:**
- Consumes: `pom_artifact`, `bom_entry`, `bom_set_entry` (Task 2).
- Produces: `scripts/sync-bom-entry.sh <module-directory> <X.Y.Z>` prints `bom/pom.xml`; exits 1 with
  `bom/pom.xml has no entry for <artifactId> (<module>)` when the BOM does not list the module.

- [ ] **Step 1: Write the failing tests**

Add above `if __name__ == "__main__":`:

```python
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 scripts/test_version_scripts.py SyncBomEntryTest ComputeReleaseVersionTest -v`
Expected: every `SyncBomEntryTest` test FAILS (no `scripts/sync-bom-entry.sh`); both `ComputeReleaseVersionTest`
tests PASS already, because Task 1 restored the root `<revision>`.

- [ ] **Step 3: Write `scripts/sync-bom-entry.sh`**

```bash
#!/usr/bin/env bash
#
# Point a module's BOM entry at its newly released version, and print
# bom/pom.xml so a caller can commit it.
#
# A BOM entry is the module's project version: its <revision> followed by the
# literal ${changelist}, which the BOM resolves to -SNAPSHOT in the working tree
# and to nothing in a BOM release. checkstyle has an empty changelist, so its
# entry is the bare <revision>. This script replaces the X.Y.Z and keeps the
# rest, so a second run with the same version changes nothing.
#
# Usage: scripts/sync-bom-entry.sh <module> <version>
#
#   scripts/sync-bom-entry.sh engine 1.5.2

set -euo pipefail

MODULE="${1:?module directory required}"
VERSION="${2:?version (X.Y.Z) required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::version must be X.Y.Z (got '$VERSION')"
    exit 1
}

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/pom.sh"

[ -f "$MODULE/pom.xml" ] || {
    echo "::error::No pom.xml at $MODULE/"
    exit 1
}
artifact="$(pom_artifact "$MODULE/pom.xml")"

bom_set_entry "$artifact" "$VERSION" || {
    echo "::error::bom/pom.xml has no entry for $artifact ($MODULE)"
    exit 1
}

# shellcheck disable=SC2016
case "$(bom_entry "$artifact")" in
    "$VERSION" | "$VERSION"'${changelist}') ;;
    *)
        echo "::error::Failed to write $artifact $VERSION into bom/pom.xml"
        exit 1
        ;;
esac

echo bom/pom.xml
```

Then: `git add scripts/sync-bom-entry.sh && git update-index --chmod=+x scripts/sync-bom-entry.sh`.

- [ ] **Step 4: Update the comment in `compute-release-version.sh`**

Replace

```bash
# ECOSYSTEM=platform reads the root aggregator pom instead of a module. That
# number is the platform version: the drop tag and the release title.
```

with

```bash
# ECOSYSTEM=platform reads the root aggregator pom instead of a module. That
# number is the platform version: the drop tag, the release title, and the
# version the parents are published at. The BOM releases as MODULE=bom.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python3 scripts/test_version_scripts.py -v`
Expected: every test PASSES.

- [ ] **Step 6: Commit**

```bash
git add scripts/sync-bom-entry.sh scripts/compute-release-version.sh scripts/test_version_scripts.py
git commit -m "build: add a script that moves a module's BOM entry" \
    -m "sync-bom-entry.sh replaces the X.Y.Z of the module's entry in bom/pom.xml and keeps its \${changelist} suffix. The release bump step will call it, so the BOM entry and the module's <revision> move in one commit."
```

---

### Task 5: Publish the parents at each platform drop

**Files:**
- Create: `scripts/publish-parents.sh`
- Modify: `scripts/build-drop-release.sh:8-14` (header) and between the `DRY_RUN` block and
  `touched="$(bash scripts/set-platform-version.sh "$version")"`
- Test: `scripts/test_version_scripts.py` (add a class)

**Interfaces:**
- Consumes: `pom_set_own_version`, `pom_set_parent_version` (Task 2); `QIP_PARENT_POMS`.
- Produces: `scripts/publish-parents.sh <X.Y.Z>`, with env `GH_TOKEN` (required) and the settings variables
  `MAVEN_GITHUB_USERNAME`, `MAVEN_GITHUB_PASSWORD`, `MAVEN_GPG_PASSPHRASE`.

- [ ] **Step 1: Write the failing tests**

Add above `if __name__ == "__main__":`:

```python
CURL_STUB = """#!/usr/bin/env bash
# Stand-in for curl: succeeds for a URL whose file name is listed in $PUBLISHED.
url="${*: -1}"
case " ${PUBLISHED:-} " in *" ${url##*/} "*) exit 0 ;; esac
exit 22
"""

MVN_STUB = """#!/usr/bin/env bash
# Stand-in for mvn: records its arguments and the parent POMs it ran against.
echo "$*" >> mvn-arguments.log
cat parents/base/pom.xml parents/spring/pom.xml parents/quarkus/pom.xml > mvn-parents.xml
exit "${MVN_EXIT:-0}"
"""


class PublishParentsTest(RepositoryCopy):
    """publish-parents.sh deploys the missing parents at the platform version."""

    VERSION = "9.9.9"

    def setUp(self):
        super().setUp()
        stubs = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, stubs, ignore_errors=True)
        for name, text in [("curl", CURL_STUB), ("mvn", MVN_STUB)]:
            with open(stubs / name, "w", encoding="utf-8", newline="\n") as file:
                file.write(text)
            (stubs / name).chmod(0o755)
        self.environment["PATH"] = f"{stubs}{os.pathsep}{os.environ['PATH']}"

    def publish(self, **environment):
        """Run publish-parents.sh with a token and the given stand-in settings."""
        return self.run_script(
            "publish-parents.sh", self.VERSION, **{"GH_TOKEN": "token", **environment}
        )

    def assertParentsRestored(self):  # pylint: disable=invalid-name
        """Assert that parents/ is back to the committed state."""
        self.assertEqual(self.git("status", "--porcelain", "--", "parents"), "")

    def test_deploys_the_missing_parents_at_the_version(self):
        """Only the unpublished parents are deployed, rewritten to the platform version."""
        boot = self.value(
            "parents/spring/pom.xml",
            r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)<",
        )
        result = self.publish(PUBLISHED=f"qip-parent-base-{self.VERSION}.pom")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("deploy -pl spring,quarkus", self.read("mvn-arguments.log"))
        deployed = self.read("mvn-parents.xml")
        self.assertEqual(deployed.count(f"<version>{self.VERSION}</version>"), 3)
        self.assertIn(f"<version>{boot}</version>", deployed)
        self.assertParentsRestored()

    def test_skips_when_every_parent_is_published(self):
        """A rerun after a complete deploy builds nothing."""
        published = " ".join(
            f"qip-parent-{name}-{self.VERSION}.pom" for name in ["base", "spring", "quarkus"]
        )
        result = self.publish(PUBLISHED=published)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"Every parent is published at {self.VERSION}.", result.stdout)
        self.assertFalse((self.root / "mvn-arguments.log").exists())

    def test_restores_the_parents_when_the_deploy_fails(self):
        """A failed deploy leaves no rewritten version behind."""
        self.assertNotEqual(self.publish(MVN_EXIT="1").returncode, 0)
        self.assertParentsRestored()

    def test_needs_a_token_before_it_touches_the_parents(self):
        """Without GH_TOKEN the script stops before any lookup or rewrite."""
        environment = {**self.environment}
        environment.pop("GH_TOKEN", None)
        self.environment = environment
        result = self.run_script("publish-parents.sh", self.VERSION)
        self.assertFailsWith(result, "GH_TOKEN")
        self.assertFalse((self.root / "mvn-arguments.log").exists())
        self.assertParentsRestored()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 scripts/test_version_scripts.py PublishParentsTest -v`
Expected: FAIL; `scripts/publish-parents.sh` does not exist.

- [ ] **Step 3: Write `scripts/publish-parents.sh`**

```bash
#!/usr/bin/env bash
#
# Publish the parent POMs at a platform version, signed, to GitHub Packages, and
# skip every parent already published there.
#
# Between drops the parents carry a literal <version>X.Y.Z-SNAPSHOT</version>: a
# parent cannot take its version from ${revision}, because Maven 3 then resolves
# it from the repository instead of the relativePath. So this script rewrites the
# version to X.Y.Z in the working tree, deploys, and restores parents/ on exit,
# whatever the outcome. set-platform-version.sh writes the version the branch
# keeps afterwards.
#
# GitHub Packages refuses to overwrite a release, so each parent is looked up
# first, and a rerun after a partial failure deploys only what is still missing.
# qip-parents is an aggregator that no POM inherits from, and is never deployed.
#
# Env: GH_TOKEN (reads GitHub Packages); MAVEN_GITHUB_USERNAME,
#      MAVEN_GITHUB_PASSWORD, and MAVEN_GPG_PASSPHRASE, which
#      .github/maven-settings.xml reads for the deploy.
#
# Usage: scripts/publish-parents.sh 1.6.0

set -euo pipefail

VERSION="${1:?version (X.Y.Z) required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::version must be X.Y.Z (got '$VERSION')"
    exit 1
}
: "${GH_TOKEN:?GH_TOKEN env var required}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"
. "$ROOT/scripts/pom.sh"

REGISTRY="https://maven.pkg.github.com/Netcracker/qubership-integration-platform"
GROUP_PATH="org/qubership/integration/platform"

missing=()
for module in base spring quarkus; do
    artifact="qip-parent-$module"
    if curl --silent --fail --output /dev/null --user "x-access-token:$GH_TOKEN" \
        "$REGISTRY/$GROUP_PATH/$artifact/$VERSION/$artifact-$VERSION.pom"; then
        echo "$artifact $VERSION is already published."
    else
        missing+=("$module")
    fi
done

if [ "${#missing[@]}" -eq 0 ]; then
    echo "Every parent is published at $VERSION."
    exit 0
fi

trap 'git checkout --quiet -- parents' EXIT
for pom in "${QIP_PARENT_POMS[@]}"; do
    pom_set_own_version "$pom" "$VERSION"
done
pom_set_parent_version parents/quarkus/pom.xml "$VERSION"

modules="$(
    IFS=,
    echo "${missing[*]}"
)"
echo "Deploying the parents at $VERSION: $modules"
mvn --batch-mode -s .github/maven-settings.xml -f parents/pom.xml deploy -pl "$modules"
```

Then: `git add scripts/publish-parents.sh && git update-index --chmod=+x scripts/publish-parents.sh`.

- [ ] **Step 4: Call it from `scripts/build-drop-release.sh`**

Replace the header lines

```bash
# VERSION is the platform version of the wave: the root <revision> bumped by the
# wave's release-type. The caller (release-all) computes it once; this
# script writes it into the platform POMs (root <revision>, parent, and the
# children that pin the parent), commits, tags that commit, and cuts the release.
```

with

```bash
# VERSION is the platform version of the wave: the root <revision> bumped by the
# wave's release-type. The caller (release-all) computes it once; this script
# publishes the parent POMs at it (publish-parents.sh), writes it into the
# platform POMs (root <revision>, the parents, and every POM that pins a parent),
# commits, tags that commit, and cuts the release.
```

and replace

```bash
# Write + push the version BEFORE cutting the release: a blocked push fails cleanly
```

with

```bash
# Publish the parents at the platform version before the bump below moves them
# to the next development version. A parent already published is skipped, so a
# rerun of a failed drop resumes here.
bash scripts/publish-parents.sh "$version"

# Write + push the version BEFORE cutting the release: a blocked push fails cleanly
```

Add `MAVEN_GITHUB_USERNAME, MAVEN_GITHUB_PASSWORD, MAVEN_GPG_PASSPHRASE (for publish-parents.sh)` to the `# Env:` line
of the header.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python3 scripts/test_version_scripts.py -v`
Expected: every test PASSES.

- [ ] **Step 6: Commit**

```bash
git add scripts/publish-parents.sh scripts/build-drop-release.sh scripts/test_version_scripts.py
git commit -m "build: publish the parent POMs at each platform drop" \
    -m "publish-parents.sh looks up each parent in GitHub Packages, rewrites the parents to the platform version, deploys the missing ones signed, and restores parents/ on exit. build-drop-release.sh calls it before set-platform-version.sh moves the parents to the next development version."
```

---

### Task 6: Sign every release and move the BOM entry in the reusable release

**Files:**
- Modify: `.github/workflows/_maven-module-release.yaml` (inputs, secrets, the install, deploy, and bump steps)
- Modify: `.github/maven-settings.xml` (whole file)

**Interfaces:**
- Consumes: `scripts/sync-bom-entry.sh` (Task 4).
- Produces: the reusable's inputs are `module`, `release-type`, `version`, `build-docker`, `mvn-args`, `tag-prefix`,
  `sync-property`, `sync-poms`; secrets are `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`,
  `GH_BUMP_VERSION_APP_KEY`. `profile`, `MAVEN_USER`, and `MAVEN_PASSWORD` no longer exist.

- [ ] **Step 1: Write the failing check**

```bash
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 -color .github/workflows/_maven-module-release.yaml
grep -nE 'profile|SKIP_GPG|MAVEN_CENTRAL|MAVEN_USER|parent/pom.xml' .github/workflows/_maven-module-release.yaml
```

Expected: actionlint passes, and the grep prints the lines to remove (the check is that it prints nothing at the end).

- [ ] **Step 2: Edit the inputs and secrets**

Delete the `profile` input (the five lines from the `profile:` key through its `default: "central"` line).
Delete the `MAVEN_USER` and `MAVEN_PASSWORD` secrets (six lines). In the header comment, replace
`# flatten-maven-plugin inlines the parent, so the published POM has no SNAPSHOT.` with

```yaml
# flatten-maven-plugin removes the parent, so the published POM names no SNAPSHOT
# parent. Every deploy is signed, and the bump commit also moves the module's
# BOM entry (scripts/sync-bom-entry.sh).
```

- [ ] **Step 3: Replace the parent install step**

Replace the step named `Install qip-monorepo-parent locally` (with its comment) by:

```yaml
      # Install the parent POMs so the single-module deploy below resolves them
      # from the local repository. Signing is skipped: nothing leaves the runner.
      - name: Install the parent POMs locally
        if: ${{ needs.check-version.outputs.recover != 'true' }}
        env:
          MAVEN_GITHUB_USERNAME: ${{ github.actor }}
          MAVEN_GITHUB_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
        run: mvn --batch-mode -s "$GITHUB_WORKSPACE/.github/maven-settings.xml" -f parents/pom.xml install -Dgpg.skip=true
```

- [ ] **Step 4: Replace the deploy step's env and command**

In `Build and deploy module`, delete the env lines `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`MVN_PROFILE`, and `SKIP_GPG`, and change the comment above `MAVEN_GITHUB_USERNAME` to
`# From .github/maven-settings.xml: github resolves the BOMs and takes the deploy.`
Replace the `mvn --batch-mode deploy` command with:

```yaml
          mvn --batch-mode deploy \
            -s "$GITHUB_WORKSPACE/.github/maven-settings.xml" \
            -pl $MODULE_NAME \
            -Drevision="$RELEASE_VERSION" \
            -Dchangelist= \
            $EXTRA_ARGS
```

- [ ] **Step 5: Move the BOM entry in the bump step**

In `Write released version back to revision, tag, and commit`, insert before the line
`# shellcheck disable=SC2086` that precedes `bash scripts/commit-and-push.sh`:

```yaml
          # The BOM lists every Maven module at its project version, so this
          # module's entry moves with its <revision>. The BOM has no entry for
          # itself.
          if [ "$MODULE" != bom ]; then
            files="$files $(bash scripts/sync-bom-entry.sh "$MODULE" "$RELEASE_VERSION")"
          fi
```

- [ ] **Step 6: Rewrite `.github/maven-settings.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Shared CI Maven settings, selected with `mvn -s .github/maven-settings.xml`.

  `github` resolves the private com.netcracker.cloud BOMs from GitHub Packages
  and takes every deploy; `gpg.passphrase` unlocks the signing key that
  actions/setup-java imports. setup-java configures only one server-id, so the
  credentials live here instead. Each workflow step sets only the env vars its
  Maven goal needs; unused servers stay empty and untouched.
-->
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>${env.MAVEN_GITHUB_USERNAME}</username>
      <password>${env.MAVEN_GITHUB_PASSWORD}</password>
    </server>
    <server>
      <id>gpg.passphrase</id>
      <passphrase>${env.MAVEN_GPG_PASSPHRASE}</passphrase>
    </server>
  </servers>
</settings>
```

- [ ] **Step 7: Run the check to verify it passes**

Run the two commands from Step 1. Expected: actionlint passes; the grep prints nothing.

Then run the release deploy locally against a file repository, as the workflow runs it:

```bash
REPO_DIR="$(mktemp -d)"
URL="file://$(cygpath -m "$REPO_DIR" 2> /dev/null || echo "$REPO_DIR")"
mvn -B -q -s .github/maven-settings.xml -f parents/pom.xml install -Dgpg.skip=true
mvn -B -q -pl engine deploy -Drevision=1.5.2 -Dchangelist= -Dgpg.skip=true -DskipTests \
    -DaltDeploymentRepository="local::$URL"
grep -nE '<parent>|SNAPSHOT' "$REPO_DIR/org/qubership/integration/platform/qip-engine/1.5.2/qip-engine-1.5.2.pom" || echo clean
```

Expected: `clean`. The published engine POM has no parent and names no SNAPSHOT.

- [ ] **Step 8: Commit**

```bash
git add .github/workflows/_maven-module-release.yaml .github/maven-settings.xml
git commit -m "ci: sign every module release and move its BOM entry" \
    -m "The reusable release skipped signing for profile=github, which is every release now that Central is gone, and installed parent/pom.xml, which no longer exists. It installs the parents/ POMs, deploys signed without a profile, and moves the module's BOM entry in the bump commit. The central server leaves the CI settings."
```

---

### Task 7: The module release wrappers, the BOM cascade, and the BOM release

**Files:**
- Create: `.github/workflows/bom-release.yaml`
- Modify: `.github/workflows/engine-release.yaml`, `runtime-catalog-release.yaml`, `sessions-management-release.yaml`,
  `integration-build-pipeline-release.yaml`, `integration-build-maven-plugin-release.yaml`,
  `checkstyle-release.yaml`, `micro-engine-release.yaml`, `schemas-release.yaml`, `schemas-maven-release.yaml`

**Interfaces:**
- Consumes: the reusable's inputs and secrets from Task 6.
- Produces: every Maven wrapper accepts `cascade-bom` (boolean, default `true`) on `workflow_call` and
  `workflow_dispatch`; `bom-release.yaml` accepts `release-type` on `workflow_call` and the secrets
  `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`, `GH_BUMP_VERSION_APP_KEY`.

- [ ] **Step 1: Write the failing check**

```bash
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 -color
grep -lE 'profile|MAVEN_USER|MAVEN_PASSWORD' .github/workflows/*-release.yaml
grep -L 'cascade-bom' .github/workflows/{engine,runtime-catalog,sessions-management,integration-build-pipeline,integration-build-maven-plugin,checkstyle,micro-engine,schemas}-release.yaml
```

Expected now: the first grep lists the nine Maven wrappers (Task 6 already cleaned `_maven-module-release.yaml`);
the second lists all eight wrappers. At the end of the task, both print nothing. `release-all.yaml` does not match
`*-release.yaml`; Task 8 handles it.

- [ ] **Step 2: Rewrite `engine-release.yaml`**

```yaml
---
name: Engine Release

run-name: Engine Release (patch)

on:
  workflow_call:
    inputs:
      release-type:
        description: "patch | minor | major"
        required: false
        type: string
        default: "patch"
      build-docker:
        description: "Build Docker image"
        required: false
        default: true
        type: boolean
      mvn-args:
        description: "Additional maven arguments to pass (e.g. -DskipTests=true -Dmaven.javadoc.skip=true)"
        required: false
        default: ""
        type: string
      cascade-bom:
        description: "After the release, release the BOM with a patch bump"
        required: false
        default: true
        type: boolean
    secrets:
      MAVEN_GPG_PRIVATE_KEY:
        required: false
      MAVEN_GPG_PASSPHRASE:
        required: false
      GH_BUMP_VERSION_APP_KEY:
        required: false
  # Patch only: minor and major go through release-all.
  workflow_dispatch:
    inputs:
      build-docker:
        description: "Build Docker image"
        required: false
        default: true
        type: boolean
      mvn-args:
        description: "Additional maven arguments to pass (e.g. -DskipTests=true -Dmaven.javadoc.skip=true)"
        required: false
        default: ""
        type: string
      cascade-bom:
        description: "After the release, release the BOM with a patch bump"
        required: false
        default: true
        type: boolean

permissions: {}

jobs:
  release:
    permissions:
      contents: write
      packages: write
    uses: ./.github/workflows/_maven-module-release.yaml
    with:
      module: engine
      release-type: ${{ inputs.release-type }}
      build-docker: ${{ inputs.build-docker }}
      mvn-args: ${{ inputs.mvn-args }}
    secrets:
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
      GH_BUMP_VERSION_APP_KEY: ${{ secrets.GH_BUMP_VERSION_APP_KEY }}

  # Cascade: the release moved this module's BOM entry, so publish a BOM that
  # carries it. Calls the reusable directly rather than bom-release.yaml, so that
  # release-all -> this wrapper -> the reusable stays three levels deep.
  # release-all passes cascade-bom: false and releases the BOM once per wave. If
  # another cascade cancels this one while it waits on the BOM's concurrency
  # group, nothing is lost: the next BOM release carries every moved entry.
  cascade-bom:
    needs: release
    if: ${{ inputs.cascade-bom && always() && !failure() && !cancelled() }}
    permissions:
      contents: write
      packages: write
    uses: ./.github/workflows/_maven-module-release.yaml
    with:
      module: bom
      release-type: patch
      build-docker: false
    secrets:
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
      GH_BUMP_VERSION_APP_KEY: ${{ secrets.GH_BUMP_VERSION_APP_KEY }}
```

- [ ] **Step 3: Apply the same changes to the other wrappers**

Each of these files gets the same four changes as `engine-release.yaml`:
- delete the `profile` input from `workflow_call` and from `workflow_dispatch`;
- delete the `MAVEN_USER` and `MAVEN_PASSWORD` secrets and the `profile:` line and the two secret lines in the
  `release` job;
- add the `cascade-bom` input to both triggers, with the text from Step 2;
- add the `cascade-bom` job from Step 2, comment included, after the `release` job.

Per-file differences that stay or change:

| File | Keep | Change |
| --- | --- | --- |
| `runtime-catalog-release.yaml` | `module: runtime-catalog`, its name and `build-docker` input | none |
| `sessions-management-release.yaml` | `module: sessions-management`, its name and `build-docker` input | none |
| `integration-build-pipeline-release.yaml` | `build-docker: false`, no `build-docker` input | `sync-property: qip.integration-build-pipeline.revision` (was `qip-integration-build-pipeline.version`) |
| `integration-build-maven-plugin-release.yaml` | `build-docker: false`, the no-consumers comment | none |
| `checkstyle-release.yaml` | its `release-type` choice on dispatch, `build-docker` default `false` | `sync-property: qip.checkstyle.version` and `sync-poms: parents/base/pom.xml parents/spring/pom.xml` |
| `micro-engine-release.yaml` | `module: micro-engine` | delete `profile: github` and its comment; add `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` to the `workflow_call` secrets and pass them in the `release` job; replace the header comment (below) |

The new header of `micro-engine-release.yaml`, replacing its first six comment lines:

```yaml
# micro-engine is the only module with private com.netcracker.cloud.* deps,
# pulled from maven.pkg.github.com/netcracker/*. The shared release resolves
# them with the github server in .github/maven-settings.xml (GITHUB_TOKEN), and
# publishes to GitHub Packages like every other module.
```

- [ ] **Step 4: Edit the schemas wrappers**

In `schemas-maven-release.yaml`: delete the `profile` input and the `profile:` line in the job; delete the
`MAVEN_USER` and `MAVEN_PASSWORD` secrets and their two lines in the job; change `name:` to
`Schemas Maven Release`; in the header, replace `# GPG-signed) to Maven Central via the shared _maven-module-release workflow.`
with `# signed) to GitHub Packages via the shared _maven-module-release workflow.`

In `schemas-release.yaml`: delete the `maven-profile` input and the `profile:` line in `release-maven`; delete the two
Central secret lines in `release-maven`; add to `workflow_dispatch.inputs`:

```yaml
      cascade-bom:
        description: "After releasing the Maven artifact, release the BOM with a patch bump"
        required: false
        type: boolean
        default: true
```

and append this job:

```yaml
  # Cascade: the Maven release moved schemas' BOM entry, so publish a BOM that
  # carries it. Calls the reusable directly, like cascade-ui calls ui-release.
  cascade-bom:
    needs: release-maven
    if: ${{ inputs.cascade-bom && always() && !failure() && !cancelled() }}
    permissions:
      contents: write
      packages: write
    uses: ./.github/workflows/_maven-module-release.yaml
    with:
      module: bom
      release-type: patch
      build-docker: false
    secrets:
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
      GH_BUMP_VERSION_APP_KEY: ${{ secrets.GH_BUMP_VERSION_APP_KEY }}
```

- [ ] **Step 5: Create `bom-release.yaml`**

```yaml
---
# Release the BOM (qip-bom) through the shared _maven-module-release workflow.
#
# Every standalone Maven module release cascades a patch release of the BOM, and
# release-all releases it once after its Maven jobs, with the wave's release
# type. Dispatch this workflow to release the BOM on its own. The BOM's entries
# already carry the module versions: each module release moves its entry.

name: BOM Release

run-name: BOM Release (patch)

on:
  workflow_call:
    inputs:
      release-type:
        description: "patch | minor | major"
        required: false
        type: string
        default: "patch"
    secrets:
      MAVEN_GPG_PRIVATE_KEY:
        required: false
      MAVEN_GPG_PASSPHRASE:
        required: false
      GH_BUMP_VERSION_APP_KEY:
        required: false
  # Patch only: minor and major go through release-all.
  workflow_dispatch: null

permissions: {}

jobs:
  release:
    permissions:
      contents: write
      packages: write
    uses: ./.github/workflows/_maven-module-release.yaml
    with:
      module: bom
      release-type: ${{ inputs.release-type || 'patch' }}
      build-docker: false
    secrets:
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
      GH_BUMP_VERSION_APP_KEY: ${{ secrets.GH_BUMP_VERSION_APP_KEY }}
```

- [ ] **Step 6: Run the check to verify it passes**

Run the three commands from Step 1. Expected: actionlint passes; both greps print nothing.

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/*-release.yaml
git commit -m "ci: release the BOM after every Maven module release" \
    -m "Each Maven release wrapper gains a cascade-bom input and job, which release the BOM with a patch bump after the module. bom-release.yaml releases the BOM on its own and for release-all. The wrappers lose the Central profile and secrets, micro-engine passes the signing secrets, and the checkstyle and library releases sync the renamed pins."
```

---

### Task 8: Release the BOM once per wave and publish the parents at the drop

**Files:**
- Modify: `.github/workflows/release-all.yaml`

**Interfaces:**
- Consumes: `cascade-bom` (Task 7), `bom-release.yaml` (Task 7), `QIP_BOM_MODULES` with `bom` (Task 2),
  `publish-parents.sh` through `build-drop-release.sh` (Task 5).

- [ ] **Step 1: Write the failing check**

```bash
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 -color .github/workflows/release-all.yaml
grep -nE 'profile|MAVEN_USER|MAVEN_PASSWORD|central' .github/workflows/release-all.yaml
grep -c 'cascade-bom: false' .github/workflows/release-all.yaml
```

Expected now: the first grep prints the profile input, the Central guard, and the Central secrets; the count is `0`.
At the end: the first grep prints nothing; the count is `6`.

- [ ] **Step 2: Remove the profile and the Central guard**

Delete the `profile` input (eight lines from the `profile:` key through its `default: "github"` line), the step
`Reject a Central wave that cannot resolve the library` with its four-line comment, every `profile: ${{ inputs.profile }}`
line, and every `MAVEN_USER:` and `MAVEN_PASSWORD:` secret line.

- [ ] **Step 3: Pass `cascade-bom: false` and the micro-engine signing secrets**

Add `cascade-bom: false` to the `with:` of `release-engine`, `release-micro-engine`,
`release-integration-build-pipeline`, `release-runtime-catalog`, `release-integration-build-maven-plugin`, and
`release-sessions-management`. In `release-micro-engine`, replace the comment

```yaml
    # micro-engine publishes to GitHub Packages (private com.netcracker.cloud
    # deps are not on Central); its wrapper fixes profile=github and needs no
    # Central/GPG secrets.
```

with nothing, and add to its `secrets:`

```yaml
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
```

- [ ] **Step 4: Decide whether the wave releases the BOM**

In `prepare`, add the output `release-bom: ${{ steps.check.outputs.bom }}`. In the `Determine modules to release`
step, change the loop to skip `bom` and compute it afterwards:

```bash
          for mod in $ALL_MODULES; do
            [ "$mod" = bom ] && continue
            if [[ "$MODULES" == "all" ]] || [[ "$requested" == *",${mod},"* ]]; then
              echo "${mod}=true" >> "$GITHUB_OUTPUT"
            else
              echo "${mod}=false" >> "$GITHUB_OUTPUT"
            fi
          done

          # The BOM lists every Maven module, so a wave that releases one of them
          # releases the BOM after it. bom can also be requested on its own.
          release_bom=false
          for mod in bom engine micro-engine runtime-catalog sessions-management integration-build-pipeline integration-build-maven-plugin schemas; do
            if [[ "$MODULES" == "all" ]] || [[ "$requested" == *",${mod},"* ]]; then
              release_bom=true
            fi
          done
          echo "bom=$release_bom" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 5: Add the `release-bom` job**

Insert after `release-schemas-maven`:

```yaml
  # After every Maven job: each one moved its BOM entry, and this job must check
  # out a tree that carries all of them.
  release-bom:
    needs:
      - prepare
      - release-engine
      - release-micro-engine
      - release-integration-build-pipeline
      - release-runtime-catalog
      - release-integration-build-maven-plugin
      - release-sessions-management
      - release-schemas-maven
    if: ${{ !failure() && !cancelled() && needs.prepare.outputs.release-bom == 'true' }}
    permissions:
      contents: write
      packages: write
    uses: ./.github/workflows/bom-release.yaml
    with:
      release-type: ${{ inputs.release-type }}
    secrets:
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
      GH_BUMP_VERSION_APP_KEY: ${{ secrets.GH_BUMP_VERSION_APP_KEY }}
```

Add `- release-bom` to the `needs:` lists of `publish-bom` and `create-drop-release`.

- [ ] **Step 6: Give the drop job Java, the signing key, and package access**

In `create-drop-release`, add `packages: write` to `permissions:`. After the `Checkout` step, add:

```yaml
      # build-drop-release.sh publishes the parent POMs at the platform version,
      # signed, before it moves them to the next development version.
      - name: Set up Java
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          java-version: "21"
          distribution: "temurin"
          gpg-private-key: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
```

Add to the `env:` of `Create platform drop release`:

```yaml
          MAVEN_GITHUB_USERNAME: ${{ github.actor }}
          MAVEN_GITHUB_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
```

- [ ] **Step 7: Run the check to verify it passes**

Run the three commands from Step 1. Expected: actionlint passes; the grep prints nothing; the count is `6`.

- [ ] **Step 8: Commit**

```bash
git add .github/workflows/release-all.yaml
git commit -m "ci: release the BOM once per wave and publish the parents at the drop" \
    -m "release-all turns the module cascades off and releases the BOM after every Maven job, with the wave's release type. The drop job sets up Java and the signing key, so build-drop-release.sh can publish the parents. The Central profile and its wave guard are gone, and micro-engine gets the signing secrets."
```

---

### Task 9: Signed snapshots, including the parents and the BOM

**Files:**
- Modify: `.github/workflows/snapshot-publish.yaml`

- [ ] **Step 1: Write the failing check**

```bash
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 -color .github/workflows/snapshot-publish.yaml
grep -nE 'Pgithub|gpg.skip|parent/pom.xml' .github/workflows/snapshot-publish.yaml
```

Expected now: the grep prints the parent install, `-Pgithub` twice, and `-Dgpg.skip=true` twice. At the end: the grep
prints only the install step's `-Dgpg.skip=true`.

- [ ] **Step 2: Rewrite the header comment**

Replace the paragraph starting `# GitHub-only: the \`github\` profile targets maven.pkg.github.com and skips GPG.` with:

```yaml
# GitHub Packages only, signed like a release. Nothing is committed or tagged
# (contents: read), so it's safe from any branch. `parents` publishes the three
# parent POMs at their X.Y.Z-SNAPSHOT; `bom` publishes the BOM, whose entries
# name the module SNAPSHOTs, which exist only once each was published here.
```

Keep the paragraph that follows it about republishing the same coordinates.

- [ ] **Step 3: Add the choices, the signing key, and the parents install**

Add `- bom` and `- parents` to the `module` options. In `Set up Java`, add
`gpg-private-key: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}` and change the comment to
`# GitHub Packages credentials live in .github/maven-settings.xml (passed via -s). GPG key here.`
Replace the step `Install qip-monorepo-parent locally` and its comment with:

```yaml
      # Install the parent POMs so the single-module deploy below resolves them
      # from the local repository. Signing is skipped: nothing leaves the runner.
      - name: Install the parent POMs locally
        env:
          MAVEN_GITHUB_USERNAME: ${{ github.actor }}
          MAVEN_GITHUB_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
        run: mvn --batch-mode -s "$GITHUB_WORKSPACE/.github/maven-settings.xml" -f parents/pom.xml install -Dgpg.skip=true
```

- [ ] **Step 4: Sign the deploys and add the parents deploy**

In `Deploy the pinned library SNAPSHOT`, add `MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}` to `env:`, and
replace its command with:

```yaml
          mvn --batch-mode -s "$GITHUB_WORKSPACE/.github/maven-settings.xml" \
            -f integration-build-pipeline/pom.xml deploy \
            -Dmaven.test.skip=true -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true
```

Replace the step `Deploy SNAPSHOT to GitHub Packages` with these two steps:

```yaml
      - name: Deploy the parent POMs SNAPSHOT
        if: ${{ inputs.module == 'parents' }}
        env:
          MAVEN_GITHUB_USERNAME: ${{ github.actor }}
          MAVEN_GITHUB_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
        # qip-parents is an aggregator that no POM inherits from, so it stays out.
        run: mvn --batch-mode -s "$GITHUB_WORKSPACE/.github/maven-settings.xml" -f parents/pom.xml deploy -pl base,spring,quarkus

      - name: Deploy SNAPSHOT to GitHub Packages
        if: ${{ inputs.module != 'parents' }}
        working-directory: ${{ inputs.module }}
        env:
          MAVEN_GITHUB_USERNAME: ${{ github.actor }}
          MAVEN_GITHUB_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
        run: |
          set -eu
          # Run from the module dir so Maven reads only this module + parent (via
          # relativePath), never siblings. Keep ${revision}${changelist} intact
          # (no -Drevision / -Dchangelist=) → publishes the current X.Y.Z-SNAPSHOT.
          mvn --batch-mode -s "$GITHUB_WORKSPACE/.github/maven-settings.xml" deploy
```

- [ ] **Step 5: Run the check to verify it passes**

Run the two commands from Step 1. Expected: actionlint passes; the grep prints only `-Dgpg.skip=true` in the install
step.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/snapshot-publish.yaml
git commit -m "ci: sign snapshots and publish the parent and BOM snapshots" \
    -m "snapshot-publish installed parent/pom.xml and deployed with -Pgithub -Dgpg.skip=true. It installs the parents/ POMs, signs every deploy, and offers parents and bom as modules."
```

---

### Task 10: Build pull requests that change the parents

**Files:**
- Modify: `.github/workflows/engine-build.yaml`, `runtime-catalog-build.yaml`, `sessions-management-build.yaml`,
  `integration-build-pipeline-build.yaml`, `integration-build-maven-plugin-build.yaml`, `main-build.yaml`,
  `snapshot-flow-build.yaml`, `micro-engine-build.yaml`, `checkstyle-build.yaml`, `schemas-build.yaml`

- [ ] **Step 1: Write the failing check**

```bash
grep -n "'parent/\*\*'" .github/workflows/*.yaml
grep -L "'parents/\*\*'" .github/workflows/{engine,runtime-catalog,sessions-management,integration-build-pipeline,integration-build-maven-plugin,main,snapshot-flow,micro-engine,checkstyle,schemas}-build.yaml
```

Expected now: the first grep lists eight lines (`snapshot-flow-build.yaml` has the filter in both its
`pull_request` and its `push` trigger); the second lists all ten files. At the end: both print nothing.

- [ ] **Step 2: Edit the filters**

- In every file the first grep lists, replace each `- 'parent/**'` with `- 'parents/**'`.
- In `micro-engine-build.yaml` and `checkstyle-build.yaml`, add `- 'parents/**'` after the module's own path.
- In `schemas-build.yaml`, add `- 'parents/**'` after `- 'schemas/**'`.
- In `main-build.yaml`, change the comment `# shared parent/pom.xml or the schemas -> ui -> vscode-extension workspace chain).`
  to `# shared parents/ POMs or the schemas -> ui -> vscode-extension workspace chain).`

- [ ] **Step 3: Run the check to verify it passes**

Run the two commands from Step 1, then
`docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 -color`.
Expected: both greps print nothing; actionlint passes.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/*-build.yaml
git commit -m "ci: build the modules when their parent POMs change" \
    -m "The build path filters watched parent/**, which no longer exists, and micro-engine, checkstyle, and schemas watched no parent at all. Every module build now runs on a change under parents/."
```

---

### Task 11: Rebuild the Spring images when their parent POM changes

**Files:**
- Modify: `e2e/env/provision/compose.ts:53-61` (doc comment) and the three `sources` arrays that list
  `"parent/pom.xml"`
- Modify: `e2e/specs/schema/provision.spec.ts:263-273`
- Modify: `e2e/k8s/README.md:140`

- [ ] **Step 1: Write the failing test**

In `e2e/specs/schema/provision.spec.ts`, replace

```ts
    // Two of the three inputs to a jar this image runs sit outside the module. `parent/pom.xml` is
    // the parent of every Spring service and carries the dependency versions and the repackage
    // plugin, so a bump there changes every jar the build produces; the `Dockerfile` is the only
```

with

```ts
    // Two of the three inputs to a jar this image runs sit outside the module. `parents/spring/pom.xml`
    // is the parent of every Spring service and carries the dependency versions and the repackage
    // plugin, so a bump there changes every jar the build produces; the `Dockerfile` is the only
```

and replace `"parent/pom.xml",` in the `toContain(` call with `"parents/spring/pom.xml",`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd e2e && npm install && npx playwright test --project=schema specs/schema/provision.spec.ts`
(`npm install` runs inside `e2e/`, which sits outside the root workspaces.)
Expected: FAIL. `compose.ts` still lists `parent/pom.xml`, which also fails the "not in the checkout" assertion.

- [ ] **Step 3: Edit `compose.ts`**

Replace each `"parent/pom.xml"` in the three `sources` arrays with `"parents/spring/pom.xml"`. In the doc comment,
replace

```ts
 * one. `parent/pom.xml` is the parent of all three Spring services — every one of them declares
 * `qip-monorepo-parent` with `<relativePath>../parent</relativePath>` — and it carries the
```

with

```ts
 * one. `parents/spring/pom.xml` is the parent of all three Spring services — every one of them
 * declares `qip-parent-spring` with `<relativePath>../parents/spring/pom.xml</relativePath>` — and
 * it carries the
```

and replace

```ts
 * deliberately absent — it aggregates the reactor and parents nothing, `parent/pom.xml` inherits
 * from `spring-boot-starter-parent` instead, and the `revision` and `changelist` the modules
 * interpolate are the parent's own properties rather than the aggregator's.
```

with

```ts
 * deliberately absent — it aggregates the reactor and parents nothing, and the Spring parent
 * inherits from `spring-boot-starter-parent` instead. `parents/base/pom.xml` is absent for the same
 * reason: no Spring service inherits from it.
```

- [ ] **Step 4: Edit `e2e/k8s/README.md`**

On line 140, replace ``and `parent/pom.xml` for the`` with ``and `parents/spring/pom.xml` for the``.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd e2e && npm run check-types && npx playwright test --project=schema`
Expected: types check; every `schema` case PASSES.

- [ ] **Step 6: Commit**

```bash
git add e2e/env/provision/compose.ts e2e/specs/schema/provision.spec.ts e2e/k8s/README.md
git commit -m "test(e2e): rebuild the Spring images when parents/spring/pom.xml changes" \
    -m "The provisioner decided staleness from parent/pom.xml, which no longer exists, so an edit to the Spring parent left the old jars running. The three Spring services now watch parents/spring/pom.xml."
```

---

### Task 12: Documentation

**Files:**
- Modify: `.apm/instructions/monorepo.instructions.md:24-26, 52, 110-111`
- Modify: `.apm/agents/maven-verifier.agent.md:13, 58`
- Modify: `.apm/skills/release-process/SKILL.md`
- Modify: `scripts/README.md`, `checkstyle/README.md:25`, `integration-build-maven-plugin/README.md:436`,
  `scripts/commit-and-push.sh:263-265` (comment)
- Regenerate: `AGENTS.md`, `.claude/`, `.cursor/`, `.codex/` with `apm compile`

Load the `apm-authoring` skill before editing `.apm/`.

- [ ] **Step 1: Write the failing check**

```bash
git grep -nE 'parent/pom.xml|qip-monorepo-parent|qip-checkstyle-revision|qip-integration-build-pipeline\.version|profile=github|not on Central' \
    -- .apm scripts checkstyle/README.md integration-build-maven-plugin/README.md AGENTS.md .claude .cursor .codex
```

Expected now: about 15 lines. At the end: nothing.

- [ ] **Step 2: Edit `.apm/instructions/monorepo.instructions.md`**

In this table, ` / ` separates the lines of a multi-line passage.

| Find | Replace with |
| --- | --- |
| ``configuration instead of a service: `checkstyle/` (the `qip-checkstyle` artifact) and`` / `` `parent/` (the Maven parent POM `qip-monorepo-parent`).`` | ``configuration instead of a service: `checkstyle/` (the `qip-checkstyle` artifact) and`` / `` `parents/` (the parent POMs `qip-parent-base`, `qip-parent-spring`, and `qip-parent-quarkus`). `bom/` holds `qip-bom`,`` / ``the public BOM that lists every published module at its project version.`` |
| ``(GPG signing is configured in `parent/pom.xml` for release publishing).`` | ``(GPG signing is configured in `parents/base/pom.xml` and `parents/spring/pom.xml` for publishing).`` |
| ``- The `parent/` POM controls Spring Boot, MapStruct, Lombok, Checkstyle, JaCoCo versions for every Spring module — bump dependency versions there, not in module POMs.`` | ``- `parents/spring/pom.xml` controls Spring Boot, MapStruct, Lombok, Checkstyle, and JaCoCo versions for every Spring module, and `parents/quarkus/pom.xml` the Quarkus ones for micro-engine. Bump dependency versions there, not in module POMs; a property both `parents/base` and `parents/spring` declare must keep one value, which `scripts/check-version-invariants.sh` checks.`` |
| ``- The `qip-checkstyle` rules version is pinned via `qip-checkstyle-revision` in `parent/pom.xml`.`` | ``- The `qip-checkstyle` rules version is pinned via `qip.checkstyle.version` in `parents/base/pom.xml` and `parents/spring/pom.xml`.`` |

- [ ] **Step 3: Edit `.apm/agents/maven-verifier.agent.md`**

Line 13: replace ``configured in `parent/pom.xml` for release publishing and fails locally without it.`` with
``configured in the `parents/` POMs for publishing and fails locally without it.``
Line 58: replace `` `parent/pom.xml` binds `checkstyle:check` to the `compile` `` with
`` the parent POMs bind `checkstyle:check` to the `compile` ``.

- [ ] **Step 4: Edit `.apm/skills/release-process/SKILL.md`**

Replace the two lines

```markdown
- **micro-engine** is the exception. It publishes to GitHub Packages (`profile=github`, fixed in its
  wrapper) because its private `com.netcracker.cloud` dependencies are not on Central.
```

with

```markdown
- Every Maven release is signed and published to GitHub Packages; nothing goes to Maven Central.
- **The BOM** (`bom/pom.xml`, `qip-bom`) lists every Maven module at its project version. A module
  release moves its BOM entry in the bump commit, and its wrapper then releases the BOM with a patch
  bump (`cascade-bom`, on by default). `release-all` turns the cascades off and releases the BOM once
  after its Maven jobs, with the wave's release type.
- **The parent POMs** (`parents/`) carry the platform version with `-SNAPSHOT` appended. The drop
  publishes them at the platform version (`scripts/publish-parents.sh`), then moves them to the next
  development version.
```

and replace

```markdown
- Maven → `snapshot-publish.yaml`, publishing the current `X.Y.Z-SNAPSHOT`.
```

with

```markdown
- Maven → `snapshot-publish.yaml`, publishing the current `X.Y.Z-SNAPSHOT`, signed. It also takes
  `parents` and `bom`.
```

- [ ] **Step 5: Edit the READMEs and the comment**

- `checkstyle/README.md:25`: `<version>${qip-checkstyle-revision}</version>` becomes
  `<version>${qip.checkstyle.version}</version>`.
- `integration-build-maven-plugin/README.md:436`: `` `qip-integration-build-pipeline.version` in `pom.xml` `` becomes
  `` `qip.integration-build-pipeline.revision` in `pom.xml` ``.
- `scripts/commit-and-push.sh:263-265`: replace
  `# Push the tag before the branch, with retries: it is the recovery sentinel after`
  / `# a non-idempotent Central deploy, so losing it would re-deploy and wedge the`
  / `# module next run. Idempotent — a prior run may already hold it.` with
  `# Push the tag before the branch, with retries: it is the recovery sentinel after`
  / `# a deploy GitHub Packages will not repeat, so losing it would re-deploy and`
  / `# wedge the module next run. Idempotent — a prior run may already hold it.`
- `scripts/README.md`: rename the heading `## The model in four lines` to `## The model in five lines`, and replace
  its third and fourth bullets (the ones starting `- The **platform version**` and `- In-repo libraries`) with:

```markdown
- The **platform version** lives in the root `pom.xml` `<revision>`, and the parent POMs carry it with `-SNAPSHOT`
  appended. A `release-all` wave bumps it and every module except checkstyle by the same release type, publishes the
  parents at it, tags the drop `v<platform-version>`, and titles the GitHub Release with it. A module dispatched alone
  releases a patch, so major.minor stay shared and only patches differ.
- In-repo libraries (`qip-integration-build-pipeline`, `qip-checkstyle`) are pinned by property, not by the reactor
  version. Their release moves the pins in the same bump commit. The pins name the last release either way, but
  runtime-catalog appends `${changelist}` to the library pin so a normal build resolves the development coordinate
  from the reactor; `qip-checkstyle` is a plugin dependency and is never in a reactor, so its pin is used as is.
- The BOM (`bom/pom.xml`) lists every Maven module at its project version. A module release moves its entry in the
  bump commit and then releases the BOM with a patch bump; `release-all` releases the BOM once per wave instead.
```

- In the `scripts/README.md` table: the `check-version-invariants.sh` row's description becomes
    ``Fails when POM versions that must agree have drifted: the parent chain, the library and checkstyle pins, the BOM entries, and the properties both parents declare.``;
    the `set-platform-version.sh` row's becomes
    ``Writes one platform version into the root POM, the parent POMs, and every POM that pins a parent. Verifies every write and prints the files it changed.``;
    the `sync-consumer-pins.sh` row's example becomes
    `` `bash scripts/sync-consumer-pins.sh qip.checkstyle.version 0.0.4 parents/base/pom.xml` ``;
    the `modules.sh` row says ``sourced by the version scripts``.
- Add three rows to that table after `sync-consumer-pins.sh`:

```markdown
| `sync-bom-entry.sh` | Points a module's BOM entry at its released version and keeps its `${changelist}` suffix. | `_maven-module-release` bump step | `bash scripts/sync-bom-entry.sh engine 1.5.2` |
| `publish-parents.sh` | Publishes the parent POMs at the platform version, signed, skipping any already published, and restores `parents/` on exit. | `build-drop-release.sh` | No — it deploys |
| `pom.sh` | POM readers and writers, sourced by the version scripts. It declares functions and runs nothing. | sourced | — |
```

- After the table, add: ``The scripts above are tested by `python3 scripts/test_version_scripts.py`, which the Version Invariants workflow runs.``

- [ ] **Step 6: Regenerate the compiled instructions**

Run: `apm compile`
Expected: `AGENTS.md`, `.claude/rules/monorepo.md`, `.cursor/rules/monorepo.mdc`, `.claude/agents/maven-verifier.md`,
`.cursor/agents/maven-verifier.md`, `.codex/agents/maven-verifier.toml`, and the deployed `release-process` skill
change, and nothing else. Check with `git status --short`.

- [ ] **Step 7: Run the check to verify it passes**

Run the command from Step 1. Expected: nothing.

- [ ] **Step 8: Commit**

```bash
git add .apm scripts/README.md scripts/commit-and-push.sh checkstyle/README.md \
    integration-build-maven-plugin/README.md AGENTS.md .claude .cursor .codex
git commit -m "docs: describe the parent POMs, the BOM, and the signed releases" \
    -m "The instructions, the release-process skill, and the script README still described parent/pom.xml, the old pin names, and a Central deploy. They now describe the parents/ POMs, the BOM cascade, the parents publish at the drop, and the two new scripts."
```

---

### Task 13: Verify the branch end-to-end

**Files:** none changed, unless a check fails.

- [ ] **Step 1: Run the script tests and the invariants**

```bash
python3 scripts/test_version_scripts.py -v
LC_ALL=C.UTF-8 bash scripts/check-version-invariants.sh
```

Expected: every test PASSES; `Version invariants hold: platform 1.5.1, library 1.5.1, checkstyle 0.0.3.`

- [ ] **Step 2: Deploy the BOM and the parents as the release would, into a file repository**

```bash
REPO_DIR="$(mktemp -d)"
URL="file://$(cygpath -m "$REPO_DIR" 2> /dev/null || echo "$REPO_DIR")"
P=org/qubership/integration/platform
mvn -B -q -pl bom deploy -Drevision=1.5.2 -Dchangelist= -Dgpg.skip=true -DaltDeploymentRepository="local::$URL"
grep -c SNAPSHOT "$REPO_DIR/$P/qip-bom/1.5.2/qip-bom-1.5.2.pom" || true
. scripts/modules.sh && . scripts/pom.sh
for pom in "${QIP_PARENT_POMS[@]}"; do pom_set_own_version "$pom" 1.6.0; done
pom_set_parent_version parents/quarkus/pom.xml 1.6.0
mvn -B -q -s .github/maven-settings.xml -f parents/pom.xml deploy -pl base,spring,quarkus -Dgpg.skip=true \
    -DaltDeploymentRepository="local::$URL"
git checkout -- parents
ls "$REPO_DIR/$P/qip-parent-base/1.6.0" "$REPO_DIR/$P/qip-parent-spring/1.6.0" "$REPO_DIR/$P/qip-parent-quarkus/1.6.0"
git status --short parents
```

Expected: the BOM's SNAPSHOT count is `0`; each parent directory holds a `.pom` at `1.6.0`; `git status` prints nothing
for `parents`.

- [ ] **Step 3: Confirm the effective dependencies did not change**

```bash
for m in checkstyle schemas integration-build-pipeline integration-build-maven-plugin runtime-catalog sessions-management engine micro-engine; do
    (cd "$m" && mvn -B -q -o dependency:list -DincludeScope=test -DoutputAbsoluteArtifactFilename=false -Dsort=true -DoutputFile="/tmp/deps-$m.txt") || echo "FAIL $m"
done
```

Compare each list with the same command run in a worktree of `main` (`git worktree add --detach <dir> main`), as the
branch analysis did. Expected: identical lists for all eight modules. Remove the worktree afterwards.

- [ ] **Step 4: Run super-linter as CI runs it**

```bash
docker run --rm -e RUN_LOCAL=true -e DEFAULT_BRANCH=main -e VALIDATE_ALL_CODEBASE=false \
    --env-file .github/super-linter.env -v "$PWD:/tmp/lint" ghcr.io/super-linter/super-linter:slim-v8.1.0
```

Expected: no errors for the changed files (shellcheck, shfmt, actionlint, zizmor, the Python linters, markdownlint).
Fix any finding in the task that owns the file and amend that task's commit only if it is not pushed; otherwise add a
`fix:` commit.

- [ ] **Step 5: Record what only a real release can verify**

Write this in the pull request description, under a heading for reviewers: a deploy to GitHub Packages, the `curl`
lookup in `publish-parents.sh` against it, the nesting of `release-all` → wrapper → reusable, and the tag and bump
pushes are exercised by the first release after the merge, not by a local run.
