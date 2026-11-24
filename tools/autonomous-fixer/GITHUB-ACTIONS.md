# Engineering on GitHub Actions

In `hosted` mode, the engineering worker runs OpenCode 2.2.6 on a GitHub runner and calls the configured models through OpenRouter. The author follows the repository's `fix-issue` skill, edits the candidate checkout, runs product checks on the runner's kind cluster, and delegates source reviews in OpenCode. A separate job validates the candidate and publishes the draft PR. Local engineering runs use Codex. Jev runs separately for pre-ready triage and documentation review.

## Repository setup

If the repository used the earlier experimental release, finish its outstanding runs and publications and close or merge its agent PRs before updating the workflows. Resume or apply older runs on the release that created them. Keep archived evidence with its original source revision.

Install the workflows, controller, product source, and agent skills on the repository's default branch. Enable Actions, create the `ready-for-agent` label, and make sure the runner can restore or download the QIP kind workflow's dependencies. A new fork may have cold caches. Configure the variables and secrets below, including the new `QIP_AGENT_*` names, then open a new issue to start work.

| Repository variable | Hosted setting |
| --- | --- |
| `AGENT_ENABLED` | `true` |
| `AGENT_MODE` | `hosted`. The default is the model-free `fake` worker. |
| `AGENT_ALLOWED_ACTORS` | Comma-separated GitHub logins allowed to assign, answer and stop work |
| `AGENT_SCOPE_PATHS` | Paths the candidate may change, including relevant tests. For a UI issue, for example, use `ui,e2e` if both are in scope. |
| `QIP_AGENT_MODEL` (default) | `openrouter/openai/gpt-6-luna` |
| `QIP_AGENT_EFFORT` (default) | `high` |
| `QIP_AGENT_REVIEW_MODEL` (default) | `openrouter/openai/gpt-6-luna` |
| `QIP_AGENT_REVIEW_EFFORT` (default) | `xhigh` |
| `AGENT_USE_ORG_BOT` | Set to `true` to publish from the organization account. |
| `AGENT_ORG_BOT_LOGIN` | GitHub login that owns `GH_ACCESS_TOKEN`, for example `NetcrackerCLPLCI` in QIP. Required in organization account mode. |
| `AGENT_AUTO_PUBLISH` | Set to `true` for label-to-draft-PR operation. Leave unset for manual publication of the selected worker run and attempt. |

| Repository secret | Where it is used |
| --- | --- |
| `OPENROUTER_API_KEY` | Model calls in the worker. A dedicated key lets you track its usage and revoke it independently. |
| `AGENT_VARIABLES_READ_TOKEN` | Trusted controller steps. Grant repository Variables read access so the controller can recheck the allowlist and enable flag. |
| `AGENT_PUBLICATION_TOKEN` | Trusted publication step. Grant Contents and Pull requests write and Issues read on this repository. The publisher creates a branch and draft PR and reads the issue to confirm eligibility. |

