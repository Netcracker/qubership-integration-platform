# Skill: QIP Chain Structural Patterns

Use this skill in **CREATE_CHAIN_PLAN** and **IMPLEMENT_CHAIN** whenever the chain contains
routing, branching, loop, error-handling, split, or circuit-breaker containers.

**§10 IMPLEMENT_CHAIN failure policy** applies only in **IMPLEMENT_CHAIN** (materialize/repair), not during planning.

These examples are structural templates only. Select only the containers required by the user
request or IDS. Keep the generated plan focused on the requested flow.

---

## 1. Required construction rules

1. Represent every QIP chain as a tree first:
   root elements go in `elements[]`; nested elements go in the direct parent's `children[]`.
2. Use `connections[]` only for runtime execution dependencies between **sibling** elements on the
   **same level**: root `connections[]` connects direct siblings in `elements[]`; each container’s
   own `connections[]` connects direct siblings in that node’s `children[]` (for example script →
   service-call under the same `if`). Do not skip nested lists when sequential workflow steps share
   a parent.
3. Put routing shell elements directly inside their container:
   `if` / `else` inside `condition`; `try-2` / `catch-2` / `finally-2` inside
   `try-catch-finally-2`; split branch shells inside `split-2` or `split-async-2`.
4. Put business steps under the branch or block where they run:
   `service-call`, `script`, `mapper-2`, senders, logs, and similar workflow elements go under
   the relevant shell's `children[]`, or directly under `loop-2.children[]` for loop bodies.
5. Put properties on the element that owns them:
   condition predicates on `if.expectedProperties.condition`; loop control on
   `loop-2.expectedProperties`; circuit-breaker settings on
   `circuit-breaker-configuration-2.expectedProperties`; operation binding ids on the
   `service-call` element.
6. Create or plan shell children according to the relationship matrix before adding normal
   workflow steps.
7. Call `describeElementPatchSchema(type)` for each container type before manually patching
   properties. Use `describeElementProperty(type, path)` when details are available.

---

## 2. Tree and dependency model

Use this mental model for both `ChainImplementationPlan` and catalog repair:

- Tree placement answers: "Where does this element live?"
- Runtime dependencies answer: "What runs before what?"

Correct placement examples:

- `condition.children[]` contains `if` and optional `else`.
- `if.children[]` contains workflow steps for the true branch.
- `else.children[]` contains workflow steps for the fallback branch.
- `try-2.children[]` contains protected workflow steps.
- `catch-2.children[]` contains exception handling steps.
- `loop-2.children[]` contains the loop body.

Correct dependency examples:

- Root: full chain between workflow siblings in `elements[]` (for example `http-trigger` → `script` → `service-call` → … → `condition`).
- Nested: only on the **container** element’s `connections[]`, and only between **workflow** siblings in that container’s `children[]` (for example `script` → `service-call` under the same `if`, both listed in `if.children[]`).
- A dependency entering a root **container** workflow step from outside (for example last root `script` → `condition`), not into an `if`/`else` shell.

Critical structural errors:

- Root-level `if` or `else`.
- Any `connections[]` edge whose **from** or **to** is a shell type (`if`, `else`, `try-2`, `catch-2`, `finally-2`, split shells, …).
- `condition -> if` or `condition -> else` in `connections[]` (catalog: *dependency to container child cannot be created*).
- `if -> service-call` or `if -> script` in `connections[]` when those rows are already under that `if` in `children[]` (placement only).
- `else -> script` in `connections[]` for the same reason when the script is under `else.children[]`.
- `condition.expectedProperties.condition`; the predicate belongs to `if`.

---

## 3. Container relationship matrix

