# Release scripts

Everything the release workflows do to versions, tags, and the drop release lives here. The workflows in
`.github/workflows/` supply inputs and credentials; the decisions are in these files, so you can run most of them
locally to see what a release would do.

## The model in five lines

- You pick `patch`, `minor`, or `major`. Nothing anywhere lets you type a version number.
- Each version file holds the **last released** version. A release bumps it, publishes that, writes it back, and tags
  the bump commit — so `git checkout <tag>` reproduces the tree that was published.
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

## The scripts

| Script | What it does | Run by | By hand |
| --- | --- | --- | --- |
| `compute-release-version.sh` | Decides the version for one release and appends it to `$GITHUB_OUTPUT`. `ECOSYSTEM=maven\|npm` reads the module's POM or `package.json`; `ECOSYSTEM=platform` reads the root POM. Sets `recover=true` when the tag already exists. | both release reusables, `snapshot-publish-npm`, `release-all` | `GITHUB_OUTPUT=/dev/stdout ECOSYSTEM=maven MODULE=engine RELEASE_TYPE=minor bash scripts/compute-release-version.sh` |
| `commit-and-push.sh` | Commits the given paths as the org bot and pushes to `$BRANCH`, rebasing with retries. With `TAG=`, pushes the tag before the branch and re-points it after a rebase. | both release reusables, `build-drop-release.sh` | No — it pushes |
| `build-drop-release.sh` | Cuts the one GitHub Release per wave: writes the platform version, commits, tags, and publishes the notes. | `release-all` | `VERSION=1.3.0 DRY_RUN=1 REPO=<owner/repo> GH_TOKEN=$(gh auth token) bash scripts/build-drop-release.sh` |
| `build-bom.sh` | Prints the released version of every shipped module as JSON, derived from Git tags. | `release-all`, `build-drop-release.sh` | `bash scripts/build-bom.sh` |
| `check-version-invariants.sh` | Fails when POM versions that must agree have drifted: the parent chain, the library and checkstyle pins, the BOM entries, and the properties both parents declare. | `version-invariants` on PRs, `release-all` after a full wave | `bash scripts/check-version-invariants.sh` |
| `set-platform-version.sh` | Writes one platform version into the root POM, the parent POMs, and every POM that pins a parent. Verifies every write and prints the files it changed. | `build-drop-release.sh` | `bash scripts/set-platform-version.sh 1.3.0` — this is how you re-baseline |
| `sync-consumer-pins.sh` | Points consumer POMs at a newly released in-repo artifact. | `_maven-module-release` via `sync-property` / `sync-poms` | `bash scripts/sync-consumer-pins.sh qip.checkstyle.version 0.0.4 parents/base/pom.xml` |
| `sync-bom-entry.sh` | Points a module's BOM entry at its released version and keeps its `${changelist}` suffix. | `_maven-module-release` bump step | `bash scripts/sync-bom-entry.sh engine 1.5.2` |
| `publish-parents.sh` | Publishes the parent POMs at the platform version, signed, skipping any already published, and restores `parents/` on exit. | `build-drop-release.sh` | No — it deploys |
| `pom.sh` | POM readers and writers, sourced by the version scripts. It declares functions and runs nothing. | sourced | — |
| `modules.sh` | Module topology, sourced by the version scripts. It declares lists and runs nothing. | sourced | — |

The scripts above are tested by `python3 scripts/test_version_scripts.py`, which the Version Invariants workflow runs.

## What is not here

`modules.sh` is not the only place the module topology appears. Dispatch `choice` options, the `main-build` matrix, and
`release-all`'s per-module jobs must be literal YAML, so they stay hand-written. Adding or removing a module means
touching those three too.
