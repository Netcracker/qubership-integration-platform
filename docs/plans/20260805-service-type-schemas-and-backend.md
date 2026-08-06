# Dedicated service-type schemas and backend support (issue #553)

> **Revision 3.** Revision 1 restored the type in a version-gated migration, which can never run — the exporter stamps
> every registered version as applied. Revision 2 moved the restore to `IntegrationSystemDtoMapper` and keyed it on
> `$schema`, but `$schema` is project-configurable in the VS Code extension, so it does not identify a type. The file
> name does, and that is what revision 3 uses.

## Overview

Today a service file carries its type in `content.integrationSystemType`, while context and MCP services carry theirs
in the file name and `$schema`. Two mechanisms describe one concept, and the single `service.schema.yaml` cannot
express the per-type rules the backend enforces at runtime.

This plan replaces the field with three dedicated schemas — `external-service`, `internal-service`,
`implemented-service` — and makes the backend read, write, import, and export them. After it, the file name is the
statement of a service's type, and each schema states the constraints that type actually has.

What this buys:

- **Offline validation gains teeth.** `service.schema.yaml` today accepts `METAMODEL` on an external service and ten
  environments on an internal one. Both are rejected by the backend — the second by a bare `RuntimeException` in
  `SystemExportImportService:536`, at import time, after the user has already committed the file.
- **One source of truth per file.** No document can claim `.internal-service.` in its name and `EXTERNAL` in its body.
- **Symmetry with context and MCP services**, which already work exactly this way.

Out of scope, decided explicitly:

- **The `integration_system_type` column stays.** The issue asks to remove the field from the *schema*, i.e. from the
  file format. The runtime needs the type at every turn (allowed protocols, environment rules, action-log entity
  type), so the database keeps it.
- **No JPA inheritance.** A discriminator column is the same column under another name; the payoff would come from a
  class hierarchy, and that hierarchy reaches `SystemRepository`, every filter specification, the MapStruct mappers,
  and `equals`/`hashCode` against `HibernateProxy` — a large blast radius for a partial win, since most per-type rules
  live in Spring services that cannot move into an entity. The per-type data that *can* move goes onto the enum
  instead (Task 3).

**Correction to a premise stated during planning:** the type is *not* mutable through `PUT /systems/{id}`. The
generated `SystemMapperImpl.mergeWithoutLabels` assigns only `name`, `description`, and `activeEnvironmentId` — the
property names differ (`type` vs `integrationSystemType`) and the merge methods carry no `@Mapping`. The one REST
surface that can set a type on an existing id is `updateSystem` falling through to `createSystem` when the id is
unknown (`SystemController.java:125`). Type mutation is real in the VS Code extension (`serviceApiModify.ts:121-131`),
which plan 2 covers. Task 11 here is scoped accordingly.

## Context (from discovery)

Files and components involved:

- `schemas/src/main/resources/qip-model/service.schema.yaml` — the single service schema, `integrationSystemType`
  required at line 36; already `$ref`s the `description`, `labels`, and `migrations` common properties at `:16-19`
- `schemas/src/main/resources/qip-model/context-service.schema.yaml` — the pattern to mirror
- `runtime-catalog/.../model/system/IntegrationSystemType.java` — bare three-value enum
- `runtime-catalog/.../service/SystemBaseService.java:43-53` — `ALLOWED_PROTOCOL_MAP`
- `runtime-catalog/.../rest/v1/controller/EnvironmentController.java:94-98,116-118` — the live one-environment check
  (INTERNAL only) and the PUT-falls-through-to-create path
- `runtime-catalog/.../rest/v1/mapper/SystemMapper.java:66-72` — `activeEnvironmentId` derivation, which **ignores**
  the stored value for INTERNAL and IMPLEMENTED and returns the first environment
- `runtime-catalog/.../service/SystemEnvironmentsGenerator.java:56-72` — deploy-time active-environment resolution
- `runtime-catalog/.../service/exportimport/SystemExportImportService.java` — `:449-457` swallows `MigrationException`
  into an `ImportSystemResult(ERROR)`; `:528-565` per-type import merge with the INTERNAL environment check at `:536`;
  `:850` is the unchecked create path
- `runtime-catalog/.../service/exportimport/deserializer/ServiceDeserializer.java:93-105` — **the type-restore
  point**: it receives `File serviceFile`, migrates, deserializes to `IntegrationSystemDto`, and calls
  `toInternalEntity` at `:104`. It is the only caller of that mapper on any import path, rollout included.
- `runtime-catalog/.../service/exportimport/mapper/services/IntegrationSystemDtoMapper.java` — `toExternalEntity:79`
  stamps `$schema` from its own `@Value` (`:41`); `:88` stamps `MigrationUtil.formatVersions(...)`
- `runtime-catalog/.../service/exportimport/mapper/services/ContextServiceDtoMapper.java:35,65` — injects the **same**
  `List<ServiceImportFileMigration>` and stamps the same version list onto context services
- `runtime-catalog/.../service/exportimport/migrations/FileMigrationService.java` — `:49-51` sorts revert migrations by
  version **descending**; `:88-93` throws on an unknown claimed version; `:95` computes `versionsToMigrate`;
  `:131` re-evaluates `supportsDocument` on the result of each preceding revert
- `runtime-catalog/.../service/exportimport/migrations/revert/ServiceDocumentMatcher.java:42` — matches on `$schema`
  against `{service, context-service, mcp-service}` and **early-returns** when `$schema` is present
- `runtime-catalog/.../service/exportimport/migrations/common/MigrationUtil.java:51-63,66-72` —
  `moveContentFieldsToRoot` and `formatVersions`
- `runtime-catalog/.../service/exportimport/ExportImportConstants.java:36-43` — file-name prefixes and postfixes
- `runtime-catalog/.../util/ExportImportUtils.java:220-235,281` — per-kind export naming; the import-side directory
  scan is **shared** with `ContextExportImportService` and `MCPSystemImportExportService`
- `runtime-catalog/.../service/exportimport/serializer/ExportableObjectWriterVisitor.java:51` — picks the service file
  name from an `ExportedIntegrationSystem` (`model/system/exportimport/`, a `@Getter @Setter` class extending
  `ExportedSystemObject`), which carries no type
- `runtime-catalog/.../configuration/ApplicationJsonSchemaProperties.java` + `src/main/resources/application.yml:288-297`
- `runtime-catalog/src/test/java/.../migrations/system/TestServiceMigrations.java` — the single migration registry four
  test classes read from

Facts that shaped the design, established across two review rounds:

- **`$schema` cannot identify a type.** `vscode-extension/.config.qip.yaml.example:15-22` sets
  `service: http://qubership.org/schemas/product/${appName}/service` — no `.schema.yaml` suffix, arbitrary app name —
  and `fileApiImpl.ts:801` / `serviceApiModify.ts:165` write that value verbatim. Any project with a custom config
  produces a `$schema` the backend has never heard of. The **file name** is stable: only `${appName}` varies in the
  configured extensions, the type segment does not.
- **Context services share the service migration list.** `ContextServiceDtoMapper:65` stamps
  `formatVersions(serviceImportFileMigrations)`, so registering V105 puts 105 into context-service documents too. A
  revert migration that does not strip it from them makes their legacy export unimportable by an older QIP — the
  exact failure `runtime-catalog/CLAUDE.md` documents for V104.
- **A new `$schema` disables every revert gated on `ServiceDocumentMatcher` — including V105's own.** The matcher
  early-returns on a present `$schema` (`ServiceDocumentMatcher:49-52`) against a set built from only the three old
  URIs (`:42`). A plain service exported after Task 9 carries a new URI, so *nothing* matches it — not V105Revert
  (whose `revert()` would write the type and restore the old `$schema`), and consequently not V104 or V103 either.
  The matcher must therefore learn the three new URIs (Task 5). Once it has, the rest follows:
  `FileMigrationService:49-51` runs reverts in descending version order and `:131` re-evaluates `supportsDocument`
  on each intermediate result, so V105Revert restores the plain `$schema` before V104 and V103 look at the document.
  That restore is the real reason V104/V103 keep working; it is **not**, as revision 2 claimed, about running before
  V101, which drops `$schema` unconditionally via `moveContentFieldsToRoot`.
- **The compatibility barrier degrades, it does not refuse.** `FileMigrationService:88-93` throws, but
  `SystemExportImportService:449-457` catches it and reports that one service as `ImportSystemStatus.ERROR`. The rest
  of the archive imports. The outcome is right — no typeless service is persisted — but it is a partial import, not a
  rejected archive, and the release note must say so.
- **The REST layer ignores the stored `activeEnvironmentId` for INTERNAL and IMPLEMENTED (`SystemMapper:66-72`), but
  deploy-time resolution honours it for IMPLEMENTED** (`SystemEnvironmentsGenerator:58-64`: blank → first
  environment, stale → none). Keeping the field in all three schemas therefore protects deploy behaviour as well as
  existing files.
- **runtime-catalog does not depend on `qip-schemas`.** No such Maven dependency exists; the schema URIs are opaque
  strings and nothing validates against them. Tasks 1–2 and 3–12 have no build-order dependency, and nothing detects
  the backend drifting from the schema. Keeping them in step is on the tests in Tasks 3, 4, and 13.
- **`integrationSystemType` appears only in `runtime-catalog`, `schemas`, `ui`, `vscode-extension`.** Nothing in
  engine, micro-engine, sessions-management, or Helm. The runtime-catalog consumers not listed above
  (`ServiceCallBeansBuilder:64`, `RoutesGetterService:145,164`, `DeploymentBuilderService:165`, `TemplateDataBuilder:56`,
  `DetailedDesignService:277`, `SwaggerSpecificationParser:383`, `WSDLSpecificationParser:366,377`) read the type off
  the **entity**, which this plan keeps — they need no changes.

Dependencies identified:

- The frontends (plan 2 — the UI and the VS Code extension, planned separately and not committed alongside this file)
  depend on the migration version this plan introduces (105) and on the file postfixes it defines. **Plan 1 must merge
  before plan 2 starts.**

## Development Approach

- **testing approach**: Regular (code first, then tests) — matches how this branch has been built
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** — no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility: an archive exported by an older QIP must still import

## Testing Strategy

- **unit tests**: required for every task (see Development Approach)
- **schema conformance**: `schemas/` validates samples with AJV (npm) and networknt (Maven). Every new schema needs a
  passing sample and a `__SHOULD_FAIL.yaml` sample per constraint it adds.
- **round-trip tests**: export → import must preserve the type for all three types, in both the current and the legacy
  format, and must **assert the persisted type is non-null**. The failure mode this plan is most exposed to is a null
  type written silently (the column is nullable, `V100_000__init.sql:415`), surfacing much later as an NPE in
  `EntityType.getSystemType:57`.
- **legacy round trip of a context service**, exported alongside a plain service. This is the specific regression
  Task 8's predicate decision creates, and it is invisible to any test that only looks at plain services.
- **revert-chain test**: after the `$schema` change, assert `ServiceDocumentMatcher` still matches a plain service
  document at the point V104 and V103 run, using a golden document that actually contains api groups.
- no e2e tests in this module.

### Test architecture

- **Schema samples**: the existing harnesses (`SchemaOnSamplesTest` on the Maven side, AJV `schemas.test.ts` on npm)
  resolve the schema per sample and honour `__SHOULD_FAIL.yaml`; place Task 2's samples in per-schema directories
  (`samples/external-service/`, …) mirroring `samples/context-service/`. No new infrastructure.
