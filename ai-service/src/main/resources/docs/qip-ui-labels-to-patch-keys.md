# UI labels and user language vs catalog PATCH keys

## Rules of thumb

1. **Source of truth for PATCH** is the JSON **property name** in the element schema under `properties.properties` (what you send in `updateElement` inside `properties.{key}`; the service validates merged properties before PATCH).
2. The QIP UI (JSON Forms / RJSF) usually shows the JSON Schema **`title`** next to a field. That `title` comes from the same YAML as in the monorepo [`schemas`](../../../../../schemas/) module (`qip-model/`), also bundled in the UI as `@netcracker/qip-schemas` (`ChainElementModification.tsx` loads `*.schema.yaml` and strips top-level `description`). The AI service loads the full **`qip-model`** tree as `classpath:qip-schemas/` (with `$ref`) copied from `schemas/src/main/resources/qip-model` at Maven build time (see `ai-service/pom.xml`). The published npm `@netcracker/qip-schemas` ships dereferenced **`assets/`** element YAML only; it is not a drop-in replacement for the AI service classpath tree. Use **`describeElementPatchSchema`** for the full PATCH key summary, or **`describeElementProperty`** for a specific path when you need `title`, `enum`, or nested shape from the embedded schema (prefer fresh tool output over this note).
3. The UI can **override** the visible label with **`ui:title`** in `INITIAL_UI_SCHEMA` — see the table below (scraped from `qubership-integration-ui`).
4. Users often describe behavior with **REST or informal words** (URI, URL, “method”). Those phrases are **not** PATCH keys unless they happen to match the schema literally.

## How labels are chosen in `qubership-integration-ui`

| Mechanism | Where | Effective label |
|-----------|--------|-------------------|
| JSON Schema `title` | Element YAML (`qip-schemas`) | Default RJSF label (custom fields use `schema.title` unless overridden) |
| `ui:title` | `ChainElementModificationConstants.ts` → `INITIAL_UI_SCHEMA.properties` | Replaces schema title for that property |
| Custom field | e.g. `ContextPathWithPrefixField`, `serviceField` | Still `uiSchema["ui:title"] ?? schema.title` (same resolution pattern) |

Schema reference (UI): `ChainElementModification.tsx` — dynamic import path `/node_modules/@netcracker/qip-schemas/assets/${elementType}.schema.yaml`.

## Explicit `ui:title` overrides (property key → UI label)

UI file: `qubership-integration-ui/src/components/modal/chain_element/ChainElementModificationConstants.ts` (`INITIAL_UI_SCHEMA.properties`).

