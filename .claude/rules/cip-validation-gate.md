---
paths:
  - "**/*.cip.yaml"
  - "**/*.yaml"
  - "**/*.groovy"
  - "resources/**"
---

# CIP validation gate

Applies whenever CIP artifacts are **created, modified, fixed, or declared complete**
(design path, bugfix path, or direct edits).

> **Iron Law (`verification-before-completion`):** NO completion claims without fresh
> verification evidence in the **same turn**.

## Scope — lint ALL changed CIP conf-model files

Run **`ReadLints`** on **every** changed file matching:

| Pattern | Artifact |
|---------|----------|
| `*.chain.cip.yaml` | Chain |
| `*.service.cip.yaml` | Integration service |
| `*.specification-group.cip.yaml` | Spec group |
| `*.specification.cip.yaml` | Spec version |
| `resources/*.groovy` | Scripts referenced by chains |

**Fail closed:** any schema linter **ERROR** on any file in scope ⇒ **not deployable**.

## Catalog hierarchy checks (specification files)

When a `*.specification.cip.yaml` is created or changed, verify in the same turn:

1. **`content.parentId`** is present and equals the parent **specification-group** `id`.
2. **`content.labels`** is present (use `[]` if empty).
3. Hierarchy is consistent:
   - `service` ← `specification-group.parentId`
   - `specification-group` ← `specification.parentId`
4. Prefer **`specificationSources`** + OpenAPI under `resources/source-…/` (repo convention);
   flag hand-authored `source: MANUAL` specs without a source file as **VALIDATION WARNING**.

Missing `parentId` is a **blocking** defect (schema-required).

## Sparse YAML checks (chain files)

When a `*.chain.cip.yaml` is created or changed by generators, flag:

1. **ERROR** — optional metadata emitted as `""`:
   `content.description`, `content.businessDescription`, `content.assumptions`,
   `content.outOfScope`
2. **WARNING** — `description: ""` on every element (omit key per CORPORATE_CIP_STANDARDS §2.4)
3. **ERROR** — quoted numeric properties where numbers are required (`retryCount: "3"`)
4. **WARNING** — unnecessary `chainId` on nested elements when export-profile is minimal

## Required before "done" / "complete" / "ready to deploy"

1. **`ReadLints`** on all files in scope — report per-file error/warning counts.
2. **`cip-chain-validator`** when compiler runtime is available. Verdict:
   **PASS** / **PASS-WITH-WARNINGS** / **FAIL**.
3. Summarize evidence using the **Completion template** below.

## Forbidden without attached evidence

- Phrases: "Design execution complete", "validation passed", "chain is valid/ready",
  "looks good", "should work"
- Hand-writing on the **design path**:
  - `*.chain.cip.yaml`
  - `*.service.cip.yaml`, `*.specification-group.cip.yaml`, `*.specification.cip.yaml`
  - Use **`cip-design-executor`** → **`cip-chain-assembler`** → **`cip-folder-organizer`**
    (see `cip-design-gate`). `cip-service-call-generator` **consumes** catalog IDs; it does
    not create spec files.
- Emitting optional chain metadata as empty strings (`""`) instead of omitting keys
- Declaring done after linting only the chain while companion service/spec files also changed
- Declaring done after only reading skills or copying patterns from other chains

## When full validator runtime is unavailable

Do **not** imply success. State explicitly:

> **VALIDATION INCOMPLETE** — `cip-chain-validator` runtime not executed (reason: …).
> Schema linter: \<per-file error counts\>.

Linter on **all** conf-model files in scope is the **minimum** bar, not a substitute for
`cip-chain-validator`.

## Completion template (mandatory for CIP integration work)

```
## Verification
- Chain: <path> — linter: <0 / N errors>
- Service: <path or n/a> — linter: <0 / N errors>
- Spec group: <path or n/a> — linter: <0 / N errors>
- Spec: <path or n/a> — linter: <0 / N errors>; parentId: <present → group id | MISSING>
- Scripts: <paths or n/a>
- Sparse YAML: <no empty optional metadata | list violations>
- cip-chain-validator: <PASS | FAIL | NOT RUN — reason>
- Deployable: <yes only if ALL linter 0 errors AND validator PASS/PASS-WITH-WARNINGS>
```

## Escalation

If linter errors remain after a fix attempt, stop claiming progress — list remaining
violations per file and fix or ask the user before continuing.