- **Golden corpus (build once, in Task 9's capture step)**: `runtime-catalog/src/test/resources/exportimport/golden/`
  with three sets — `pre553-current/` (a real pre-Task-9 export: one `.service.` file per type, at least one with api
  groups and sources, plus a context and an MCP service), `legacy-flat/` (`service-<id>.yaml` per type, one with
  inline `specificationGroups`), and `post553/` (committed after Task 9: new names, per-type `$schema`, no field —
  the regression pin for the exporter and the input to the revert-chain test). The conformance corpus in `schemas/`
  stays spec-source-only; this is a sibling for service documents.
- **Unit: extend, do not duplicate** — `TestServiceMigrations.all()` (+V105), `TestRevertMigrations.all()`
  (+V105Revert), `ServiceDeserializerTest` (parameterize the `.service.` helper at `:1098`), the `V104*MigrationTest`
  pair as the structural template for the V105 pair, `ServiceSerializerTest` for legacy-export assertions.
- **Migration golden tests**: one test runs the full revert chain over the `post553` golden with api groups and
  asserts semantic equality with `legacy-flat` — proving V105→V104→V103→V101 in sequence, including the `$schema`
  restore that keeps V104/V103 alive. Forward: migrate `pre553-current` and `legacy-flat` through
  `TestServiceMigrations` and assert a non-null type per kind.
- **The two integration tests with the best confidence-per-effort**: (a) the all-five-kinds current-format archive
  through preview + commit (Task 12) — covers cross-kind discovery, dedup, and instructions in one place; (b)
  `ServiceTypeRoundTripTest` as serialize → archive bytes → unzip → import per type × format — the only test that
  sees an export/import asymmetry neither side's unit tests can.
- **Manual-only**: the Post-Completion checklist. Skip gRPC round trips on the local stack — the
  `qip-runtime-catalog` image ships no `protoc`, so they fail for unrelated reasons.

Commands: `mvn -pl schemas clean install -Dgpg.skip=true`, `mvn -pl runtime-catalog test`,
`npm -w @netcracker/qip-schemas test`.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview

**The file name states the type; the import resolves it where the file name is in scope; the migration version is a
compatibility barrier and nothing else.**

Three separate jobs, kept separate because conflating any two of them is what broke revisions 1 and 2:

1. **Resolving the type on import** happens in `ServiceDeserializer.deserializeSystem`, which receives
   `File serviceFile` (`:93`) and calls `toInternalEntity` at `:104`. The restore goes immediately after that call.
   Resolution order: the file-name postfix, then `content.integrationSystemType` (old archives and the legacy flat
   format, which has no type-bearing name), then fail. `$schema` is **not** consulted — the VS Code extension writes
   whatever a project's `.config.qip.yaml` says, and that is neither a fixed host nor a fixed path.

   It cannot live in `IntegrationSystemDtoMapper`: that mapper never sees a file name.

2. **Refusing to mis-import an archive from a newer QIP** is what version 105 is for — with an honest statement of
   how far it reaches. `FileMigrationService:88-93` throws when a document claims a version the running QIP does not
   know, and `SystemExportImportService:449-457` reports that service as `ImportSystemStatus.ERROR`. But a pre-#553
   QIP discovers service files by the old names (`ExportImportUtils:287-288`: the `service-` prefix and `.service.`),
   which the new `-service` postfixes deliberately do not match — so a post-#553 **plain** service is not rejected
   by an old QIP, it is *silently invisible* to it: absent from the import result, no error row. The barrier fires
   on the documents an old QIP still discovers — **context services** (still named `.context-service.`, stamped 105
   by `ContextServiceDtoMapper:65` from the shared list) and legacy-named files — and in a mixed archive the erroring
   context service is the only visible signal that the archive came from a newer QIP. The safety property — no
   typeless service is ever persisted — holds either way; the release note must state the silent absence explicitly.

   `V105ServiceImportFileMigration.makeMigration` has nothing to do — resolution is handled by (1) for every
   document, migrated or not — so it returns the node untouched. **This must be commented in the class**, or the
   next reader deletes an apparently empty migration and removes the barrier.

3. **Producing an importable legacy export** is `V105RevertMigration`, and it needs two different scopes in one class:
   - `supportsDocument` must be **broad** — `ServiceDocumentMatcher.matches`, with the matcher's URI set widened in
     Task 5 to include the three new URIs. Broad, because `ContextServiceDtoMapper:65` stamps 105 onto context
     services from the same migration list, and a document that keeps the claim cannot be imported by an older QIP.
     Widened, because otherwise the matcher rejects a post-Task-9 plain service outright and `revert()` — where the
     narrow gate lives — is never reached: gating only inside `revert()` is circular.
   - Writing `content.integrationSystemType` and restoring the plain service `$schema` must be **narrow**, gated
     inside `revert()` on the three new URIs, or a context service gets a service type stamped onto it.

   Restoring `$schema` is load-bearing beyond its own document: without it, V104 and V103 revert stop matching plain
   services and the legacy export silently loses its `apiGroups` → `specificationGroups` rename. Ordering needs no
   new mechanism — `FileMigrationService:49-51` already sorts reverts descending and `:131` re-evaluates
   `supportsDocument` on each intermediate result — but it does need a test over a document carrying the **new**
   `$schema`: Task 8 lands before Task 9, so Task 8's own tests run against old-URI documents and stay green even if
   the matcher was never widened.

Per-type rules that are pure data — allowed protocols and the environment limit — move onto `IntegrationSystemType`.
`activeEnvironmentId` stays in all three schemas. The REST layer discards the stored value for INTERNAL and
IMPLEMENTED (`SystemMapper:66-72`), but deploy-time resolution honours it for IMPLEMENTED
(`SystemEnvironmentsGenerator:58-64`), so dropping it from the file format would change deploy behaviour as well as
break existing documents.

## Technical Details

### Schema layout

Three new files under `schemas/src/main/resources/qip-model/`, each `$ref`-ing a shared content base:

| Schema | Protocols | Environments | `activeEnvironmentId` |
|---|---|---|---|
| `external-service.schema.yaml` | all except `METAMODEL` | unbounded | yes |
| `internal-service.schema.yaml` | all | `maxItems: 1` | yes (REST ignores it; kept for compatibility) |
| `implemented-service.schema.yaml` | `HTTP`, `SOAP`, `GRAPHQL` | `maxItems: 1` | yes (read at deploy time) |

Sources: `SystemBaseService:43-53` for protocols; the one-environment limit for both INTERNAL and IMPLEMENTED is the
domain owner's ruling.

The shared base holds only `Environment`, `SourceType`, and `activeEnvironmentId`. `description`, `labels`, and
`migrations` already have their own common-property schemas that `service.schema.yaml:16-19` `$ref`s — re-extracting
them would create a second definition of each. Cross-references from the three type schemas must use the **absolute**
URI (`http://…/common-properties/service-content.schema.yaml#/definitions/Environment`); a bare `#/definitions/…`
resolves against the referring document and fails silently.

Suppressing `integrationSystemType` needs an explicit `not: {required: [integrationSystemType]}`.
`additionalProperties: false` will not do it: in draft-07 it does not see properties contributed through `allOf`
`$ref`s, so it would reject the positive samples too.

**The backend enforces the environment limit in two places, both incomplete.** `SystemExportImportService:536` checks
INTERNAL only, inside an `if (INTERNAL) … else if (EXTERNAL)` chain where IMPLEMENTED has no branch, and only on the
update path — `prepareIntegrationSystemForCreate:850` has no check. `EnvironmentController:94-98` likewise guards
INTERNAL only. Task 4 closes all three before the schemas start claiming the rule.

`service.schema.yaml` stays and keeps describing the **current** format — every pre-#553 archive uses it, and
`V105RevertMigration` produces it.

### File names

| Type | Current format | Legacy format (unchanged) |
|---|---|---|
| EXTERNAL | `<id>.external-service.<app>.yaml` | `service-<id>.yaml` |
| INTERNAL | `<id>.internal-service.<app>.yaml` | `service-<id>.yaml` |
| IMPLEMENTED | `<id>.implemented-service.<app>.yaml` | `service-<id>.yaml` |

The `-service` suffix keeps them from colliding with `.service.` under `endsWith` and under VS Code's
`*.service.qip.yaml` glob — the same reason `.context-service.` is safe today.

The legacy flat name carries no type, which is why `content.integrationSystemType` remains the second resolution
source and `V105RevertMigration` must write it.

Import discovery must read **all four** postfixes plus the legacy `service-` prefix. Dropping one imports nothing and
says nothing. `ExportImportUtils.extractSystemsFromImportDirectory:281` is shared with the context and MCP import
services, so widen at the call site, not in the shared helper.

The export side needs a type it currently does not have: `ExportableObjectWriterVisitor:51` picks the file name from
an `ExportedIntegrationSystem`, which carries only id, node, and groups. Add a field to that class or read the type
back off the node.

### Migration 105

- `V105ServiceImportFileMigration` — `@Component`, `getVersion() == 105`, `makeMigration` returns the node unchanged,
  `isIdempotent() == true`. Its purpose is the version stamp, documented in the class.
- `V105RevertMigration` — broad `supportsDocument` (the matcher widened with the new URIs in Task 5), narrow write.
  Strips 105 from `content.migrations` for every document stamped from the service migration list, including context
  services.
- `TestServiceMigrations` must list V105, or four existing test classes run against a stale migration set.

## What Goes Where

- **Implementation Steps**: schema files, Java changes, migrations, tests, config
- **Post-Completion**: frontend work (plan 2), manual verification against the local stack, help-doc updates

## Implementation Steps

### Task 1: Extract the shared service content base

**Files:**
- Create: `schemas/src/main/resources/qip-model/common-properties/service-content.schema.yaml`
- Modify: `schemas/src/main/resources/qip-model/service.schema.yaml`

- [x] move only `Environment`, `SourceType`, and `activeEnvironmentId` into the new common file
- [x] leave `description`, `labels`, and `migrations` alone — `service.schema.yaml:16-19` already `$ref`s their own common-property schemas
- [x] leave `Protocol` out of the base — each type constrains it differently
- [x] rewrite `service.schema.yaml` to `$ref` the base, keeping `integrationSystemType` required (it still describes the current format of older archives)
- [x] verify the existing samples under `schemas/src/test/resources/samples/service/` still validate unchanged
- [x] run `npm -w @netcracker/qip-schemas test` and `mvn -pl schemas test` — must pass before task 2

➕ added two `__SHOULD_FAIL` service samples (manual environment without an address, environment without a source type)
that pin the extracted `Environment`/`SourceType` definitions: a cross-document `$ref` that fails to resolve validates
everything, and no existing sample would notice.

⚠️ `schemas` is **not** a module of the root aggregator `pom.xml` — run `mvn -f schemas/pom.xml test`, not
`mvn -pl schemas test`. Also run `clean`: surefire validates the sample copies under `target/test-classes/`, so a
removed or edited sample is invisible without it.

⚠️ Two untracked chain samples left over from unrelated in-flight work — `samples/chain/context-storage-ttl__SHOULD_FAIL.yaml`
and `scs-sender-ttl__SHOULD_FAIL.yaml` — fail on both harnesses: they expect a positive-`ttl` constraint that the
`context-storage` and `scs-sender` element schemas do not carry. Pre-existing, out of this plan's scope, not committed here.

### Task 2: Add the three service-type schemas

**Files:**
- Create: `schemas/src/main/resources/qip-model/{external,internal,implemented}-service.schema.yaml`
- Create: `schemas/src/test/resources/samples/{external,internal,implemented}-service/<type>-service-sample.yaml`
- Create: `schemas/src/test/resources/samples/{external,internal,implemented}-service/*__SHOULD_FAIL.yaml` (six files, see below)

- [x] write the three schemas against the shared base, each with its own `Protocol` enum and environment limit
- [x] reference the shared definitions by **absolute** URI — a bare `#/definitions/Environment` resolves against the referring document and fails silently
- [x] suppress `integrationSystemType` with `not: {required: [integrationSystemType]}`, not `additionalProperties: false`
- [x] set `metaInfo.fileExtension` per schema (`external-service.qip`, …), mirroring `context-service.schema.yaml`
- [x] add one positive sample per schema, each carrying an `activeEnvironmentId`
- [x] add `__SHOULD_FAIL.yaml` samples: `METAMODEL` on external, two environments on internal, two on implemented, `KAFKA` on implemented, `integrationSystemType` present
- [x] run `npm -w @netcracker/qip-schemas run build` and check `types/index.d.ts` — `generateTypes.ts:158-190` de-duplicates exported names first-seen-wins, so colliding `Environment`/`SourceType` names may be dropped silently
- [x] run schema tests — must pass before task 3

[deviation] samples live in per-schema directories (`samples/external-service/`, …) as the Testing Strategy prescribes,
not in `samples/service/` as this task's file list said. Neither harness resolves the schema from the directory — both
read `$schema` out of the sample — so the choice is organizational only.

➕ added a sixth `__SHOULD_FAIL` sample, `internal-service-manual-environment-without-address`, pinning the
cross-document `Environment` reference. An unresolved `$ref` validates everything, and the five specified negatives
would all still fail for their own reasons.

Each negative sample was checked to fail for its own constraint alone: `enum` on the two protocol samples, `maxItems`
on the two environment-count samples, `not` on the type-field sample, `required: address` on the added one.

⚠️ `types/index.d.ts` re-exports `ExternalService`, `InternalService`, and `ImplementedService1`/`ImplementedService2`.
The bare `ImplementedService` name is taken by the http-trigger endpoint definition
(`element/http-trigger.schema.yaml:112-113`), which the barrel sees first. No `Environment`/`SourceType` export was
dropped — neither name has ever been emitted, for the same reason `service.schema.yaml` emits none: the compiler keeps
the `content` `allOf` intersection and discards its sibling `properties`. `Service` was likewise already absent before
this task. Consumers read service schemas through `schemasByType`, not through the barrel, so this is left as is.

### Task 3: Move per-type rules onto IntegrationSystemType

**Files:**
- Modify: `runtime-catalog/.../model/system/IntegrationSystemType.java`
- Modify: `runtime-catalog/.../service/SystemBaseService.java`
- Modify: `runtime-catalog/.../persistence/configs/entity/actionlog/EntityType.java`
- Create: `runtime-catalog/src/test/java/.../model/system/IntegrationSystemTypeTest.java`

- [x] add `allowedProtocols()` and `maxEnvironments()` to the enum, each an exhaustive `switch` with no `default`
- [x] `maxEnvironments()` returns `int`, with `Integer.MAX_VALUE` for EXTERNAL — pick one representation, Task 4 compares against it in three places
- [x] do **not** add `usesActiveEnvironment()`: `SystemMapper:66-72` already ignores the stored value for INTERNAL and IMPLEMENTED, so the predicate would encode a distinction the REST layer does not make
- [x] replace `SystemBaseService.ALLOWED_PROTOCOL_MAP` with `type.allowedProtocols()`
- [x] rewrite `EntityType.getSystemType` as an exhaustive switch (drop the `default` branch that silently maps an unknown type to `EXTERNAL_SERVICE`)
- [x] write tests asserting each type's protocol set and environment limit
- [x] write a test asserting every enum constant is covered, so adding a type without updating the rules fails
- [x] run `mvn -pl runtime-catalog test` — must pass before task 4

➕ added `SystemBaseServiceTest` and `EntityTypeTest` — both methods the task rewrites live outside the enum, and
neither had a test. `EntityTypeTest` also pins that a typeless service still raises rather than being reported as
`EXTERNAL_SERVICE`: dropping the `default` branch does not change that, because a `switch` over a null enum already
threw.

[decision] `validateSpecificationProtocol` now rejects a null service type with a message naming the service id. The
old `ALLOWED_PROTOCOL_MAP.getOrDefault(...)` fell through to `systemType.name()` and threw a bare NPE on the same
input, so this replaces one throw with a better one rather than adding a check. The environment-side null guards stay
in Task 4.

[decision] the three protocol sets are private static constants selected by the exhaustive `switch`, not built per
call: `allowedProtocols()` is on the specification-import path, and the sets are immutable
(`Set.of` / `toUnmodifiableSet`), which `IntegrationSystemTypeTest` pins.

### Task 4: Close the environment-limit holes

**Files:**
- Modify: `runtime-catalog/.../service/SystemBaseService.java`
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java`
- Modify: `runtime-catalog/.../rest/v1/controller/EnvironmentController.java`
- Modify: `runtime-catalog/src/test/java/.../service/SystemBaseServiceTest.java`
- Modify: `runtime-catalog/src/test/java/.../exportimport/SystemExportImportServiceTest.java` (create if absent)
- Modify: `runtime-catalog/src/test/java/.../rest/v1/controller/EnvironmentControllerTest.java` (create if absent)

- [x] hoist the environment count check out of the `if (INTERNAL) … else if (EXTERNAL)` chain at `:534-560` so IMPLEMENTED is covered, driving it from `type.maxEnvironments()`
- [x] apply the same check on the create path (`prepareIntegrationSystemForCreate:850`), which has none today
- [x] widen the `EnvironmentController:94-98` guard from INTERNAL to `type.maxEnvironments()`
- [x] guard against a null type before dereferencing it — the column is nullable and legacy rows may carry one; today's `IntegrationSystemType.INTERNAL.equals(...)` is null-safe and `getType().maxEnvironments()` is not
- [x] keep one shared message so all three paths report the violation identically
- [x] write tests: a second environment is rejected for INTERNAL and IMPLEMENTED, accepted for EXTERNAL, on import-create, import-update, and the REST path
- [x] write a test for `EnvironmentController.updateEnvironment:116-118`, which falls through to create on an unknown id — a stale id against a full service now throws where it previously created
- [x] write a test: a service row with a null type does not crash the guard
- [x] run tests — must pass before task 5

[decision] the shared check started as `SystemBaseService.validateEnvironmentCount(system, count)`, next to the existing
`validateSpecificationProtocol` — the same per-type validation shape, and both call sites already held a
`SystemService`, so no new bean or injection. **Revised twice in review.** First the method became `static`: it reads
none of the service's fields, and routing a pure rule through an injected bean made two test classes build a
`SystemBaseService(null, null, null)` and delegate a Mockito stub back to it just to exercise the real rule. Then it
left `SystemBaseService` altogether — a static rule on a `@Service` is not where a caller looks for one — and now lives
in `util/EnvironmentLimitUtils` as `validate(system, count)`, with the non-throwing `violation(system, count)` beside
it for the export warning. It takes the count the service *would* end up with, because the REST create path checks
`existing + 1` while the import paths check the count the file carries.

[decision] a null type skips the check rather than raising. Today `IntegrationSystemType.INTERNAL.equals(null)` is false
on all three paths, so a typeless row is unconstrained; raising here would newly reject legacy rows on a path this task
is only meant to widen. Task 9 is where a null type becomes an error, and there it is the export that needs one.

[decision] the message is a `BadRequestException`, not the bare `RuntimeException` the import path threw. The REST path
already answered 400 through `GlobalExceptionHandler`, and `importOneSystemInTransaction:449` catches `Exception`, so
the import path still degrades to `ImportSystemStatus.ERROR` with the message intact.

➕ added environment-limit cases to `SystemBaseServiceTest` — the rule itself is unit-tested there, and the controller
and import tests delegate their `systemService` mock to a real `SystemBaseService`, so all three suites exercise one
implementation rather than a stub of it. **Superseded in review**: once the rule moved to `EnvironmentLimitUtils` the
cases moved with it into `EnvironmentLimitUtilsTest`, and both mock-delegation blocks were deleted — a static utility
needs no stub to route around.

### Task 5: Add the file-postfix ↔ type registry

**Files:**
- Modify: `runtime-catalog/.../configuration/ApplicationJsonSchemaProperties.java`
- Modify: `runtime-catalog/src/main/resources/application.yml`
- Modify: `runtime-catalog/.../service/exportimport/ExportImportConstants.java`
- Modify: `runtime-catalog/.../service/exportimport/migrations/revert/ServiceDocumentMatcher.java`
- Create: `runtime-catalog/.../service/exportimport/ServiceTypeFiles.java`
- Create: `runtime-catalog/src/test/java/.../exportimport/ServiceTypeFilesTest.java`

- [x] add `EXTERNAL_SERVICE_YAML_NAME_POSTFIX`, `INTERNAL_…`, `IMPLEMENTED_…` next to the existing constants
- [x] add a component mapping file name → `IntegrationSystemType` and type → postfix; import, export, and both migrations use it, so it lives in one place
- [x] add `externalService`, `internalService`, `implementedService` URI properties with `*_JSON_SCHEMA_URI` overrides — defaults **in the class fields**, not only `application.yml`: `TestRevertMigrations.matcher()` builds `ServiceDocumentMatcher` from `new ApplicationJsonSchemaProperties()`, so a yml-only default leaves the test matcher unwidened
- [x] add a URI → type mapping used **only** by the revert migration, which works on documents that have no file name
- [x] document at the mapping that `$schema` is not a reliable type source on the import path — the VS Code extension writes a project-configured value (`.config.qip.yaml.example:15-22`)
- [x] widen `ServiceDocumentMatcher`'s URI set with the three new URIs — every revert migration is gated on it, and a post-Task-9 plain service otherwise matches nothing, silencing V105/V104/V103 at once (see Solution Overview)
- [x] write tests: each postfix resolves to its type, `.service.` and the legacy `service-` prefix resolve to none, `.context-service.` is never mistaken for a plain service
- [x] write a test: the matcher accepts a document carrying each of the three new URIs and still rejects a chain document
- [x] write a test asserting the configured URIs match the `$id`s of the schemas added in Task 2
- [x] run tests — must pass before task 6

[decision] the `$id` check reads the schema files off the test classpath through a new `<testResource>` in
`runtime-catalog/pom.xml` (`../schemas/src/main/resources/qip-model` → `qip-model`, limited to `*service.schema.yaml`),
mirroring how the conformance corpus is already wired. runtime-catalog has no Maven dependency on `qip-schemas`, so a
sibling-directory read is the only way to compare, and the classpath route needs no assumption about the surefire
working directory. The test covers all six configured service URIs, not only the three new ones.

➕ added `ServiceDocumentMatcherTest`. The matcher is the gate every revert migration hangs on and had no test of its
own — the widening was only observable through `V103RevertMigrationTest` / `V104RevertMigrationTest`, neither of which
exercises a per-type URI.

[decision] `ServiceTypeFiles.postfixes()` is static and `schemaUris()` is not: the postfixes are compile-time constants
that import discovery (Task 10) needs from a static context, while the URIs come from configuration.

### Task 6: Resolve the type from the file name on import

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/deserializer/ServiceDeserializer.java`
- Modify: `runtime-catalog/src/test/java/.../exportimport/deserializer/ServiceDeserializerTest.java`

- [x] after `toInternalEntity` at `:104`, set the type from the file name when the entity has none
- [x] resolution order: file-name postfix, then `content.integrationSystemType`, then fail — the legacy flat name carries no type, so the field remains a required fallback
- [x] do **not** consult `$schema`; do **not** move this into `IntegrationSystemDtoMapper`, which never sees a file name
- [x] fail loudly rather than persisting a null: the column is nullable and a null surfaces much later as an NPE in `EntityType.getSystemType:57`
- [x] write tests: each of the three postfixes yields its type with no field present
- [x] write a test: a legacy `service-<id>.yaml` resolves from the field
- [x] write a test: a file whose name and field disagree is reported, not silently resolved
- [x] write a test: neither source present is rejected with a clear message
- [x] run tests — must pass before task 7

[decision] a name/field disagreement raises rather than letting the name win. The task says "reported, not silently
resolved", and a mismatch is a hand-edited or mis-renamed file either way — resolving it silently would import a service
under a type nobody chose. The exception is a `ServiceImportException`, so `SystemExportImportService:449-457` degrades
it to `ImportSystemStatus.ERROR` for that one service, as with every other per-service import failure.

➕ added `keepsTheTypeWhenTheFileNameAndTheDocumentAgree` and `resolvesTheTypeFromTheDocumentForAPre553FileName`. The
first pins the agreement path that the disagreement test only approaches from one side; the second covers the
current-format pre-#553 `.service.` name, which is a separate resolution source from the legacy flat one the task lists.

[decision] `ServiceTypeFiles` is constructor-injected into `ServiceDeserializer`. The three existing test suites that
build the deserializer by hand (`V103RevertMigrationTest`, `V104RevertMigrationTest`, `V104ServiceImportFileMigrationTest`)
pass `new ServiceTypeFiles(new ApplicationJsonSchemaProperties())` — the class-field defaults Task 5 added make that
instance equivalent to the wired one.

**Revised in review.** `typeFromFileName` reads only the static postfix map, so it is now `static` and the injection is
gone: `ServiceDeserializer` lost its ninth constructor parameter and the four hand-wired test sites lost their
`new ServiceTypeFiles(...)`. Only the schema URIs come from configuration, and only the revert migration reads them.

### Task 7: Add V105 as a compatibility barrier

**Files:**
- Create: `runtime-catalog/.../service/exportimport/migrations/system/V105ServiceImportFileMigration.java`
- Modify: `runtime-catalog/src/test/java/.../migrations/system/TestServiceMigrations.java`
- Create: `runtime-catalog/src/test/java/.../migrations/system/V105ServiceImportFileMigrationTest.java`

- [x] implement `makeMigration` as a documented no-op: resolution lives in Task 6 and runs for every document, so this class exists only so exports stamp 105 and an older QIP refuses to mis-import
- [x] state in the comment how far the barrier reaches: an old QIP never discovers the new plain-service names (`ExportImportUtils:287-288`), so it fires only on documents the old QIP still finds — context services and legacy-named files — as a per-service `ImportSystemStatus.ERROR` (`SystemExportImportService:449-457`), never as a rejected archive
- [x] return `isIdempotent() == true` and register the class as a `@Component`
- [x] add V105 to `TestServiceMigrations`, or four existing test classes keep running against a stale set
- [x] write a test asserting the document is returned unchanged, including for a context and an MCP document
- [x] write a test asserting a document claiming 105 is rejected by a `FileMigrationService` whose registry lacks it
- [x] run tests — must pass before task 8

[decision] `makeMigration` returns the node it was handed rather than a `deepCopy`. Every other service migration copies
because it writes; copying to write nothing would only invite the next reader to look for the write. The test pins both
that the result equals the input and that the input itself is unmutated.

[decision] `MigrationBeanRegistrationTest.theRolloutImportPathClaimsOnlyTheseServiceMigrationVersions` needed no change:
`isIdempotent() == true` keeps 105 out of the rollout-claimed set, which is the correct outcome — the rollout converter
runs the no-op instead of claiming a version it never applied.

➕ added `theSameDocumentPassesOnceTheMigrationIsRegistered` and `aPre553DocumentIsStillAccepted`. The barrier test only
shows the refusal; these two show that the same document passes on this QIP and that a pre-#553 document still migrates
forward, so a later change cannot turn the barrier into a blanket rejection unnoticed.

### Task 8: Add V105 revert migration

**Files:**
- Create: `runtime-catalog/.../service/exportimport/migrations/revert/V105RevertMigration.java`
- Modify: `runtime-catalog/src/test/java/.../migrations/revert/TestRevertMigrations.java`
- Create: `runtime-catalog/src/test/java/.../migrations/revert/V105RevertMigrationTest.java`

- [x] use the **broad** `ServiceDocumentMatcher.matches` (widened with the new URIs in Task 5) for `supportsDocument`, so the 105 strip reaches context services — `ContextServiceDtoMapper:65` stamps them from the same migration list, and a kept claim makes their legacy export unimportable
- [x] register V105Revert in `TestRevertMigrations.all()` — the parallel registry to `TestServiceMigrations`, consumed by `V103RevertMigrationTest`, `V104RevertMigrationTest`, and `ServiceSerializerTest`; without it the legacy-export tests run a chain missing V105
- [x] gate the `content.integrationSystemType` write and the `$schema` restore **inside** `revert()` on the three new URIs, so a context service is not stamped with a service type
- [x] strip `105` from `content.migrations` unconditionally
- [x] write tests for all three types: field written, plain service `$schema` restored, version stripped
- [x] write a test: a context-service document keeps its shape but loses the 105 claim
- [x] write a test asserting `ServiceDocumentMatcher` matches the document again after this revert runs, so V104 and V103 still apply
- [x] write a full revert-chain test over a golden exported document **containing api groups**, asserting the `apiGroups` → `specificationGroups` rename still happens
- [x] run tests — must pass before task 9

[deviation] the revert-chain test builds its document inline, carrying the **new** per-type `$schema`, rather than
reading a golden file: the golden corpus is captured in Task 9, and an export written today still stamps the plain URI.
The inline document is the shape Task 9 will produce, so this task's tests already run against a new-URI document
instead of staying green on old-URI ones — the trap the Solution Overview records. Verified by temporarily removing the
three new URIs from `ServiceDocumentMatcher`: 9 of the 22 cases fail, including the whole chain test. Task 9's re-run
checkbox stays, now as a check against a real export rather than a hand-written stand-in.

[decision] the plain service URI comes from an injected `ApplicationJsonSchemaProperties`, not from a `@Value` on the
constructor as `V103RevertMigration` does for the specification URI. `ServiceDocumentMatcher` and `ServiceTypeFiles`
already read their URIs from that bean, and the test registry builds all three from one instance, so a per-migration
`@Value` default would be a fourth copy of the same string.

[decision] `revert()` overwrites `content.integrationSystemType` instead of writing it only when absent. A document
carrying a per-type `$schema` and a disagreeing field is hand-edited either way, and the exporter derives both from one
type, so the two can only disagree if somebody edited one of them.

➕ added three cases beyond the listed ones: a pre-#553 document is left alone apart from the strip (the URI gate has to
be a no-op on the old URI, not just correct on the new ones), the input node is not mutated (the chain aliases each
result into the next migration), and a legacy round trip per type through the real `ServiceDeserializer` — the last is
what shows *why* the field is written, since Task 6 refuses a service that states its type nowhere.

