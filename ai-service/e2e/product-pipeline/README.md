# Product-pipeline quality gate

Use [the shared runner](../test-system.md) for agent and UI runs. Live cases and fixtures are supplied
by `.private/rocky-skills/testing/product-pipeline`; the scripts here read that development bundle.

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
- `check_element_test_coverage.py` fails when a `SUPPORTED` element in the generated support matrix
  has no generator-regression case or active product-pipeline scenario.

Check declared live-test coverage after generating the support matrix:

```bash
python3 ai-service/e2e/product-pipeline/check_element_test_coverage.py
```

Add `--fail-on-known-failure` when the gate should also require every supported element to have a
non-failing live scenario.

The default gate includes synchronous and asynchronous split scenarios. They verify branch-shell
containment and catalog materialization for `split-2` and `split-async-2`.

## Scripted recovery checks

Run the recovery gate without a model provider:

```bash
bash ai-service/e2e/product-pipeline/run-recovery-deterministic.sh
```

The test-only `ScriptedStreamingChatModel` supplies a finite sequence of tool calls, text, or provider errors.
Every turn checks the actual incoming `ChatRequest` before responding. A missing turn, unexpected call, or
request mismatch fails the script, including a mismatch swallowed by an asynchronous caller.
The scripted provider is only in `src/test/java`; it cannot be selected by the deployed service or live campaign.

`ScriptedRecoveryTest` runs the native compiler runtime, HTTP capture agent, generated argument mapper,
capture policy, element schemas, and graph consumer. Only model inference and external knowledge/storage
dependencies use local fixtures. The ordinary retry settings come from `AppConfig`; a recording sleeper
checks delays without waiting. No capture tool, validator, or successful repair result is mocked.

`ScriptedPatchRecoveryTest` checks element patches through the actual next `ChatRequest` and native graph consumer.
It covers nested paths, conditional required properties, six sequential corrections, and shared enums with 160 values.
Production schema checks cover ABAC and RBAC; large-enum stress checks use a test-only schema resource.
Current errors retain their complete constraints and rejected values. Shared constraints use local JSON references;
compact history retains issue identities and progress without duplicating those constraints.

Source repair checks revalidate authored mappings before either successful discovery handoff.
New mapping facts need a matching required target in native recovery evidence; accepted facts remain protected.
Script-literal checks distinguish source metadata from generated code through structured diagnosis and scoped admission.
Source corrections retain their draft lineage and require a new brief approval. Native checks still require actual
quote characters when they belong to the requested value.

| Check | Required result |
| --- | --- |
| Malformed JSON, wrong types, missing fields, unknown fields, and duplicate keys | Native rejection reaches the next model request; corrected capture reaches the graph consumer |
| Unknown semantic reference or incomplete trigger set | Ordinary admission rejects it before the corrected proposal is accepted |
| Sequential distinct defects | Each correction preserves the approved endpoint, existing script, and execution edges |
| Same defect with different invalid IDs | Configured repair allowance is not reset; no accepted capture or graph mutation |
| A model claims success without calling capture | The compiler step fails |
| Mixed 429 and transient failures followed by invalid tool arguments | Native retries preserve the request; tool admission and consumer continuation remain mandatory |
| Requirement summary/input errors and attempts to overwrite approved facts | The real brief tool rejects the candidate; accepted output preserves the selected draft and reaches handoff |

`ProductAnalysisRepairLoopTest` also checks the stateless requirement agent and its native brief handoff.
Argument failures retain their detailed stored rejection and use the ordinary JSON repair instruction;
semantic failures include their native diagnostic in the repair request.
The gate reuses model-wrapper and budget tests for exhaustion, pre-output retries, and refusal to replay
after streamed text, thinking, or tool-call output. Expected stops are safety checks, not successful recovery.

Add a scenario as named `Turn` values in a module test. Assert the initial input, the native rejection store,
the feedback delivered to the next request, and the actual accepted artifact and consumer output.
Keep expected repairs in test data. Do not add a production branch that recognizes the scenario.
No additional root e2e case is needed: these checks exercise module behavior without a running platform stack.
The existing lifecycle e2e case continues to cover container and storage isolation.

Scripted checks do not establish that a real model can find a correction. Independent real-model acceptance
remains mandatory, with ordinary limits and the checkpoint/completion evidence described below.
The held-out campaign and variation generator remain separate planned work; the current live pilot does not
satisfy that independent acceptance requirement.

## Native PLAN recovery proof

