# Jev documentation classification

The local classifier uses OpenRouter's Decisions API with `typesafe/jev-1.14`. It returns a typed documentation-impact prediction, probabilities, provider confidence when available, the served model ID, input/output tokens, reported USD cost, and request duration. It uses the standard Go HTTP client, with no SDK dependency.

The command runs in **shadow mode**. `effective_route` remains `observe_only`, including when Jev says that documentation is unnecessary. It performs no GitHub operation, starts no coding model, and does not change the current product work cycle. The local worker can consume this report through an explicit routing configuration. It does not require a second model review for every negative classification.

Policy `docs-impact-v5` targets existing product help. Kubernetes/Helm setup, service-account wiring, CI, and developer tooling are outside that audience unless the change makes a concrete existing help statement false. Configuration topics already documented in help, such as retention and token processing, remain in scope. An internal enum or routine confirmation dialog alone does not require a new explanation. The question concerns work remaining against current documentation, not whether a feature historically deserved documentation.

Three typed questions separate the proposed action (`needed`, `no_change`, or `investigate`), its reason, and the documentation problem's relationship to the implementing change. A visible fix can require no new explanation. An older independent help error is a separate finding, not documentation work caused by this issue.

The classifier command remains observational: it saves a report and performs no GitHub operation. `suggested_route` records the branch selected by the default routing policy. The local documentation worker opts into routing through `documentation_triage`, validates the packet and report against its task and checkout, and recomputes the route from raw answers. It does not trust a stored route or recommendation.

Three branches are available:

- `docs-no-change`: a confident negative result with an allowed reason produces a local completion/comment draft without a native model call.
- `docs-write`: a confident positive result starts one session that investigates the gap and writes the fix. It may dismiss a false positive only while the checkout remains unchanged. A patch still requires independent review and final checks.
- `docs-assess`: uncertain, incomplete, or inconsistent results use the existing assessment cycle.

The selected-category probability and the lead over the next option drive routing. Provider confidence is retained for analysis; it is derived from the probability distribution and does not add independent evidence.

## Input

Provide a JSON packet containing:

- `id`: an archive identifier; it is excluded from model state;
- `scope`: the exact behavior, commit, or PR being assessed;
- `issue`: issue context as JSON;
- `repository`, `base_sha`, `head_sha`, `docs_sha`: the recorded source identities;
- `changed_paths` and `diff`: all changes in the verified Git range for each implementing PR;
- `sources`: relevant source files as `{path, content}`;
- `documentation`: current help pages in the same form;
- `search_evidence`: searches actually performed and their results;
- `limitations`: missing context, narrow scope, or synthetic modifications;
- `complete`: whether the input contains the evidence needed for its declared scope.

The classifier does not fetch or verify Git objects itself. The caller must assemble the packet from recorded sources; `complete` is a caller declaration, not proof. A partial packet remains eligible for observing Jev's behavior, but its suggested route is always `docs-assess`. A narrow contract check cannot establish that a large issue or PR needs no documentation. Expected labels belong in a separate evaluation manifest and must not be sent to Jev.

## Prepare real issues from Git

`cmd/jev-prepare` builds packets from reviewed issue and PR metadata and local Git objects. It checks that the documentation revision contains every selected merge on `main` and ignores uncommitted files. For a merge commit, the packet includes changes since its first parent. For a squash or rebase merge, the builder verifies the landed range against the original PR before including the diff. Exclude backports and unrelated cross-references from the dataset.

The dataset contains `repository`, `docs_ref`, and `issues`.
Each issue contains `number`, `title`, `body`, `url`, `timeline_complete`, `primary_linked_merged_prs` (numbers), `prs`, `docs_paths`, and optional `classification_sources`.
Each PR requires `number`, `merge_sha`, and `target`.
For a squash or rebase merge, also supply the original PR commit count in `commit_count` and its full head commit SHA in `head_sha`.
Fetch the original PR head into the local repository before preparing the packet.
If the builder cannot verify the landed range, it writes `error.json` for the issue.
Keep manual expected outcomes in a separate file.