### Task 9: Write the new file names on export

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/mapper/services/IntegrationSystemDtoMapper.java`
- Modify: `runtime-catalog/.../model/exportimport/system/IntegrationSystemContentDto.java`
- Modify: `runtime-catalog/.../service/exportimport/serializer/ExportableObjectWriterVisitor.java`
- Modify: `runtime-catalog/.../model/system/exportimport/ExportedIntegrationSystem.java`
- Modify: `runtime-catalog/.../util/ExportImportUtils.java`
- Create: `runtime-catalog/src/test/java/.../exportimport/ServiceExportFormatTest.java`

- [x] capture a golden legacy-format export **before** changing anything, so the no-regression claim is measurable
- [x] make `toExternalEntity:79` stamp the per-type `$schema` through the Task 5 registry instead of its `@Value` field
- [x] suppress `integrationSystemType` with `@JsonProperty(access = WRITE_ONLY)` on the DTO field — `@Jacksonized` copies the annotation onto the builder setter, so deserialization keeps binding it. Not `@JsonIgnore`, which kills deserialization of every pre-#553 archive; not the shared `baseEntityFilter` (`MapperAutoConfiguration:125`), which is audit-field stripping shared by five DTOs
- [x] keep the field in the legacy format, where Task 8's revert restores it
- [x] carry the type to `ExportableObjectWriterVisitor:51` (add a field to `ExportedIntegrationSystem` or read it off the node) and pick the file name from it
- [x] note that until Task 10 lands, a fresh export cannot be re-imported — the intermediate state is knowingly broken and the module test suite will not detect it
- [x] write tests: each type exports to the expected file name with the expected `$schema` and no type field
- [x] write a test: an old archive still deserializes with its type field intact
- [x] re-run Task 8's revert-chain test over a golden document carrying the **new** `$schema` — Task 8 predates this task, so its own tests stay green on old-URI documents even if the matcher was never widened
- [x] write a test: with `qip.export.legacy-format=true` the output is **semantically** equal to the golden file — `ObjectNode` is insertion-ordered and the revert appends restored keys last, so byte equality is unattainable
- [x] fail exporting a null-type service with a clear message naming the service id — the file name now requires a type, and today such a row only NPEs later at `logSystemExportImport` (`:906` → `EntityType.getSystemType:57`)
- [x] write a test: exporting a null-type service yields the message, not an NPE
- [x] run tests — must pass before task 10

⚠️ **RESOLVED in Task 10.** Between Task 9 and Task 10 a fresh export could not be re-imported, and no test here
detected it: `ExportImportUtils` discovery matched only `.service.` and the legacy `service-` prefix, and
`.external-service.` does not contain `.service.`, so a post-#553 archive was silently invisible to this build's own
import path. Deliberate, as the plan said — the intermediate state was knowingly broken. Task 10 widened discovery to
all four postfixes and pinned the round trip on the golden corpus
(`SystemExportImportServiceTest.everyArchiveFormatImportsWithItsType`, over `post553`, `pre553-current`, and
`legacy-flat`).

➕ built the golden corpus (`runtime-catalog/src/test/resources/exportimport/golden/{pre553-current,legacy-flat,post553}/`)
plus its generator, `GoldenServiceCorpus` (fixtures + serializer wiring + readers) and `GoldenCorpusCapture` (the
regeneration entry point). Five fixture systems per set — EXTERNAL with two environments, one api group, one api and one
real openapi source, INTERNAL, IMPLEMENTED, a context service and an MCP service — exported through the real
`ServiceSerializer` → `ArchiveWriter` chain and unzipped into the resource tree. `pre553-current` and `legacy-flat` were
captured on the untouched exporter, `post553` after the change; `ServiceExportFormatTest` measures the change against
all three.

[decision] `GoldenCorpusCapture` is a committed class, not a throwaway script, so the corpus is reproducible and its
provenance reviewable. Its name is outside Surefire's include patterns, so the suite never runs it —
`mvn -pl runtime-catalog test -Dtest=GoldenCorpusCapture#capturePost553 -DfailIfNoTests=false` does. `capturePre553Current`
asserts it produced a `.service.` name, so re-running it on this checkout fails loudly instead of overwriting the
baseline with today's format.