| Parent type           | Allowed direct child              | Multiplicity                | Parent properties                                                    | Child or shell properties                                          | Normal workflow steps go under               |
| --------------------- | --------------------------------- | --------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------------ | -------------------------------------------- |
| `condition`           | `if`                              | `one-or-many`               | Keep empty unless schema says otherwise                              | `condition` is required; use `priority` for multiple `if` branches | `if.children[]`                              |
| `condition`           | `else`                            | `one-or-zero`               | Keep empty unless schema says otherwise                              | Usually empty                                                      | `else.children[]`                            |
| `split-2`             | `main-split-element-2`            | `one-or-zero`               | `timeout`, `stopOnException`, `aggregationStrategy` only when needed | `splitName` when required by schema                                | `main-split-element-2.children[]`            |
| `split-2`             | `split-element-2`                 | `one-or-many`               | Same as above                                                        | `splitName` should be unique per branch                            | `split-element-2.children[]`                 |
| `split-async-2`       | `async-split-element-2`           | `one-or-many`               | Schema-backed keys only                                              | Branch metadata only when schema requires it                       | `async-split-element-2.children[]`           |
| `try-catch-finally-2` | `try-2`                           | `one`                       | Keep empty unless schema says otherwise                              | Usually empty                                                      | `try-2.children[]`                           |
| `try-catch-finally-2` | `catch-2`                         | `one-or-many`               | Keep empty unless schema says otherwise                              | Exception settings only when schema requires them                  | `catch-2.children[]`                         |
| `try-catch-finally-2` | `finally-2`                       | `one-or-zero`               | Keep empty unless schema says otherwise                              | Usually empty                                                      | `finally-2.children[]`                       |
| `circuit-breaker-2`   | `circuit-breaker-configuration-2` | `one`                       | Keep empty unless schema says otherwise                              | Circuit-breaker settings live here                                 | `circuit-breaker-configuration-2.children[]` |
| `circuit-breaker-2`   | `on-fallback-2`                   | `one`                       | Keep empty unless schema says otherwise                              | Usually empty                                                      | `on-fallback-2.children[]`                   |
| `loop-2`              | Normal workflow elements          | `one-or-many` body elements | Loop control properties live on `loop-2`                             | N/A                                                                | `loop-2.children[]`                          |

Legacy `split-async` follows the same structural idea with `async-split-element` and
`sync-split-element` shells. Prefer current `split-async-2` for new plans.

---

## 4. Pattern: `condition`

Use this pattern when the design requires conditional routing.

```json
{
    "clientId": "condition-1",
    "type": "condition",
    "expectedProperties": {},
    "children": [
        {
            "clientId": "if-condition-true",
            "type": "if",
            "expectedProperties": {
                "condition": "${exchangeProperty.conditionMatched} == true",
                "priority": 0
            },
            "children": [
                {
                    "clientId": "primary-action-call",
                    "type": "service-call",
                    "expectedProperties": {
                        "integrationSystemId": "<system-id>",
                        "integrationSpecificationId": "<model-id>",
                        "integrationOperationId": "<operation-id>"
                    }
                }
            ]
        },
        {
            "clientId": "else-condition-false",
            "type": "else",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "fallback-action",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Build fallback response."
                    }
                }
            ]
        }
    ]
}
```

For multiple `if` branches, add more `if` siblings under the same `condition` and use increasing
`priority` values. Keep at most one `else`.

---

## 5. Pattern: `split-2`

Use this pattern when the design requires parallel or split processing.

```json
{
    "clientId": "split-1",
    "type": "split-2",
    "expectedProperties": {
        "timeout": 0,
        "stopOnException": true
    },
    "children": [
        {
            "clientId": "split-main",
            "type": "main-split-element-2",
            "expectedProperties": {
                "splitName": "main"
            },
            "children": [
                {
                    "clientId": "main-branch-step",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Prepare main split branch."
                    }
                }
            ]
        },
        {
            "clientId": "split-extra",
            "type": "split-element-2",
            "expectedProperties": {
                "splitName": "extra"
            },
            "children": [
                {
                    "clientId": "extra-branch-step",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Prepare extra split branch."
                    }
                }
            ]
        }
    ]
}
```

Use one or more `split-element-2` branches. Add `main-split-element-2` only when the design needs a
main branch.

---

## 6. Pattern: `split-async-2`

Use this pattern when the design requires asynchronous parallel branches.

```json
{
    "clientId": "async-split-1",
    "type": "split-async-2",
    "expectedProperties": {},
    "children": [
        {
            "clientId": "async-branch-a",
            "type": "async-split-element-2",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "async-branch-a-step",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Run async branch A."
                    }
                }
            ]
        },
        {
            "clientId": "async-branch-b",
            "type": "async-split-element-2",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "async-branch-b-step",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Run async branch B."
                    }
                }
            ]
        }
    ]
}
```

