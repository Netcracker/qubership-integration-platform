# Manual documentation workflow

**QIP documentation agent** runs the documentation workflow on the default branch when `AGENT_DOCS_ENABLED` is `true`. The issue must be in **In doc review** on the configured project and have a linked, merged implementing PR.

Select **Run workflow**, enter the issue number, and choose an operation.

- Choose `prepare` to freeze the issue, source revision, and change packet for local model work. This operation reads GitHub only.
- Choose `run` to prepare the packet, run the documentation work, and validate a completed result. The publisher restores the exact candidate from the work artifact and checks it against the original preparation. With `apply` selected, it opens a draft documentation PR or records a no-change decision and completes the issue. If the work needs a decision, read the question in the run summary. Blocked verification fails the work job.
- Choose `reconcile` to inspect or recover a documentation publication. Reconciliation recovers the original plan from the publisher's GitHub state and validates the PR against it. Select `apply` to complete an eligible issue and move the project item to **Done**.

`apply` defaults to `false`. A new `run` dispatch starts a new plan and model run. To apply the exact result of a dry run, use the downloaded artifacts with the local publication command below.

Use `QIP agent control` with operation `check` to build and test the controller without model credentials. Its tests use fake adapters and local HTTP servers. The demo exercises dispatch, duplicate delivery, admission, stop, and stale completion. These checks do not establish that live workflow callbacks and publication work correctly.

## Repository setup

Configure these settings in the repository's Actions variables and secrets.

| Repository variable | Value |
| --- | --- |
| `AGENT_DOCS_ENABLED` | Set to `true` to enable the workflow. |
| `AGENT_DOCS_PROJECT_NUMBER` | The number of the project linked to the issue. Defaults to `12`. |
| `AGENT_DOCS_PUBLISHER_LOGIN` | The account or App bot that publishes state comments and documentation PRs. The PR gate checks this login. |
| `AGENT_ALLOWED_ACTORS` | Comma-separated GitHub logins allowed to run the workflow and publish. |
| `AGENT_DOCS_MODEL` | OpenRouter model for the documentation author. Defaults to `openrouter/openai/gpt-6-luna`. |
| `AGENT_DOCS_EFFORT` | Reasoning effort for the documentation author. Defaults to `medium`. |

Set the `OPENROUTER_API_KEY` secret for `run`. Jev classifies the prepared packet and can complete an automatic no-change decision. Routes that need an author start one OpenCode 2.2.6 session through a loopback relay. In that session, the author assesses the issue, edits help when needed, and runs the documentation checks.

Choose one GitHub credential mode.

- **QIP organization account and project PAT.** Set `AGENT_USE_ORG_BOT=true` and
  `AGENT_DOCS_PUBLISHER_LOGIN=NetcrackerCLPLCI`. The fixer uses the organization secret
  `GH_ACCESS_TOKEN` to read repository variables, publish PRs and comments, and close issues.
  It uses `AGENT_DOCS_GITHUB_TOKEN` to find project items, read their status, and move them to
  **Done**. The project token needs access to the board and its linked issues. A classic PAT
  needs the `project` scope and its owner needs write access to the board.
  Before changing the publisher login, finish active documentation runs, reconcile interrupted
  publications, and merge or close outstanding documentation PRs under the previous identity.
  Follow the same procedure when switching back. Completed issues keep their original comments.
- **GitHub App.** Set variable `AGENT_DOCS_APP_ID` to the App ID and secret `AGENT_DOCS_APP_PRIVATE_KEY` to its private key. Install the App in the organization that owns the repository and project. Grant repository Contents, Issues, and Pull requests read and write, repository Variables read, and organization Projects read and write. Preparation and dry-run publication request a read-only token. Applied publication and reconciliation request write access. The App login must match `AGENT_DOCS_PUBLISHER_LOGIN`. If token creation fails, the job stops.
- **Personal access token.** Leave `AGENT_DOCS_APP_ID` unset. Set secret `AGENT_DOCS_GITHUB_TOKEN` to a dedicated token with access to repository variables and the write permissions needed to create branches and pull requests, post comments, close issues, and update the project. Its account must match `AGENT_DOCS_PUBLISHER_LOGIN`. If you also set `AGENT_DOCS_READ_TOKEN`, preparation and dry-run publication use that token for reads.

The publisher rereads the enable switch, actor allowlist, and publisher identity from GitHub before applying changes. The pinned App token action reads `INPUT_PERMISSION-VARIABLES` from its environment to request repository Variables read. Check that input when updating the action.

## Process the documentation queue

Run **QIP documentation queue** from the default branch to preview the configured project. Leave **Apply** clear. The job lists eligible issue numbers and records the reason for each skipped item in `docs-discovery-*`. Preview uses GitHub reads and finishes before model work.

| Repository variable | Value |
| --- | --- |
| `AGENT_DOCS_SCHEDULE_ENABLED` | Set to `true` to run discovery every day at 03:17 UTC. |
| `AGENT_DOCS_SCHEDULE_APPLY` | Set to `true` after checking a live preview. Scheduled runs process eligible issues when both schedule variables are `true`. |
| `AGENT_DOCS_SCHEDULE_BATCH_SIZE` | Maximum issues to process in one run, from 1 to 10. Defaults to 2. The preview still lists every eligible issue. |

To process the queue manually, set both `AGENT_DOCS_SCHEDULE_ENABLED` and `AGENT_DOCS_SCHEDULE_APPLY` to
`true`, then select **Apply** when you dispatch the workflow. You can preview the queue with either
variable unset.

The queue uses the same repository and project permissions as manual documentation work. The GitHub Actions actor and triggering actor must both be in `AGENT_ALLOWED_ACTORS`. For a cron run, GitHub chooses the actor associated with the schedule. Check that login in a scheduled preview before enabling application.