`native_plan_proof.verify_plan_recovery(evidence, rejected, graph_row)` checks a saved native PLAN refusal from
`captureChainSemanticRevision`. It joins the complete immutable feedback to the first next provider request, the
owning native invocation, its adjacent successful commit, and the exact approved semantic output consumed by the graph.
It checks the whole semantic payload hash and provenance, without duplicating the semantic adapter.
Missing or unsupported evidence fails closed. The caller must still verify budgets, source requirements, the consumed
validated bundle, catalog readback, and generated behavior. This helper proves artifact identity and continuation;
it does not establish independent business equivalence or grant permission to change requirements.

Run its controls with the existing offline Python recovery checks:

```bash
python3 -m unittest discover -s ai-service/e2e/product-pipeline -p 'test_recovery_*.py'
```

## Control-wave checkpoint: October 22, 2026

Six real-model runs used service commit `29775de52dc0116f3d5dc26b26f23fb16ff9a625`, unchanged knowledge, and existing
limits. Ordinary requests used `gpt-6-luna`; comparison requests used `gpt-4o-mini`. No extra allowance was granted.

| Case | Ordinary model | gpt-4o-mini |
| --- | --- | --- |
| Five-node OM to Salesforce | Materialized; original proof FAIL for unsupported PLAN feedback. Separate persisted Script checks: 30/30 PASS. | FAIL before a graph; partial progress, invalid diagnosis, then a local diagnostic timeout. |
| HTTP and local Script | Full local acceptance PASS; 22/22 Script checks. Recovery was not exercised. | FAIL before an accepted draft; incompatible business-interaction/capability interpretation, then a local diagnostic timeout. |
| Original internal HTTP/NONE regression | Full local acceptance PASS; 20/20 Script checks and one native requirement repair. | FAIL at repeated rejected output and the recovery-allowance card. |

Original frozen verdicts remain immutable. A versioned PLAN checker assesses saved traces separately; its verification
and replay receipt is recorded with the local wave evidence. No new model run is implied by a replay.
After the PLAN proof correction, separate assessments of the same saved traces pass all three ordinary cases
(30 + 22 + 20 persisted Script effects); all three mini cases remain FAIL. These are post-hoc assessments, not fresh
model samples. The original Restock FAIL is retained. Final verification passes 86 Python tests, 37 scalar controls,
101 inherited proof controls, and 30 local Camel calibrations; independent review is clean.
Two additional Docker subnet-pool startup failures occurred before any provider request and are infrastructure-only.

Open issues are unsupported automatic semantic reinterpretation, ineffective repeated edits, diagnosis validation and
local deadline/cancellation behavior, and token-reservation settlement after interruption. Native structural validity
also does not establish complete generated Script behavior. Automatic completion remains NO-GO on the failed cases.
Catalog-write recovery stays in the previously agreed backlog. New skills, Kimi, browser UI, and full integration runtime
were not exercised in this wave. Kimi comparison is the next separately configured step, with these failures visible.

Detailed local evidence and the complete baseline pins are in `.dev/ai-stabilization-pre-kimi-20261021/`;
`ai-service/docs/analysis/2026-10-22-recovery-control-wave.md` records results, proof boundaries, and open issues.
Both locations follow the repository's ignored local-report convention; neither is force-staged.

## Isolated recovery pilot

`recovery_campaign.py` tests automatic recovery with the ordinary configured model and limits.
The pilot covers an HTTP reference defect, its full-completion counterpart, and a missing-route control.
Independent cases can also use `http-exposure-type`: after a genuine model call passes ordinary argument
normalization and schema checks, the test changes the first endpoint's boolean `externalRoute` to a string.
The ordinary schema boundary must reject it. Its exact stored rejection reference and native issue must
precede the accepted model correction and consumer checkpoint. The test supplies no repair response.
Independent cases can use `element-property-values` to exercise element schema refusals:

```json
{
  "kind": "element-property-values",
  "applications": 1,
  "propertyValues": {"logLevel": "unsupported"}
}
```

The control replaces the first matching property value for each declared key after the original patch passes
native validation. It runs once in the owned instance and conversation. Native admission must reject the changed
patch, and its stored proposal must match the observed fault exactly. Every native issue, including the complete
schema constraint and rejected value, must reach the first subsequent provider request. The oracle resolves local
constraint references before comparing data. A corrected model capture and a later consumer checkpoint are required;
full cases also require catalog materialization and readback. Model, retry, context, and time limits stay unchanged.

An optional `checks.edgeTypes` lists the exact directed type pairs for a case with distinct element types;
this supports held-out topologies without assuming a direct trigger-to-script edge.
`checks.forbiddenTools` lists prohibited tool names. It checks completed stream tool calls and response tool calls
in provider evidence. Mentions in negative requirements or available tool schemas do not count as calls.
Use these checks with exact graph requirements when prohibiting external integration activity.
The canonical request uses the recommended RBAC policy with the explicit role `qip-viewer`.
This keeps policy-exception confirmation separate from recovery of a generated semantic reference.