[deviation] `legacy-flat` carries no document with inline `specificationGroups`, which the Testing Strategy lists for
that set. The exporter never inlines groups — `IntegrationSystemDtoMapper` does not fill `apiGroups`, and every group is
written as its own file — so such a document cannot come out of a capture. The inline shape belongs to pre-V101
archives; `V105RevertMigrationTest.aLegacyExportedServiceReimportsWithItsType` covers its round trip from a hand-built
document, and `ServiceExportFormatTest.theRevertChainStillRenamesTheApiGroupsOfARealPost553Export` inlines the real
golden api-group node into the real golden service document to exercise V104's rename over a new-URI export.

[decision] the null-type export raises a new `ServiceExportException` (id, name, message), mirroring
`ServiceImportException`. Not `IllegalArgumentException`: `SystemExportImportService.exportOneSystem:181` catches that
one and rewrites it into "Error while serializing system with system id: X …", which doubles the id and buries the
sentence. `GlobalExceptionHandler` answers 500 with the message intact — an unexportable row is a data problem, not a
bad request.

[decision] `ServiceTypeFiles.postfix(type)` became static, alongside `postfixes()`. `ExportImportUtils` builds the
export file name from a static context, and the postfixes are compile-time constants — only the URIs come from
configuration.

➕ `ServiceSerializerTest.eachExportedEntityCarriesItsSchemaId` now also pins the three per-type URIs and asserts an
EXTERNAL service stamps `external-service.schema.yaml`; `V104RevertMigrationTest.aBareServiceExportsWithoutClaimingVersion104`
gained a type, because a typeless service no longer exports at all.

### Task 10: Read the new file names on import

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java`
- Modify: `runtime-catalog/.../util/ExportImportUtils.java`
- Modify: `runtime-catalog/src/test/java/.../exportimport/deserializer/ServiceDeserializerTest.java`

- [x] add a multi-postfix overload of `extractSystemsFromImportDirectory` — one directory walk, one legacy-prefix check, a deduplicated result — and call it from the four `SystemExportImportService` sites (`:224,251,322,376`); calling the single-postfix version four times returns every legacy-prefix file four times (`:287` ORs the prefix in unconditionally) and imports it once per copy
- [x] leave the existing single-postfix version to the context and MCP import services, which share it
- [x] reject duplicate ids at the discovered-list level in `SystemExportImportService`, grouping by `extractSystemIdFromFileName` **before** the per-file transaction loop — the two files land as separate `deserializeSystem` calls in separate transactions and never see each other
- [x] update `ServiceDeserializerTest:1098`, which hardcodes `SYSTEM_ID + ".service." + APP_NAME + ".yaml"`
- [x] write tests: an archive of each new format imports with the right type, on both the commit path and the preview path (`:224`, the import-preview request)
- [x] write tests: a legacy archive and a current-format pre-#553 archive both still import
- [x] write a test: an archive containing two service files for one id is rejected rather than resolved arbitrarily
- [x] run tests — must pass before task 11

[decision] the single-postfix overloads of `extractSystemsFromImportDirectory` and `extractSystemsFromZip` now delegate
to the multi-postfix ones with a one-element list rather than keeping their own walk. The context and MCP import
services call them unchanged, and one implementation cannot drift from the other.

[decision] the duplicate-id check rejects the whole archive with a `BadRequestException` rather than erroring the one
service. It runs before the per-file loop, so no service has been written yet, and answering 400 with the id and both
file names is the only outcome the caller can act on. `getSystemsImportPreview:251` needed a `catch (BadRequestException)`
rethrow so its blanket `catch (Exception)` does not bury the message in "Error while extracting systems".

**Revised in review.** "No service has been written yet" holds for services and not for the session:
`GeneralImportService.importDirectoryAsync` applies import instructions and common variables *before*
`importSystems`, and runs chains, context services, and MCP services *after*, so the throw ended the session with part
of it already committed — while the message said "Nothing is imported from this archive." The colliding id now
degrades to one error row (`SystemCompareAction.ERROR` on the preview, `ImportSystemStatus.ERROR` on the commit) and
the rest of the archive imports, which is how every other per-service failure on this path behaves. The
`catch (BadRequestException)` rethrow went with it.

[decision] `ServiceDeserializerTest.writeService` now defaults to the current per-type name (`.external-service.`), with
`writePre553Service` for the two cases that need a file stating no type of its own. Every other case in that suite states
`integrationSystemType: EXTERNAL` in the document, so it exercises the agreement path against the name the exporter
actually writes.

➕ moved `ServiceExportFormatTest.deserializer()` into `GoldenServiceCorpus.deserializer()`. Task 10's commit-path tests
need the same real deserializer, and two hand-wired copies of a nine-argument constructor drift.

➕ added the discovery unit tests to `ExportImportUtilsTest`: one asserting the four postfixes plus the legacy prefix are
found in one walk while context, MCP, and api-group neighbours are not, and one pinning the trap this task exists for —
four single-postfix calls return a legacy-named file four times, the multi-postfix call returns it once.

### Task 11: Reject service-type changes

**Files:**
- Modify: `runtime-catalog/.../rest/v1/controller/SystemController.java`
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java`
- Modify: `runtime-catalog/src/test/java/.../rest/v1/controller/SystemControllerTest.java` (create if absent)

