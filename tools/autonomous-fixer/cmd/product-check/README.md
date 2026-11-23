# Product test command capture

`product-check` runs a test command without a shell by default, saves its stdout, stderr, duration, and actual exit code, and checks the newly written Playwright or Jest JSON report. It never reuses a previous product result.

A zero exit code alone cannot pass the check. The report must be new, nonempty, parseable, and consistent with its individual tests and the child exit code. Its start time must fall within this invocation. An empty selection, missing named test, setup/runtime error, interruption, flaky result, or expected-failure annotation cannot establish a successful product check. Skipped tests do not count toward `--min-tests`, and a named required test must pass.

## Playwright

Use a new output directory for every invocation. The runner supplies `PLAYWRIGHT_JSON_OUTPUT_FILE`, `PLAYWRIGHT_JSON_OUTPUT_DIR`, and `PLAYWRIGHT_JSON_OUTPUT_NAME` for its fresh report. Select the JSON reporter explicitly so an existing reporter configuration cannot redirect the output.

```bash
product-check \
  --cwd /absolute/worktree/e2e \
  --output /absolute/evidence/browser-after \
  --report-format playwright \
  --min-tests 2 \
  --require-test 'the chains header uses the compact search layout' \
  --require-test 'an opened chain returns to its parent folder' \
  -- npx --no-install playwright test \
       --project=ui --no-deps --workers=1 --reporter=line,json \
       specs/ui/chains-list.spec.ts
```

The command uses the caller's existing product environment, dataset, and provisioning settings. It does not create a stack or select a source revision. The native worker must still identify the deployed source and use the contract's required product paths. Playwright's [JSON reporter options](https://playwright.dev/docs/test-reporters#json-reporter) describe the output environment variables.

## Jest

`{report}` in an argument is replaced with the absolute report path before execution; it is not shell expansion.

```bash
product-check \
  --cwd /absolute/worktree/ui \
  --output /absolute/evidence/jest-after \
  --report-format jest \
  --require-test 'renders the Chains heading' \
  -- npm test -- --json --outputFile={report} --maxWorkers=4 \
       tests/pages/Chains.test.tsx
```

A name matches the exact test title or full name and must identify one test. Use a full name when titles repeat. `--min-tests` also detects an unexpectedly small selection. These inputs belong to the accepted verification plan; a generic runner cannot infer which tests an issue needs.

## Expected negative checks

A baseline or mutation check can expect named assertion failures:

```bash
product-check \
  --cwd /absolute/worktree/e2e \
  --output /absolute/evidence/browser-before \
  --report-format playwright \
  --expect tests-fail \
  --expect-failed-test 'an opened chain returns to its parent folder' \
  -- npx --no-install playwright test --project=ui --no-deps \
       --workers=1 --reporter=line,json \
       --grep 'an opened chain returns to its parent folder'
```

This returns `expected-test-failure`, with the original nonzero child exit code and failed test count preserved.
The CLI exits zero only to report that the declared negative expectation was met; the product test remains failed.
At least one exact failing test name is required.
Every failed test must be named and carry assertion-shaped failure evidence.
Unexpected timeouts, launcher errors, unavailable fixtures, and unrelated failures do not satisfy the negative expectation.
Assertion wording identifies the reported failure mechanism, not its root cause: the worker still checks that the observed assertion is the intended defect rather than a bad setup.

## Measurement and expectation

The record distinguishes the observed test result from the command's requested expectation:

| Verdict | Meaning | Typical CLI exit |
|---|---|---|
| `tests-passed` | The report contains executed passing tests and agrees with the child status. | 0 for `--expect pass`; 1 when a negative expectation was not observed |
| `tests-failed` | A valid run contains actual assertion failures. The raw nonzero child exit remains recorded. | 1 |
| `expected-test-failure` | Every named negative assertion failed as expected, with no unrelated failed test. | 0, while the child exit remains nonzero |
| `rejected` | Missing, stale, inconsistent, incomplete, or non-assertion execution evidence prevents a test verdict. | 1 |

`expectation_error` records an unmet expectation separately from an invalid capture's `error`. A genuine assertion failure on the fixed candidate remains a typed `fail` for the controller's repair cycle; it is not converted into a protocol rejection.

The CLI prints an `observations` array for each valid capture. Each entry includes an immutable `id`, measured outcome, test title and full name, file, project, and capture paths. IDs come from the validated capture record and test index, so repeated titles in separate runs remain distinct.

A single invocation can cover a failing baseline defect and a passing healthy peer.
Copy the supporting IDs into each stage result's `observation_ids`, set `outcome` to `measured`, and leave `evidence` empty.
The controller supplies the measured outcome and proof paths: all selected tests passing establishes `pass`; any selected assertion failure establishes `fail`.
It preserves a failed baseline even when the adapter's negative expectation was met.
All valid failed product measurements in the final-verification stage catalog must appear in failed typed results, so selecting only passing tests cannot hide a failure.

The controller scans the current stage's capture records, validates report and log hashes, process status, report time, and paths, and excludes rejected captures from selectable observations. Unknown IDs and ambiguous legacy selectors can receive one declaration-only correction using existing proofs. Capture corruption remains an integrity failure. Archived responses with `product_tests` still use their original selector validation; new responses use observation IDs. IDs bind measurements precisely, but the worker must still choose tests that establish the accepted scenario.

The worker uses `product_check_binary` to point to an absolute executable. Its prompt receives the adapter recipes, while the saved invocation retains the original task context. Referenced product captures are checked against their archived report and log hashes, current-stage evidence paths, selected test outcomes, and typed result. The adapter cannot identify omitted captures or infer the framework used by an arbitrary raw command; its guidance requires Playwright and Jest claims to use the configured runner.

## Captured evidence and limits

The new output directory contains `result.json`, `stdout.log`, `stderr.log`, progress records, and `captured-test-report.json`. The result records the resolved command, child status, report summary, and hashes of captured logs and report. `--report` can name another absolute report path, but that path must not exist before the command starts. Existing reports and output directories are never deleted or overwritten by this runner. The default report is `OUTPUT/test-report.json`.

`--shell 'script'` is available only when a shell is needed. It executes `bash -o pipefail -c` and supplies `PRODUCT_CHECK_REPORT`. It is mutually exclusive with command arguments. Ordinary test commands need no `tee`: their output is already captured. A manually supplied `bash -c 'tests | tee ...'` can still mask the child pipeline status, but a failed JSON report with exit zero is rejected. The adapter does not intercept arbitrary native-model commands outside `product-check`.

Use the observation IDs printed by the CLI; the controller adds their capture result and raw report to the stage's evidence. The parent controller retains responsibility for candidate identity, accepted scenarios, fresh datasets, and evidence binding. This CLI validates recorded test execution; it does not prove product correctness, source attribution, or adequate coverage by itself. Static check caching remains a separate feature.

## Local validation

```bash
go test ./internal/checkrunner ./cmd/product-check
```

Tests cover actual assertion failures flowing to repair, mixed baseline selectors, all final failures remaining visible, a timeout with no named selectors, a real failure hidden by `tee`, explicit Bash `pipefail`, expected negative assertions, missing and stale reports, empty selections, required test names, report/count inconsistencies, and setup/interruption failures.
The Playwright fixture preserves the structure of an archived product run with image attachments removed; the Jest fixture follows the installed `@jest/test-result` JSON formatter.
These tests launch small local fixture processes, not the product or a model.
