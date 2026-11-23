# Local model experiments

The comparison archive records outcomes, including incomplete attempts; it does not enforce the complete production engineering cycle. New controlled work uses [the local work cycle](WORK-CYCLE.md). An explicit cancellation in a campaign's `queue-policy.json` blocks `execute` and `capability` before a model request. Reading, metrics, and archival operations remain available.

The `experiment` command prepares independent checkouts, fixed model profiles, and evidence archives. Agents run directly on the allocated machine with Docker, kubectl, build tools, and a browser. There is no filesystem sandbox or product-control RPC layer.

## Comparison matrix

| Profile | Runtime | Model | Effort |
| --- | --- | --- | --- |
| c1 | Codex | gpt-6.1-sol | medium |
| c2 | Codex | gpt-6.1-terra | medium |
| c3 | Codex | gpt-6-luna | high |
| o1 | OpenCode | opencode/deepseek-v4.1-flash-free | high |
| o2 | OpenCode | opencode/glm-5.3-flash-free | high |
| o3 | OpenCode | opencode/mimo-v2.6-flash-free | Unset |
| o4 | OpenCode | opencode/beam-free | high |

The five issues are 1215, 878, 1093, 1168, and 1158. There are 35 issue/profile slots. Each candidate has independent reviews by Sol high, Terra high, and the executor's own model and effort: up to 105 reviews. Executor and reviewer order rotates between tasks.

Issue 1158 is diagnostic because SDS and its downstream implementation are unavailable. Issue 1168 still requires a decision on consistent startup failure isolation and recovery. Neither should be graded as an ordinary failed fix solely because that information is missing.

The comparison measures model and runtime combinations. Native context windows remain unchanged. OpenCode compaction uses a 15% buffer and retains 15000 recent tokens beside its summary. Compaction and title generation use the role's exact model and effort. Subagents are disabled for the first series. Unavailable context occupancy and compaction counts remain unknown.

## Working copies and context

Each role receives an independent Git repository with one snapshot commit and no remotes or shared object store. Project AGENTS.md files and skills are preserved. The experiment prompt uses the frozen task package and excludes GitHub intake and delivery operations.

A fresh HOME and explicit environment prevent accidental reuse of personal CLI configuration. Codex skips project runtime hooks and plugins; OpenCode loads the project instructions and skills explicitly. These settings do not isolate the filesystem. Agents have ordinary host permissions and must follow the experiment rule against reading other solutions or archives.

The selected Docker endpoint and kubeconfig are passed directly to the agent. GitHub publication credentials are not inherited. This is not a guarantee that same-user files are inaccessible on a local machine. On Actions, write credentials belong in the separate publication job.

Maven and npm dependencies are copied from the fixed baseline into each run's own directories. The local speed policy allows 8192 MiB of Node old space for UI builds. Full UI Jest runs use eight workers with 2048 MiB of old space per worker; model runs, reviews, and live Playwright work remain sequential. Run full Jest and large builds separately. These are local workstation settings, not measured limits for a GitHub runner. Earlier single-worker runs remain identifiable when comparing elapsed times.

## Prepare and check

Keep datasets, local plans, credentials, and transcripts outside the product repository. Run the build from `tools/autonomous-fixer`:

```bash
go build -o /tmp/qip-experiment ./cmd/experiment
/tmp/qip-experiment init --root <campaign> --source <repository> \
  --revision <full-commit-sha> --inputs <dataset-directory>
/tmp/qip-experiment prepare --root <campaign> --job 1215-c1-01
/tmp/qip-experiment toolcheck --root <campaign> --job 1215-c1-01 \
  --tools <tool-paths-json> --stand-config <stand-json>
```

The tool paths file identifies Node, Maven, browsers, and baseline dependencies. The stand file contains `docker_host` and `kubeconfig`. `toolcheck` checks Java, Maven, Docker/buildx, Kubernetes access, and the product UI through a headless browser. It makes no model requests.

`capability --job <job-id> --node-root <node-installation>` performs a real model request on a generated fixture. Codex also requires `--codex-login`. The probe checks instructions, a skill, and a shell command without giving the model a product issue.

`environment --kubeconfig <file>` records the allocated desktop cluster's identity, images, and health without changing it. `baseline` creates a separate source snapshot for infrastructure preparation.

## Execute and capture

```bash
/tmp/qip-experiment execute --root <campaign> --job 1215-c1-01 \
  --node-root <node-installation> --tools <tool-paths-json> \
  --stand-config <stand-json> --readiness <readiness-json> \
  --codex-login <chatgpt-login-json>
/tmp/qip-experiment capture --root <campaign> --job 1215-c1-01
/tmp/qip-experiment prepare --root <campaign> --job 1215-c1-01 --role r1
```

The readiness manifest binds the source commit, issue, and role profile to hashed access, context, and environment evidence. It records reviewed evidence; it does not prove task correctness automatically. `refresh-context` updates only unstarted preparations.

The local experiment adapter requires ChatGPT subscription authentication for Codex. It copies the selected login into the isolated runtime directory and removes the copy after execution. Free OpenCode profiles inherit no provider credentials and have no paid-provider fallback.

The runtime version is checked before inference: Codex 0.166.0 and OpenCode 2.2.6. OpenCode 2.2.6 fixes premature CLI exit while background shell commands are running. The recorder saves stdout JSONL, stderr, session files, timing, available token data, and host resource samples. Those samples include other host processes and the Docker VM; they are not a measurement of a GitHub runner limited to four CPUs and 16 GB.