- [x] reject an import that would change an existing service's type, naming both values
- [x] close the `updateSystem` → `createSystem` fall-through at `SystemController:125`: an unknown id on PUT should 404, not create a service with a caller-chosen type
- [x] check no client depends on that fall-through before removing it (UI, extension, tests)
- [x] leave `mergeWithoutLabels` alone — it already does not map the type; add a test that pins this rather than changing the mapper
- [x] write a test: importing an `internal-service` file over an existing EXTERNAL service is rejected and the stored entity is unchanged
- [x] write a test: PUT with a different type on an existing service does not change it
- [x] write a test: PUT on an unknown id no longer creates a service
- [x] run tests — must pass before task 12

**Fall-through dependency search (the checkbox above), result: none.** `PUT /v1/systems/{id}` has exactly one caller,
`RestApi.updateService` (`ui/src/api/rest/restApi.ts:1593`), reached from `ServicesList.tsx:314` (`record.id`),
`ServiceParametersTab.tsx:108` (`systemId` from the route), and `ServiceEnvironmentsTab.tsx:171` (`systemId`) — every one
of them an id the UI has already loaded. Creation goes through `RestApi.createService` (`:1335`), a POST. The VS Code
extension's `updateService` (`serviceApiModify.ts:94`) is the offline file API and reaches no backend. No runtime-catalog
test, help page, or Nginx rule referenced the fall-through. Removing it needed no client change.

[decision] the import rejection is a `ServiceImportException`, not the `BadRequestException` Task 4 chose. Task 4's rule
is shared with the REST path, which answers 400; this one is import-only, and `ServiceImportException` carries the id and
name that `SystemExportImportService:449-457` turns into an `ImportSystemStatus.ERROR` row for that one service, leaving
the rest of the archive to import.

[decision] a stored null type accepts whatever the import states, rather than being treated as a change. The column is
nullable, so a legacy row genuinely has no type; an import that states one repairs the row instead of overwriting a
decision. This matches Task 4's null handling, and Task 9 already refuses to *export* such a row.

[decision] the PUT 404 is a `jakarta.persistence.EntityNotFoundException` — `GlobalExceptionHandler:76-79` answers 404
with the message intact, and `SystemBaseService.delete` already reports an unknown service the same way.

➕ pinned `patchMergeWithoutLabels` alongside `mergeWithoutLabels`. `PATCH /systems/{id}` shares the request DTO and the
same generated-merge shape, so it is the second door onto the type and needs the same pin. Added two more import cases —
the same type is not read as a change, and a typeless stored row accepts one — so the guard cannot degrade into rejecting
every update unnoticed.

### Task 12: Round-trip verification

**Files:**
- Create: `runtime-catalog/src/test/java/.../exportimport/ServiceTypeRoundTripTest.java`

- [x] write a round-trip test per type: create → export → import → assert the persisted type is **non-null and equal**
- [x] write a round-trip test per type in the legacy format, with the same non-null assertion
- [x] write a legacy round-trip test for a **context service exported alongside a plain service**, asserting it imports into a pre-#553-shaped QIP — this is the regression Task 8's predicate exists to prevent
- [x] write a cross-format test: export legacy, import into current-format QIP
- [x] write a test covering the create path specifically, not only update — they are separate code paths (`:477` vs `:474`)
- [x] write a current-format test importing one archive containing all five kinds (three plain types + context + MCP) through both the preview and commit paths — it also exercises Task 10's multi-postfix dedup against real neighbours
- [x] run the full runtime-catalog suite — must pass before task 13

[decision] every round trip runs over **live** archive bytes — `GoldenServiceCorpus.archive(legacy)` through the real
serializer and `ArchiveWriter` — and enters the importer through `importSystemRequest`, the production unzip path.
`SystemExportImportServiceTest.everyArchiveFormatImportsWithItsType` already pins the *committed* golden sets end to end
on the same commit path; reading them again here would test the corpus rather than what today's exporter writes. The two
are complementary: one is a regression pin on the recorded format, this one is a live loop.

[decision] the cross-format test does the legacy→current conversion in full — import a legacy archive, re-export the
imported entity in the current format, then re-import that. Asserting only "a legacy archive imports" would duplicate
the legacy row of the per-type round trip; the conversion is the statement worth pinning, and it fails if the exporter
and the file-name registry ever disagree about a type it just read out of a document.

[decision] a "pre-#553-shaped QIP" is modelled as the real deserializers over `TestServiceMigrations.all()` minus V105.
That is exactly what an older registry holds, and it needs no second copy of the deserializer wiring.

➕ extended `GoldenServiceCorpus` rather than re-wiring the serializers in the test: `serviceSerializer(legacy)`,
`exportServices(services, legacy)`, `archive(exported, legacy)`, `serviceFileIn(root, id)`, `deserializer(migrations)`,
`contextServiceDeserializer(migrations)`, and `mcpSystemDeserializer()`. The five-kinds test needs all three import
services, and three hand-wired copies of their constructors would drift.

⚠️ `Environment.equals` NPEs when **both** label lists are null: `CompareListUtils.listSizeEquals(null, null)` returns
true and the loop then iterates the null one. A row loaded from the database has an empty `@ElementCollection`, never
null, so production does not reach it — but a fixture used as a stored row does. `ServiceTypeRoundTripTest.stored(...)`
normalizes the fixture the way the database would. Left as is: pre-existing, unrelated to #553, and a fix belongs with
`CompareListUtils`.

### Task 13: Verify acceptance criteria

