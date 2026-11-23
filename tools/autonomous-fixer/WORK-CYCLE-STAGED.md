# Archived staged workflow

`cmd/work` implements the engineering process independently of the model-comparison campaign. The Go controller schedules each stage and review session. The model does the engineering work through ordinary local tools in the same checkout and product environment.

Set `task_kind` to `documentation` for the [documentation cycle](DOCUMENTATION-CYCLE.md). That policy can finish without a patch and does not require product datasets A/B. The default implementation cycle retains its existing checks.

The staged workflow runs locally and saves a checkpoint and a question when work needs a decision. To reproduce an earlier run, use its original source revision and select `workflow: "staged"`. New local engineering runs use the [`fix-issue` workflow](WORK-CYCLE.md). Engineering runs on GitHub Actions use the [hosted worker](GITHUB-ACTIONS.md).

## Process

1. Inspect the source and record a contract before changing source or tests. The contract contains observable scenarios, their consumers, baseline predictions, procedures, module checks, and risk directions.
2.
Reproduce those scenarios on baseline product code with data A.
Write the permanent regression test at this stage when it can demonstrate the defect.
The controller permits measured changes only to recognized test paths; application code, shared test support, and build configuration stay unchanged.
Carry the same regression test and baseline evidence into implementation.
Record measured outcomes even when they differ from the predictions.
The controller reconciles baseline labels and preserves the original declaration, evidence, required behavior, and procedures.
An observed behavioral failure differs from a build error or an unavailable environment.
3. Implement the contract and run affected existing tests early, before lengthy product validation. The controller then runs configured static checks before review. Failed checks return to implementation.
4. Run independent reviews in separate CLI sessions. Correctness is always required. Multiple consumers, shared code, or changes across modules add compatibility review. Contract risks and changed paths can add data, security, or concurrency review. File count alone does not determine the review plan.
5.
Return findings and their logs as a focused repair assignment, then review the repair delta and affected consumers.
A test-expectation-only repair runs affected tests without repeating unrelated browser or baseline work.
Production or product-test changes require validation of affected behavior.
Fresh final data B remains mandatory.
Correctness and directions with findings run again.
A clean specialized review can be retained when the only changes since it are tests or help documentation; production changes repeat all required directions.
The controller recalculates directions from the actual changed paths.
Repeating the same candidate and the same findings produces a question about the lack of progress; there is no fixed attempt-count limit.
6. After clean reviews, repeat every scenario on new data B in the same environment. Run required checks that the controller does not manage. Controller checks reuse successful results only when source, recipe, toolchain, environment configuration, and declared dependency inputs match. A behavioral failure returns to implementation. Missing results, malformed declarations, or a source change during a read-only stage reject the result.

Each reviewer receives the task, project guidance, contract, candidate identity, and assigned direction.
It receives no author conversation or author handoff.
A repair review receives the previous findings and changed paths, without earlier reviewer conversation logs.
The `profile` selects the model and effort for contract, reproduction, implementation, and final verification.
An optional `review_profile` selects a separate model and effort for every independent review session; it uses the same runtime binary.
Each stage records its selected profile in `profile.json`, and usage metrics account for the observed models separately.
The controller runs reviews sequentially; it does not ask a model to spawn its own reviewers.

The contract cannot silently lose a criterion or required check. Trusted input supplies exact criterion/consumer pairs, not two unrelated lists. Configured required checks belong to the controller: it retains omitted checks and replaces descriptive scopes with configured scopes while preserving the raw declaration and recording each adjustment. Duplicate check IDs are rejected. Added checks remain mandatory at final verification.

A reviewer can add coverage for a confirmed compatibility regression under an existing criterion/consumer pair.
Its finding must supply a complete scenario and separate baseline-pass and candidate-fail evidence from the same procedure and inputs.
The controller adds the scenario, records the adjustment, and sends the finding through repair, repeated review, and fresh final verification.
This validates the declaration and evidence provenance, not the truth of the comparison.
An undecided product behavior or a finding without this comparison still causes a saved question.
Verification-only resumption accepts a validated checkpoint blocked during final verification and preserves its original outcome.
It checks the same candidate and prior review evidence, then runs fresh verification without repeating implementation.

## Context

The packet always contains the complete root `AGENTS.md`, `.agents/skills/fix-issue/SKILL.md`, and `task.md`. The configuration explicitly selects required module instructions, skills, and task attachments. A referenced policy annex must be included in `attachments`; mentioning a path in prose does not include its contents. Selected files must exist, be nonempty, and stay within their declared roots.

The packet stores source names, full contents, and hashes. It is checked before and after each invocation. Nothing is shortened to fit an output budget. Codex receives the prompt on stdin. OpenCode receives the full prompt as a file attachment, avoiding the operating system's argument-length limit. These transport paths have local tests; they do not establish that a provider accepts every possible context length or that a model follows every instruction.

