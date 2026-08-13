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

## Regression testing

`run-suite.sh` (integration-platform-skills/regression/bin/) drives `/api/v1/harness/skill-run`
(`SkillHarnessResource`) against a real catalog chain it creates for the run. This harness path is
scoped to single-element regression checks for generator skills, deliberately avoids
`ChainPlanGraph` (README: "Do not use ChainPlanGraph on this path"), and bypasses the chat router
entirely — it is not a production entry point.

**COMPARE_AND_PATCH regression mode** (built):
A second harness mode (`run-patch-suite.sh`), separate from the single-element-on-empty-chain
suites above: seeds a small non-empty base chain per case, drives `POST
/api/v1/harness/chain-patch-run` (`ChainPatchHarnessService`) — the same import→agent→capture→
apply→write path `ChainPatchScenario` uses in production, via the shared `ChainPatchPipeline`,
minus the decision card (ADR 0001; a regression run has no reader to answer one) — and compares
both (a) the patched element(s) against golden and (b) every untouched element reads back exactly
as seeded. The second check is what actually proves the patch didn't touch anything outside its
scope; a violation reports as `SCOPE_VIOLATION`, distinct from an ordinary `FAIL`. Case files and
the case-shape README section live under `integration-platform-skills/regression/`.
