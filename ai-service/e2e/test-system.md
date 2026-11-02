# AI service test system

The agent, terminal, and local browser use one runner and one run directory. Native assertions stay in their
existing Java, Python, and campaign runners. There is no second result ledger.

## Start and inspect a run

From the repository root:

Use Python 3.12 or later on macOS/Linux, Bash, and the service's supported JDK (Java 21).
Maven must be on `PATH`. Python suites use the interpreter that starts the runner; install their
existing development dependencies in that environment. Missing dependencies are visible in the report.

```bash
python3 ai-service/e2e/run.py catalog
python3 ai-service/e2e/run.py start core-regression
python3 ai-service/e2e/run.py serve
```

`start` returns immediately with a run ID. Open `http://127.0.0.1:8110/#<run-id>` to follow it.
The UI can also start and cancel runs. It shows progress, runner output, native artifacts, failure episodes,
original diagnosis, recovery attempts, and the separate recovery diagnosis. The server binds only to loopback.
Restarting it reopens the same saved history; workers run independently of the browser.

```bash
python3 ai-service/e2e/run.py status <run-id>
python3 ai-service/e2e/run.py cancel <run-id>
python3 ai-service/e2e/run.py report <run-id>
python3 ai-service/e2e/run.py start core-regression --parent <previous-run-id>
```

Use `run` instead of `start` for a foreground process with a nonzero exit status on an unsuccessful verdict.
Use `--store <directory>` before the subcommand to select a different history directory. Runs in the same
workspace serialize because Maven reports and the running stack are shared.

The service agent entry is [cip-tool-ai-service-tests](skills/cip-tool-ai-service-tests/SKILL.md).
The chain entry is `testing/skills/cip-tool-chain-eval/SKILL.md` in the separate skills bundle.
Install the intended entry in your development agent's skill search path, or ask the agent to read that source file.
Neither belongs in the generation agent's context.

## Profiles and boundaries

| Profile                     | Owner         | LLM | What a pass proves                                                                        |
| --------------------------- | ------------- | --- | ----------------------------------------------------------------------------------------- |
| `core-regression`           | Service       | No  | Runner, imports, conversations, presentation, and recovery mechanisms                     |
| `service-full`              | Service       | No  | Full Java suite, knowledge sidecar, evaluator, and runner tests                           |
| `runner-self-test`          | Service       | No  | Runner and HTTP report contracts                                                          |
| `chain-offline`             | Skills bundle | No  | Corpus, generic driver, planner/executor, and golden comparison contracts                 |
| `chain-acceptance`          | Skills bundle | Yes | Existing product assertions and catalog readback                                          |
| `planner`                   | Skills bundle | Yes | Isolated planner contract                                                                 |
| `executor`                  | Skills bundle | Yes | Executor contract over saved planner input                                                |
| `generators`                | Skills bundle | Yes | Accepted element goldens and catalog readback; proposals cannot pass                      |
| `recovery`                  | Skills bundle | Yes | Native fault, feedback, budget, and consumer proof at the case's declared boundary        |
| `recovery-acceptance-check` | Skills bundle | No  | Prepared candidate, source, image, schema, settings, and proof-tool readiness             |
| `recovery-acceptance`       | Skills bundle | Yes | Registered recovery components and separate full product cases on Kimi, ordinary, or both |

Live profiles run the core regression gate first. Selecting a live suite retains its profile's mandatory gates.
A failed, blocked, or unexecuted gate prevents dependent model work. Missing packages leave service profiles available
and report chain profiles as `BLOCKED`. Missing dependencies also produce `BLOCKED`; missing or empty required reports
produce `ERROR`. Neither is a passing result.

The import boundary regression uses production MCP and catalog HTTP clients with local upstream fixtures.
It verifies multipart bytes, APIHub document transport, existing-group reimport, version selection, and mismatched
source rejection. It makes no model calls. It does not prove a deployed catalog or APIHub instance is healthy.

