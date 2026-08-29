# cip-service-call-generator addon

## Upstream

- Source: `skills/cip-service-call-generator/SKILL.md`
- Hash: `186a1d4fa925771016b2c496710fdbe63bb94e312b126952cd2aba98ceee5fff`
- Runtime mode: `GRAPH_PATCH_GENERATOR`
- Status: `reviewed`

## Runtime contract

- Input artifacts: `CHAIN_PLAN_GRAPH`, `REQUIREMENT_BRIEF`, `RAW_USER_REQUEST`
- Capture tool: `captureGraphPatch`
- Output artifacts: `CHAIN_PLAN_GRAPH`, `GRAPH_PATCH`
- Patch kinds: primarily `propertyPatches` on integration nodes

## Applicability in ai-service

- Configure runtime options and hooks on existing `service-call` nodes. Java hydrates their
  catalog identity from resolved bindings before this generator runs.
- Do not emit `nodePatches` or `edgePatches`. `cip-chain-generator` and
  `cip-structure-generator` own executable topology. Configure properties on existing shells
  only.
- The same catalog cascade (service, specification, operation) on `http-trigger` Implemented
  Service and on `async-api-trigger` is owned here. Do not add trigger nodes; structure and
  `cip-trigger-generator` own topology. Custom URI fields (`contextPath`,
  `httpMethodRestrict`, `externalRoute`, `privateRoute`) are owned by
  `cip-http-trigger-endpoint-generator` on edit.
- Add hook properties only when the plan uses inline service-call handlers (`before` / `after`).
  When no before hook is needed, use `before: (type: none)` — never `before: []`.
- Java supplies the catalog binding on each `service-call`. If that binding is missing or stale,
  do not reconstruct it with property patches.
- Emit an empty patch when no owned runtime option or hook needs a change.
- Do not configure authentication or timeout (delegated to other generators).
- Do not invent a retry policy. `retryCount` and `retryDelay` are schema-required and default to
  `0` and `5000`; omit them or set those defaults. Custom retry belongs to `cip-retry-generator`.

## Trigger catalog id fidelity (hard forbid)

Never emit angle-bracket or template placeholders as catalog ids, including:

- `<your-integration-system-id>`
- `<your-integration-specification-id>`
- `<your-integration-specification-group-id>`
- `<your-integration-operation-id>`
- any other `<…>` token used as a stand-in UUID

For `http-trigger` and `async-api-trigger`, resolve real `integrationSystemId` /
`integrationSpecificationGroupId` / `integrationSpecificationId` / `integrationOperationId`
from:

1. The **Resolved catalog binding** block in the requirement draft / brief seed (when present).
2. Catalog tools (`searchCatalogSystems`, `getApiSpecifications`, `listCatalogOperations`) for the
   service and operation named in the brief.
3. Design-execution catalog binding resolutions seeded into `RAW_USER_REQUEST` /
   `REQUIREMENT_BRIEF` (systemId, specificationId, operationId lines).

If the brief names a catalog service/operation but ids are still unresolved, keep searching the
catalog — do **not** capture a patch with placeholder tokens to "pass schema".

## Not-applicable path (mandatory empty patch)

The create-chain derived spine **always schedules** this skill (DAG). Scheduling does **not** mean
you must invent a binding. Audit `CHAIN_PLAN_GRAPH` first.

**When any of these is true, call `captureGraphPatch` once with `notApplicable=true` and stop:**

1. The graph has **zero** `service-call` / sender nodes (`http-sender`, `kafka-sender-2`,
   `graphql-sender`, `rabbitmq-sender-2`, `scs-sender`, `dbaas`, and similar) **and** the generator
   plan does not name catalog keys on `http-trigger` or `async-api-trigger`.
2. `RAW_USER_REQUEST` or `REQUIREMENT_BRIEF` forbids service calls / APIHub / catalog binding
   (for example "No service calls", "No APIHub").

Set `notApplicable: true` and keep **all** patch arrays empty. Do not invent patches.

Copy this capture body (adapt only `patchId` if needed):