Use one or more `async-split-element-2` branches.

---

## 7. Pattern: `try-catch-finally-2`

Use this pattern when the design requires error handling around a protected flow.

```json
{
    "clientId": "try-catch-1",
    "type": "try-catch-finally-2",
    "expectedProperties": {},
    "children": [
        {
            "clientId": "try-block",
            "type": "try-2",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "protected-call",
                    "type": "service-call",
                    "expectedProperties": {
                        "integrationSystemId": "<system-id>",
                        "integrationSpecificationId": "<model-id>",
                        "integrationOperationId": "<operation-id>"
                    }
                }
            ]
        },
        {
            "clientId": "catch-block",
            "type": "catch-2",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "catch-handler",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Handle exception."
                    }
                }
            ]
        },
        {
            "clientId": "finally-block",
            "type": "finally-2",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "finally-step",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Run final cleanup."
                    }
                }
            ]
        }
    ]
}
```

Use exactly one `try-2`, one or more `catch-2`, and at most one `finally-2`.

---

## 8. Pattern: `circuit-breaker-2`

Use this pattern when the design requires circuit-breaker protection and fallback.

```json
{
    "clientId": "circuit-breaker-1",
    "type": "circuit-breaker-2",
    "expectedProperties": {},
    "children": [
        {
            "clientId": "circuit-breaker-config",
            "type": "circuit-breaker-configuration-2",
            "expectedProperties": {
                "failureRateThreshold": 50
            },
            "children": [
                {
                    "clientId": "protected-step",
                    "type": "service-call",
                    "expectedProperties": {
                        "integrationSystemId": "<system-id>",
                        "integrationSpecificationId": "<model-id>",
                        "integrationOperationId": "<operation-id>"
                    }
                }
            ]
        },
        {
            "clientId": "circuit-breaker-fallback",
            "type": "on-fallback-2",
            "expectedProperties": {},
            "children": [
                {
                    "clientId": "fallback-step",
                    "type": "script",
                    "expectedProperties": {
                        "script": "Build fallback response."
                    }
                }
            ]
        }
    ]
}
```

Put circuit-breaker settings on `circuit-breaker-configuration-2`, and fallback workflow steps under
`on-fallback-2.children[]`.

---

## 9. Pattern: `loop-2`

Use this pattern when the design requires repeated processing.

```json
{
    "clientId": "loop-1",
    "type": "loop-2",
    "expectedProperties": {
        "expression": "${exchangeProperty.items}",
        "doWhile": false,
        "maxLoopIteration": 1500
    },
    "children": [
        {
            "clientId": "loop-body-step",
            "type": "script",
            "expectedProperties": {
                "script": "Process current loop item."
            }
        }
    ]
}
```

`loop-2` is a container, but it does not use branch shell children. Put normal workflow steps
directly under `loop-2.children[]`.

---

## 10. IMPLEMENT_CHAIN failure policy

When `createElementsByJson(chainId, "{}")` returns `{ "ok": true, "data": { ... } }`, use
`data.clientIds` and `data.stages.*.failures[]` for targeted repair.

When `createElementsByJson` returns `{ "ok": false, "error": { "code": "PLAN_VALIDATION_ERROR", ...
} }` or another fatal validation error, treat the approved `ChainImplementationPlan` as invalid.
Stop catalog mutation work and return to planning or ask the user to modify the plan.

Catalog tools return a JSON envelope: `{ ok, tool, data?, message?, error? }`. On success, read
payload from `data`; on failure, read `error.code`, `error.message`, and `error.hint`.

Structural plan defects are fixed in `ChainImplementationPlan`, not by manual catalog mutations.
This includes invalid shell placement, root-level shell elements, missing required container
children, and containment represented as `connections[]`.

---

## 11. Final checklist

1. Each container appears only when required by the user design.
2. Each container matches the relationship matrix.
3. Shell elements are direct children of their container.
4. Normal workflow steps are children of the branch or block where they run.
5. Properties are placed on the element that owns them.
6. `connections[]` contains only runtime dependencies between siblings.
7. Structural materialization errors route back to planning.
