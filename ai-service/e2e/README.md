# ai-service end-to-end

CREATE and COMPARE_AND_PATCH acceptance tests live under `product-pipeline/`.
Isolated design-planner evaluations live under `planner-eval/`.

Run the [deployment and logging smoke test](deployment-logging-smoke.md) once per live browser campaign
on a retained HTTP chain. It checks the automatic logging question, chat reload, logging settings, and engine deployment.

For chat presentation changes, run the [value formatting smoke test](chat-value-formatting-smoke.md).
It checks creation results, patch review, and import cards for multiple specifications.

## Offline

```bash
ai-service/e2e/test-offline.sh
```

This command runs the live-runner contract tests and the stubbed quality gate without Docker,
network access, or a private knowledge package.

## Live

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/ai-service-create-gate \
  --base-url http://localhost:8094
```

The live gate requires the private `integration-platform-skills` directory and local LLM
credentials. See `product-pipeline/README.md` for the evidence and catalog-retention contracts.
