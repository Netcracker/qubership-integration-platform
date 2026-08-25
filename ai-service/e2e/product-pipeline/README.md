# Product-pipeline quality gate

This harness verifies CREATE and COMPARE_AND_PATCH against one certified knowledge package. It has
no FULL/SLIM runtime selection.

## Commands

- `run-product-scenario.sh` drives one CREATE conversation through chat or A2A.
- `run-patch-scenario.sh` seeds an existing catalog chain and drives COMPARE_AND_PATCH through
  `POST /api/v1/chat` (SSE), answering each `apply-chain-patch` decision card.
- `build-report-from-evidence.py` builds a CREATE report from the durable evidence endpoint.
- `assert-product-run.sh` checks CREATE package identity, terminal state, required facts, and
  catalog materialization evidence.
- `assert-patch-run.sh` checks COMPARE_AND_PATCH terminal state and prompt counts.
- `evaluate-plan.py` sends the approved CREATE plan and requirement facts to the semantic evaluator.
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
sidecar. Created and patched catalog chains are retained intentionally for inspection.

The active `product-create-chain-recovery-revise-plan` scenario injects one validation failure at
`design-execution`, before catalog materialization. The runner submits the resulting feedback,
selects the typed `revise` action, verifies the causal reopen of `design-planning`, approves the
repaired plan, and then verifies catalog materialization and reconciliation. The quality gate
automatically scopes the disabled-by-default fault to this scenario's chain-name prefix.

Run only that recovery scenario:

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --scenario product-create-chain-recovery-revise-plan \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/ai-service-recovery-gate \
  --base-url http://localhost:8094
```

Run only COMPARE_AND_PATCH after the stack is up:

```bash
ai-service/e2e/product-pipeline/run-patch-scenario.sh \
  --scenario product-patch-chain-edit-script \
  --rep 1 \
  --base-url http://localhost:8094 \
  --report /tmp/ai-service-patch-gate/report.json
```

## Scope

Active CREATE scenarios use `create-chain@2` and expect `CHAIN_MATERIALIZED`. Active patch
scenarios use `compare-and-patch` and expect `CHAIN_PATCHED`. Patch runs seed a small catalog chain,
send one chat prompt per requested change (with the production open-chain attachment), and apply
the decision card. They do not use `POST /api/v1/harness/chain-patch-run`. The LLM loads skill,
addon, and example knowledge through `ChainEditCompiler`, the same path as the browser.

The gate records runtime failures separately from semantic evaluator results. Deployment packaging
is outside the scenario boundary. Inactive fixtures (Petstore CREATE, try-catch wrap patch, and
replace-subgraph patch) are not part of the live gate.

Set `PRODUCT_PIPELINE_STUB_MODE=1` to run the orchestration path without Docker or an evaluator.
Unknown command-line options exit with code 2.
