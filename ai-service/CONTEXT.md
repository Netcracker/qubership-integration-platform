# ai-service

The AI-assisted chain design/generation service for the Qubership Integration Platform. Turns
user requirements into executable chains in the runtime-catalog, via LLM-driven generator skills.

## Language

**ChainPlanGraph**:
Flat, in-memory working model of a chain (`nodes` + `edges` + `PlanProperty` per node) that
generator skills build and read. Not persisted to the catalog directly — always goes through
materialization.
_Avoid_: chain draft, plan draft (use ChainPlanGraph)

**Materialization**:
One-directional, per-node write of a `ChainPlanGraph` into real catalog chain elements
(`ChainPlanSkeletonMaterializer` creates, `ChainPlanPropertiesMaterializer`/`ChainPlanConnectionsMaterializer`
patch). Already touch-only-what's-in-the-graph by construction: skeleton materializer refuses to
re-create a `nodeId` already present in the `MaterializationMap`; properties materializer only
patches (merge, not replace) nodes present in the passed graph.

**MaterializationMap**:
`nodeId -> catalogElementId` mapping plus `chainId`, produced by materialization and required by
every materializer/reconcile call to know which real catalog element a plan node corresponds to.

**GraphPatch**:
Existing explicit-operation patch format (`nodePatches`/`edgePatches`/`propertyPatches`/`chainPatches`,
`ownerCapabilityId`, `rationale`, base/result graph digests via `GraphPatchArtifact`) used today to
assemble a `ChainPlanGraph` incrementally during generation, applied via `GraphPatchApplier` and
scoped by `GraphPatchOwnershipPolicy` (which node types / properties / chain fields a given
capability may touch). Deliberately reused, not reinvented, for the partial-update feature: no new
patch format needed.
_Avoid_: diff, delta (use GraphPatch/patch)

**Reconcile** (`ChainReconcileService.compare`):
Write-then-verify check that compares a `ChainPlanGraph` this run just materialized against a
freshly-read catalog snapshot of the *same* chain, to confirm the write landed correctly.
**Not** a mechanism for comparing a new proposal against a pre-existing, previously-deployed chain
— despite the name, it has no use in importing/patching an existing chain.
_Avoid_: using "reconcile" to mean "compare against an existing user chain"

**COMPARE_AND_PATCH**:
The chat router scenario for editing an already-existing catalog chain, as opposed to
`CREATE_CHAIN_PLAN`/`IMPLEMENT_CHAIN`, which build from scratch. The classifier already knows it:
`router-system.md` (the prompt `RouterAgent` actually loads, assembled from `prompts/roles/*` by
`scripts/merge-system-prompts.groovy`) carries a full description, and `routing/route-examples.yaml`
carries few-shot utterances. What is missing is the handler: no `@ForScenario(COMPARE_AND_PATCH)`
bean exists, so `ScenarioRouter.coerceToSupportedHandler` silently degrades the classification to
`CREATE_CHAIN_PLAN`. Read `router-system.md`, not `prompts/roles/router.md`, to see what the
classifier is told.

**COMPARE_AND_PATCH scope** (decided):
The scenario covers element-property edits ("fix the script in element X") as well as structural
ones ("add an error-handling branch"). This widens the current router description, which says "QIP
element property PATCH is not COMPARE_AND_PATCH" -- that exclusion was written to fence the
scenario off from CREATE while it had no implementation. Both kinds run the same path (import ->
`GraphPatch` -> materialize), and `GraphPatch` already carries `nodePatches` and `propertyPatches`
side by side, so splitting them into two scenarios would duplicate a handler to satisfy one line of
prompt text. The exclusion sentence in `router-system.md` needs rewriting as part of the feature.

**Chain import** (proposed, does not exist yet):
The one missing primitive for partial chain updates: reading an existing real catalog chain into a
`ChainPlanGraph` + seeded `MaterializationMap` (identity: existing `elementId` becomes the
`nodeId`) + a `baseGraphDigest` (via `CanonicalGraphDigest`). Once this exists, the rest of the
partial-update pipeline is existing infrastructure reused as-is: generator skill emits a `GraphPatch`
against the imported graph -> `GraphPatchApplier` -> materializers touch only the patched nodes.

**Chain import mapping** (decided):
`ChainCatalogFactsService.load(chainId)` already reads the full real chain (elements + dependencies,
flattened) for reconcile/presentation today -- the import primitive is a small mapper from
`ChainCatalogFacts` to `ChainPlanGraph`, not a new catalog integration. `ChainPlanNode.order`
(container priority, e.g. catch-N) has no dedicated source: runtime-catalog stores it as a regular
property (`OrderedElementUtils.getPriorityAsInt` reads `element.getProperty(priorityProperty)`), so
it already survives as a normal `PlanProperty` when properties are carried over. Import leaves
`ChainPlanNode.order = null` -- nothing is lost, `order` is just AI-plan-side sugar duplicating a
property that's already present.

