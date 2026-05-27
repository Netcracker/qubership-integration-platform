# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Centralized JSON Schema repository for the Qubership Integration Platform (QIP). Stores JSON Schema Draft-07 definitions (in YAML) for integration models: chains, services, specifications, context services, and ~70+ element types (triggers, senders, routing, transformation, etc.).

Published as both npm package (`@netcracker/qip-schemas`) and Maven artifact (`org.qubership.integration.platform:qip-schemas`).

## Build & Test Commands

```bash
# Install dependencies
npm install

# Build: resolve $refs, generate TypeScript types, generate runtime index
npm run build

# Clean generated files (assets/, types/, dist/)
npm run clean

# Run tests (schema conformance + sample validation via AJV)
npm test

# Run a single test
npm test -- --testPathPattern=schemas.test
```

Maven build (for Java consumers):
```bash
mvn clean package    # Creates ZIP artifact with qip-model/ directory
```

## Architecture

### Schema Structure

Source schemas live in `src/main/resources/qip-model/`:

- **Top-level entities**: `chain.schema.yaml`, `service.schema.yaml`, `specification.schema.yaml`, `context-service.schema.yaml`, `specification-group.schema.yaml`
- **Elements** (`element/`): ~70+ element schemas — triggers (HTTP, Kafka, RabbitMQ, PubSub, SFTP, Scheduler), senders (HTTP, Kafka, Mail, GraphQL), routing (Split, Condition, Loop, Try-Catch), transformation (Script, Mapper, XSLT, Headers Modification), etc.
- **Element properties** (`element/properties/`): 18 shared property schemas (common-element-properties, correlation-id, idempotency, access-control, kafka-connection, etc.)
- **Common properties** (`common-properties/`): 7 shared schemas (top-level-entity-properties, labels, description, migrations, parent, service-type)

### Build Pipeline

`npm run build` runs `ts-node src/main/scripts/build.ts build`, which executes three steps:

1. **Schema Resolver** (`schemaResolver.ts`): resolves `$ref` references from `http://qubership.org/schemas/product/qip/*` URLs to local files → writes `assets/*.schema.yaml`.
2. **Type Generator** (`generateTypes.ts`): generates TypeScript `.d.ts` definitions using `json-schema-to-typescript` → writes `types/` with a barrel `index.d.ts` (102 exports).
3. **Runtime Index** (`generateRuntimeIndex` in `build.ts`): walks `assets/`, inlines each YAML as a string into `dist/index.mjs` exporting `schemasByType: Record<SchemaType, string>`, and writes a matching `dist/index.d.ts` that re-exports types from `../types/index` plus declares `schemasByType` and the `SchemaType` union.

### Package Entry Points

`package.json` declares three consumer-facing APIs:

| Import | What you get | Who uses it |
|---|---|---|
| `import { Chain, Element } from "@netcracker/qip-schemas"` | TypeScript types from `dist/index.d.ts` (re-exports `types/index`) | vscode-extension |
| `import { schemasByType } from "@netcracker/qip-schemas"` | Runtime map `{ "http-trigger": "<yaml string>", ... }` from `dist/index.mjs` | UI (chain element forms) |
| `import yaml from "@netcracker/qip-schemas/assets/<name>.schema.yaml?raw"` | Single resolved YAML file via subpath export | future / ad-hoc consumers |

### Testing

`schemas.test.ts` validates:
- All schemas conform to JSON Schema Draft-07
- 50+ YAML samples in `src/test/resources/samples/` validate against their target schemas
- Failure cases (files ending `__SHOULD_FAIL.yaml`) correctly fail validation

## Conventions

- Conventional Commits required
- Schemas in YAML format, JSON Schema Draft-07
- References use `http://qubership.org/schemas/product/qip/` base URL (resolved to local files at build time)

## Platform Context

This is the **schema foundation** of the Qubership Integration Platform (QIP). See `~/.claude/CLAUDE.md` for the full platform map.

### Consumers

| Consumer | How | What For |
|---|---|---|
| **qubership-integration-ui** | npm dependency `@netcracker/qip-schemas` | RJSF forms dynamically load schemas from `assets/*.schema.yaml` to render element configuration forms |
| **qubership-integration-vscode-extension** | npm dependency `@netcracker/qip-schemas` | Schema validation and form generation for offline chain/service editing |
| **qubership-integration-runtime-catalog** | Maven dependency `qip-schemas` | Server-side validation and element type definitions |

### Impact of Changes

Modifying schemas here affects the entire platform. Adding/renaming/removing element properties will impact:
- UI forms (auto-generated from schemas)
- VS Code extension forms
- Runtime Catalog validation and compilation
- Import/export compatibility (may need migration in Runtime Catalog's `service/exportimport/migrations/`)
