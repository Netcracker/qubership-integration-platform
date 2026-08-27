# cip-structure-generator addon

## Upstream

- Source: `skills/cip-structure-generator/SKILL.md`
- Hash: `555aad66205b76ee24a28c8060d515663d8fb85483ab49d8be3bc91d9b38fe3e`
- Runtime mode: `CHAIN_STRUCTURE_GENERATOR`
- Status: `reviewed`

## Runtime contract

- Input artifacts: `ELEMENT_SKELETON`, `NAMING_MANIFEST`, `CONFIGURED_TRIGGER_SET`
- Capture tool: `captureChainStructure`
- Output artifacts: `CHAIN_STRUCTURE`, `CHAIN_PLAN_GRAPH`

## Applicability in ai-service

- Third **PLANNING** skill in the create-chain spine after naming and configured triggers.
- The semantic compiler already created the canonical graph seed. Capture labels, configured
  trigger properties, and remaining generator-owned structure on that seed. Do not rebuild topology
  from IDS markdown.
- Call **captureChainStructure** in the same turn once the DAG is valid and reachable.
- Do not invent behavioral element properties. Carry only the already configured trigger properties
  from `ConfiguredTriggerSet`.
- Do not call captureChainPlan or captureGraphPatch.

### Existing-chain edit mode

When the prompt contains an edit intent and a current `ChainPlanGraph`, capture `subgraph`, never
`graph`. `graph` re-emits the whole chain, so it lets you restate an element the edit never named;
`subgraph` describes only what the edit adds, so Java places it and reconnects the chain around it.

- **Wrap / branch (`NEST`):** name `containerType` and one entry in `branches` per child the
  container has. Each branch names its `childType`; `moveExisting` lists the ids of the existing
  elements that move into that branch, and nothing else does. A branch that creates new elements
  puts them in its own `body`, each with `nodeId`, `type`, and `label` — no `parentNodeId`, since
  the branch it is declared in is where it nests. See
  `examples/cip-structure-generator/valid-edit-wrap-subgraph.json`.
- **Insert (`KEEP`):** name no `containerType` and no `branches`. Put every new element the request
  describes in the top-level `body`, wired to each other in the order the request gives. The
  address elements are not named anywhere in the capture; Java splices the body between them and
  leaves them exactly as they are.
- **Replace (`REMOVE`):** same shape as an insertion — no `containerType`, new elements and their
  connections in the top-level `body` — except the address element being replaced is not named
  anywhere either. Java removes it and reconnects its neighbours to the body's entry and exit.
- Leave all configuration properties on new elements empty, including a catch's exception if the
  request does not distinguish it from a sibling. Downstream owners configure those elements from
  the structural delta. The one exception is a branch property that tells it apart from a sibling
  of the same child type, such as the exception a catch handles — set that on the branch itself,
  since assembly needs it to place the branch.
- An existing element you did not name is never mentioned anywhere in the capture — not in a
  branch, not in a body, not with a new type or property. Naming one this way is refused; leaving
  it out entirely is what keeps it exactly as it already is.

## Error-handling topology (ai-service)

- When the brief / raw request does **not** explicitly ask for error handling (or says
  "No error handling"), emit a **linear** graph: trigger → business nodes only. See
  `valid-linear-structure.json`. Do **not** add `try-catch-finally-2` / `try-2` / `catch-2`
  solely because GP-01 or the skeleton summary mentions them.
- When error handling **is** explicitly requested, emit a **complete** atomic wrapper in the same
  capture: `try-catch-finally-2` + `try-2` + `catch-2` (catch may have empty properties; EH
  property fill is owned by `cip-error-handling-generator`). Never leave a wrapper or `try-2`
  without its sibling `catch-2` — incomplete EH shells force a costly graph-patch repair later.
- Never emit orphan or half-built EH shells.

## Mapping rules

- Emit stable semantic node IDs, element types, containment (`parentNodeId`), and execution edges.
- Apply labels from `NamingManifest`. Prefer `NamingManifest.chainName` for `chain.name`.
- For routing shells (`condition` / `if` / `else`) keep descriptive labels from naming even when
  `properties` stay empty — for example `Minute parity` / `Even minute` / `Odd minute` in
  `valid-routing-structure.json`. Do not emit type-default labels (`Condition`, `If`, `Else`).
- **Catalog backend → `service-call`:** when the skeleton or brief requires a CIP catalog / APIHub /
  named integration service call, emit a `service-call` node (under `try-2` when EH is present).
  Do not substitute `script`, omit the workflow child, or leave an empty try shell. Binding
  properties stay empty here; `cip-service-call-generator` fills them.