**Node targeting** (decided):
No UI click-to-select elementId — a stray click would silently redirect the edit to the wrong
element. The generator skill resolves the target node itself: by name/type mentioned in the user's
prompt, or by matching identifiers/names that appear in pasted log text, searched within the
imported `ChainPlanGraph`. Ambiguous or no match -> the skill asks the user for the exact name or
id, it does not guess. Same resolution path for chat requests and log-driven bugfixes; no separate
mechanism for either.

**Router prompt**:
`RouterAgent` loads `prompts/router-system.md` from the classpath, and that file is **generated** at
`process-resources` by `scripts/merge-system-prompts.groovy` from `prompts/qip-base-system.md` plus
`prompts/roles/router.md`. It overwrites the same-named file checked in under
`src/main/resources/prompts/`, which therefore never reaches the model — edit `roles/router.md`.
`routing/route-examples.yaml` is read by nothing.

**Routing** (decided):
`COMPARE_AND_PATCH` stays with the LLM classifier (`router-system.md`/`RouterAgent`). No
`PhaseRoutingPolicy`/`UserIntentPatterns` heuristic shortcut, unlike `ASK_CHAIN`, which a phase
heuristic intercepts before the classifier runs. Dispatch goes through the plain `ScenarioHandler`
CDI mechanism (`@ForScenario(COMPARE_AND_PATCH)`, same shape as `ChainQuestionScenario`), not the
CREATE product pipeline -- `isCreateOwnedScenario` does not list it.

**Causal reopen**:
Returning a run to the stage that *produced* the defective input artifact, not to the stage that
observed the failure. The owning stage emits a new candidate; the previous Decision card binding
is stale until the user accepts the new one.
_Avoid_: go back one step, retry previous stage, silent rewrite of an approved plan

**Repair candidate**:
The replacement artifact produced after diagnosis, shown on a Decision card. The pipeline does not
continue on that artifact until the user accepts the new binding.
_Avoid_: auto-approved fix, in-place edit of an approved plan

**Owner diagnosis**:
Choosing the stage that produced the defective artifact. Candidates are the producers of the failed
stage's inputs (and those producers' inputs, if the search must go deeper). The deterministic
router picks among that set from the typed cause. If more than one candidate stays plausible, a
Decision card asks which artifact to revise. More owner-choice cards is the recorded trade for
dropping model-assisted disambiguation: a card the author answers beats a routing decision nothing
can check.
_Avoid_: previous-approval heuristic, unconstrained LLM blame, a second authority that names the owner

**Recoverable halt**:
A pause that keeps the run inside the product pipeline. The user can retry the current stage, go
back to an earlier approval, ask questions, or revise the plan. The run is not finished and is not
a tombstone. A typed message at this pause is a halt follow-up: it stays on this run and continues
the diagnosis, and it is not a new router classification. Every command at this pause advances the
semantic recovery state, or produces a transcript message that answers what was typed. No command
produces neither.
_Avoid_: terminal FAILED, abort the conversation, irreversible pipeline death, drop out of CREATE

**Failure narrative**:
LLM-authored explanation of what went wrong, written for the user at a recoverable halt. The
runtime supplies structured evidence (outcome class, exception, validation findings, stage id) and
the instruction naming what to change. The model authors the explanation only. A technical halt
uses a short narration turn on the same evidence. If that turn fails, the card keeps its actions,
the raw evidence, and the runtime instruction.
_Avoid_: hardcoded halt copy, template error strings, "rolled back to stage X", a model-authored prescription

**Halt question**:
A typed message at a pause that asks about the run rather than instructing it. A model tells the
two apart, so the decision holds in the language of the conversation; the regex plan-question path
is English-only and never reaches here. Three outcomes: an answer, a message that was not a
question, and an inability to answer. An unanswerable turn produces a card that says no explanation
is available and keeps the raw evidence; it is never treated as an instruction. The answer is
written from the evidence the card was already built from, arrives as a transcript message, and
leaves the run status and the card where they were. Answers are deduplicated by the identity of
the question and the evidence. Question turns do not spend the explanation budget. Rationing an
author's questions by count is the thing to avoid; an absolute model-call ceiling a working
conversation never reaches is a process-safety backstop, not that budget.
_Avoid_: rationing questions by a per-run count, keyword matching for "why", moving the run to answer it

**Failure routing** (decided):
A recoverable halt is the only user-visible stop. Same-stage technical retry still runs first;
when that budget is exhausted the halt card always includes Retry (including connection loss).
Validation, domain, and contract failures take owner diagnosis and causal reopen. Missing mandatory
input and policy failures halt on a Decision card; the model does not rewrite policy. No outcome
class may leave the user with nothing to do.