```bash
go -C tools/autonomous-fixer run ./cmd/jev-prepare \
  --dataset /absolute/path/reviewed-dataset.json \
  --repository /absolute/path/repository \
  --output /absolute/path/new-packets \
  --max-doc-bytes 200000
```

The builder retains every changed path.
It compresses bodies of test fixtures, test sources, and developer references, preserving exact hashes and byte counts in `preparation.json`.
Production implementation changes remain in the model patch.
Historical help patches are replaced by current pages, including directly changed pages not listed manually.
The repository's old page names resolve to same-directory `readme.md` when appropriate; unresolved moves make the packet incomplete.
Additive mappings from changed Snapshots, Deployments, Domains, and Variables UI components select their reader pages even when the PR did not update those pages.
These mappings add candidates; they do not claim exhaustive coverage or decide that documentation is needed.
Help image and SVG bodies are not sent as text; conclusions requiring their visual contents need a separate check.

The default extraction limits are 160000 bytes of behavior diff, 80000 bytes of current help, and 64000 bytes of selected current source. These bound packet extraction, not task execution. Every behavior or documentation truncation sets `complete=false` and records what is missing. Increase the relevant limit and rebuild, or send the issue to full assessment. Hosted routing skips packets with gaps in the implementing PRs, behavior diff, or selected help text. If only supplementary retrieval is incomplete, the classifier can read up to 128 KiB of landed diff and 128 KiB of whole help pages in retrieval order. The full packet remains available to the author.

`packet.json` preserves the evidence and revision identities. The classifier request excludes archive IDs and top-level revision metadata; `packet_sha256` binds the full archived packet separately from `input_sha256`, which binds the actual API request. The caller still supplies relevant-page selection and correct issue/PR linkage; this command does not discover them from GitHub.

## Local use

Prepare the request without making an API call:

```bash
go -C tools/autonomous-fixer run ./cmd/jev \
  --input /absolute/path/packet.json \
  --output /absolute/path/new-dry-run \
  --dry-run
```

For an explicitly requested live classification, provide `OPENROUTER_API_KEY` or a private file containing only the token:

```bash
go -C tools/autonomous-fixer run ./cmd/jev \
  --input /absolute/path/packet.json \
  --output /absolute/path/new-run \
  --key-file /absolute/path/private-token
```

The key file must be readable only by its owner. The token is used only in the Authorization header and is absent from output artifacts. The endpoint is fixed to OpenRouter; loopback overrides exist for unit tests. Redirects are refused. A failed request is not retried automatically, avoiding an unrecorded duplicate charge after an uncertain response.

Each new output directory stores the exact `request.json` and a `report.json` with the request hash and outcome. Existing directories are not overwritten. Authentication, quota, transport, and invalid-response errors remain failures with an investigation route; they cannot become no-doc decisions. Failed responses have unknown cost unless the provider supplies usable accounting. Unknown usage is not zero.

## Decisions

| Action | Reasons |
| --- | --- |
| `needed` | `missing_guidance`, `stale_guidance` |
| `no_change` | `already_covered`, `restored_behavior`, `no_explanation_needed`, `internal_only`, `outside_help_scope`, `preexisting_only` |
| `investigate` | `insufficient_evidence`, `scope_unclear` |

Attribution is `change_related` for a required correction, `preexisting_independent` for an unrelated older error, `unchanged` when no necessary help work remains, or `unclear`. A `needed` answer without `change_related` attribution requires investigation. With partial retrieval, a negative recommendation can close an issue only for recognized release tooling. Other negative recommendations go to assessment.

`recommendation` contains the validated action, reason, and attribution. `assessment_notes` explains conflicts or missing evidence. The raw answers, probabilities, hashes, and provider usage remain available for evaluation. The selected branch is recorded with its evidence and policy. Negative outcomes need no mandatory second reviewer. A documentation patch retains the existing independent review and final checks.

The v4 schema replaced v3's mixed impact categories. The v5 request adds general decision examples and makes each question self-contained, since questions are evaluated independently. Archived v3 reports remain unchanged. Re-evaluation creates a new output directory and records the new policy version; it does not reinterpret or overwrite earlier predictions.

