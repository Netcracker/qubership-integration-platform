# Product-pipeline CREATE quality gate

This harness verifies CREATE against one certified knowledge package. It has no FULL/SLIM runtime
selection.

## Commands

- `run-product-scenario.sh` drives one CREATE conversation through chat or A2A.
- `build-report-from-evidence.py` builds a report from the durable evidence endpoint.
- `assert-product-run.sh` checks package identity, terminal state, required facts, and catalog
  materialization evidence.
- `evaluate-plan.py` sends the approved plan and requirement facts to the semantic evaluator.
- `run-quality-gate.sh` verifies the package, starts the stack, and runs all active scenarios.
- `verify-knowledge-package.sh` checks that the sidecar serves the selected certified package.
- `test-quality-gate-offline.sh` and `test-live-runner-contracts.sh` run without network access.

## Live gate

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/ai-service-create-gate \
  --base-url http://localhost:8094
```

When `--evaluator-url` is omitted, the command starts the local evaluator with the `ai-e2e`
Compose profile. Use `--skip-deploy` when the AI service, sidecar, catalog, database, and evaluator
are already healthy.

Live execution requires LLM credentials in the local, ignored
`infrastructure/.env.local` file. The knowledge package directory is mounted read-only into the
sidecar. Created catalog chains are retained intentionally for inspection.

## Scope

Every active scenario uses `create-chain@2` and expects `CHAIN_MATERIALIZED`. The gate records
runtime failures separately from semantic evaluator results. Deployment packaging is outside the
scenario boundary. The inactive service-call scenario remains available as a fixture but is not
part of the live gate.

Set `PRODUCT_PIPELINE_STUB_MODE=1` to run the orchestration path without Docker or an evaluator.
Unknown command-line options exit with code 2.