With `AGENT_USE_ORG_BOT=true`, trusted jobs use the organization secret `GH_ACCESS_TOKEN`
for new issue comments, labels, branches, and PRs. Existing GitHub Actions comments keep their
original author when the fixer edits them. Actions still uses its workflow token to dispatch
work. Keep a separate read-only `AGENT_VARIABLES_READ_TOKEN` for the worker to recheck
the allowlist after it leaves the queue. Finish active engineering runs and their PRs
before changing the organization login or turning organization mode off. Model jobs receive neither the organization's publication credential nor the project PAT.
See [documentation credentials](DOCS-GITHUB.md#repository-setup) before changing the publisher.

If the fork token cannot read the required Maven packages for the first issue, configure `QIP_AGENT_MAVEN_USERNAME` and the `QIP_AGENT_MAVEN_TOKEN` secret with Packages read access. The first issue's environment preparation and the trusted revision cache workflow use these credentials.

The inference process uses a loopback relay for its configured models. The real key is kept out of OpenCode's config and ordinary tool environment. Tools still have shell, Docker and Kubernetes access on that runner. The relay does not isolate secrets from a process controlling the host. The credential for publishing the PR belongs to a different job.

## First issue

Start with one issue and check disk use, the preparation log, billed OpenRouter usage, and the draft PR before assigning more. During rollout, verify repository permissions, concurrency, callback delivery, artifact provenance, cancellation, and recovery after an interrupted job.

1. Open a narrowly scoped issue in the repository. If `AGENT_PRE_READY_ON_OPEN` is set to `true`, this starts **QIP issue triage**. You can also record your assessment and run the workflow manually. Use its English summary as advice before assigning work. See [Pre-ready issue advice](JEV-ENGINEERING.md#pre-ready-issue-advice).
2. Add `ready-for-agent` as an allowed operator. Follow the controller's issue comment to **QIP agent work**. Compare the registered worker run and attempt, base commit, issue text, and any captured attachments.
3. Inspect the worker's preparation and author result. An `author-reported-fixed` outcome means the author reports that the checks and reviews completed. The draft and evidence are available for human review. If the author needs a decision, answer the versioned question in the issue. A new execution starts with the recorded answer. Use `/agent stop` to stop work.
4. With automatic publication enabled, the trusted worker completion leads to a draft PR. For manual publication, dispatch **QIP agent publish** with the issue number and exact candidate run and attempt. Inspect the changed files, evidence and ordinary PR CI before merging.

If OpenCode exits with an error, download the worker's `agent-hosted-evidence-<run-id>-<run-attempt>` artifact and open `result/step-0001-author/runtime-failure.json`. It records the exit code, recognized stderr signals, and the last recognized OpenCode error or HTTP status found in the captured output. If the output contains no recognized cause, the report marks the reason as unknown. A nonzero exit still prevents publication even when the author's final line says `Outcome: fixed`.

The draft PR draws its verification steps and observed results from the sealed author report. **How to verify** ends with the end-to-end decision. It names the regression test and target when the run needs that coverage, or explains why the change needs no end-to-end case. If an older or incomplete report lacks the decision, the draft says so and links to the worker evidence. A publication retry keeps edits to an existing draft description.

## Request changes in the same PR

Submit a **Request changes** review on the current engineering PR head to ask the fixer to address it. The coordinator reads the submitted review and its inline comments from GitHub. It checks the reviewer's current write access and the live `AGENT_ALLOWED_ACTORS` list. Ordinary review comments remain available for discussion.

For automatic revisions, the receipt stores the accepted review IDs and CI failure keys. The worker reads the feedback from GitHub and checks it against the digest recorded at admission. This lets one revision carry reviews whose combined text exceeds the size of a GitHub comment. The worker includes up to 4,096 bytes from each review message and marks truncated excerpts.

To select individual comments yourself, leave a new top-level comment on the published engineering PR. Copy a link to each review comment, submitted review, or PR conversation comment that the author should address. For example:

```text
/agent revise #discussion_r123456 #issuecomment-234567
Keep the existing behavior for a chain opened directly from a link.
```

Select up to five feedback messages from that PR. A link to a submitted review includes its summary and inline comments. Link individual comments to request a smaller batch. Put any extra instruction on the next line. The operator needs repository write access and a place in `AGENT_ALLOWED_ACTORS`. Keep the source issue open and labeled `ready-for-agent`.

Run **QIP agent revision cache** on the repository's default branch to prepare a new fork or recover
from a missing private Maven dependency. An allowed operator can run it from Actions. It uses the
default-branch checkout and the same read-only Maven package access as the first issue. The workflow stores
the four Java services' Maven dependencies under `maven-Linux-agent-revision-<default-branch POM hash>` in
that repository's default-branch cache. Revisions can download from public repositories and use this
cache. If a revision adds a private dependency unavailable from the default-branch build, preparation
stops before the author starts.

The receipt identifies the run and the PR head that the author will work from. Check the resulting commit
and its evidence before resolving review threads. If someone pushes a change while the author works, review
the new head before requesting another attempt. The branch update requires the expected head and rechecks
that the PR is open immediately before pushing. GitHub handles the branch update and PR closure separately.
If the publication job fails temporarily, the operator who posted the command can rerun the failed publisher
job to reuse the first worker artifact. The maintainer decides when to mark the PR ready and merge it.

## Read CI and the final outcome

**QIP agent CI observation** updates one comment on the open engineering PR. It reads GitHub check runs and commit statuses for the current head and, when available, the test merge. Check the SHA in the report when a new commit arrives. You can set `AGENT_CI_REQUIRED_CHECKS` to a list of expected job or status names, one per line, to supplement the branch rules available to Actions. If a branch rule requires an entire workflow, list its required job names in this variable.

The observer runs on PR changes and CI completions. A daily scan and manual dispatch can refresh the report if an event was missed. Reading checks uses no model calls.

With `AGENT_ENABLED=true` and `AGENT_MODE=hosted`, automatic follow-up applies to eligible open engineering PRs, including drafts already in the daily scan. To inspect the decision locally, run `go run ./cmd/agent-follow --preview` from `tools/autonomous-fixer` with the same repository, default ref, tokens, and `INPUT_PULL_REQUEST` as the follow job.

The coordinator waits for the required checks and includes failures from optional checks that have finished. If the branch has no required checks, it waits for all reported checks. When the same check runs on both the PR head and the test merge, the coordinator uses its test merge result. Checks that run only on the head still count.

After dispatch, the worker checks the requested failures again before accepting a revision. Only diagnostics still present in completed failing jobs qualify for admission. The worker rechecks the accepted diagnostics before publication. An unrelated optional workflow may rerun in the meantime. Explicitly required checks must still finish.

The engineering author receives the job link and diagnostic evidence for each identifiable test, build, or lint failure. When review feedback and CI failures arrive together, the author handles both in one revision. If a check has failed while another required check is running, the coordinator defers a new review. Once the review is ten minutes old, the next workflow event, manual run, or daily scan can send it to the author while CI finishes.

A confirmed runner or service failure can trigger one retry of the failed jobs without calling a model. The coordinator leaves a note on the PR when a failure needs manual diagnosis or the same diagnostic returns after an automatic repair. If another test starts failing in that job, the author can address the new test in the same pass. For source errors, the comparison uses the reported file and location. Moving the error to a different line can trigger a new repair.

If the head changes during a revision, the coordinator asks the reviewer to confirm any outstanding feedback on the old head. The maintainer resolves review threads, marks the draft ready, and decides when to merge.

**QIP agent PR outcome** records both merged and closed-unmerged PRs. Its comment and Actions artifact identify the published candidate, the last PR head, and the merge commit when applicable. The report timestamps the CI observation because checks can finish or rerun after closure. The comparison uses each version's patch against its own base. Missing historical evidence produces an unknown result. A changed patch can include work by the author, a maintainer, or both. Use the commit and discussion history when attributing a change.

## What crosses jobs

The worker seals its run identity and author report. For a claimed fix it also packages the candidate's
regular-file edits relative to the authorized commit. The publisher verifies the recorded artifact identity
and digest, reconstructs the actual tree in a temporary index against the trusted base, checks every changed
path against the issue scope, and compares the tree returned by GitHub. It seeds a run-specific branch from
an existing base commit and fast-forwards it to the validated candidate. When only the fixer tooling or its
engineering workflows advanced, it can seed from the current tip and replay the same product edits. The PR
body records both commits. A repeated publication reads back the same branch and PR. An unrelated commit on
the default branch leaves the pinned candidate intact. If the default branch has been rewritten and no
longer contains the admitted base, the publisher stops.

The runner is ephemeral. A restart gets a fresh checkout and stand. The first
[fork run](https://github.com/AkhDmitrii/qubership-integration-platform/actions/runs/44315529424) exercised
the real stand, author and reviewers. OpenRouter billed $0.35611274 for 74 requests.
[Draft PR #2](https://github.com/AkhDmitrii/qubership-integration-platform/pull/2) used the original
candidate after an operator reconciled the missing completion notification and retried publication. A later
run on [issue #8](https://github.com/AkhDmitrii/qubership-integration-platform/issues/8) published
[draft PR #9](https://github.com/AkhDmitrii/qubership-integration-platform/pull/9). Keep label-to-draft
delivery in the smoke test when deploying a new controller version.

## GitHub result signatures

The result publication follows the [AI agent comment signature specification](https://github.com/Netcracker/qubership-ai-packages/blob/main/research/ai-autonomous-design/README.md#ai-agent-comment-signatures).
A completed fix reports its usage on the last line of the draft PR description. When an author stops for help, its issue result comment carries the footer. The state comment tracks control events. It does not repeat the PR's charges.

Each footer identifies the main model and sums the available runtime measurements for the execution, including reviewer and retry requests. Cache reads have their own counter. Output includes reasoning. A missing value means the runtime could not establish a complete total. API-equivalent price estimates are not provider charges.

To inspect the footer before delivery or recover it from a downloaded evidence artifact:

```bash
go -C tools/autonomous-fixer run ./cmd/agent-signature /absolute/path/to/result
```