The first local evidence set is a smoke and sensitivity check, not an accuracy benchmark. It includes narrowly scoped real source/documents, a single test-only commit, a real configuration-change candidate without an approved gold verdict, and synthetic incomplete/modified-document cases. Descriptive IDs in initial probes were identified as potential answer leakage; neutral-ID observations are recorded separately. No result permits automatic issue closure.

The subsequent real-issue development set uses all selected implementing main PRs and help from a current main snapshot containing those merges. It compares earlier policy variants with improved scope and evidence preparation. These cases guided revisions, so they are not a held-out accuracy benchmark. Unresolved false positives, incomplete packets, and editorial ambiguities remain separate from confirmed outcomes.

Manual expectations also need verification. In the development set, refresh and saved filters appeared covered by seven pages, but a wider consumer check found missing refresh entries in other changed screens. Classifier disagreement is a reason to inspect the evidence, not proof that either the classifier or the manual label is correct.

## Verification and API references

Local tests use fake classifiers and HTTP servers. They cover wire format, authentication without disclosure, redirects, HTTP errors, malformed or inconsistent choice responses, missing usage, incomplete evidence, immutable output directories, and shadow routing. The existing module suite covers the implementation; no product end-to-end case is needed for this HTTP adapter and decision policy.

- [OpenRouter Jev model](https://openrouter.ai/typesafe/jev-1.14)
- [Decisions request and response schema](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request)
- [Jev on OpenRouter](https://openrouter.ai/docs/guides/community/jev)

The Decisions endpoint is an alpha API. The served dated model and actual `usage.cost` are recorded for each observation; a model alias or published token price alone is not evidence of the amount billed.

## Cycle costs

Measure a completed local run in a new metrics file. Routed runs automatically include the classifier response from their verified routing evidence:

```bash
go -C tools/autonomous-fixer run ./cmd/work-metrics \
  --output /absolute/path/to/new-metrics.json \
  /absolute/path/to/work-output
```

The report separates native API-equivalent estimates from provider-reported Jev charges. The combined amount has a mixed accounting basis. Unknown or unfinished usage remains unknown. Stage and model-request counts show the expensive work that actually ran, including failed completed invocations. Earlier retries and resume sources are excluded unless measured separately; a per-directory report is not automatically the complete task cost.

A completed automatic negative with verified provenance has a known native cost of zero, plus the actual Jev charge. Missing or unfinished runs remain unknown. `--jev-report` can add explicitly associated classifier reports; duplicate provider request IDs count once and conflicting costs are rejected. A classifier response reused during resume can appear in several per-run reports, so cross-run totals must deduplicate request IDs.

`savings_status` stays `not_measured` without a comparable baseline. Routing proof establishes which classifier response was consumed; an optional caller-supplied report alone does not establish that association.

## Routing configuration

`DefaultRoutingPolicy()` defines the rules the documentation worker uses to route classifier results.

| Setting | Default |
| --- | --- |
| Negative action probability | 0.95 |
| Positive action probability | 0.80 |
| Negative lead over the next action | 0.80 |
| Positive lead over the next action | 0.50 |
| Selected reason probability | 0.50 |
| Selected attribution probability | 0.70 |
| Automatic negative reasons | `internal_only`, `outside_help_scope` |

All relevant conditions must pass. A tied action always requires assessment. `preexisting_only` cannot authorize automatic completion because an agent must record the separate finding. Other negative reasons can be enabled explicitly after observing their behavior. Partial policy overrides preserve unspecified defaults and reject unknown field names.

The worker configuration selects both triage files as context attachments:

```json
{
  "documentation_triage": {
    "packet_path": "packet.json",
    "report_path": "jev-report.json",
    "policy": { "positive_probability": 0.85 }
  }
}
```

The complete worker configuration and branch lifecycle are described in `DOCUMENTATION-CYCLE.md`. The local completion is a delivery plan, not an already-published GitHub comment or status change.

Policy changes create new request identities. Re-evaluations preserve old artifacts, and examples used to improve a policy belong to a regression set rather than an independent accuracy benchmark. Record false completion decisions, unnecessary writing, escalations, and the subsequent agent or human disposition when the pipeline runs on real issues.
