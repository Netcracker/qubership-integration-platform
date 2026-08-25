---
description: JSON Schema sources and the ts-node build and codegen pipeline.
applyTo: "schemas/**"
---

### Project Overview

Centralized JSON Schema definitions (authored as YAML, JSON Schema Draft-07) for QIP chains, services, specifications, and ~72 integration element types. Dual-published: as the npm package `@netcracker/qip-schemas` (v0.2.30) and as the Maven artifact `org.qubership.integration.platform:qip-schemas`. Build/codegen is a TypeScript pipeline (`ts-node` + `@apidevtools/json-schema-ref-parser` + `json-schema-to-typescript`); tested with Jest + AJV 8 (npm) and JUnit 5 + networknt `json-schema-validator` (Maven).

### Build & Test Commands

#### npm (root `package.json` scripts)

```bash
npm run build                                  # ts-node src/main/scripts/build.ts build → assets/ + types/ + dist/
npm run clean                                  # ts-node src/main/scripts/build.ts clean → removes assets/, types/, dist/
npm test                                       # jest — AJV Draft-07 conformance + sample validation
```

- `prepublishOnly` runs `build`, `postpublish` runs `clean` (npm publish hooks).
- Build is a single script with a `build`/`clean` subcommand; there is no Gulp/Vite step.

#### Maven (`pom.xml`, `org.qubership.integration.platform:qip-schemas`)

```bash
mvn -pl schemas clean install -Dgpg.skip=true  # runs JUnit conformance tests + zips qip-model schemas
mvn -pl schemas test                           # JUnit 5 + networknt json-schema-validator (1.5.8)
```

- Maven build emits a `models` ZIP (`zip-pack.xml` assembly) packaging `src/main/resources/qip-model/`.
- Checkstyle (`qip-checkstyle` `0.0.3`) is bound to the `compile` phase, zero violations allowed.

### Project Structure

#### Build pipeline (`build.ts`)

1. **clean** — removes `assets/`, `types/`, `dist/`.
2. **`SchemaResolver.resolveAllSchemas()`** — for each `element/*.schema.yaml` (excluding `element.schema.yaml`), dereferences cross-refs under `http://qubership.org/schemas/product/qip/`, inlines nested `$ref`s, removes nested `$id`s, and writes the flattened result to `assets/`. Returns a `Map<elementType, yamlString>`.
3. **`generateTypes()`** — copies all `qip-model` schemas to a temp dir, rewrites `$ref`/`$id` to local paths, compiles each to TypeScript via `json-schema-to-typescript`, and emits `types/**/*.d.ts` plus a conflict-deduplicated `types/index.d.ts` (common-properties win on name collisions).
4. **`generateRuntimeIndex()`** — writes `dist/index.mjs` exporting the frozen `schemasByType` map (`{ "http-trigger": "<resolved yaml>", … }`, alphabetically sorted) and `dist/index.d.ts` (`SchemaType` union + `schemasByType` typing, re-exporting `../types/index`).

### Conventions

- **Edit only `src/main/resources/qip-model/**`.** `assets/`, `types/`, and `dist/` are generated — never hand-edit (each carries an `AUTO-GENERATED … do not edit` banner; rerun `npm run build`).
- Schema files are YAML named `<name>.schema.yaml`, first lines `$id` + `$schema: http://json-schema.org/draft-07/schema`; cross-references use the `http://qubership.org/schemas/product/qip/...` URI namespace.
- Element variants suffixed `-2` are the newer/v2 element schemas (e.g. `mapper-2`, `try-catch-finally-2`); both old and new coexist.
- Tests validate against samples under `src/test/resources/samples/`; a sample ending in `__SHOULD_FAIL.yaml` is asserted to be rejected. Add samples (positive and negative) when adding or changing a schema.
- AJV is configured with `discriminator: true` and `allErrors: true`; keep schemas Draft-07 compatible (the Java side rejects unknown keywords).
- **Service schemas are per type.** `external-service`, `internal-service`, and `implemented-service` each state only
  what differs by type: its own `Protocol` enum in all three, `maxItems: 1` on `environments` in `internal-service` and
  `implemented-service` (`external-service` declares no `environments` key, so it is unbounded), and
  `internalServiceName` in `internal-service` alone. Everything they
  share sits in `common-properties/typed-service-content.schema.yaml`, which they `allOf`-`$ref` from `content`: the
  three common-property refs, `activeEnvironmentId`, the `environments` array, and the
  `not: {required: [integrationSystemType]}` that suppresses the type field, because since #553 the file name carries
  it.
  `additionalProperties: false` cannot do the suppression: in Draft-07 it does not see properties contributed
  through `allOf` `$ref`s, so it would reject the positive samples too. Reference `SourceType` by **absolute** URI
  even from inside `environment.schema.yaml`, which declares it. A bare `#/definitions/SourceType` resolves against
  the referring document, which defines nothing of the sort, and the constraint stops applying without either harness
  saying so.
  - **An environment is one schema, `common-properties/environment.schema.yaml`,** referenced by both
    `typed-service-content` and the legacy `service.schema.yaml`, so the two formats cannot drift apart on what an
    environment is. It owns the `SourceType` enum and the `if MANUAL then address required` rule.
  - **The live path and the legacy format share only `environment`.** A single
    `common-properties/service-content.schema.yaml` used to carry `Environment`, `SourceType` **and**
    `activeEnvironmentId` for both, which forced the `not` guard into a separate file because `service.schema.yaml`
    requires the very field the typed schemas forbid. Splitting `environment` out dissolved that conflict: the shared
    part is now exactly the part both formats agree on.
  - `common-properties/*` `$id`s never reach a document as `$schema`; only top-level entity schemas do. So these
    files can be merged, renamed, or moved freely, while a top-level `$id` cannot change without migrating every
    document that carries it — the resolver maps a `$id` URI onto a file path by string replacement
    (`schemaResolver.ts`), so the URI *is* the path.
- The npm package version (`package.json`) and the Maven version (`pom.xml`) are independent and bumped separately.

### Platform Context

`qip-schemas` is the single source of truth for QIP entity/element JSON Schemas, consumed by three downstream modules. See `README.md` for the repository layout.

This is a **shared library**, not a runtime service — it has no inter-service communication.

#### Consumed By

| Consumer | Mechanism | What it uses |
|---|---|---|
| **Runtime Catalog** | Maven (`org.qubership.integration.platform:qip-schemas`) | `qip-model` schemas (validation/compilation of chains & elements) |
| **UI** (`@netcracker/qip-ui`) | npm workspace dep `@netcracker/qip-schemas` | `schemasByType` from `dist/index.mjs` (via `chainElementSchemaModules.ts`); element type → schema (e.g. `"http-trigger"`); RJSF chain-element forms |
| **VS Code Extension** | npm dep (embeds qip-ui) | same `@netcracker/qip-schemas` schemas, offline |

- Local cross-module changes need only `npm install` at the repo root once — workspace symlinks propagate; no publish step required for testing.
- The UI also exposes the raw flattened schemas via the package's `./assets/*` export (`@netcracker/qip-schemas/assets/*.schema.yaml`), resolvable through Vite `import.meta.glob`.
