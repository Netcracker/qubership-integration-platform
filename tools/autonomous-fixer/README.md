# Autonomous fixer

The autonomous fixer turns assigned GitHub issues into draft pull requests in `hosted` mode. The worker runs OpenCode through OpenRouter and verifies the fix on a QIP kind cluster. A separate job validates the candidate and publishes the draft PR. The controller tracks task state in a GitHub issue comment and checks run admission and candidate identity before authorizing publication. See [Engineering on GitHub Actions](GITHUB-ACTIONS.md).

The local `experiment` command prepares model comparisons with independent workspaces and runtime archives. See [Local model experiments](EXPERIMENTS.md).

The local `work` command runs the repository's `fix-issue` skill. The author handles implementation, tests, and native reviews. See the [local work cycle](WORK-CYCLE.md).

The local `jev` command evaluates documentation impact through OpenRouter's Decisions API. It records predictions in shadow mode and cannot close issues or create pull requests. See [Jev documentation classification](JEV.md).

Engineering runs skip Jev, including runs with archived engineering_triage settings. The standalone [pre-ready triage command](JEV-ENGINEERING.md) remains available before issue assignment.

## Documentation on GitHub Actions

The documentation pipeline has a separate lifecycle because an issue in `In doc review` may already be closed. `cmd/docs-agent` provides read-only GitHub preparation, automatic help/source retrieval, the local three-branch worker, validated publication, and merge reconciliation. `cmd/docs-feedback` records observations and explicit human/model judgments without treating agreement as ground truth.

`agent-docs.yaml` supports three operations:

| Operation | Model usage | GitHub changes |
| --- | --- | --- |
| `prepare` | None | Reads the issue and merged PRs and saves the evidence packet in Actions. |
| `run` | Calls Jev when the evidence permits classification. OpenCode handles assessment or writing when needed. OpenRouter bills these calls even in a dry run. | With **Apply** enabled, publishes a draft help PR or records a no-change decision, closes the issue if open, and moves its project item to **Done**. |
| `reconcile` | None | Inspects a previous publication. With **Apply** enabled, finishes an eligible publication using its recorded GitHub state. |

Manual runs default to a dry run. The documentation queue can also start work daily or in response to `/agent docs`. Merging a verified documentation PR triggers reconciliation. The workflows use a GitHub App or a dedicated PAT.

See [Documentation GitHub setup](DOCS-GITHUB.md), [documentation lifecycle](DOCUMENTATION-CYCLE.md), and [Jev routing](JEV.md). Engineering runs use separate settings and credentials.

## Local checks

Use Go 1.26.10, as pinned by `go.mod`. The module uses only the standard library and has no `go.sum`.

From the repository root:

```bash
go -C tools/autonomous-fixer test -race ./...
go -C tools/autonomous-fixer test ./internal/fixer -run TestNativeCLI -v
go -C tools/autonomous-fixer vet ./...
go -C tools/autonomous-fixer run ./cmd/agent demo
go -C tools/autonomous-fixer build -trimpath -o /tmp/qip-agent ./cmd/agent
```

The demo runs in memory without credentials or network access. It simulates an uncertain dispatch response, duplicate delivery, run admission, a stop, observed completion, and a late candidate. It checks that the controller dispatches once and does not publish.

Tests use fake adapters and local HTTP servers. They cover state validation, event identity, comment authenticity, effect readback, uncertain responses, workflow provenance, stop and continuation, question versions, candidate declarations, and Actions routing. These checks do not establish that the workflows work against live GitHub.

The native CLI tests compile the executable with a loopback API endpoint and invoke each mode in a separate process. They pass events and outputs through files and copy the worker's checkpoint into a fresh callback directory. A local HTTP server holds state across these processes. Child processes receive a fake token and block non-loopback HTTP traffic. Models and the product stack are not part of this test.

## Components

| File | Responsibility |
| --- | --- |
| `cmd/agent/main.go` | Native binary entry point and Actions environment handling |
| `internal/fixer/state.go` and `types.go` | Task schema, identities, state validation, and adapter interfaces |
| `internal/fixer/reducer.go` | Pure transitions and durable effect intents |
| `internal/fixer/controller.go` | State persistence, readback, effect observation, and recovery |
| `internal/fixer/policy.go` | Actor allowlist, event identity, stop reasons, and path restrictions |
| `internal/fixer/manifest.go` | Candidate declarations matched against trusted expectations |
| `internal/fixer/comment_store.go` | Canonical issue comment encoding and authenticated reads |
| `internal/fixer/github_client.go` and `github_adapter.go` | GitHub HTTP calls, run provenance, dispatch, and cancellation |
| `internal/fixer/github_events.go` | Supported GitHub events and command parsing |
| `internal/fixer/actions.go` | Routing, admission, checkpoint creation, and completion handling |

`Transition(previous, event, context)` returns the resulting state, a disposition, and a reason. Accepted transitions clone the state and increment its revision. Ignored events preserve the previous state. Adapters handle GitHub reads, writes, and workflow operations outside the reducer.

## GitHub configuration

Engineering and pre-ready triage run in these workflows.

