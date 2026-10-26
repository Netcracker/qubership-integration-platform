---
paths:
  - "**/*.cip.yaml"
  - "**/*.yaml"
  - "**/*.groovy"
  - "**/*.md"
---

# CIP design gate



When the user asks to **create, design, generate, expose, implement, or add** a CIP chain,

integration flow, HTTP trigger, service call, or IDS — treat it as **design-first work**.



## Required flow (do not skip)



1. Read and follow **`using-superpowers`**.

2. Read and follow **`brainstorming`** — HARD GATE: no YAML/Groovy/config until design is approved.

3. **`cip-design-generator`** → IDS document (APIHub-backed; no invented API ids/paths).

4. User approves the IDS.

5. **`cip-design-planner`** → numbered plan under `docs/plans/`.

6. User explicitly approves the plan (`Agree` / `Execute plan`).

7. **`cip-design-executor`** → generated chain **and** catalog companions via generator skills

   + **`cip-folder-organizer`** (never hand-authored catalog YAML).

8. **`cip-chain-validator`** + **`verification-before-completion`** + **`cip-validation-gate`**

   before declaring done (lint **chain + service + spec-group + spec**; completion template).



## Forbidden before plan approval



- Hand-writing any of: `*.chain.cip.yaml`, `*.service.cip.yaml`,

  `*.specification-group.cip.yaml`, `*.specification.cip.yaml`, or scripts "from patterns"

- Creating catalog files to "support" a service-call — catalog comes from the executor /

  import path, not manual YAML

- Calling **`cip-design-executor`** or **`compiler-orchestrator`** without an approved plan

- Skipping APIHub spec lookup for service-call bindings



## Catalog artifact rule



New integration targets need a full hierarchy published together:



```

service.cip.yaml

  └── specification-group.cip.yaml  (parentId → service id)

        └── specification.cip.yaml  (parentId → spec-group id; prefer specificationSources)

```



`cip-service-call-generator` only binds to existing catalog IDs — it does **not** emit

spec files. Missing `content.parentId` on a specification is a schema error.



## Escape hatch (user must say explicitly)



Skip brainstorming/planning only if the user says something like:

- "skip design", "skip brainstorming", "just implement the approved IDS/plan", or

- they attach an **already-approved** IDS or plan file.



Even with the escape hatch, **`cip-validation-gate`** still applies before "done".



## CIP mapping (Superpowers ↔ CIP)



| Superpowers | CIP equivalent |

|-------------|----------------|

| brainstorming spec | IDS from `cip-design-generator` |

| writing-plans | `cip-design-planner` |

| executing-plans | `cip-design-executor` |

## IDS diagram policy

When this gate applies to chain or IDS work:

- **MUST** use Mermaid **`sequenceDiagram`** only (with `autonumber`) for integration flows — **any number** of sequence diagrams per IDS is fine (multiple chains, scenarios, or sub-flows).
- **MUST NOT** use flowchart, graph, stateDiagram, classDiagram, erDiagram, Graphviz/dot, or ASCII flow art in `docs/ids/`, plans, or design chat for chains.
- During **`brainstorming`** approval for a CIP chain, show each flow as a `sequenceDiagram` preview — not `flowchart LR/TD` or other types. **Do not modify** the `brainstorming` skill (third-party fork); this gate and **`cip-design-generator`** supply CIP diagram rules instead.
- `cip-design-generator` owns the canonical diagrams in the IDS.