The controller's stage instructions adapt the existing `fix-issue` process: GitHub intake and delivery, internal delegation, and old fixed-attempt limits do not run inside a stage. Repository engineering conventions still apply. Required guidance should be selected before starting; extra reference material remains available in the checkout.

## Run configuration

Use a clean, dedicated worktree and an output directory outside both the checkout and task directory. Prepare the product environment first. The command does not reset the cluster, delete product data from earlier experiments, or provision a shared environment on the owner's behalf. The agent can build and deploy through the repository's normal tools.

This configuration is a template, not a selected model profile or an instruction to launch a run:

```json
{
  "workspace": "/absolute/path/to/dedicated-worktree",
  "task_directory": "/absolute/path/to/task-package",
  "output_directory": "/absolute/path/to/new-run-directory",
  "runtime_binary": "/absolute/path/to/codex",
  "runtime_version": "<exact output of codex --version>",
  "profile": {"Runtime": "codex", "Model": "<explicit-model-id>", "Effort": "<explicit-effort>"},
  "model_catalog": "/absolute/path/to/model-catalog.json",
  "login_file": "/absolute/path/to/subscription-login.json",
  "context": {
    "instructions": [
      ".apm/instructions/engine.instructions.md",
      ".apm/instructions/micro-engine.instructions.md",
      ".agents/skills/fix-issue/verification.md",
      ".agents/skills/fix-issue/k8s.md"
    ],
    "attachments": ["policy.md"]
  },
  "task_id": "example",
  "required_criteria": ["literal-value", "default-value"],
  "required_consumers": ["classic", "micro"],
  "required_coverage": [
    {"criterion_id": "literal-value", "consumer": "classic"},
    {"criterion_id": "literal-value", "consumer": "micro"},
    {"criterion_id": "default-value", "consumer": "classic"},
    {"criterion_id": "default-value", "consumer": "micro"}
  ],
  "required_checks": [
    {"id": "engine-checks", "scope": "engine"},
    {"id": "micro-checks", "scope": "micro-engine"}
  ],
  "environment_id": "allocated-local-stack",
  "environment": {"KUBECONFIG": "/absolute/path/to/allocated-kubeconfig"}
}
```

The trusted caller supplies the required coverage and checks from the task and project policy. The model may add scenarios and checks during the contract stage, but cannot remove required coverage. Runtime profiles have no default winner or automatic fallback. The native adapter verifies the runtime version before every invocation.

Codex engineering stages receive a strict response JSON schema. The controller binds each synchronous response to the invocation snapshot and checks the source before and after it. The model's candidate-ID echo is retained for diagnostics; it is not the source-identity authority.

Engineering declarations allow one bounded deterministic correction pass, with no model call or product command. A single JSON Markdown fence can be removed when it contains exactly one valid response object and no surrounding prose. The raw response and normalization error remain saved.

An evidence path with a mistyped prefix can use an existing file with the exact suffix under the current invocation's evidence directory.
A missing aggregate summary can instead refer to a new `controller-declaration-index.json` when the handoff includes that reference, has a nonempty summary, and every typed measurement reference is valid.
This restricted case accepts `.json` or `.txt` summary/observations filenames; missing images, unknown report types, and measurement references remain errors.
The index labels the original outcomes as claims, records existing proof hashes and the missing references, and explicitly declares that it adds no measurement.
It never substitutes for scenario, check, finding, or question evidence.

The controller preserves the original declaration and error, records corrections, and verifies that the candidate and existing evidence stay unchanged. It never overwrites an existing index or copies a previous stage's summary. Missing measurements, an empty proof set, invalid JSON, and invalid corrections remain protocol errors without a Jev call. Required coverage, independent review, and fresh final verification still decide the outcome.

Configure `product_check_binary` with a built `cmd/product-check` executable to capture Playwright and Jest runs. It preserves the child exit status and validates a fresh framework report. A failed assertion remains a measured failure that can return to repair; missing reports, setup errors, and conflicting exit statuses cannot establish a result. An expected baseline failure is distinct from a passing fix. See [product-check](cmd/product-check/README.md).

`product-check` returns generated observation IDs for the tests in its report.
A result names these `observation_ids`, uses `outcome: "measured"`, and leaves its measurement `evidence` empty.
The controller supplies the proof paths and measured pass/fail outcome.
Repeated test titles in different captures have different IDs.
Archived `product_tests` selectors remain readable; new declarations use IDs.
A passing healthy scenario and a failing defect can share one capture without sharing an outcome.
Final verification accounts for every valid failed product measurement in the stage catalog, including captures omitted from the model's response.
Rejected attempts can remain diagnostic attachments but cannot establish a scenario result.

