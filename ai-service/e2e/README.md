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

Build ai-service with `-Pwith-rocky-skills` before running CREATE/EDIT acceptance tests.
The private source package is supplied out of band; it is excluded from public builds.

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --runs 1 \
  --knowledge-package .private/rocky-knowledge \
  --report-dir /tmp/ai-service-create-gate \
  --base-url http://localhost:8094
```

The live gate uses the privately supplied `.private/rocky-knowledge` package and requires local LLM
credentials. See `product-pipeline/README.md` for the evidence and catalog-retention contracts.