These are the **only** hard-coded English labels in `INITIAL_UI_SCHEMA.properties` that replace schema `title`. All other fields use the **`title` from YAML`.

| PATCH key (under `properties`) | UI label (`ui:title`) |
|-------------------------------|----------------------|
| `integrationOperationAsyncProperties` | Operation Parameters |
| `integrationOperationPathParameters` | Path Parameters |
| `integrationOperationQueryParameters` | Query Parameters |
| `integrationAdditionalParameters` | Additional Parameters |
| `integrationGqlOperationName` | Operation Name |
| `integrationGqlQueryHeader` | Query Header |
| `integrationGqlVariablesHeader` | Variables Header |
| `contextServiceId` | Context Storage |
| `integrationSystemId` | Service |
| `integrationSpecificationId` | API Specification |
| `integrationOperationId` | Operation |
| `abacParameters.resourceMap` (nested) | Resource Map |

**Note:** `abacParameters.resourceMap` is nested in the form schema; in PATCH JSON it appears under `properties.abacParameters.resourceMap` when present.

When adding or renaming `ui:title` entries in the UI, update this table so RAG and agents stay aligned.

## `http-trigger` — Custom Endpoint (catalog branch “Custom Endpoint”)

Typical **Endpoint** tab wording vs PATCH keys under `properties`:

| User / informal wording | Visible UI label | PATCH key |
|-------------------------|------------------|-----------|
| URI, URL, path, route (informal) | **Path** (schema `title`; no `ui:title` override) | `contextPath` |
| HTTP method(s), verb, GET/POST/… | **HTTP Methods** (schema `title`) | `httpMethodRestrict` |

The form uses `ui:widget: uri` and placeholder `e.g. /api/v1/resource` on `contextPath`, so users often say “URI” even though the label matches schema **Path**.

`httpMethodRestrict` is often a comma-separated list when multiple methods are allowed (see schema pattern).

## `http-trigger` — Implemented Service endpoint

Different **`oneOf`** branch: business keys include `integrationOperationPath`, `integrationOperationMethod`, `integrationOperationId`, `integrationSystemId`, etc. User words like “operation” / “service” still map only after **`describeElementPatchSchema`** / **`describeElementProperty`** — do not reuse Custom Endpoint keys.

## Shared access control block

From `access-control-properties` (`properties.accessControlType`):

| Schema title (UI) | PATCH key | Allowed values (`enum`) |
|-------------------|-----------|-------------------------|
| Access Control Type | `accessControlType` | `NONE`, `RBAC`, `ABAC` |

## Enumerations, `const` values, and patterns

### Policy

- Complete **`enum` / `const` / `pattern`** sets live in **`classpath:qip-schemas/`** (same pack as the UI). **Do not** treat this file as an exhaustive catalog of every element: it highlights fields that are often mis-typed in PATCH JSON (especially **`http-trigger`**).
- For any property not listed here, use **`describeElementPatchSchema`** / **`describeElementProperty`** — tool output should carry `enum`, `const`, and `title` where the formatter exposes them.

### UI `title` vs PATCH string

Many `oneOf` branches use **`const`** values that are **lowercase or kebab-case**. The JSON Schema **`title`** (shown in the UI) may look like **English prose** (e.g. “Default”, “Mapper”). In PATCH JSON you must send the **`const` / `enum` string**, not the title — e.g. **`default`** not `"Default"`, **`mapper-2`** not `"Mapper"`.

### `httpMethodRestrict` (custom endpoint)

Allowed verbs (from schema `pattern`): **`POST`**, **`GET`**, **`PUT`**, **`DELETE`**, **`PATCH`**, **`HEAD`**, **`OPTIONS`**.

Format: a single method, or **comma-separated** methods with **no spaces** (e.g. `GET,POST`), matching  
`^(POST|GET|PUT|DELETE|PATCH|HEAD|OPTIONS)(,(POST|GET|PUT|DELETE|PATCH|HEAD|OPTIONS))*$`.

### `http-trigger` — failure handlers

| PATCH key | UI title (schema) | Allowed `const` values (PATCH) |
|-----------|-------------------|-------------------------------|
| `handleValidationAction` | Action on validation failure | `default`, `script`, `mapper-2` |
| `handleChainFailureAction` | Action on chain failure | `default`, `script`, `mapper-2`, `chain-call` |

Defaults in schema: both **`default`** action variants use `default` as `const`.

### `idempotency` — `actionOnDuplicate`

Nested under `properties.idempotency` when present:

| Schema title | PATCH path | `enum` values |
|--------------|------------|---------------|
| Action on duplicate message | `idempotency.actionOnDuplicate` | `ignore`, `throw-exception`, `execute-subchain` |

Schema **`default`**: `ignore`. Other keys (`enabled`, `keyExpiry`, etc.) follow conditional rules — use `describeElementProperty` or embedded YAML for full shape.

### ABAC `resourceDataType` (when branch applies)

Under `properties.abacParameters`: **`resourceDataType`** uses enum **`String`**, **`Map`** (capital **S** / **M** per schema).

## Maintenance

- When UI copy diverges from schema `title`, extend this table **or** rely on deterministic schema tools; avoid inventing keys from generic API knowledge.
- After editing **`INITIAL_UI_SCHEMA`** in the UI repo, sync the **Explicit `ui:title` overrides** section above.
- After changing **`enum` / `const` / `pattern`** in `qip-schemas` YAML, sync the **Enumerations** subsection values here.