- [x] verify all requirements from Overview are implemented
- [x] verify the schemas reject each constraint they claim to enforce (negative samples pass)
- [x] verify the schema constraints and the backend checks agree — nothing enforces this automatically (see Context)
- [x] run `mvn -f schemas/pom.xml clean install -Dgpg.skip=true` **and** `mvn -pl runtime-catalog clean install -Dgpg.skip=true` — two commands, not the one this task originally listed: `schemas` is not a module of the root aggregator, so `-pl schemas` resolves nothing (same correction as Task 1's ⚠️)
- [x] run `npm -w @netcracker/qip-schemas test`
- [x] verify Checkstyle reports zero violations and coverage did not drop below the project standard

**Overview verified claim by claim**, each against the symbol that satisfies it:

| Claim | Satisfied by |
|---|---|
| three dedicated schemas replace the field | `external-service.schema.yaml`, `internal-service.schema.yaml`, `implemented-service.schema.yaml`, each suppressing the field with `not: {required: [integrationSystemType]}` |
| the backend **reads** them | `ServiceDeserializer.resolveServiceType`, over `ExportImportUtils.extractSystemsFromImportDirectory(String, Collection)` driven by `SystemExportImportService.SERVICE_FILE_POSTFIXES` (all four postfixes plus the legacy prefix) |
| the backend **writes** them | `IntegrationSystemDtoMapper.toExternalEntity:86` stamps `serviceTypeFiles.schemaUri(...)`; `ExportImportUtils.generateMainSystemFileExportName:227` builds the name from `ServiceTypeFiles.postfix(type)`; the type reaches it through `ExportedIntegrationSystem.type` |
| the file name states the type | `ServiceTypeFiles.typeFromFileName`; the name wins, `content.integrationSystemType` is the fallback, `$schema` is not consulted |
| `METAMODEL` on an external service is now rejected offline | the `Protocol` enum of `external-service.schema.yaml`, pinned by `external-service-metamodel-protocol__SHOULD_FAIL.yaml` |
| ten environments on an internal service are now rejected offline | `maxItems: 1` in `internal-service.schema.yaml`, pinned by `internal-service-two-environments__SHOULD_FAIL.yaml` |
| the bare `RuntimeException` at import time is gone | `EnvironmentLimitUtils.validate` raises `BadRequestException`, called from `SystemExportImportService:636` (import update), `:971` (import create), and `EnvironmentController:99` (REST) |
| no document can name one type and state another | `ServiceDeserializer.resolveServiceType` raises `ServiceImportException` on a name/field disagreement |
| symmetry with context and MCP services | the three schemas mirror `context-service.schema.yaml` — per-type `$id`, `metaInfo.fileExtension`, no type field |
| the `integration_system_type` column stays | still declared at `V100_000__init.sql:415`; no migration drops it |
| no JPA inheritance | no `@Inheritance` or `@DiscriminatorColumn` anywhere under `runtime-catalog/src/main/java` |
| the type is not mutable through PUT | `SystemController.updateSystem:119` raises `EntityNotFoundException` instead of falling through to create; `mergeWithoutLabels` still maps no type |

**Every negative sample fails for its own constraint, and only for it.** Checked one sample at a time under the AJV
configuration `schemas.test.ts` uses, reading the reported keyword rather than the pass/fail bit — a negative sample
that fails for an unrelated reason proves nothing:

| Sample | Reported failure |
|---|---|
| `external-service-metamodel-protocol` | `enum` at `/content/protocol` |
| `external-service-with-type-field` | `not` at `/content` |
| `implemented-service-kafka-protocol` | `enum` at `/content/protocol` |
| `implemented-service-two-environments` | `maxItems` at `/content/environments` |
| `internal-service-two-environments` | `maxItems` at `/content/environments` |
| `internal-service-manual-environment-without-address` | `required: address` at `/content/environments/0` |

No sample reported a second, unrelated error, and all three positive samples validate.

**The schemas and the backend agree — no drift.** `IntegrationSystemType.allowedProtocols()` and `maxEnvironments()`
read against the three `Protocol` enums and their `maxItems`:

| Type | Enum protocols | Schema `Protocol` | Enum limit | Schema `maxItems` |
|---|---|---|---|---|
| EXTERNAL | every `OperationProtocol` but `METAMODEL` (6) | the same 6 | `Integer.MAX_VALUE` | absent |
| INTERNAL | every `OperationProtocol` (7) | the same 7 | 1 | 1 |
| IMPLEMENTED | HTTP, SOAP, GRAPHQL | HTTP, SOAP, GRAPHQL | 1 | 1 |

➕ that agreement is now enforced rather than checked once. `ServiceTypeFilesTest` gained
`allowsExactlyTheProtocolsItsSchemaEnumerates` and `limitsEnvironmentsExactlyAsItsSchemaDoes`, which read each type's
schema off the test classpath — the `<testResource>` Task 5 added — and compare it against the enum. The Context
section records that nothing detects the backend drifting from the schema and leaves it "on the tests in Tasks 3, 4,
and 13"; this is that test. Verified by mutation: adding `METAMODEL` to the external enum and widening the internal
`maxItems` to 5 fails both cases with the schema file named in the message.

[decision] "coverage did not drop below the project standard" is recorded as a measurement, not a gate. Neither
`parent/pom.xml` nor `runtime-catalog/pom.xml` binds the JaCoCo `check` goal — only `prepare-agent` and `report` — so
the module has no configured minimum. runtime-catalog stands at 28.1% instruction and 23.4% branch overall, and every
class this plan touched sits far above that: `IntegrationSystemType`, `EntityType`, `ServiceDocumentMatcher`,
`V105ServiceImportFileMigration`, and `IntegrationSystemDtoMapper` at 100%, `V105RevertMigration` 96%,
`ExportableObjectWriterVisitor` 95%, `ServiceDeserializer` 95%, `ServiceTypeFiles` 95%.

**Validation.** `mvn -f schemas/pom.xml clean install -Dgpg.skip=true` — 779 tests, 0 failures, with the two untracked
chain samples Task 1 recorded set aside; quarantining them and rerunning confirmed they are the module's only failures
and that neither touches #553. `mvn -pl runtime-catalog clean install -Dgpg.skip=true` — 1377 tests, 0 failures,
0 Checkstyle violations. `npm -w @netcracker/qip-schemas test` — 127 tests, 0 failures.

⚠️ `mvn install` on runtime-catalog logs two `MavenReportException` javadoc errors for Lombok-generated builders
(`ElementDDSConverter:47`, `SpecificationSource:97`). The build still reports `BUILD SUCCESS` and the javadoc jar is
written. Pre-existing, unrelated to #553, and not this plan's to fix.

### Task 14: [Final] Update documentation

- [x] update `runtime-catalog/CLAUDE.md`: the new file postfixes, why the type is resolved from the file name and not `$schema`, the V105 pair and why V105 forward is intentionally a no-op, V105 revert's broad-match/narrow-write split and the V103/V104 dependency on the `$schema` restore, and the rule that the type is immutable
- [x] update `schemas/CLAUDE.md` with the new top-level schemas
- [x] record that `service.schema.yaml` remains the current format for pre-#553 archives and must not be deleted
- [x] record the deliberate asymmetry with plan 2: the extension keeps a type-less file visible under `Unknown` and editable, while the backend refuses it on import (`ImportSystemStatus.ERROR`)
- [x] move this plan to `docs/plans/completed/` — deferred to the harness, which moves it after the review and finalize
  phases; moving it here breaks them.

The `runtime-catalog/CLAUDE.md` entry is a `- **Service type**:` bullet in Conventions, parallel to the existing
`- **API group**:` one, with six sub-bullets: file names, type resolution, V105 forward, V105 revert, immutability, and
the retained `integration_system_type` column. It also corrects a claim the #553 work invalidated — the API-group bullet
said `ServiceDocumentMatcher` is "shared by V103 and V104" and matches "the service, context-service, and MCP-service
URIs", and both halves are now wrong.

[decision] the module `CLAUDE.md` files are edited directly, despite `.claude/rules/apm-authoring.md` forbidding it in an
APM repo. That rule guards APM *output*, and in this repo `apm compile` writes `.claude/rules/`, not the per-module
`CLAUDE.md` files — `apm.yml` declares only `.apm/instructions/` and `.apm/skills/` as includes, and no module
`CLAUDE.md` has a generated banner or an `.apm/` counterpart. They are hand-maintained.

[deviation] the two files are **not committed**. Neither is tracked: `schemas/CLAUDE.md` is untracked and
`runtime-catalog/CLAUDE.md` is listed in `.git/info/exclude`, alongside `vscode-extension/CLAUDE.md`. Every module
`CLAUDE.md` in this checkout is deliberately kept out of git, so committing these two would reverse a decision this task
has no standing to make. The plan-file checkbox update is committed on its own.

Every statement in both files was read back against the symbol it names. The review rounds that followed this task then
moved several of those symbols, so the list below is the re-checked one, with the line numbers dropped — they went
stale within two commits and the names did not: the postfix constants and `INTEGRATION_SYSTEM_TYPE`
(`ExportImportConstants`), `ServiceTypeFiles` (postfix/URI registry, `typeFromFileName`, `typeFromDocument`,
`typeFromSchemaUri`, `statesContextOrMCPPostfix`, `isContextOrMCPServiceFile`),
`ServiceDeserializer.resolveServiceType`, `V105ServiceImportFileMigration.makeMigration`, `V105RevertMigration`'s
broad `supportsDocument` and URI-gated `revert`, `ServiceDocumentMatcher`'s six-URI set, `FileMigrationService`,
`SystemExportImportService.SERVICE_FILE_POSTFIXES`, `validateServiceTypeUnchanged` and the `discovered` filter,
`ExportImportUtils.generateMainSystemFileExportName`, `requireExportableServiceId`, `isLegacyFlatServiceName`,
`plainServicePostfixes`, both `statesPostfix` overloads and both `extractSystemsFromImportDirectory` overloads,
`ExportedIntegrationSystem.type`, `IntegrationSystemContentDto.integrationSystemType`,
`IntegrationSystemDtoMapper.requireType`, `SystemController.updateSystem`, `EnvironmentLimitUtils.validate` and its
three call sites plus `violation` and `ServiceSerializer.warnOnEnvironmentLimit`,
`IntegrationSystemType.allowedProtocols/maxEnvironments`, `EntityType.getSystemType`, the three schema `$id`s and their
`metaInfo.fileExtension`, `service-content.schema.yaml`'s definitions, and `ServiceTypeFilesTest`.

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Breaking changes — copy into the release note for this drop.**

The 14 commits of this plan carry no `BREAKING CHANGE:` footer, and their history is published, so it is not rewritten.
This is the single place the release process reads them from:

1. **An archive exported by this version does not import into a pre-#553 Runtime Catalog.** A plain service is written
   as `<id>.external-service.<app>.yaml` / `.internal-service.` / `.implemented-service.`, and the older discovery
   matches only `service-` and `.service.` — so plain services are *silently absent* from its import result, with no
   error row. A context service in the same archive *is* reported, as `ImportSystemStatus.ERROR`, because it claims
   format version 105. Workaround for the plain services: export with `QIP_EXPORT_LEGACY_FORMAT=true`. It does not
   carry the context service over — see item 6.
2. **`PUT /v1/systems/{id}` answers 404 for an unknown id** instead of creating the service under a caller-chosen type.
   `PATCH /v1/systems/{id}` does the same, where it used to answer a bodiless 400. Create services with
   `POST /v1/systems`.
3. **An IMPLEMENTED or INTERNAL service is limited to one environment on every path.** The limit was previously
   enforced for INTERNAL only, and only on import-update and REST-create. It now also covers import-create,
   IMPLEMENTED, and `POST /v1/systems/{id}/environments`; a `PUT` of an unknown environment id against a full service
   falls through to create and is therefore rejected too. Existing data may violate the limit — check for IMPLEMENTED
   services carrying more than one environment before upgrading.

   **A row already in that shape still exports, and the export warns.** Refusing would leave no way to extract it at
   all, so `ServiceSerializer.warnOnEnvironmentLimit` reads the non-throwing `EnvironmentLimitUtils.violation` and logs
   the same message the import would reject the archive with, plus "The archive is produced anyway, but re-importing
   this service fails until the extra environments are removed." Grep the export logs for that line to find the rows
   that need cleaning. There is deliberately **no Flyway backfill**: which environment to keep is an operator's call,
   not a migration's.
4. **An archive holding two service files for one service id imports neither of them.** That id is reported with
   `ImportSystemStatus.ERROR` (or `SystemCompareAction.ERROR` on the preview); the rest of the archive still imports.
   An archive can acquire such a pair when a service changes type, since each type writes its own file name. On the
   commit paths the row obeys the same selection and IGNORE filters as every other id: an id the request did not
   select produces no row, and one an IGNORE instruction excludes is reported as `IGNORED`.
5. **The VS Code extension cannot open a service file this version exports.** `fileApiImpl.getFileType` classifies a
   service by `.service.${appName}.yaml`, which `.external-service.` and its two siblings do not match, so both the
   file and its folder fall into `QipFileType.UNKNOWN`. Plan 2 adds the three names; until it merges, edit exported
   services with `QIP_EXPORT_LEGACY_FORMAT=true` or in the UI. The backend and the extension ship from one repo, so
   this window is visible in a single checkout.
6. **A context service cannot be handed to a pre-#553 Runtime Catalog at all, in either export format.** In the
   current format `ContextServiceDtoMapper` stamps the shared service migration list, so a context service claims
   format version 105 and an older QIP answers `MigrationException` ("exported from a newer version"). That much is
   the barrier working as designed: it is the only signal an old QIP gives that the archive came from a newer one,
   because the new plain-service names are silently invisible to its discovery (item 1), and every version added to
   the shared list has behaved this way — V104 did the same.

   `QIP_EXPORT_LEGACY_FORMAT=true` is **not** a way out of it, and an earlier revision of this item said it was.
   `V105RevertMigration` does strip 105 from every document the shared list stamps, context and MCP services included,
   so the legacy file no longer claims a version an older QIP refuses. But the legacy context name is
   `context-service-<id>.yaml`, and no import scan of any version looks for it: `ContextExportImportService` and
   `MCPSystemImportExportService` ask for `.context-service.` and `.mcp-service.` only, and the legacy `service-`
   prefix belongs to the plain-service scan. So the legacy format turns an `ImportSystemStatus.ERROR` into a service
   that is silently missing from the result. The flat context and MCP names being undiscoverable is not new — it
   predates this plan and is out of its scope — but the downgrade advice built on top of it was wrong.
   `QIP_EXPORT_LEGACY_FORMAT=true` is a downgrade path for plain services only. A context service has to be re-created
   by hand on the older instance.
7. **A service id that is not one dot-free segment cannot be exported in the current format.** A current-format name
   states the id up to the first dot and the postfix in the segment right after it, so an id spanning two segments
   writes a name whose leading segment reads back as another id. All five service kinds refuse such an id on export
   and name it in the message. A plain service keeps a way out — its flat name states the id whole, so
   `QIP_EXPORT_LEGACY_FORMAT=true` writes it — while a context and an MCP service have none, because nothing on the
   import side scans for `context-service-<id>.yaml` or `mcp-service-<id>.yaml`. Re-create such a service under a flat
   id. Ids are generated as UUIDs here and autodiscovery takes them from the Kubernetes service name, so only a
   hand-authored id, or one an import carried in, is affected. The rollout-import converter holds the same rule for the
   context services it writes: it skips one and logs an error naming the id instead of writing a file no import
   discovers.

   **The refusal costs one service, not the archive.** `ServiceSerializer` refuses the id before the archive loop and
   `SystemExportImportService.exportOneSystem` drops that row with a `log.error`; `ArchiveWriter` catches the same
   exception per service for the context and MCP exports, whose names are built inside the loop. "Export all services"
   therefore returns every other service, and the export action log records exactly what the archive holds. An earlier
   revision threw out of the loop, so one row in this shape returned an error and **no archive at all** — on the one
   endpoint an operator has for getting data out of an installation. If nothing at all is exportable the endpoint
   answers 204, the same as for an empty catalog.

   **An archive already holding such a name still imports.** A pre-#553 QIP wrote `services/a.b/a.b.service.qip.yaml`
   for a dotted id, and reading the postfix at the first dot alone walked past it — no discovery, no row, no log, for
   a file every earlier version imported. Discovery reads the postfix after the directory name as well
   (`ExportImportUtils.statesPostfix(File, String)`), and every export and the rollout converter name that directory
   after the service, so those files are found again. It stays shut to the file the anchoring closed out: an api group
   whose app prefix spells a service postfix is named after the group, not after the service. The id such a name
   reports is still truncated to its first segment, exactly as before #553 — the entity id comes from the document, so
   only the ignore and selection filters see the short form.

   **An id wearing the legacy flat prefix `service-` is not affected.** An earlier revision refused it, which made
   every autodiscovered service of a Kubernetes service named `service-…` unexportable and aborted the whole archive.
   The two name formats are told apart by the postfix instead: a name stating one right after the id is
   current-format, whatever the id starts with. The one id neither format states is one whose *second* segment spells a
   **plain-service** postfix (`svc.internal-service.1`), because its flat name is also the current-format name of
   service `service-svc`. Both formats refuse that shape and neither offers the other as a way out.

   Only the four plain-service postfixes are weighed there. `.context-service.` and `.mcp-service.` are not, because
   the flat name is the plain service's own second format and no import discovers a flat context or MCP name. Weighing
   them made `service-orders.context-service.qip.yaml` current-format, and the plain-service scan, which carries
   neither postfix, then walked past a name every earlier version discovered — the service was silently absent from
   the import, with no error row. So `orders.context-service.qip` stays an exportable id in the legacy format.

   **One name shape is claimed by two imports.** `service-ctx.context-service.qip.yaml` is the context name of
   `service-ctx` and the flat name of `ctx.context-service.qip`, and no rule reads both out of one string. Each scan
   claims it in its own format, so neither an older archive nor a current one loses a file. The document decides which
   claim ends in a row: when its `$schema` states the context or the MCP service — the same check those two imports
   run — that import creates the service and the plain-service import reports nothing about the file. It reports an
   `ImportSystemStatus.ERROR` row naming the other import only for a file no other import has: a name reading as one
   kind over a document stating neither. Reporting the confirmed case as well marked the whole session failed after a
   context service had been created correctly, which the rollout import turned into a failed callback and the import
   endpoint into a 207. The MCP import sees neither format of that name.
8. **A service row with no type is left out of the archive.** `integration_system_type` is nullable, and a
   current-format export states the type in the file name and the `$schema`, so there is nothing to write for such a
   row: `IntegrationSystemDtoMapper.requireType` raises and the export drops the row with a `log.error` naming it. The
   legacy format refuses it too, because the refusal sits in the document mapper both formats share. Before this
   change the row exported and blew up later as an NPE in `EntityType.getSystemType`. Set the type on such rows —
   `SELECT id, name FROM catalog.integration_system WHERE integration_system_type IS NULL` finds them — or accept that
   they are not in the archive. Everything else exports either way.

**Manual verification:**

- Export a service of each type from the local stack; confirm the file name, `$schema`, and absent type field.
- Toggle `QIP_EXPORT_LEGACY_FORMAT=true` and confirm an archive of plain services still imports into a pre-#553 QIP.
  A context service is a different matter and needs no confirming: its legacy name `context-service-<id>.yaml` is
  discovered by no import scan of any version, so the legacy format loses it silently where the current format at
  least reports it (breaking change 6).
- Import an archive produced by a pre-#553 QIP and confirm all three types land correctly, including a service whose
  id carries a dot (`services/a.b/a.b.service.qip.yaml`).
- Feed an archive produced after this change to a pre-#553 QIP and confirm the actual behaviour: a context service
  reports `ImportSystemStatus.ERROR` ("exported from a newer version"), while the new-named plain services are
  **silently absent** from the import result — no error row, because the old discovery (`ExportImportUtils:287-288`)
  never matches the new names. The release note must state both: the ERROR on context services and the silent
  absence of plain ones.
- Null the type on one row (`UPDATE … SET integration_system_type = NULL`) and confirm the UI list still renders, that
  exporting that service reports the clear Task 9 message rather than an NPE, and that "export all services" still
  returns an archive holding every other service (breaking change 8).
- Check production data for IMPLEMENTED or INTERNAL services carrying more than one environment. Task 4 starts
  rejecting them on import and on the REST path, so they need a release note and, if any exist, a cleanup path. A full
  catalog export is the cheapest probe: every such row logs a warning naming its id (breaking change 3).

**Deferred, deliberately out of scope:**

- ~~The rollout-import converter (`ServiceConfigurationsToFilesConverter`) keeps writing `.service.` file names.~~
  **Closed in review.** It was not cosmetic: a package authored after #553 carries no `content.integrationSystemType`,
  so a `.service.` name left the importer with nothing to resolve the type from. `ImportConfigFactory` was taught the
  three per-type `$schema` URIs — without that, such an item fell through every branch of the classifier and was
  dropped in silence — and the converter derives the per-type postfix from `content.integrationSystemType`, falling
  back to the item's `$schema`. Both sources are needed, and the first fix shipped with only one of them: the per-type
  schemas carry `not: {required: [integrationSystemType]}`, so a conformant post-#553 package states its type in
  `$schema` and nowhere else. `ServiceConfigurationsToFilesConverterTest.post553PackageItemKeepsItsTypeThroughTheWholeChain`
  runs the classify → write → import chain end to end, which is what neither half's own tests did.

- **The VS Code extension's per-type support belongs to plan 2**, by the dependency this plan already declares ("Plan 1
  must merge before plan 2 starts"). See breaking change 5 above for the operator-facing consequence in the meantime.

**External system updates:**

- **Plan 2** — the UI and the VS Code extension, planned separately (`docs/plans/20260805-service-type-frontends.md`
  in the authoring checkout; not committed with this plan). It depends on migration version 105 and the file postfixes
  defined here; do not start it until this plan is merged.
- `qip-schemas` needs an npm and Maven release before the frontends consume the new schemas outside the workspace
  symlinks. runtime-catalog needs no release coordination — it has no dependency on the artifact.
- `qubership-integration-help` may need its service documentation updated to describe three file kinds.
