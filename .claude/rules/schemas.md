---
paths:
  - "schemas/**"
---

### Project Overview

Centralized JSON Schema definitions (authored as YAML, JSON Schema Draft-07) for QIP chains, services, specifications, and the integration element types. Dual-published: as the npm package `@netcracker/qip-schemas` and as the Maven artifact `org.qubership.integration.platform:qip-schemas`. Build/codegen is a TypeScript pipeline (`ts-node` + `@apidevtools/json-schema-ref-parser` + `json-schema-to-typescript`); tested with Jest + AJV 8 (npm) and JUnit 5 + networknt `json-schema-validator` (Maven).

### Build & Test Commands

#### npm (`schemas/package.json` scripts)

```bash
npm -w @netcracker/qip-schemas run build   # ts-node src/main/scripts/build.ts build → assets/ + types/ + dist/
npm -w @netcracker/qip-schemas run clean   # ts-node src/main/scripts/build.ts clean → removes assets/, types/, dist/
npm -w @netcracker/qip-schemas test        # jest — AJV Draft-07 conformance + sample validation
```

- `prepublishOnly` runs `build`, `postpublish` runs `clean` (npm publish hooks).
- Build is a single script with a `build`/`clean` subcommand; there is no Gulp/Vite step.

#### Maven (`pom.xml`, `org.qubership.integration.platform:qip-schemas`)

```bash
mvn -pl schemas package   # runs JUnit conformance tests + zips conf-model schemas
mvn -pl schemas test      # JUnit 5 + networknt json-schema-validator
```

- Maven build emits a `models` ZIP (`zip-pack.xml` assembly) packaging `src/main/resources/conf-model/`.
- Checkstyle (`qip-checkstyle`, version from `qip-checkstyle-revision` in this POM) is bound to the `compile` phase, zero violations allowed.

### Project Structure

#### Build pipeline (`build.ts`)

1. **clean** — removes `assets/`, `types/`, `dist/`.
2. **`SchemaResolver.resolveAllSchemas()`** — for each `element/*.schema.yaml` (excluding `element.schema.yaml`), dereferences cross-refs under `http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/` (an id maps to `conf-model/<path>.schema.yaml`), inlines nested `$ref`s, removes nested `$id`s, and writes the flattened result to `assets/`. Returns a `Map<elementType, yamlString>`.
3. **`generateTypes()`** — copies all `conf-model` schemas to a temp dir, rewrites `$ref`/`$id` to local paths, compiles each to TypeScript via `json-schema-to-typescript`, and emits `types/**/*.d.ts` plus a conflict-deduplicated `types/index.d.ts` (common-properties win on name collisions).
4. **`generateRuntimeIndex()`** — writes `dist/index.mjs` exporting the frozen `schemasByType` map (`{ "http-trigger": "<resolved yaml>", … }`, alphabetically sorted) and `dist/index.d.ts` (`SchemaType` union + `schemasByType` typing, which also exports `../types/index`).

### Conventions

- **Edit only `src/main/resources/conf-model/**`.** `assets/`, `types/`, and `dist/` are generated — never hand-edit (each carries an `AUTO-GENERATED … do not edit` banner; rerun `npm run build`).
- Schema files are YAML named `<name>.schema.yaml`, first lines `$id` + `$schema: http://json-schema.org/draft-07/schema`. The `$id` and every cross-reference use `http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/<path>`, where `<path>` is the file path under `conf-model/` without `.schema.yaml` (`element/xslt` for `conf-model/element/xslt.schema.yaml`). `metaInfo` names the `Cloud Integration Platform` application with the `CIP` label, and each top-level schema's `fileExtension` ends in `.cip`.
- Element variants suffixed `-2` are the newer/v2 element schemas (e.g. `mapper-2`, `try-catch-finally-2`); both old and new coexist.
- Tests validate against samples under `src/test/resources/samples/`; a sample ending in `__SHOULD_FAIL.yaml` is asserted to be rejected. Add samples (positive and negative) when adding or changing a schema.
- **The end-to-end suite reads these sources directly, so a new element or axis value fails there too.**
  `e2e/registry/discriminators.ts` resolves `src/main/resources/conf-model/element/` (the sources, not the generated
  `assets/`), and `e2e/specs/harness/coverage.spec.ts` turns red on any element family or discriminator axis value with
  no row in `e2e/registry/elements.ts`. Add the row in the same commit as the schema; `e2e/README.md` says what a row
  holds. `src/test/resources/samples/` is read the same way, for the migration floors
  `e2e/specs/schema/frozen-corpus.spec.ts` measures. After changing either tree, run
  `npx playwright test --project=schema --project=harness` in `e2e/`; neither project needs a stack.
