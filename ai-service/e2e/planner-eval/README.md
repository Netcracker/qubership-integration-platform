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
required pattern or contains a forbidden pattern.

Run the offline contract check without an LLM:

```bash
ai-service/e2e/planner-eval/test-offline.sh
```

When a live run exposes a defect, keep its request and attempt responses as evidence. Replay them
after defining the expected behavior; do not treat the observed response as the expected result
automatically. Commit a sanitized recording or a focused `CipDesignPlannerAdapter` test when the
case should become a permanent regression.

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