**Internal failure**:
An invariant broken inside the service, as opposed to a model reply the contract rejects: a
capability that emits the wrong number of completion signals, an artifact kind the profile never
declared, or a throwable nothing classified. It halts recoverably like every other outcome, and
the card carries the run identifier the author hands to support, but it offers no Retry, because
re-entering the stage meets the same defect. Owner diagnosis considers only earlier producers. The
card binds a valid producer stage directly. Without one, the card offers Stop with report so the
author can end the run.
_Avoid_: contract failure for a service defect, a Retry that cannot work, terminal FAILED

**Causal reopen window** (decided):
Causal reopen runs only before any materializer has written to the catalog. A failure at or after
materialization is outside this feature: the model does not reopen the plan and write again on its
own.
_Avoid_: auto-reopen after a catalog write

**Reasoning effort** (deferred):
Owner diagnosis and the failure narrative use the ordinary chat model. This feature does not turn
on service-wide `LLM_REASONING_EFFORT`.
_Avoid_: global reasoning as a substitute for halt or retry

**Decision card** (`ChatEvent.Decision`):
The typed gate a run stops at, rendered as a card in the transcript and answered by a
`ChatDecisionCommand` on its own endpoint. The card carries its own binding (`artifactType`,
`artifactHash`, `revision`) and the `actions` it accepts; `ChatDecisionService` applies the answer
with "no routing, no classification, no model call", and refuses a stale binding by re-issuing the
gate the run actually waits at. This replaced approval by prose: matching an English word such as
"Agree" in the next message was removed from the codebase, because no reply in another language
ever matched it. Any new user confirmation belongs here, not in a phrase the user has to type.
`CREATE_ACTION` states the underlying rule: writing to the catalog is "the one irreversible step,
never a model's to take".
_Avoid_: approval prose, "type yes to confirm"

**DEPLOY_CHAIN** (decided):
The chat scenario for catalog Snapshot, deploy/redeploy, undeploy, and deployment status of an
identifiable chain (open graph, just-created, or name/id). Graph explanation stays `ASK_CHAIN`.
A chain cannot run on an engine without a catalog Snapshot; bare "deploy" creates a new Snapshot
only when the chain has none or has unsaved changes, otherwise reuses `currentSnapshot` / latest.
Default engine domain is `default` unless the user asks to choose or `default` is unavailable.
After create/redeploy the chat waits briefly for Deployed, Failed, or Warning, then reports;
Progressing past that timeout is still a valid answer. Snapshot build failure stops the flow with
a plain-language reason from the catalog error; it does not fall back to an older Snapshot when the
user wanted the current graph. Auto-repair via `COMPARE_AND_PATCH` is a later stage, not this
scenario.
_Avoid_: folding deploy into ASK_CHAIN, silent deploy of a stale Snapshot after a failed build

**Catalog Snapshot**:
The runtime-catalog versioned XML cut of a chain that deployment requires. Distinct from in-memory
run/task "snapshots" elsewhere in this service. User-requested create is in scope for
`DEPLOY_CHAIN`; automatic Snapshot-around-every-patch is not (see Failed write).
_Avoid_: calling a run document or reconcile read a "snapshot" when you mean this catalog object

**Chain deployment** (decided):
Binding of one catalog Snapshot to one engine domain so the chain runs there. At most one
deployment per chain per domain; replacing it is redeploy. Confirmation: open graph and no
existing deployment on the target domain runs immediately; otherwise one Decision card whose
action is Deploy or Redeploy from current state. Undeploy always uses a Decision card. Out of
scope for v1: revert-to-snapshot, multi-domain in one request, a separate micro-engine path, A2A
without UI.
_Avoid_: deploy without a Snapshot, prose "yes" to confirm undeploy or redeploy

## Regression testing

`run-suite.sh` (integration-platform-skills/regression/bin/) drives `/api/v1/harness/skill-run`
(`SkillHarnessResource`) against a real catalog chain it creates for the run. This harness path is
scoped to single-element regression checks for generator skills, deliberately avoids
`ChainPlanGraph` (README: "Do not use ChainPlanGraph on this path"), and bypasses the chat router
entirely — it is not a production entry point.

Interactive `COMPARE_AND_PATCH` is not part of this harness. Cover it with
`ai-service/e2e/product-pipeline/` (`run-patch-scenario.sh` through chat SSE and the decision card).

**Failed write** (decided, ADR 0002):
A patch that fails partway is unwound in reverse through the same materializers that wrote it, and
`ChainPatchWriteResult.rollback` reports what became of it. Only two steps cannot be compensated: a
property key the patch introduced stays, because the properties merge never deletes a key
(`PARTIAL`), and a deleted element does not come back (`REFUSED`, naming what is gone). Deleting
elements is therefore the last step and one atomic bulk call. Snapshots were rejected as an
automatic mechanism -- the catalog refuses one on a chain that fails property verification, which is
exactly the mid-edit state the semantic validator tolerates, and building one overwrites
`currentSnapshot`.
_Avoid_: taking a snapshot around every patch, recreating a deleted element under its old name