- AJV is configured with `discriminator: true` and `allErrors: true`; keep schemas Draft-07 compatible (the Java side rejects unknown keywords).
- `common-properties/*` `$id`s never reach a document as `$schema`; only top-level entity schemas do. So these
  files can be merged, renamed, or moved freely. A top-level `$id` is different: documents carry it as `$schema`,
  and runtime-catalog checks that value on import through `ApplicationJsonSchemaProperties`, which accepts the
  configured URI and the legacy `http://qubership.org/schemas/product/qip/<type>`. A change to a top-level `$id`
  needs a matching change there.
- `schemaResolver.ts` and `generateTypes.ts` map an `$id` to a file by replacing the prefix with `conf-model/` and
  appending `.schema.yaml`, so moving or renaming a schema file changes its `$id`.
- The npm package (`package.json`) and the Maven artifact (`pom.xml`) are released under one version:
  `schemas-release.yaml` lets npm compute the number and passes it to the Maven release.
- **The element property form comes from these schemas,** in the web UI and the VS Code extension alike:
  `chainElementSchemaModules.ts` reads `schemasByType`, and `DescriptionTooltipFieldTemplate.tsx` shows a property's
  schema `description` next to its label. The element library's `description.yaml` in integration-build-pipeline
  drives the palette, titles, mandatory flags, defaults, and the Camel URI `query` flag, and the form ignores its
  property descriptions. Text a user should read goes into `conf-model/element/*.schema.yaml`. A `description` or
  `title` next to a `$ref` does render in the form, although Draft-07 ignores siblings of `$ref`.
- **After editing a schema, rebuild `dist/`** with `npm -w @netcracker/qip-schemas run build`. The UI reads
  `dist/index.mjs`, and a stale build keeps showing the old form without an error.

### Platform Context

`qip-schemas` is the single source of truth for QIP entity/element JSON Schemas, consumed by the modules below. See `README.md` for the repository layout.

This is a **shared library**, not a runtime service — it has no inter-service communication.

#### Consumed By

| Consumer | Mechanism | What it uses |
|---|---|---|
| **AI service** | `ai-service/scripts/fetch-qip-schemas.sh` at build time (no Maven dependency) | the `conf-model` sources, copied into `target/classes/qip-schemas` |
| **End-to-end suite** | reads the sources directly | `conf-model/element/` and `src/test/resources/samples/` (see Conventions) |
| **UI** (`@netcracker/qip-ui`) | npm workspace dep `@netcracker/qip-schemas` | `schemasByType` from `dist/index.mjs` (via `chainElementSchemaModules.ts`); element type → schema (e.g. `"http-trigger"`); RJSF chain-element forms |
| **VS Code Extension** | npm dep (embeds qip-ui) | same `@netcracker/qip-schemas` schemas, offline |

- Local cross-module changes need only `npm install` at the repo root once — workspace symlinks propagate; no publish step required for testing.
- The UI also exposes the raw flattened schemas via the package's `./assets/*` export (`@netcracker/qip-schemas/assets/*.schema.yaml`), resolvable through Vite `import.meta.glob`.