```json
{
  "patchId": "http-service-call-catalog-binding",
  "ownerCapabilityId": "cip-service-call-generator",
  "notApplicable": true,
  "nodePatches": [],
  "edgePatches": [],
  "propertyPatches": [],
  "chainPatches": [],
  "usedKnowledgeRefs": [],
  "rationale": "No service-call nodes are present in the chain, thus no service-call configurations are needed."
}
```

Canonical files:

- `examples/cip-service-call-generator/valid-patch-not-applicable-no-service-calls.json`
- `examples/cip-service-call-generator/valid-patch-empty.json` (bindings already complete; omit
  `notApplicable` or set `false`)

`notApplicable=true` with any non-empty node/edge/property/chain patch is **rejected** (fail closed).

### Hard forbids on the not-applicable path

| Forbidden | Why |
|-----------|-----|
| Any non-empty patch array while `notApplicable=true` | Tool rejects; empty arrays only |
| `chainPatches` on `name` (or any chain field) | Ownership violation; naming belongs to `cip-naming-generator` |
| `propertyPatches` with key `script` | Script bodies belong only to `cip-script-generator` |
| Inventing `service-call` nodes or catalog UUIDs | Brief forbids backends; notApplicable empty patch is the contract |

Failing to emit this capture (or emitting a rejected patch) fails the skill with
`contract failure: skill did not complete cip-service-call-generator`.

## Mapping rules

Use flat `propertyPatches` on the existing `service-call`, `http-trigger`, or
`async-api-trigger` `targetNodeId`. One `captureGraphPatch` must include the catalog
binding set for that type:

On `http-trigger` (Implemented Service) and `async-api-trigger`, emit the cascade keys owned in
runtime metadata. Do not emit Custom URI keys. Java owns catalog identity on `service-call`;
never submit catalog identity property patches for it. On `service-call`, emit only:

1. `propagateContext` — typically `true`
2. `errorThrowing` — typically `true`
3. `before`
4. `after`

Hook properties use catalog object shapes; use native JSON objects in `value` (not
JSON-in-string). Follow `global/graph-patch-contract.md`.

## Patch decision tree

| Graph / brief | Required capture |
|---------------|------------------|
| Zero service-call/sender nodes, and/or brief forbids service calls/APIHub | `valid-patch-not-applicable-no-service-calls.json` (`notApplicable: true`, all arrays empty) |
| Service-call nodes present; no runtime option or hook change needed | `valid-patch-empty.json` |
| Service-call nodes present; bindings incomplete | `valid-patch-empty.json`; the server binding is missing or stale |
| Hooks required on an existing binding | `valid-patch-service-call-hooks.json` |

## Examples

- `examples/cip-service-call-generator/valid-patch-not-applicable-no-service-calls.json`
- `examples/cip-service-call-generator/valid-patch-empty.json`
- `examples/cip-service-call-generator/valid-patch-service-call-hooks.json`

## Readiness signals

```yaml
readiness:
  mode: ai-service-adapter
  signals:
    - backend_integration_intent
    - service_call_nodes
    - incomplete_service_call_bindings
```

## Runtime metadata

```yaml
runtime:
  promoted: true
  category: runtime
  runtime-skill: true
  ownership:
    mayAddNodes: false
    mayAddEdges: false
    nodeTypes: [service-call, http-sender, kafka-sender-2, graphql-sender, rabbitmq-sender-2, scs-sender, dbaas, mapper-2, header-modification]
    chainFields: []
    properties:
      service-call: [propagateContext, errorThrowing, before, after]
      http-trigger: [systemType, integrationSystemId, integrationSpecificationGroupId, integrationSpecificationId, integrationOperationId, integrationOperationPath]
      async-api-trigger: [systemType, integrationSystemId, integrationSpecificationGroupId, integrationSpecificationId, integrationOperationId, integrationOperationPath, integrationOperationProtocolType, integrationOperationMethod]
      http-sender: [path, method]
      kafka-sender-2: [topic]
      graphql-sender: [operationName]
      rabbitmq-sender-2: [exchange, routingKey]
      scs-sender: [bindingName]
      dbaas: [query]
      mapper-2: [mapping]
      header-modification: [headers]
  capture:
    tool: captureGraphPatch
```
