# ai-service end-to-end

Use [the shared test runner](test-system.md) for agent, terminal, and browser runs.
This directory contains the runner, results UI, regression tests, and acceptance drivers.

Run the [deployment and logging smoke test](deployment-logging-smoke.md) once per live browser campaign
on a retained HTTP chain. It checks the automatic logging question, chat reload, logging settings, and engine deployment.

For chat presentation changes, run the [value formatting smoke test](chat-value-formatting-smoke.md).
It checks creation results, patch review, and import cards for multiple specifications.

## Offline

Run the service regression suite without calling an LLM.

```bash
python3 ai-service/e2e/run.py run core-regression
```

## Live

See the [product-pipeline quality gate](product-pipeline/README.md#live-gate) for live prerequisites,
evidence, and catalog retention.