The queue skips issues outside **In doc review**, issues without a merged implementing PR, and issues with
active documentation runs. An open help-only PR that explicitly closes the issue also takes precedence. A past
mention in a merged help PR leaves the issue eligible. The queue processes selected issues one at a time. A
scheduled attempt claims the issue and its implementing changes after preparation. Manual and scheduled jobs
share the same concurrency group for an issue.

The progress comment links to the run. The publisher edits that comment when it has a decision. If work or
publication stops, a final job updates the same comment with the reason and recovery instructions. If GitHub
cancels that job too, the original run link remains available.

If the claimed run stops before the documentation work step starts, a later queue run can try again. Once that step starts, the queue skips the same issue and implementing changes on later runs. Use the manual `run` operation to retry after inspecting the scheduled run. To return the queue to preview, unset `AGENT_DOCS_SCHEDULE_APPLY`.

### Request documentation from an issue

Post exactly `/agent docs` as a new comment on the issue. The command requires `AGENT_DOCS_ENABLED` and an allowlisted actor. Use it on an issue in **In doc review**, including an issue that is already closed. The command processes only that issue, even when the daily queue is disabled.

The command uses the same eligibility, concurrency, and duplicate checks as the daily queue. An ineligible or duplicate request gets an English reply that links to its workflow run. The queue ignores edited comments, pull request comments, and requests from actors outside the allowlist.

## Model execution and evidence

Keep Codex subscription authentication on your local machine. Hosted work uses the separately billed OpenRouter key. The work job uses a read-only workflow token for checkout and artifact download. The work job receives no publication credentials. The queue, publisher, reconciler, and stop notification use the configured GitHub credentials for their own operations.

A queue run builds `docs-agent` once from the trusted workflow commit. Later jobs in that run download the binary by artifact ID. A standalone run builds its own binary during preparation.

- `docs-prepared-*` contains the frozen plan and context packet. `docs-prepare-log-*` contains the preparation log.
- `docs-result-*` contains the completed result, the candidate files, the evidence bound to the completion receipt, and the feedback record. Runtime homes, authentication files, and transcripts stay on the work runner.
- `docs-work-diagnostics-*` records the status, route, observed stages, and reported usage when available. The controller records the route and its reason before creating the worktree. The work job summary explains whether Jev recommended the route or the controller selected it, lists the models used, and shows the outcome and reported costs. Use the artifact to inspect a failed or unresolved work job.
- `docs-publish-*` and `docs-reconcile-*` contain publication and reconciliation logs. Applied publication also records its GitHub operations in a journal.

If publication stops, the issue comment identifies the failed operation and links to the attempt that failed. For an issue close, check the issue state and repository permissions. For a move to **Done**, check the project item and project permissions. Reconciliation reads both states before repeating a write.

Keep the workflow artifacts until publication finishes. For a transient publication error, use **Re-run failed jobs** in the same run to reuse the verified result. If the default branch has moved, follow the recovery instructions in the issue comment. When GitHub confirms that the old plan has no outstanding writes, the publisher can mark it **superseded**. A fresh assessment then uses the current default branch.

## Local operation and feedback

`docs-agent prepare` reads the issue, its project status, and merged implementing PRs. A PR that closes
the issue can qualify even when it changes only tests. Preparation uses strong closing relationships to
select implementations and records ambiguous production references as limitations. The command accepts
an already-closed issue in documentation review. For a squash or rebase merge, preparation verifies the
landed changes against the original PR head and commit count. If verification fails, preparation stops
before calling Jev. The finalized plan binds the source revision, initial issue state, and exact evidence
packet. A reopened issue invalidates a plan prepared while it was closed.

`docs-agent work` uses the prepared packet. An incomplete packet routes directly to assessment and skips the billable Jev request. Completed output includes an immutable feedback record. A result that still needs an owner decision is preserved as an unresolved run and cannot publish completion.

To publish a downloaded result locally, check out the plan's base commit and run
`docs-agent restore --prepared ... --run ... --source ... --workspace ...` to restore the candidate into a
new worktree. Pass that worktree as `--source` to
`docs-agent publish --prepared ... --run ... --source ... --actor ... --apply`. For a hosted artifact,
`--run` points to its `work/` directory. Omit `--apply` to validate the local publication without writes.
Local applied publication requires a token whose login matches the declared actor and configured publisher.
The initiating workflow actor may differ from the App publisher identity.

Human reviewers can append documentation-only corrections to the published branch. The gate and merge reconciliation check ancestry and the complete PR file list, including rename origins. The original candidate identity remains recorded, with the later merge head marked separately; added commits are an observation, not automatically a classifier error. A rewritten unrelated head or a product-code change needs separate validation.

When a publisher-owned documentation PR merges into the default branch, **Reconcile merged documentation** checks the merge and the publisher's canonical issue comment before completing the issue. A reviewer with merge permission can finish an authorized publication. The actor allowlist still governs manual operations. The merge workflow uses default-branch code and no model credentials.

The workflow updates the issue and its project item once. If a merge check fails, inspect the workflow log and use the manual `reconcile` operation after resolving the failure.

`docs-feedback import --run <completed-directory> --output <new-record.json>` validates a completed archive. `docs-feedback adjudicate` adds an explicit later judgment with actor, basis and evidence. `summary` separates independent observations of one dataset case from corrections to the same observation. Human and model judgments remain separate. Records use the current `docs-feedback-v2` schema and never overwrite earlier exports. Standalone downloaded archives can be validated after controlled path relocation; relocated resume chains need their original evidence layout.

The workflow keeps proposed results and GitHub observations in Actions artifacts. Use audit judgments from `docs-feedback` to assess individual classifier decisions.
