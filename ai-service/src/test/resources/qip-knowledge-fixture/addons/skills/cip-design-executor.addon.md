# cip-design-executor addon

## Upstream

- Source: `.apm/skills/cip-design-executor/SKILL.md`
- Hash: `b68f499bc4b30a48635044da7bb67829bdeb68b62d57b29ffb709be38b115590`
- Runtime mode: `JAVA_ADAPTER`
- Status: `reviewed`
- Adapter: `cip-design-executor`
- Input artifacts: `IDS_DOCUMENT`, `CHAIN_SEMANTIC_REVISION`, `DESIGN_PLAN_REPORT`,
  `DESIGN_EXECUTION_PLAN`, `IMPLEMENTATION_PLAN`, `APPROVAL_RECORD`, `RUN_MANIFEST`
- Output artifacts: `CHAIN_PLAN_GRAPH`, `GRAPH_ASSEMBLY_RESULT`, `PLAN_VALIDATION_RESULT`,
  `COMPILER_VALIDATION_BUNDLE`,
  `EXECUTOR_VALIDATION_BUNDLE`, `VALIDATED_EXECUTION_BUNDLE`, `MATERIALIZATION_REQUEST`

## Applicability in ai-service

- Orchestrates an approved design plan through `CipDesignExecutorJavaAdapter` after
  implementation approval. Process orchestrator, not a GraphPatch skill.
- Not safe for the generic `captureGraphPatch` runtime. Do not invent empty GraphPatch
  examples for the executor itself.

## Phase mapping

| Upstream phase | ai-service mapping |
|---|---|
| Runtime Optimization | Pinned run context and `CompilerSkillContextBuilder` |
| Preconditions | Approval, report, projection, provenance, catalog, and pin verification |
| APIHub retrieval | `ExecutorCatalogBindingAdapter`: catalog lookup, then APIHub retrieval and import on a miss |
| Requirements normalization | Approved semantic revision and typed data mappings |
| Generator delegation | Execute the approved owning-skill closure and collect ordered graph patches |
| Assembly | Build `ChainPlanGraph` and `GraphAssemblyResult` from those patches |
| Validation | Produce fresh graph, plan, compiler, and executor validation results |
| File publishing | Delegate the write to product Java materialization and consume its reconciled result |

After Phase 5 the adapter checkpoints `WAITING_FOR_MATERIALIZATION` and emits
`VALIDATED_EXECUTION_BUNDLE` plus `MATERIALIZATION_REQUEST`. Phase 6 materialization is owned by
`MaterializationCapability`.

## Semantic node and edge ownership

The semantic compiler owns canonical `semantic node` and `execution edge` identity. This executor
applies the owning-skill closure to that pinned seed. It does not recover topology from IDS
markdown.

| Contract rule | Owner | Addon section | Runtime descriptor |
|---|---|---|---|
| http-trigger | cip-http-trigger-endpoint-generator | Semantic node and edge ownership | runtime-catalog/src/main/resources/elements/http-trigger/description.yaml |
| kafka-trigger-2 | cip-messaging-generator | Semantic node and edge ownership | runtime-catalog/src/main/resources/elements/kafka-trigger-2/description.yaml |
| service-call | cip-service-call-generator | Semantic node and edge ownership | runtime-catalog/src/main/resources/elements/service-call/description.yaml |
| script | cip-script-generator | Mapping rules | runtime-catalog/src/main/resources/elements/script/description.yml |
| mapper-2 | cip-transformation-generator | Semantic node and edge ownership | runtime-catalog/src/main/resources/elements/mapper-2/description.yml |
| condition | cip-structure-generator | Containment vs flow | runtime-catalog/src/main/resources/elements/condition/description.yaml |
| if | cip-structure-generator | Containment vs flow | runtime-catalog/src/main/resources/elements/if/description.yaml |
| split-2 | cip-structure-generator | Containment vs flow | runtime-catalog/src/main/resources/elements/split-2/description.yml |
| split-async-2 | cip-structure-generator | Containment vs flow | runtime-catalog/src/main/resources/elements/split-async-2/description.yaml#allowedChildren |
| loop-2 | cip-loop-generator | Semantic node and edge ownership | runtime-catalog/src/main/resources/elements/loop-2/description.yml |
| try-catch-finally-2 | cip-structure-generator | Error-handling topology | runtime-catalog/src/main/resources/elements/try-catch-finally-2/description.yaml |
| catch-2 | cip-error-handling-generator | Error-handling topology | runtime-catalog/src/main/resources/elements/catch-2/description.yaml |
| reconvergence | cip-structure-generator | Containment vs flow | runtime-catalog/src/main/resources/elements/split-async-2/description.yaml |
| generic-barrier | unsupported | Semantic node and edge ownership | unsupported |
| generic-aggregate | unsupported | Semantic node and edge ownership | unsupported |
| choice | unsupported | Semantic node and edge ownership | runtime-catalog/src/main/resources/elements/choice/description.yaml |

## Examples

- none

## Runtime metadata

```yaml
runtime:
  promoted: true
  category: runtime
  runtime-skill: true
```
