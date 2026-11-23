# Documentation feedback

Capture a completed routed documentation run as an immutable record:

```bash
go -C tools/autonomous-fixer run ./cmd/docs-feedback import \
  --run /absolute/path/to/work-output \
  --output /absolute/path/to/new-feedback.json
```

The importer verifies the completion receipt, source and context identities, routing evidence, classifier request, and selected route. It records the raw prediction and observed worker outcome separately. Explicit preflight skips preserve `request_attempted=false` and are counted separately from classifications. The initial assessment status is `pending`; a completed candidate or an agent's no-change result is not a correctness label.

A downloaded standalone archive can be imported from another directory. Paths under the recorded `output_directory` map to the downloaded root, with their original hashes. External paths, path escapes, and symlinks are rejected. Relocated resume archives need a separate prior-archive mapping and are unsupported. Failed or unfinished runs without a completion receipt are also unsupported.

Record an explicit later judgment in a new file:

```bash
go -C tools/autonomous-fixer run ./cmd/docs-feedback adjudicate \
  --record /absolute/path/to/new-feedback.json \
  --kind context_gap \
  --actor reviewer-login \
  --actor-type human \
  --basis "The supplied evidence omitted the affected consumer." \
  --evidence https://github.com/OWNER/REPO/issues/NUMBER#issuecomment-COMMENT_ID \
  --at 2026-11-13T10:00:00Z \
  --output /absolute/path/to/adjudicated-feedback.json
```

Supported kinds are `missed_needed_change`, `unnecessary_change`, `context_gap`, `editorial_disagreement`, and `confirmed_correct`. Error labels concern the classifier's raw prediction. The controller may have prevented the corresponding downstream action by routing the case to an assessor. An ordinary PR revision or merge does not imply an error or a correct prediction.

`actor-type` distinguishes a declared human judgment from a declared model judgment. This local CLI does not authenticate the actor; GitHub integration must authenticate the event before recording its author. An evidence reference is either an HTTPS URL or an absolute file whose content hash is stored. The timestamp is explicit. Labels are not sent back into classifier inputs.

Summarize records:

```bash
go -C tools/autonomous-fixer run ./cmd/docs-feedback summary \
  /absolute/path/to/new-feedback.json \
  /absolute/path/to/adjudicated-feedback.json
```

A dataset `case_id` identifies the task and classifier input.
An `observation_id` also binds the provider request ID and worker report hash, so repeated calls remain separate observations.
An adjudicated record supersedes its predecessor within that observation.
The summary counts distinct dataset cases and observations separately, retains repeated attempts, and rejects conflicting current labels for one observation.
Groups distinguish the model, classifier policy, routing policy, and recommendation reason.
Human and model judgments remain separate, and pending observations stay out of judgment counts.
The summary does not estimate overall accuracy or economic savings.

Library entry points are `Import`, `Adjudicate`, `Validate`, `WriteNew`, `Evidence`, and `Summarize` in `internal/docfeedback`. Exported records can be adjudicated after a policy upgrade without reinterpreting their original predictions. Importing a run requires the classifier policy understood by the installed importer.

## Sample automatic no-change outcomes

Select a reproducible fraction of pending automatic no-change results for an independent audit. Set `audit_seed` to your campaign's seed. For a rerun, use the seed recorded in the audit manifest.

```bash
go -C tools/autonomous-fixer run ./cmd/docs-feedback audit \
  --seed "$audit_seed" \
  --fraction 0.10 \
  --output /absolute/path/to/new-audit-candidates.json \
  /absolute/path/to/pending-feedback.json \
  /absolute/path/to/adjudicated-feedback.json
```

Both the seed and fraction are required. The fraction must be between 0 and 1: zero selects none, and one selects all eligible pending revisions. A fraction is a sampling probability, not a promise to select exactly that percentage from a small batch. Keep the seed fixed for a campaign instead of changing it until a preferred subset appears.

The `docs-audit-v1` sample hashes the seed, repository, issue, implementation base and head, source revision, and documentation revision. Repeated observations at the same revisions share one sample decision and retain their individual feedback references. A changed source, implementation, or documentation revision gets a new sample identity; it may still land on the same side of the threshold. Input order and repeated input paths do not change the selection.

Only completed `docs-no-change` results with classifier origin qualify. An assessor's no-change result and documentation-writing runs are excluded. Supply the complete relevant feedback history: a supplied human or model judgment excludes that revision from another audit. Changed revisions remain independently eligible. Conflicting current judgments for the same observation are rejected by the existing feedback validator.

The command writes a new immutable candidate file and changes no feedback records. It neither invokes a model nor publishes a comment, closes an issue, or changes any of the three documentation routes. A completed local no-change result does not itself prove that GitHub closed the issue. The output is an audit work list, not a list of confirmed mistakes.

The November 2026 evaluation designated Terra as the independent documentation reviewer. Record a model assessment with `adjudicate`, using `--actor-type model` and the actual model identity, evidence, timestamp, and one of the existing judgment kinds. Use `missed_needed_change` only when the supplied implementation required a help change that the classifier missed. Human corrections use `--actor-type human`. The existing `summary` command keeps these judgments separate and retains the observed no-change result alongside its later correction. It does not treat model agreement as ground truth.

No additional documentation reviewer is required for outcomes outside the sample. A scheduler can consume the candidate file later; this command supplies selection and provenance only.
