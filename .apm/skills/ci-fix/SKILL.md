---
name: ci-fix
description: Diagnose a failed GitHub Actions run in this repository and fix the cause — pull the failing log, reproduce the linter or build locally, and verify the fix before pushing. Use only when the user points at a red CI run, a workflow URL, or pasted CI output; do not auto-fire on unrelated turns.
---

# Fixing a red CI run

## Get the failing log

```bash
gh run view <run-id> --log-failed
```

When a run has many jobs, go job by job — `--log-failed` on the run mixes them
together and the useful line scrolls away:

```bash
gh run view <run-id> --json jobs -q '.jobs[] | select(.conclusion=="failure") | "\(.name) \(.databaseId)"'
gh run view --job <job-id> --log-failed
```

Logs carry ANSI colour codes that break `grep` patterns containing spaces. Strip
them when you save a log for repeated searching:

```bash
gh run view --job <job-id> --log-failed | sed 's/\x1b\[[0-9;]*m//g' > "$SCRATCH/ci.log"
```

## Route the failure to the right config

The workflows are per module (`<module>-build.yaml`) plus repo-wide ones, and
which file owns a linter is not obvious from the error text:

- **ESLint, Prettier, `tsc`, Jest failures** come from the module job in
  `ui-build.yaml` or `vscode-extension-build.yaml`, never from super-linter.
  `.github/super-linter.env` disables the TypeScript and JSX validators on
  purpose: the containerised ESLint cannot resolve each module's flat config,
  local plugins, or type-aware rules. Fix these by running the module's own
  `npm run lint` / `npm run test:unit` in its workspace.
- **YAML, shell, Markdown, Dockerfile, JSON, GitHub Actions, secrets** come from
  `super-linter.yaml`. Its behaviour is set by `.github/super-linter.env` and the
  per-linter configs in `.github/linters/` (`.markdownlint.json`,
  `.yaml-lint.yml`, `.stylelintrc.json`, `.gitleaks.toml`, `zizmor.yaml`,
  `.editorconfig-checker.json`, `.textlintrc`).
- **PR title and commit message rejections** come from
  `pr-conventional-commits.yaml` and `pr-lint-title.yaml`. These fail on the
  metadata, not the code — fix the title or amend the message.
- **Maven `[ERROR]` with 401, 403, or "Could not resolve"** on `micro-engine` is
  a credentials problem, not a code problem: it needs GitHub Packages auth for
  the private `com.netcracker.cloud` BOMs.

## Reproduce locally before pushing

CI round-trips are the slow part, so reproduce the exact check first:

```bash
npx stylelint <files> && npx prettier --check <files>   # from ui/
actionlint .github/workflows/<file>.yaml                 # if installed
```

`dotenv-linter` enforces alphabetical keys in `infrastructure/*.env`. Verify
without installing it:

```bash
cut -d= -f1 infrastructure/qip-dev.env | diff - <(cut -d= -f1 infrastructure/qip-dev.env | sort)
```

When the fix is a change to `FILTER_REGEX_EXCLUDE` in `.github/super-linter.env`,
compile the pattern and test it against real paths before pushing. It is one
long regular expression on a single line, and an invalid or over-broad edit either breaks the
job or silently stops linting files it should still cover:

```python
import re
rx = open('.github/super-linter.env').read().split('FILTER_REGEX_EXCLUDE=')[1].split('\n')[0]
pat = re.compile(rx)
for p in ['<path that must be excluded>', '<path that must still be linted>']:
    print(bool(pat.search(p)), p)
```

## Check that the failure is yours

Compare against a green run of the same workflow before changing anything — some
failures come from a shared config in `netcracker/.github` or from a flaky
external step, and editing this repository will not fix them:

```bash
gh run list --workflow=<file>.yaml --limit 10 --json databaseId,conclusion,headBranch
```
