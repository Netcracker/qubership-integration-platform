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
are already healthy. `--skip-deploy` still recreates `qip-ai-service` (no rebuild) when the selected
scenarios set `QIP_E2E_RECOVERY_FAULT_CHAIN_PREFIX`, so the prefix reaches the running container.

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

`product-create-chain-recovery-exhausted-halt` uses the same injected fault and ends at an
escalated halt card instead of repairing. Typed recovery auto-reopens `design-planning` on the
first injection; the runner agrees that plan, implements again, and the second injection parks.
The follow-up then names a stage outside the candidate set. The default gate skips this scenario
so the live run keeps one recovery-fault prefix. Drive it in isolation:

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --scenario product-create-chain-recovery-exhausted-halt \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/ai-service-exhaust-gate \
  --base-url http://localhost:8094
```

Three manual scenarios cover delayed recovery beyond planning:

- `product-create-chain-recovery-design-input` injects a catalog identity mismatch during
  execution and expects an automatic reopen of `design-input`.
- `product-create-chain-recovery-materialization-execution` injects a pre-write contract-shape
  failure during materialization and expects an automatic reopen of `design-execution`.
- `product-create-chain-recovery-sequential` injects both defects in one run and checks that each
  failure reaches its own producer before the chain materializes.

They are excluded from the default gate because each one configures a different chain-scoped fault
plan. Select each scenario explicitly with `--scenario` and use one run initially. Live execution
sends the scenario prompt and generated pipeline artifacts to the configured external model
endpoint, so obtain explicit egress approval before starting these scenarios.

Run only COMPARE_AND_PATCH after the stack is up:

```bash
ai-service/e2e/product-pipeline/run-patch-scenario.sh \
  --scenario product-patch-chain-edit-script \
  --rep 1 \
  --base-url http://localhost:8094 \
  --report /tmp/ai-service-patch-gate/report.json
```

Run the uploaded OpenAPI path against an already running stack:

```bash
ai-service/e2e/product-pipeline/run-product-scenario.sh \
  --scenario product-create-chain-uploaded-openapi-mapping \
  --rep 1 \
  --base-url http://localhost:8094 \
  --evaluator-url http://localhost:8100 \
  --report /tmp/rocky-uploaded-openapi/report.json
```

The runner uploads `fixtures/rocky-orders-openapi.yaml`, passes its object key on the first chat turn, and answers
the returned `import-specification` card with the advertised artifact hash and revision. The catalog assertion
then verifies the imported operation IDs, `POST /orders`, the request mapping script, topology, materialization,
and reconciliation.

## Scope

Active CREATE scenarios use `create-chain@2`. Materializing scenarios expect `CHAIN_MATERIALIZED`.
The exhausted-halt scenario expects `WAITING_FOR_INPUT` at an escalated card and is skipped from the
default gate. Active patch scenarios use `compare-and-patch` and expect `CHAIN_PATCHED`. Patch runs
seed a small catalog chain, send one chat prompt per requested change (with the production
open-chain attachment), and apply the decision card. They do not use
`POST /api/v1/harness/chain-patch-run`. The LLM loads skill, addon, and example knowledge through
`ChainEditCompiler`, the same path as the browser.

The gate records runtime failures separately from semantic evaluator results. Deployment packaging
is outside the scenario boundary. Inactive requirement-flow duplicates are not part of the live
gate.

Set `PRODUCT_PIPELINE_STUB_MODE=1` to run the orchestration path without Docker or an evaluator.
Unknown command-line options exit with code 2.