Live runs use the already configured provider and model. Arguments record the intended deployment; they do not
change its configuration. Select the intended test environment before enabling model calls:

```bash
python3 ai-service/e2e/run.py start chain-acceptance --allow-llm \
  --base-url http://localhost:8094 --model configured-model
python3 ai-service/e2e/run.py start recovery --allow-llm \
  --base-url http://localhost:8094 --model configured-model --image existing-service-image
```

The quality driver uses `--skip-deploy`. Its fault cases may still recreate the AI service container.
Recovery uses the existing isolated campaign driver and requires an explicit existing image. Generated catalog
objects and failed campaign resources follow those drivers' retention and cleanup rules. Inspect lifecycle artifacts
after cancellation; forcing a process to stop cannot certify that external teardown completed.

## Prepared recovery acceptance

The prepared launcher from the recovery campaign stays immutable. The common runner calls its native checks and
verifiers; it does not replace them with the generic recovery campaign. Install the testing bundle and a local
`.dev/recovery-acceptance.json` containing `launcher`, `launcherSha256`, `candidateSha256`, and `waveRoot`.
Paths resolve from the repository root. The launcher owns the candidate, frozen inputs, provider settings,
proof dependencies, isolated stores, and helper cleanup. Its local handoff lists the preparation dependencies.
Missing preparation is `BLOCKED`. Changed source, schemas, or package files require a new preparation.
Keep the old snapshot and hashes; do not edit them to admit another build.
The native `recovery_campaign.py` stays byte-identical to the frozen proof dependency. The common runner passes
`--cases` explicitly; its legacy default is a compatibility symlink to the external corpus.

```bash
python3 ai-service/e2e/run.py run recovery-acceptance-check
# Paid; run only after explicit authorization:
python3 ai-service/e2e/run.py start recovery-acceptance --allow-llm --model kimi
python3 ai-service/e2e/run.py start recovery-acceptance --allow-llm --model ordinary
python3 ai-service/e2e/run.py start recovery-acceptance --allow-llm --model both
```

The UI offers the same profiles. For this profile only, the model field selects `kimi` (default), `ordinary`, or
`both`; it changes the provider through the pinned launcher. Other live profiles keep using their configured service.
Kimi uses the existing OpenRouter/CoreWeave route and Responses transport. Normal budgets, output tokens,
recovery allowances, and provider fallback settings stay unchanged. Credentials remain in the existing local secret
store or container configuration and are never placed in the testing bundle.

The matrix checks requirement protection and read-loop recovery at registered-component boundaries, then a fresh
parcel-credit construction and its controlled HTTP-capture recovery variant through the full product API and catalog.
The two full cases share one business input. Component success does not prove full-chain recovery from either new
failure. The native verdict must retain this distinction and the open prose-only meaning-preservation gap.
`UI=NOT_RUN` and `engineRuntime=NOT_RUN` describe product execution; viewing this results UI does not change them.

Each common run gets a fresh native attempt. Native summaries, component receipts, original refusals, attempts,
model captures, product proofs, catalog readback, and script checks are copied into that run's artifacts.
A native failure or missing proof cannot pass because the command exited successfully. Reruns retain earlier failures.

## Ownership and distribution

The service manifest is [suites.json](suites.json). The external manifest is
`.private/rocky-skills/testing/suites.json`, using contract `qip-ai-tests/v1`. It can refer to service gates.
`--package <directory>` selects another installation; existing shell drivers also honor `QIP_SKILLS_TESTING_ROOT`.
The bundle records its target skills version. A version mismatch blocks its profiles.