Exit zero never becomes an issue-success verdict. Explicit runtime errors, incomplete events, and unknown measurements remain visible. OpenCode CLI output omits the full execution-success event; per-step completion, background waits, and retries are not promoted to task success.

Capture uses a controller-owned Git directory and preserves the agent's Git history. Filters from the agent's Git configuration are not executed. The candidate contains a binary patch, source snapshot, last recorded author message, runtime status, and evidence files. All three reviewers receive the same recorded candidate and evidence hashes, without earlier conversation or other reviews.

Use `assessment --job <job-id>` to start an evaluation record. Criteria need evidence; findings need independent confirmation. Missing engineer time, usage, or verification results are not zero or passed. Raw archives stay private until checked for sharing.

## Token usage and API equivalents

Each completed `execute` writes `execution/usage-cost.json` and updates `metrics-summary.json` in the campaign directory. The summary separates implementation and review costs, groups usage by model, and includes failed and calibration attempts. Active roles are excluded until their execution result is saved.

Recompute existing records without starting a model or interrupting an active run:

```bash
experiment metrics --root <campaign>
experiment metrics --root <campaign> --job <job-id> --role r1
```

The estimate uses the dated OpenAI Standard API token rates recorded in each model's cost entry. Input includes cached reads and cache writes; output includes reasoning. Rates apply to individual requests, including the long-context surcharge above 272000 input tokens. The estimate excludes tool fees, infrastructure, taxes, and subscriptions. It does not measure charges against a ChatGPT plan.

Codex usage comes from unique native session logs, including child sessions. Repeated cumulative counters are counted once. OpenCode usage comes from a consistent read-only SQLite snapshot through Python 3's standard library. Its visible output and reasoning counters are combined once. Compaction and title usage are included in session totals; auxiliary usage that cannot be assigned to individual requests remains explicitly unattributed. CLI-only fallbacks record their narrower coverage.

The four configured OpenCode free model IDs cost zero under the experiment policy, even when token coverage is incomplete. Their paid reviewers still contribute their own API equivalents. Unknown models or incomplete paid usage produce a null cost and a separate subtotal for requests that can be priced. Unknown token counts remain null.

These reports are controller artifacts outside the candidate evidence supplied to reviewers. A metrics error is reported separately and does not change the model's task outcome.

## Completed-run storage

Each role receives its own Maven and npm dependency copies. Release those copies after the entire cycle has finished and all three reviews have been assessed:

```bash
experiment cleanup --root <campaign> --exclude-job <current-job>
experiment cleanup --root <campaign> --exclude-job <current-job> --apply
```

The first command reports the removal list without deleting files. Cleanup requires successful terminal process records for the author and all three reviewers, plus an assessment listing each reviewer. It skips the current job and workspaces still used by a process. Interrupted and incomplete cycles remain intact.

Only role-local `.m2/repository` and the enumerated npm `node_modules` directories are eligible.
A dependency directory containing tracked source is retained.
If an npm dependency tree contains logs or evidence, cleanup retains the containing top-level package and removes only unrelated package directories.
Maven copies containing logs or evidence remain intact.
Shared caches, the frozen baseline, candidate snapshots, patches, input packages, native session logs, usage metrics, evidence, and build outputs remain available.
Build output directories can contain test reports, so this command preserves them in full.
Reusing a cleaned workspace requires restoring its dependency copies first.

Before removal, cleanup writes the exact path list and SHA-256 hashes of every retained regular file under the job; symlink targets are recorded separately. It verifies those records after removal. Reports are stored under `<campaign>/retention/`. Cleanup uses its own lock and can reclaim completed cycles while a different cycle runs.

To run cleanup automatically before each new execution, create `<campaign>/retention-policy.json` with `{"enabled": true}`. The current job remains excluded throughout its author and review sessions. The next cycle releases dependency copies from earlier completed cycles. A verification error stops the new execution before model startup and leaves its report for inspection.

## Sequential product work

A run means one issue/profile engineering cycle, including its reviews. Agents build, deploy, inspect logs, and verify the allocated test product themselves through ordinary tools. Inside a run, repeat the scenario on distinct data B in the same environment.

Between runs, restore changed modules to the frozen baseline and reset product storage.
Preserve the namespace, application images, and build and dependency caches.
Recreate the PostgreSQL and OpenSearch data volumes; restart Consul and the brokers to clear their nonpersistent data.
Stop application consumers before resetting storage and remove test micro domains.
The provisioner reuses images with matching source hashes and builds changed modules.
This also preserves the existing Go dependency layer, which sits before the product-source copy in the testing-service Dockerfile.
Record fresh storage identities, the unchanged namespace identity, restored image references, and product health before treating the next run as comparable.
Keep the earlier cold-reset attempts identifiable when comparing elapsed times.

Complete standard stack provisioning before product verification. A recoverable download failure requires resuming the provisioner through every selected service. A candidate requires platform readiness and verified test-data cleanup; bypassing a dependency wait or disabling session logging does not meet that requirement. Keep a run with unresolved infrastructure failures separate from completed comparisons.

The CLI holds an exclusive lock across operations in campaigns with the same parent directory. Separately launched shell commands are outside that lock. Keep builds, product tests, and reviews sequential. Publication and the complete unattended Actions loop remain separate from this local experimental tool.

## Checks

```bash
go test -race -p 1 ./...
go vet ./...
```

Tests cover independent histories, input drift, fixed profiles, identical reviewer evidence, trusted Git capture, sequential operations, process cleanup, event parsing, login-copy handling, and missing measurements. Live capability and tool checks provide separate evidence. They are not comparative issue results.