An unresolved binding with intact measurements gets one declaration-only correction.
Its input contains the contract, unresolved result IDs, and the catalog.
Its schema permits only existing result and observation IDs.
Already resolved results, manual checks, criteria, source, datasets, environment, and measured outcomes cannot change.
The Codex adapter uses a separate metadata working directory, read-only access, disabled tools and delegation, and rejects tool events.
This correction does not run product commands or repeat an engineering stage.
Other runtime adapters do not receive an unrestricted correction fallback.

The original model response stays in `response.json` and `response-raw.txt`. `response-before-resolution.json` retains the original bound declaration; `response-bound.json` records the effective controller result. An evidence-bound resolution record covers both declarations and the correction archive. Verification resumption checks those identities before invoking a model. `work-metrics` includes correction usage and duration separately from engineering stage counts. A failed correction preserves the rejected checkpoint and evidence; it does not claim success or automatically rerun the task.

Each invocation has a fresh model HOME and session. Build caches are shared across the cycle; `MAVEN_ARGS`, `npm_config_cache`, `GOCACHE`, and `GOMODCACHE` can point to prepared dependency caches through `environment`. Product data and the product environment remain shared throughout the cycle. A fresh HOME is not filesystem isolation: the agent has ordinary host access.

When a new live run is explicitly wanted, invoke the command with the completed configuration:

```bash
go -C tools/autonomous-fixer run ./cmd/work /absolute/path/to/config.json
```

This command contacts the configured model. Local validation of this implementation uses test executables instead and does not require model credentials or a product stack.

## Results and limits

The output directory contains the context packet, redacted configuration, per-stage prompts and runtime logs, model-catalog identity, parsed declarations, evidence hashes, and checkpoints. Credentials copied into an invocation HOME are removed when that invocation returns. The final `report.json` distinguishes `verified`, `rejected`, `needs-human`, and `verification-blocked`.

Source snapshots include tracked and untracked nonignored files and executable modes.
Contract, review, and final verification must leave them unchanged.
Reproduction may change recognized regression-test files while product sources remain at baseline; unknown deltas are rejected.
Each result is bound to the snapshot observed by the controller.
Native results must reference new evidence files in that invocation's directory.
Controller-managed static results retain their original output hashes and a new reuse record.
Earlier product scenario logs cannot stand in for fresh dataset B measurements.
Recorded evidence cannot be overwritten in a later stage.
A verified result also writes `receipt.json` with context, report, source, and evidence identities.

These checks establish process completeness and provenance. They cannot prove that a log describes the right product behavior, that a declared environment ID identifies the actual deployed services, or that every possible regression has been tested. Scenario design, real product measurements, and independent review remain necessary.

The publication manifest validator separately requires a controller-owned expected policy: exact review directions, criterion/consumer scenarios, required checks, context and process-report digests, and the current candidate and test-set identities. A model's `passed` field cannot supply that expected policy. Connecting a local receipt to verified GitHub artifacts and a live publisher remains part of the Actions implementation.

## Validation

```bash
go -C tools/autonomous-fixer test -race ./...
go -C tools/autonomous-fixer vet ./...
go -C tools/autonomous-fixer build ./cmd/work
```

The subprocess test exercises contract, baseline, implementation, a review finding, correction, repeated review, and final verification. Other tests retain configured checks, reconcile baseline observations, exercise compatibility repairs, and reject missing coverage, omitted final check results, changes during verification, reused evidence from an earlier candidate, context drift, repeated unchanged findings, and invalid publication declarations. No new product end-to-end case is needed: module tests reach the controller behavior, and the product code is unchanged.

## Author handoff and diagnostic continuation

Author stages return a `handoff` with successful commands, deployment identities, measured results, and current-stage evidence.
Structured `recipes` include `purpose`, executable `argv`, an absolute `working_directory`, and required `environment_keys`.
They contain no credentials and are not executed automatically.
Deployment notes identify service addresses and preview ownership so the next stage can check liveness before reusing them.
The next author stage receives the latest handoff from each author phase.
Independent reviewers receive no author handoff.
Each native invocation still uses a fresh HOME; this explicit handoff works across runtime adapters without resuming a provider-specific session.

A `verification-blocked` question can receive one additional pass in the same stage to perform the missing observation. With Jev enabled, a strong `observable_probe` decision is required. Without Jev, the controller permits that one investigation pass. An unresolved question remains recorded. `needs-human` decisions never authorize a product choice, and the extra pass follows the same source policy and cannot change criteria or required checks.

After the initial contract, Jev can identify a known consumer missing from its scenarios. The author gets at most one contract revision to inspect that suggestion before reproduction. Required criteria and consumer coverage still pass the same validation.

## Managed static checks