For the customer layout, extract the testing archive under `integration-platform-skills/rocky-skills/` and pass
that package root explicitly. The archive contains `testing/`; do not extract it inside another `testing/` directory.
See the [package layout and installation guide](../README.md#skills-and-chain-tests) for the runtime/knowledge pair,
Python dependencies, and the development-agent skill entries.

```bash
python3 ai-service/e2e/run.py --package integration-platform-skills/rocky-skills catalog
python3 ai-service/e2e/run.py --package integration-platform-skills/rocky-skills run chain-offline
```

The service suite `evaluator-python` checks the service evaluator. The bundle suite `chain-evaluator-python`
checks the separately distributed evaluator used by chain campaigns. Their IDs are distinct so both can be loaded
by the same runner. The installed `testing/element-prompts/README.md` describes the original manual generation
requests, including `http-sender.md`; these requests do not register extra automated suites.

Live CREATE/PATCH scenarios, planner/executor inputs, checkpoint and recovery campaigns, generator cases, and goldens
have one canonical home in that bundle. Old service entry scripts remain as compatibility adapters. Service Java
mechanism tests use their own small fixture sets under `src/test/resources/executor-contract` and
`src/test/resources/work-checkpoint-contract`; the recovery classifier has a service-owned archived trace.
Changes to those fixtures test service contracts and do not change the external acceptance corpus.

The testing directory is outside runtime skill discovery and the POM's runtime resource include patterns.
Do not add it to the runtime manifest or model prompts. The existing `with-rocky-skills` runtime distribution is unchanged.
Ship `testing/` as a separate development archive and extract it under the intended skills package root.

## Evidence and diagnosis

Default history: `.dev/ai-test-runs/<run-id>/run.json`. Each task keeps its output log, native reports, and campaign
artifacts. The manifest records the service commit and working-change hash, test manifest hashes, skills version and
content hash, testing bundle hash, knowledge hash when installed, settings, timestamps, and parent run.
Model responses, actual model settings, pipeline digests, and image identities remain in native campaign evidence.
An unspecified model/image is recorded as unspecified.

Each failure has a stable suite/case/code fingerprint. Classification names the feature or failing stage; it does
not claim a root cause. Diagnoses start as `UNKNOWN`. A recovered failure stays in history. A native recovery pass
requires its verifier's proof and saved attempts; the adapter rejects unexercised faults and stub acceptance reports.
General product retries without native recovery proof remain unverified and cannot make their failure episode pass.

Create a diagnosis file, with evidence paths relative to the run directory:

```json
{
  "status": "CONFIRMED",
  "summary": "The imported source differs from the requested version.",
  "evidence": ["tasks/001-service-imports/reports/TEST-import.xml"]
}
```

```bash
python3 ai-service/e2e/run.py diagnose <run-id> F001 failure --file diagnosis.json
python3 ai-service/e2e/run.py diagnose <run-id> F001 recovery --file recovery-diagnosis.json
```

`CONFIRMED` needs saved evidence. Updates append the prior diagnosis and leave the verdict intact.
Reruns create new IDs. Logs mask common credential forms; native artifacts retain original evidence, so keep the
local store private and inspect artifacts before sharing them.

## Verification and remaining coverage

CI runs runner/API tests, recovery classifiers, and evaluator tests without a provider, alongside existing Java
and sidecar jobs. The local UI has browser regressions under `e2e/specs/ai-tests/`:

```bash
cd e2e
npx playwright test --project=ai-tests --no-deps
```

The browser cases exercise agent-started and manual runs, persistence, cancellation, rerun lineage, error rendering,
and separate recovery details against a real local runner with synthetic test commands. They need no product stack.
They make no product operation coverage claims in the four-service API registry.

Still needed for broader acceptance: independent file/APIHub imports against the deployed catalog, reimport after
service restart, multi-spec import cards and chat reload through the product UI, and negative authorization cases.
Element campaigns still lack checkpoint, JMS sender/trigger, Pub/Sub sender/trigger, RabbitMQ trigger, and SFTP
download/trigger/upload cases. Their absence is a coverage gap, not a pass. Live model comparisons and convergence
campaigns remain explicit runs; this infrastructure does not certify them from old reports.
