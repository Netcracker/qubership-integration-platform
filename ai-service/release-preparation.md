# AI service release preparation

This checklist tracks the remaining work before delivery and integration into `main`.
The starting revision is `c211950ab`. Each change requires scoped checks, an independent review, and a local commit.
Subagents own separate files; the coordinating chat owns commits and the shared runtime.

## Delivery contract

- Supply the project and runtime packages through the existing unified build procedure.
- Consumers place runtime packages in `integration-platform-skills/rocky-skills` and `rocky-knowledge`.
- Without runtime packages, the service supports public documentation and disables CREATE and EDIT.
- Partial or invalid runtime packages fail the build without falling back to the base service.
- Keep the optional testing archive separate from runtime packages. Image variant tags are acceptance artifacts.

## Remaining steps

1. **UI checks — pending.** Remove the two reported unnecessary type assertions and resolve the hook dependency
   warning after checking its cause. Run the affected UI tests, lint, types, and formatting checks.
2. **Package inventory — pending.** Allow the optional source `testing/` directory while retaining strict runtime
   file inventory checks. Reproduce the existing certified-package failure and rerun the affected test.
3. **Documentation answers — pending.** Preserve source URLs without inventing a domain and preserve literal
   method/path variants when the user requests an exact listing. Check the affected prompts and repeat the
   relevant existing documentation cases. Record model identity and tool-read evidence.
4. **Candidate and live acceptance — pending.** Record the source revision, package checksums, image digests, and
   actual served model. Run the existing small CREATE/EDIT selection, including service calls, uploaded OpenAPI
   mapping, an incremental edit, and recovery. Read the generated result back from the catalog and check each
   case's assertions. Chain execution, if performed, also requires runtime session evidence and logging restoration.
5. **Merge readiness — pending.** Review the final diff and evidence, record unresolved findings and exclusions,
   and prepare the delivery summary. Recheck affected gates after any correction.

## Open gate and exclusions

The recovery response schema requires `authorQuestionEvidence.ambiguity` even for an ordinary conflicting-values
question. The existing `RecoveryDecisionSchemasTest` exposes this mismatch. The user deferred the production fix;
keep this test enabled and report the failure. A green Java acceptance claim and an unconditional merge remain blocked.

The latest full Java run before these steps recorded 6,578 tests, one failure, zero errors, and 11 skips.
The sole failure was the deferred schema case. Test fixtures were corrected in `c211950ab`.

A2A testing is excluded by user decision. Restart acceptance remains deferred. Do not expand model or recovery
budgets to make acceptance pass. New production findings require the user's selection under the repository rules.

## Evidence

Keep raw logs, model exchanges, and private package data outside the committed delivery files. The coordinating
checkout retains local evidence under `.dev/release-preparation-20261028/`. The final delivery summary must name
each accepted source revision and outcome, including failures and checks that did not run.