| Workflow | Behavior |
| --- | --- |
| `.github/workflows/agent-control.yaml` | Checks pull requests that change this tool; routes issue events and handles worker completion |
| `.github/workflows/agent-work.yaml` | Admits the exact run and saves its checkpoint. In `hosted` mode it starts OpenCode, then exports the author result and evidence. |
| `.github/workflows/agent-publish.yaml` | Verifies a selected hosted candidate and publishes a draft pull request |
| `.github/workflows/agent-revision-cache.yaml` | Prepares the Maven dependency cache from the default branch for PR revisions. |
| `.github/workflows/agent-review-submitted.yaml` | Wakes the default-branch coordinator when a review is submitted. |
| `.github/workflows/agent-ci.yaml` | Reports CI and batches actionable failures with requested review changes for the current PR head. |
| `.github/workflows/agent-outcome.yaml` | Records the final patch comparison when an engineering PR closes. |
| `.github/workflows/agent-pre-ready.yaml` | Offers read-only triage on issue opening and in authorized manual runs |

Operational jobs require repository variable `AGENT_ENABLED` to equal `true`. Configure `AGENT_ALLOWED_ACTORS` as comma-separated GitHub logins and `AGENT_SCOPE_PATHS` as comma-separated permitted product paths. An empty or malformed allowlist denies every actor. Missing or invalid scope prevents execution. Set the repository variable `AGENT_MODE=hosted` to enable the hosted worker. An unset mode selects `fake`.

The controller reads `AGENT_ENABLED` and `AGENT_ALLOWED_ACTORS` before each transition. In organization account mode, it uses `GH_ACCESS_TOKEN`. Otherwise, set the repository secret `AGENT_VARIABLES_READ_TOKEN`. The token needs Variables read access on this repository. [GitHub repository variables API](https://docs.github.com/en/rest/actions/variables#get-a-repository-variable)

The CLI supports `demo`, `authorize`, `route`, `control`, `admit`, `work`, and `publish`. `authorize` checks the pre-ready workflow operators against the job's allowlist. `route` selects supported events and checkpoint downloads. `control` applies commands or completed-run observations. `admit` records run admission. `work` writes checkpoint metadata to `AGENT_CHECKPOINT_PATH`. `publish` authorizes publication of the selected hosted artifact. In fake mode publication stays disabled.

Follow the [GitHub Actions setup](GITHUB-ACTIONS.md#repository-setup) before enabling the engineering workflows.

## Commands and task lifecycle

For feedback on an existing engineering PR, see [Request changes in the same PR](GITHUB-ACTIONS.md#request-changes-in-the-same-pr).

An allowed user starts a task with `/agent fix` or the `ready-for-agent` label. Supported comment commands include `/agent status`, `/agent stop`, `/agent resume`, `/agent retry`, and `/agent answer <question-id> <question-version> <contract-version> <answer>`. The canonical state comment lists open questions and their answer syntax.

Only newly created comments execute commands. Editing a comment does not issue another command. The event decoder checks the actor and source identity; workflow trust comes from GitHub reads rather than a callback's own claim. The contract records a SHA-256 digest of the issue title and body. The worker reads both from GitHub and checks the digest before using the text. Requirements edits update the contract version. Their receipt identity includes the requirements body hash so distinct edits within one timestamp remain distinct.

A task has one current engineering attempt. Resume retains the attempt; retry creates another. Each execution has a generation, contract version, trusted workflow commit, and exact GitHub run ID and attempt. The controller admits a run only after observing its dispatch and saving admission. Review and diagnosis work-item records exist, but their scheduling is not implemented.

A stop advances the generation, supersedes pending work and publication intents, and requests cancellation. Cancellation acknowledgement leaves active work in `stopping`. A matching run-completion observation is required to enter `stopped`. Adding the label again does not resume the task.

Every open question needs an answer for its current version and contract. A no-change proposal also needs a supported basis and evidence reference; completion requires an allowed user's explicit `confirm` answer and observed run completion.

The fake worker writes bounded checkpoint metadata. After a successful run, control validates the checkpoint and records a question asking whether to run another fake cycle. It then records completion. This checkpoint contains task and execution metadata, not a product workspace or model conversation that can restore engineering work.

A run that ends before admission opens an `environment-blocked` question. After inspecting the run, an allowed user answers that question to start another execution. A stopped task stays stopped; completion callbacks do not repeat the question or dispatch another execution.

## Durable state and recovery

The store selects a single marked comment authored by the canonical GitHub Actions bot ID. It rejects duplicate canonical comments, malformed state, changed identity, and foreign task state. The comment contains a readable task summary and encoded state.

Before writing, the store reads the current comment and compares it with its previous observation. After writing, the controller requires an exact state readback before performing effects. This is an optimistic preflight check, not atomic compare-and-swap. Workflows serialize state-changing jobs; the store alone cannot prevent a concurrent writer from overwriting another write.

Effects have stable IDs. The controller observes external results even after a successful request. On reconciliation, it observes pending effects rather than blindly repeating them. An uncertain read, write, or effect result requires recovery. An absent observation does not authorize another dispatch.

Receipts and entity histories remain in the canonical comment. Encoding fails when the comment exceeds GitHub's 65,536-character limit; it does not silently discard history. There is no history compaction, audit branch, or durable checkpoint archive. Long-running task storage and operational recovery need further implementation and live validation.

## Candidate publication

The hosted worker packages the candidate files and records the package digest. Before publication, the publisher checks that digest and validates the changes against the trusted base commit. For setup and publication steps, see [Engineering on GitHub Actions](GITHUB-ACTIONS.md). The controller fixtures use the older Git-bundle declaration API.

The publisher excludes the fixer's own files, agent instructions, and CI actions and workflows from the
candidate patch. After publication, the revision worker can update an open engineering PR to address
eligible CI failures and requested review changes. The human reviewer makes the merge decision. A runner restart starts a fresh model session.

Engineering runs use one native `fix-issue` author and its reviewers. Local runs use Codex; the hosted worker uses OpenCode. See [Local work cycle](WORK-CYCLE.md) for execution details and archived modes.
