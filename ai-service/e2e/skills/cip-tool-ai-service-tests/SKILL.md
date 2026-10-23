---
name: cip-tool-ai-service-tests
description: >-
  Run AI service regression tests without an LLM and inspect saved failures in the local test UI.
  Invoke when the user asks for an AI service regression run or its results.
---

# AI service regression tests

Use the service workspace selected by the user. Run commands from its repository root.

1. Inspect available profiles with `python3 ai-service/e2e/run.py catalog`.
2. For ordinary regression, start `python3 ai-service/e2e/run.py start core-regression`.
   Use `service-full` when the user asks for all service tests. These profiles make no LLM calls.
3. Save the returned run ID. Start the local UI with `python3 ai-service/e2e/run.py serve` if needed.
   Show the user `http://127.0.0.1:8110/#<run-id>` immediately.
4. Read progress with `python3 ai-service/e2e/run.py status <run-id>`. Inspect saved native reports and logs.
   Cancellation uses `python3 ai-service/e2e/run.py cancel <run-id>`.
5. Report the verdict and verified boundary, then name failures and their evidence. Keep missing dependencies,
   missing reports, and skipped required work visible. A unit test pass does not prove a live catalog import works.

The runner owns process execution, history, and reporting. Do not create a second ledger or launch the same run twice.
Reruns create a new run ID; use `--parent <old-run-id>` to retain the link.

For diagnosis, write a JSON file with `status`, `summary`, and `evidence` paths relative to the saved run directory.
Use `UNKNOWN`, `HYPOTHESIS`, or `CONFIRMED`; confirmation requires saved evidence.
Append the diagnosis with `python3 ai-service/e2e/run.py diagnose <run-id> <failure-id> failure --file <file>`.
Use target `recovery` for the separate explanation of why recovery failed. Previous diagnoses and verdicts are preserved.

Chain acceptance, planner/executor, and live recovery belong to the separately installed skills testing bundle.
Use its test skill for those profiles. Missing bundles are reported as `BLOCKED`.

## Prepared Kimi recovery acceptance

Use `recovery-acceptance-check` to validate the locally prepared candidate without model requests.
Use `recovery-acceptance` only with explicit authorization for paid requests and `--allow-llm`.
For that profile, `--model kimi`, `--model ordinary`, or `--model both` selects the actual prepared provider.
Preserve the pinned launcher, frozen candidate, native proof rules, budgets, and earlier results.
Report registered-component recovery separately from full product recovery; viewing results does not prove product UI
or engine runtime behavior. See the prepared recovery acceptance section in the test-system reference.