- **Script roles from the skeleton / brief:** when `ELEMENT_SKELETON` includes a `script` role, or
  the brief requires a response/transform script after the backend call, emit a `script` node and
  wire it with edges (for example `http-trigger → service-call → script`). Do not drop the script
  node because a `service-call` is present. Script **bodies** stay empty here;
  `cip-script-generator` fills them.
- **Do not invent triggers:** emit `quartz-scheduler` (or any other trigger type) only when that
  role appears in `ELEMENT_SKELETON` / `ConfiguredTriggerSet`. Never add a schedule trigger to
  match a golden-pattern title.
- Preserve configured trigger properties exactly; do not add auth, routing expressions, retry,
  timeout, or other generator-owned behavior beyond topology from the skeleton.
- **Hard rule:** copy `contextPath`, `httpMethodRestrict`, and `externalRoute` from
  `ConfiguredTriggerSet` onto each matching `http-trigger` node. Never invent a different path
  (for example shortening `/health-proxy` to `/health`). Never emit `properties: null` or
  `properties: []` on a trigger that already has endpoint fields in `ConfiguredTriggerSet`.
- Reject cycles. Every node must be reachable from at least one trigger.

## Complete control-flow projection

Project every required behavior into structural element roles before calling the capture tool.
Do not omit nodes merely because their downstream generator will configure them later.

- A service invocation is one `service-call` node.
- A value normalization or response-body evaluation is a `script` node when the skeleton assigns
  it to script generation.
- A two-way decision is one `condition` container with both `if` and `else` children. Put each
  branch's required `log-record` or other workflow nodes under the matching branch.
- A structured error response is a `script` node under `catch-2` when the skeleton assigns response
  creation to script generation.
- Put the service call, evaluation script, and condition under `try-2` in their execution order.
  Connect flow siblings with edges scoped to their common branch. Do not connect container shells
  (`try-2`, `catch-2`, `if`, `else`) as if they were executable workflow steps.
- Keep all generator-owned properties empty on these nodes. The structure turn owns node type,
  label, containment, order, and execution edges only.

For an HTTP trigger followed by try/catch, a service call, value evaluation, an if/else log branch,
and an error response, follow `valid-try-catch-routing-structure.json`. This is one atomic
`captureChainStructure` call. Do not answer with prose, a partial graph, or separate captures.

## Containment vs flow (CIP catalog truth)

Triggers (`http-trigger` and every other trigger type) are **not** containers. They sit at the
chain root with `parentNodeId: null`. Top-level workflow nodes and top-level containers also use
`parentNodeId: null`. Flow order between those siblings is **only** `edges[]`.

Use `parentNodeId` for real catalog containers only, for example:

- `try-catch-finally-2` → `try-2` / `catch-2` / `finally-2`
- `try-2` / `catch-2` / `finally-2` → workflow children
- `condition` → `if` / `else`; `if` / `else` → branch workflow children
- `loop-2`, `split-2`, `split-async-2`, and similar shells → their body / branch children

**Never** set `parentNodeId` (or legacy `parentId`) to an `http-trigger` or any other trigger.
See `invalid-trigger-parent-structure.json`.

### Pattern-selector skeleton `children` under a trigger

Upstream GP-01 skeletons nest workflow roles under `http-trigger.children`. That nesting is
**logical / flow order only**, not CIP containment. When you build `ChainPlanGraph`:

1. Emit the trigger and each top-level flow step as root siblings (`parentNodeId: null`).
2. Wire them with `edges` (trigger → first root step → …).
3. Apply real `parentNodeId` only inside true containers (try/catch, if/else, loops, splits).

Correct linear shape: `valid-linear-structure.json`. Correct EH shape: trigger and
`try-catch-finally-2` are root siblings with one edge; scripts live under `try-2` / `catch-2`
(`valid-try-catch-structure.json`).

## Examples

- `examples/cip-structure-generator/valid-linear-structure.json`
- `examples/cip-structure-generator/valid-try-catch-structure.json` (CREATE: a new chain, so it
  still captures the whole `graph`)
- `examples/cip-structure-generator/valid-try-catch-routing-structure.json` (CREATE: service call,
  evaluation, condition branches, and catch response in one capture)
- `examples/cip-structure-generator/valid-edit-wrap-subgraph.json` (existing-chain edit: a wrap
  captures `subgraph`, never `graph`)
- `examples/cip-structure-generator/valid-routing-structure.json`
- `examples/cip-structure-generator/invalid-cycle-structure.json`
- `examples/cip-structure-generator/invalid-trigger-parent-structure.json` (anti-pattern)

## Readiness signals

```yaml
readiness:
  mode: ai-service-adapter
  signals:
    - always_ready
```

## Runtime metadata

```yaml
runtime:
  promoted: true
  category: runtime
  runtime-skill: true
  capture:
    tool: captureChainStructure
```
