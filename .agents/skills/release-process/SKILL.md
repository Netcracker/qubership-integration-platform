---
name: release-process
description: Release a module of this monorepo, or read back how a release is versioned, tagged, and published. Use when the user asks to cut a release, run a release wave, publish a snapshot, or explain why a release tag or version bump behaved the way it did.
---

# Releasing a module

A release is version-type driven, so you never type a version number. The workflow computes the
version from the file in the repo, publishes it, tags it `<module>-vX.Y.Z` (the Go module tags
differently, see below), and commits the bumped version back to the branch as `qubership-actions[bot]`.
The file in the repo holds the last released version and is the source of truth for the next one.

- `release-all.yaml` takes `patch`, `minor`, or `major` and bumps every module in `QIP_BOM_MODULES`
  (`scripts/modules.sh`) by it, each from its own version. That list leaves out checkstyle and
  ai-service. The drop tag `v<platform-version>` is the root `pom.xml` `<revision>` bumped the
  same way.
- A `<module>-release.yaml` dispatch always releases a `patch`, so all modules keep one
  major.minor. A module released alone stays a patch ahead: after an engine hotfix to 1.5.1, the
  next patch wave puts engine on 1.5.2 and the rest on 1.5.1.
- `checkstyle-release.yaml` and `ai-service-release.yaml`, for the two modules outside the wave,
  offer `patch`, `minor`, or `major` on dispatch.

## Per-ecosystem behavior

- **Maven** (`${revision}${changelist}`, reusable `_maven-module-release.yaml`): bumps
  `<revision>` by `release-type`, publishes that version with the changelist stripped, writes the
  bumped `<revision>` back, and tags the bump commit.
- Every Maven release is signed and published to GitHub Packages; nothing goes to Maven Central.
- **The BOM** (`bom/pom.xml`, `qip-bom`) lists every Maven module at its project version. A module
  release moves its BOM entry in the bump commit, and its wrapper then releases the BOM with a patch
  bump (`cascade-bom`, on by default). `release-all` turns the cascades off and releases the BOM once
  after its Maven jobs, with the wave's release type. When a cascade did not publish the BOM (the BOM's
  concurrency group cancelled it, it failed, the module was dispatched with `cascade-bom` off, or a failed
  Docker build skipped it), dispatch `bom-release.yaml` to release the BOM with a patch bump.
- **The parent POMs** (`parents/`) carry the platform version with `-SNAPSHOT` appended. The drop
  publishes them at the platform version (`scripts/publish-parents.sh`), then moves them to the next
  development version.
- **npm** (`npm version`, reusable `_npm-module-release.yaml`): bumps `package.json` by
  `release-type` and releases that. The cascade schemas → ui → vscode-extension is auto-`patch`.
- **Go** (`testing-service`, `testing-service-release.yaml`): bumps `testing-service/VERSION` by
  `release-type`, builds and tests the module, writes `VERSION` back, and tags the bump commit
  `testing-service/vX.Y.Z`. The go command resolves a module in a repository subdirectory only from
  tags prefixed with that subdirectory, so `compute-release-version.sh` keys the slash form on
  `ECOSYSTEM=go` and `build-bom.sh` reads it through `tag_prefix`. No registry takes the module, so
  the tag is the release; the image `qubership-integration-testing-service` is pushed to GHCR as for
  the other modules.

## Releasing the Go module at a new major

`go.mod` declares the module path without a major suffix, and the go command refuses a `v2.0.0` or
later tag for such a module. A `major` wave would still tag `testing-service/v2.0.0`. Before that
wave, move the module path in `go.mod` and every import of it inside the module to
`.../testing-service/v2`, or leave `testing-service` out of the `modules` input of the wave.

## Branch protection

The bump commit is pushed with a GitHub App token (`vars.GH_BUMP_VERSION_APP_ID` and
`secrets.GH_BUMP_VERSION_APP_KEY`), and the App must be on the bypass list of the target branch.
Without the App the push falls back to `GITHUB_TOKEN`, which cannot bypass protection.
`scripts/commit-and-push.sh` pushes the tag before the branch, so a rejected bump push leaves the
tag behind, and the next run sees it, skips the publish, and re-applies only the bump.

`_maven-module-release.yaml` keeps an optional `version` input that overrides the computed version.
Only schemas' Maven line uses it, to take the number its npm release decided.

## Snapshot and dev publishing

On-demand, GitHub Packages only, with no tag, bump, commit, or GitHub Release:

- Maven → `snapshot-publish.yaml`, publishing the current `X.Y.Z-SNAPSHOT`, signed. It also takes
  `parents` and `bom`.
- npm → `snapshot-publish-npm.yaml`, publishing an ephemeral `<next>-dev.<UTC-ts>` under the `dev`
  dist-tag, for `ui` and `vscode-extension`.

Both run from any branch, which is how you test unreleased branch code in a consumer.
