# ai-service end-to-end

Use [the shared test runner](test-system.md) for agent, terminal, and browser runs.
The service owns the runner, UI, regression tests, and mechanism tests. Chain acceptance cases,
planner/executor inputs, generator goldens, and the chain test skill ship in the separate
`.private/rocky-skills/testing` bundle. Generic drivers remain under this directory.

Run the [deployment and logging smoke test](deployment-logging-smoke.md) once per live browser campaign
on a retained HTTP chain. It checks the automatic logging question, chat reload, logging settings, and engine deployment.

For chat presentation changes, run the [value formatting smoke test](chat-value-formatting-smoke.md).
It checks creation results, patch review, and import cards for multiple specifications.

## Offline

```bash
ai-service/e2e/test-offline.sh
```

This compatibility command needs the skills testing bundle. It runs corpus contracts and the stubbed
quality gate without Docker, network access, or an LLM. Service regression works without the bundle:

```bash
python3 ai-service/e2e/run.py run core-regression
```

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
