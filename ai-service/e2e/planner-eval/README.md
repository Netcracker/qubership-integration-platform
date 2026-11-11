# Design-planner evaluation

Run `cip-design-planner` without the preceding CREATE stages or catalog writes. Each run retains
the request, every model response, format-retry diagnostics, the skill hash, and the model name.

Start the AI service with its normal model configuration, then run one case:

```bash
ai-service/e2e/planner-eval/run-planner-eval.sh \
  --case simple-http-script \
  --runs 3 \
  --report-dir /tmp/planner-eval
```

Omit `--case` to run all cases. The command writes `summary.json` and keeps each `request.json` and
`response.json` under `runs/<case>/rep-<n>/`. A completed response can still fail when it misses a
required pattern, contains a forbidden pattern, or emits too few occurrences of a required item.

Run the small high-risk edge suite first:

```bash
ai-service/e2e/planner-eval/run-planner-eval.sh \
  --tag smoke-edge \
  --runs 1 \
  --report-dir /tmp/planner-eval-smoke-edge
```

Run every curated edge case after the smoke suite is stable:

```bash
ai-service/e2e/planner-eval/run-planner-eval.sh \
  --tag edge \
  --runs 1 \
  --report-dir /tmp/planner-eval-edge
```

Use repetitions only for cases that pass once but may be nondeterministic. Start with three runs
of one case, inspect `summary.json`, and increase the count only when the observed failure rate does
not provide enough evidence.

Run the offline contract check without an LLM:

```bash
ai-service/e2e/planner-eval/test-offline.sh
```

When a live run exposes a defect, keep its request and attempt responses as evidence. Replay them
after defining the expected behavior; do not treat the observed response as the expected result
automatically. Commit a sanitized recording or a focused `CipDesignPlannerAdapter` test when the
case should become a permanent regression.

The case manifest supports these assertions:

- `requiredPatterns`: every regular expression must occur in the final plan;
- `forbiddenPatterns`: none of the expressions may occur;
- `minimumOccurrences`: an expression must occur at least `count` times;
- `expectedStatus`: defaults to `COMPLETED` and may be set to `FAILED` for negative cases;
- `maxAttempts`: limits model calls for the case; edge cases default to one attempt;
- `tags`: selects small suites through `--tag`.

Cases default to the `NORMALIZED_DESIGN_FLOW` runtime artifact. Set `inputArtifact` to
`IDS_DOCUMENT` on a case that intentionally tests the original IDS and its sequence diagram.

Replay one retained run through the current Java adapter without calling the model:

```bash
ai-service/e2e/planner-eval/replay-planner-run.sh \
  --request /tmp/planner-eval/runs/simple-http-script/rep-1/request.json \
  --response /tmp/planner-eval/runs/simple-http-script/rep-1/response.json \
  --expected-status COMPLETED \
  --required-pattern cip-script-generator
```

Set the expected status and patterns from the diagnosed behavior. The replay uses the recorded
attempt responses in order and fails when the current adapter no longer satisfies those checks.
