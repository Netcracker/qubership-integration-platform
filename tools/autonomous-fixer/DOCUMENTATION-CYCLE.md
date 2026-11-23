# Local documentation cycle

The local `work` command accepts `task_kind: "documentation"`. It reuses the native runtime, fresh stage sessions, source snapshots, context binding, and artifact checks. Its stage controller lives in `internal/doccycle`; the implementation controller remains unchanged.

The controller supports three entry routes:

- `docs-assess` investigates the issue before any writing. This is the default when no classifier report is configured, the result is uncertain, or the configured routing thresholds do not pass.
- `docs-write` combines investigation of the alleged gap and the edit in one native invocation. A strong positive classification selects this route. The writer can dismiss a false positive with a supported no-change decision only while the checkout remains unchanged. An accepted patch proceeds through independent `docs-review` and `docs-verify`; findings return to the writer.
- `docs-no-change` completes a permitted high-confidence negative classification without a native invocation. The controller records classifier provenance and an English comment draft, verifies the unchanged source and context, and writes a receipt. It does not invent an assessor observation.

A supported native `no_change` result also produces `docs-no-change` without a mandatory second reviewer. `investigate` or an explicit blocking question preserves the work and stops with `needs-human` or `verification-blocked`. Required final checks must pass before a documentation candidate becomes `docs-ready-for-pr`.

No branch publishes to GitHub. `delivery-draft.json` records proposed actions with `github_effects_executed: false`. For a documentation candidate, `candidate.patch` and `candidate-files/` preserve the tracked diff and current changed files, including new files. The dedicated worktree remains available for inspection. A deleted file is listed in `changed_paths` and represented in the patch.

## Context and scope

The required skill is `docs-authoring`, rather than `fix-issue`. The full root guidance, selected instructions, task, and attachments are bound to the context packet. Include `english-developer-style` in the selected instructions. Use the actual documentation tree when a historical skill example names an older page path.

Supply a frozen issue and merged-change packet.
Configured triage selects the entry route from the recorded probabilities and consistency checks; simply attaching a prediction does not enable routing.
A positive Jev prediction remains a hypothesis for the combined writer to investigate.
Incomplete evidence goes to `docs-assess`.
A valid no-change result finishes without a mandatory second reviewer.
Code, environment configuration, developer reference, and product help have different audiences.
The controller permits writes only under `help/docs/`.
A no-change result needs evidence showing why the current help remains adequate or why the change falls outside its reader scope.

The decision contains an action (`needed`, `no_change`, or `investigate`) and a separate reason. `needed` uses `missing_guidance` or `stale_guidance`. `no_change` uses `already_covered`, `restored_behavior`, `no_explanation_needed`, `internal_only`, `outside_help_scope`, or `preexisting_only`. `investigate` uses `insufficient_evidence` or `scope_unclear`.

Each claim has an ID, the relevant behavior, attribution, a `needs_change` flag, source evidence, documentation evidence, and the proposed action. Attribution is `change_related`, `unchanged`, or `unclear`; unclear attribution requires investigation. A needed decision requires at least one change-related claim needing an edit. A no-change decision cannot contain a main claim needing an edit.

Independent preexisting defects go in `separate_findings`, with `preexisting_independent` attribution and their own evidence.
They are recorded for separate triage and cannot justify a PR for this issue.
The assessor compares before and after behavior and reads complete sections and linked explanations before classifying a statement as wrong.
The writer works only on required change-related claims.
Independent review checks that the actual patch respects this scope.
These declarations preserve the intended scope; they do not prove semantic correctness by themselves.
Claims and separate findings from assessment cannot disappear during writing or repair.
Their IDs, attribution, `needs_change` values, and collection membership remain fixed.
The same checks apply when resuming final verification.
Every returned evidence reference points to a new file in the current invocation's external evidence directory; source/doc paths and line numbers go inside those files.
Independent reviewers receive the contract and evidence rather than the author's conversation.

If a writer omits a required claim from an otherwise parsed declaration, the controller returns a precise protocol finding and requests a corrected declaration. Repeating the same error on the same candidate stops with a question. It does not silently discard already-covered consumers from the assessment.

The native adapter attaches candidate identity from its own invocation record; the documentation model does not transcribe hashes. The original response and a separate binding record are preserved. Read-only snapshot checks still reject a reviewer or verifier that changes the checkout, even when its response claims success.

The adapter also collects the evidence references supplied in claims, separate findings, checks, and reviewer findings. A model need not repeat those same references in a top-level list. Every collected reference still passes the native file, location, freshness, and digest checks.

## Configuration

Use the same native runtime fields as the implementation cycle, with:

```json
{
  "task_kind": "documentation",
  "task_id": "owner/repo#852:documentation",
  "profile": {"Runtime": "codex", "Model": "gpt-6.1-sol", "Effort": "medium"},
  "context": {
    "instructions": [".agents/skills/english-developer-style/SKILL.md", "help/README.md"],
    "attachments": ["packet.json"]
  },
  "required_checks": [
    {"id": "diff_scope", "scope": "documentation source changes"},
    {"id": "source_facts", "scope": "changed reader-facing claims"},
    {"id": "structure_links", "scope": "changed pages and their local links"},
    {"id": "documentation_index", "scope": "current help TOC and search index"}
  ]
}
```

This fragment needs the absolute workspace, task/output directories, runtime binary/version, catalog, and login fields described in `WORK-CYCLE.md`. It is not an instruction to start a model call. The dedicated checkout starts clean; generated indices, check scripts, and reports belong outside it.

To enable classifier routing, include both files in `context.attachments` and add:

```json
{
  "documentation_triage": {
    "packet_path": "packet.json",
    "report_path": "jev-report.json"
  }
}
```

Paths may be absolute or relative to `task_directory`; both must resolve to selected task attachments. The task ID must be `<repository>#<issue>:documentation`. The packet's documentation SHA must equal the worktree HEAD. The adapter verifies the report against the packet and current classifier policy, then recomputes routing from the raw answers. Stored route and recommendation fields cannot bypass these checks. An optional `policy` object overrides routing thresholds; [Jev classification](JEV.md) describes the defaults and reasons eligible for automatic no-change.

`routing.json` records the selected route and thresholds. `evidence/documentation-routing.json` records the inputs and provenance, and `controller-evidence-sha256.json` binds this evidence even when no model runs. The final receipt binds the same evidence together with the source and context. A malformed, mismatched, or stale classifier artifact is rejected before any native invocation. A valid recorded classifier failure uses `docs-assess`.

Document the check procedures in `task.md`. For prose verified against source, no product startup or A/B dataset is required. Execute commands or capture the product when the changed claim actually needs that evidence. Existing broken links or unrelated baseline lint problems must be distinguished from new problems rather than silently repaired or reported as new regressions.

PR drafts use `Related #N` without closing keywords. The future GitHub publisher must revalidate the issue, project status, merged basis, candidate, and current documentation before creating effects. It must also adapt the repository's closing-link gate. The local draft does not establish that those GitHub integrations are ready.

## Repeat only final verification

For an unchanged candidate that already passed independent review, set `resume_from` to the absolute path of the previous `report.json` and select a new `output_directory`. Keep the same worktree, task, profile, context, required checks, and triage configuration. A run that began with the combined writer can resume after its independent review; the controller verifies its original routing evidence. This mode validates the previous stage history, current snapshot, and saved evidence hashes, then runs only a fresh `docs-verify` invocation. The earlier reports and failed attempts remain intact.

Automatic zero-invocation completion has no native stage to resume. The recovery path does not bypass review, accept a changed candidate, or resume arbitrary human decisions. A changed task or code requires a new appropriate cycle. GitHub question delivery and general checkpoint restoration remain separate integration work.

For a completed, read-only negative assessment rejected only by a declaration-format problem, `resume_mode: "assessment"` revalidates the saved decision without invoking a model. It requires exactly one unchanged assessment, matching task/context/profile, an unchanged current snapshot, and verified hashes for every referenced evidence file. Positive decisions, changed source, questions, or missing evidence cannot use this path. The new output records the original report in `replay-source.json`; the original response remains intact.

Archived reports using the earlier combined decision categories remain unchanged. They require a new assessment before resuming under this contract; the controller does not infer missing reasons or attribution.

## Inspection and metrics

The output contains per-stage prompts, responses, runtime logs, fresh evidence, checkpoints, final report, and delivery drafts. A successful final receipt binds the source, context, report, and evidence identities. These checks establish provenance and process completeness, not the semantic truth of every model claim.

After the native invocations finish, collect token usage and the API-equivalent at the existing pricing snapshot:

```bash
go -C tools/autonomous-fixer run ./cmd/work-metrics /absolute/path/to/work-output
```

The result is an estimate, not a subscription invoice. It excludes preparation and human review. The metric collector preserves unknown usage and includes only finished invocations.

Module tests cover all three branches and the native fake-process path. They reject source edits from an assessor/reviewer, writes outside help, dropped claims, missing or stale checks, and overwritten evidence. Product code is unchanged, so no new product end-to-end case is required for this controller change.

## Large evidence packets

The documentation workflow on GitHub Actions sets `context.attachments_as_files: true` to fit the native CLI's initial prompt limit. The prompt includes the complete root instructions, documentation skill, and task text. It gives the task directory for the evidence files, which the context packet lists by relative name, size, and SHA-256 digest. Verification reads and hashes each file before and after work, including after relocation for publication.