Configure `static_checks` with recipes whose `id` and `scope` match required checks.
Each recipe supplies `command` as an argument array, a workspace-relative `cwd`, `dependency_roots`, optional `input_files`, and `toolchain_commands` that report the actual tools used.
Include installed dependencies and external scripts in the declared inputs.
Set source_modules to the source modules reached through npm workspace links.
Their source is covered by the candidate snapshot; generated library inputs such as schemas/dist must be listed in input_files.
Coverage reports and other output directories are not dependency inputs.
Product scenarios and checks that depend on mutable service data must remain outside this list.

The runner saves stdout, stderr, exit code, duration, input identity, and output hashes under `evidence/check-*/`. A source snapshot covers tracked and nonignored source, test, and configuration files. Dependency inputs are hashed, including linked packages. Toolchain output, executable bytes, command recipe, and environment configuration contribute to the input identity. Changed inputs or failed results require execution. Final verification checks these identities again; reused checks do not run another model session.

`cmd/work-check` also executes one explicit recipe with `--recipe`, `--workspace`, `--candidate`, and `--evidence`. Its caller supplies the source identity; the integrated worker computes it from the workspace. The metric collector reports static executions, reuses, and elapsed time separately from model time and API-equivalent charges.

## Runtime completion and progress

A recovered Codex `Reconnecting...` event is retained in `runtime-events.json`. It permits completion only when a later final message and `turn.completed` appear in the correct order. Fatal errors, incomplete streams, malformed declarations, and invalid evidence still fail validation.

Each stage writes `progress.json` and `progress.jsonl`; `runtime-progress.json` contains the latest stage. A heartbeat records the last event, output, active command, command exit, and idle duration even when the runtime is silent. Linux process-group CPU and state samples provide additional observations. A live PID, a waiting command, or unchanged CPU alone does not establish useful progress. No idle timeout terminates the task automatically. Wall-clock durations remain separate from process duration so long gaps remain visible.

## Keep the UI preview across stages

An operator can opt into `E2E_UI_PREVIEW_OWNER_PID` when several serial stages use the same checkout. The owner must be a separate process that stays alive until the controller exits. A short setup command's PID does not provide that lifetime.

The suite adopts only a recorded preview whose owner matches that live PID and whose process group still runs the preview command. Its teardown leaves the preview running. A stale bundle is rebuilt and the preview restarted under the same owner. A dead or mismatched owner, an unrelated process, or an unrecorded listener fails setup instead of silently changing ownership. Without this variable, setup and teardown keep their existing behavior.

Put the PID in `cfg.environment` as well as the operator's environment. The native adapter does not inherit arbitrary `E2E_*` variables from its parent. This executable Node 22 operator pattern keeps the owner alive through the Go controller and releases its preview in `finally`. Save it as `preview-owner.mjs` and run it only when a new model run is explicitly authorized:

```bash
node --experimental-transform-types preview-owner.mjs /path/to/config.json /path/to/work
```

```js
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { registerHooks } from "node:module";
import { pathToFileURL } from "node:url";

registerHooks({ resolve(specifier, context, next) {
  try { return next(specifier, context); }
  catch (error) {
    if (specifier.endsWith(".js")) return next(specifier.slice(0, -3) + ".ts", context);
    throw error;
  }
} });
const [configPath, controller] = process.argv.slice(2);
if (!configPath || !controller) throw new Error("Expected config and controller paths");
const cfg = JSON.parse(fs.readFileSync(configPath, "utf8"));
const owner = process.pid;
cfg.environment = { ...cfg.environment, E2E_UI_PREVIEW_OWNER_PID: String(owner) };
Object.assign(process.env, cfg.environment, { CIP_REPO_ROOT: cfg.workspace });
const { ensureUiServer, releaseUiServer } = await import(
  pathToFileURL(path.join(cfg.workspace, "e2e/support/ui-server.ts")).href
);
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "qip-preview-owner-"));
const effectiveConfig = path.join(temporary, "config.json");
fs.writeFileSync(effectiveConfig, JSON.stringify(cfg), { mode: 0o600 });
let child;
let canceled = false;
let acquired = false;
const cancel = () => { canceled = true; child?.kill("SIGTERM"); };
process.on("SIGINT", cancel);
process.on("SIGTERM", cancel);
try {
  await ensureUiServer();
  acquired = true;
  if (canceled) process.exitCode = 130;
  else {
    child = spawn(controller, [effectiveConfig], { stdio: "inherit", env: process.env });
    process.exitCode = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("close", code => resolve(code ?? 1));
    });
  }
} finally {
  try { if (acquired) await releaseUiServer(owner); }
  finally { fs.rmSync(temporary, { recursive: true, force: true }); }
}
```

The original config stays unchanged. A controller failure or signal still reaches final cleanup after the child exits. Do not call the release function from a suite teardown, and do not run two suites concurrently under one owner.