The runner allows revision-bound approvals, the advertised ready-draft continuation, and the fixed IDS choice.
It supplies a control answer only after the declared missing value is requested. A complete case cannot receive
additional facts, a repair hint, Retry, rebuild, or extra credit.

Build the candidate image and the local read-only evidence exporter from the repository root:

```bash
mvn -f ai-service/pom.xml -Dgpg.skip=true -Dtest=HttpTriggerCaptureToolTest,HttpTriggerCaptureAdapterTest,E2eRecoveryTestControlTest,RecoveryEvidenceProbeTest,MaterializationCapabilityTest,ProductPipelineRunResourceTest package dependency:build-classpath -Dmdep.outputFile=target/recovery-probe-classpath.txt
docker build -t qip-ai-recovery:pilot ai-service
python3 ai-service/e2e/product-pipeline/recovery_campaign.py lifecycle-check --image qip-ai-recovery:pilot --out /tmp/recovery-lifecycle
python3 ai-service/e2e/product-pipeline/recovery_campaign.py pilot --image qip-ai-recovery:pilot --out /tmp/recovery-pilot
```

The ordinary local AI service, PostgreSQL, MinIO, catalog, and knowledge sidecar must already be running.
The harness reads the AI service's runtime environment, uses a private temporary environment file, and removes
that file after teardown. Credentials do not belong in campaign artifacts. Override the baseline service with
`--baseline-container` when it has a different name. Each attempt needs a new output directory.

Each case owns a separate Compose project, database, storage volume, bucket, and localhost ports.
Its private services join the shared network only for ordinary catalog and knowledge dependencies.
The harness checks for shared mounts and changed shared process identities. It never recreates the baseline.
The `lifecycle-check` command uploads a marker through the storage API, stops the owned AI service, reads the
marker through the read-only exporter, and proves that no model budget was created.

The fault replaces one actual `captureHttpTriggers` semantic reference before native admission.
Passing requires the persisted native rejection, a subsequent accepted model/tool proposal, preserved author
requirements, and the exact graph revision admitted by materialization. The focused instance parks after native
bundle and endpoint checks, before its first write. Evidence is exported before the owned instance stops.
A full case additionally requires materialization, reconciliation, and catalog assertions over real readback.

Artifacts include the frozen case, submitted scenario, nonsecret model settings, source/image fingerprints,
interactions, SSE, durable state, pipeline/capture histories, fault observations, native model accounting,
catalog readback for full runs, and a verdict. Capture compilation IDs can differ from the pipeline run ID.
Failed attempts retain stopped stores; their project identities are recorded in `retained.json`.
Earlier failed attempts remain failures when a later attempt succeeds.

Check an archived case without calling the model:

```bash
python3 ai-service/e2e/product-pipeline/recovery_campaign.py check --cases /tmp/recovery-pilot/cases.json --case http-reference-checkpoint --evidence /tmp/recovery-pilot/http-reference-checkpoint/evidence.json
python3 -m unittest discover -s ai-service/e2e/product-pipeline -p 'test_recovery_*.py'
```

`CHECKPOINT_PASS` proves recovery and consumer continuation; it does not prove completed construction.
`COMPLETION_PASS` also proves the catalog result. Other outcomes are `RECOVERY_FAILURE`, `PUBLICATION_FAILURE`,
`NOT_EXERCISED`, and `INFRASTRUCTURE_FAILURE`. A model success statement or an accepted transport retry is insufficient.
The lifecycle stack check is `e2e/specs/env/ai-recovery-lifecycle.spec.ts`; it sends no model requests.
Set `CIP_RECOVERY_AI_IMAGE` when running that check against a candidate image.
The variation generator, transport fault matrix, and complete 18-run acceptance campaign follow this pilot.
An independent wave over the implemented HTTP boundaries does not satisfy missing families in that campaign.

The retained [missing-route regression](test-fixtures/recovery/missing-route-repeated.json) records a stage
that accepted the author's path and then requested it again at a newer revision. Its
[native trace](test-fixtures/recovery/missing-route-repeated-evidence.json) remains a failed recovery.
Replay that case with the ordinary model after a separately approved service fix:

```bash
python3 ai-service/e2e/product-pipeline/recovery_campaign.py run --cases ai-service/e2e/product-pipeline/fixtures/recovery/regressions/missing-route-repeated.json --case missing-route-answer-repeated --image qip-ai-recovery:pilot --out /tmp/recovery-route-regression
```

## Live gate

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --runs 1 \
  --knowledge-package .private/rocky-knowledge \
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
  --knowledge-package .private/rocky-knowledge \
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
  --knowledge-package .private/rocky-knowledge \
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
