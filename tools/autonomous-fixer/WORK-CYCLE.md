# Local engineering workflow

The default local workflow, `fix-issue`, uses the repository's skill of the same name. One author runs the tests and delegates native reviews. The assignment includes the configured criteria, consumers, scenarios, and check commands alongside the task file. Jev runs separately during pre-ready triage and documentation review.

Local work uses Codex. For engineering runs on GitHub Actions, set `AGENT_MODE=hosted` to run OpenCode through OpenRouter and publish the draft PR from a separate job. See [Engineering on GitHub Actions](GITHUB-ACTIONS.md).

Set `task_kind` to `documentation` for the [documentation cycle](DOCUMENTATION-CYCLE.md). To reproduce an archived engineering run, use its original source revision and see the [staged workflow](WORK-CYCLE-STAGED.md) if the run selected `workflow: "staged"`.

## Run configuration

Use a clean, dedicated worktree, a task directory containing `task.md`, and a new output directory outside both. Set absolute paths for `workspace`, `task_directory`, `output_directory`, and `runtime_binary`. Use a native Codex executable and set `runtime_version` to its version. Provide the author profile, subscription login, and product environment. The following fragment explicitly sets the reviewer profile to the default used in the `fix-issue` workflow.

```json
{
  "workflow": "fix-issue",
  "profile": {"Runtime": "codex", "Model": "gpt-6-luna", "Effort": "high"},
  "review_profile": {"Runtime": "codex", "Model": "gpt-6-luna", "Effort": "xhigh"}
}
```

Launch a prepared job with:

```bash
go -C tools/autonomous-fixer run ./cmd/work /absolute/path/to/config.json
```

The author saves its outcome and evidence locally. `author-reported-fixed` records the author's assessment.

## Legacy `agent` workflow

Select `workflow: "agent"` to reproduce the controller-managed local workflow.

1. The author receives the task, complete selected project instructions, trusted criteria and consumer requirements, and the allocated environment. It chooses how to investigate, reproduce beside a healthy peer, implement, and test. It can reference original issue images, attachments, prior notes, and preflight records. Those sources establish context; they do not establish a new measurement.
2. The controller runs configured managed static checks. A failed check returns its logs to the author. Successful checks can be reused when their source, toolchain, configuration, and dependency inputs match.
3. Independent reviewers inspect the resulting candidate. Correctness is mandatory. Consumer count and changed paths select additional review directions. Reviewers run serially in fresh sessions with the configured review profile; they do not resume the author or receive its private reasoning. Their final disposition contains only `decision`, `summary`, and `findings`.
4. Confirmed findings return to the same author session as a compact follow-up. The task and requirements stay fixed. For each repeated review, the controller supplies its previous findings and the paths changed since that reviewer last assessed the candidate. A clean specialist can be retained for a test or documentation delta. A persistent request to change the same candidate stops for a human decision.
5. After clean reviews, an independent final verifier reruns each required live case on fresh data B in the same environment. It uses the author profile. The controller supplies the results and evidence for its managed checks. The verifier reports the entry path, observed result, and data or fixture for each case. It cites fresh evidence and uses the summary for supplemental checks outside the required scope. Missing coverage, unavailable required checks, or an undecided requirement block approval. A confirmed failure returns to the author with its evidence, then goes through review again.

Independent reviewers return `decision`, `summary`, and `findings`. The final verifier also returns a `coverage` array. Author work remains ordinary prose. A malformed or incomplete verification disposition sets `verification-blocked` and preserves the completed author work and raw verifier notes. Managed static failures and source changes during independent assessment block approval.

When a blocked verification already contains complete fresh evidence and all managed checks passed, the controller requests one independent review of the blocker. The assessor reads the original verdict, evidence, and managed check receipts. A supplemental failure can be set aside by that new verdict. Missing required evidence still blocks approval. The controller preserves both dispositions and records the second invocation separately.

`verified` records a completed controller cycle and positive independent assessments. It is not a proof that the models detected every possible defect. Selecting adequate scenarios and interpreting real product behavior remain engineering responsibilities.

### Legacy workflow configuration

When reproducing an archived `agent` run, reuse its runtime and model catalog, profiles, `context`, trusted requirements, and environment configuration. Set a new output directory outside the checkout.

The caller allocates the product environment and checks readiness. The controller does not reset the cluster, delete unrelated data, or provision another environment during final verification. Preserve caches and record preparation separately from native model time.

`static_checks` uses the existing scoped recipes and cache. `product_check_binary` is an optional convenience for running Playwright/Jest with actual exit status and reports; the author is free to use other appropriate tools. The agent workflow never requires observation IDs, test-title joins, or a current-invocation path for references in prose.

Set `author_helper_profile` to a Codex profile to let the author ask focused source questions during its first turn or a repair. Use a direct native Codex executable as `runtime_binary` on Linux. Each requested helper gets a fresh read-only copy of the current source and selected task attachments. Helper commands have no network access. The author owns edits and live checks. `work-metrics` counts helper invocations under their author turn.

Set `verification_scenarios` to identify separate required cases within a criterion and consumer pair.
Give each case an `id`, `criterion_id`, `consumer`, and `description` that names the entry path and expected behavior.
A pair without explicit cases has one default case.
Each required check outside `static_checks` also has a verification case.
The final verifier cites fresh evidence from the checkout or its attempt evidence directory.
It can copy relevant fresh artifacts from elsewhere into that directory.
Task attachments and candidate source provide context.
The controller records a corrected path when a missing absolute citation has the exact same run, stage, and relative file path as an existing fresh artifact.
A new data token helps distinguish attempts.
The verifier records the data it used and can adapt the token to fixture constraints.

The [Jev engineering policy](JEV-ENGINEERING.md) describes the standalone pre-ready check and the archived experiments that used engineering triage.

### Native continuity and records

The author has one private native home and an explicit session ID. Repairs use that ID through Codex `exec resume` or OpenCode's explicit session option, not `--last`. Repairs receive feedback and current context changes instead of the full project packet again. Review and final-verification sessions have separate homes. Credentials are copied only for execution and removed afterward.

The frozen instruction packet remains the assignment. Candidate changes to instruction source files are reviewable changes, rather than a reason to discard the author's work. Internal file digests are not shown as product identity requirements.

Each invocation records its prompt, profile, native command/events, completion status, raw final text, and source snapshots. Controller summaries and assessor dispositions live in separate controller-owned evidence directories, preserving similarly named files written by the agent. The human-readable summary is assembled from these records; no model-authored path schema controls the author's next action.

Each author turn archives its cumulative native history. `session.json` links it to the previous author archive. Usage accounting verifies the per-request prefix and counts only new requests; it never charges the entire history again. A missing or inconsistent baseline leaves cost incomplete, while known costs remain visible. API-equivalent estimates remain distinct from subscription charges and operator overhead.

Author continuation works within a running job. Resumption in a new output directory is available for staged verification. Keep the saved checkout, native history, and reports when a job stops.

## Local validation

Unit and subprocess tests cover natural author prose with external source references, same-session repair, independent profiles, dynamic review, real static failures, final verification, interrupted/unusable runtime outcomes, and usage across resumed turns. These controller changes need no product end-to-end case: their module tests reach the actual runtime/driver seam. Product fixes still follow the repository's end-to-end policy.

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
