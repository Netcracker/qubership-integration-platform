---
name: release-process
description: Release a module of this monorepo, or read back how a release is versioned, tagged, and published. Use when the user asks to cut a release, run a release wave, publish a snapshot, or explain why a release tag or version bump behaved the way it did.
---

# Releasing a module

A release is version-type driven, so you never type a version number. Pick `patch`, `minor`, or
`major` — either a `<module>-release.yaml` dispatch, or `release-all.yaml` for a wave. The workflow
computes the version from the file in the repo, publishes it, tags it `<module>-vX.Y.Z`, and commits
the bumped version back to the branch as `qubership-actions[bot]`. The file in the repo is the
source of truth for the next version.

## Per-ecosystem behavior

- **Maven** (`${revision}${changelist}`, reusable `_maven-module-release.yaml`): releases the
  current `<revision>`; `release-type` sets the next dev `<revision>`.
- **micro-engine** is the exception. It publishes to GitHub Packages (`profile=github`, fixed in its
  wrapper) because its private `com.netcracker.cloud` dependencies are not on Central.
- **npm** (`npm version`, reusable `_npm-module-release.yaml`): bumps `package.json` by
  `release-type` and releases that. The cascade schemas → ui → vscode-extension is auto-`patch`.

## Branch protection

The bump commit is pushed with the runner token, so `github-actions[bot]` must be allowed to bypass
branch protection on the target branch. Without that bypass the next release of the module fails,
because its tag already exists. `scripts/commit-and-push.sh` does the commit and the rebase-retry
push, and both reusable workflows share it.

The optional `version` input overrides the computed version for edge cases such as a first release
or an explicit jump.

## Snapshot and dev publishing

On-demand, GitHub Packages only, with no tag, bump, commit, or GitHub Release:

- Maven → `snapshot-publish.yaml`, publishing the current `X.Y.Z-SNAPSHOT`.
- npm → `snapshot-publish-npm.yaml`, publishing an ephemeral `<next>-dev.<UTC-ts>` under the `dev`
  dist-tag, for `ui` and `vscode-extension`.

Both run from any branch, which is how you test unreleased branch code in a consumer.
