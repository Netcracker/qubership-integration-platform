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

- The frontends (plan 2, `20260805-service-type-frontends.md`) depend on the migration version this plan introduces
  (105) and on the file postfixes it defines. **Plan 1 must merge before plan 2 starts.**

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

- [ ] add `allowedProtocols()` and `maxEnvironments()` to the enum, each an exhaustive `switch` with no `default`
- [ ] `maxEnvironments()` returns `int`, with `Integer.MAX_VALUE` for EXTERNAL — pick one representation, Task 4 compares against it in three places
- [ ] do **not** add `usesActiveEnvironment()`: `SystemMapper:66-72` already ignores the stored value for INTERNAL and IMPLEMENTED, so the predicate would encode a distinction the REST layer does not make
- [ ] replace `SystemBaseService.ALLOWED_PROTOCOL_MAP` with `type.allowedProtocols()`
- [ ] rewrite `EntityType.getSystemType` as an exhaustive switch (drop the `default` branch that silently maps an unknown type to `EXTERNAL_SERVICE`)
- [ ] write tests asserting each type's protocol set and environment limit
- [ ] write a test asserting every enum constant is covered, so adding a type without updating the rules fails
- [ ] run `mvn -pl runtime-catalog test` — must pass before task 4

### Task 4: Close the environment-limit holes

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java`
- Modify: `runtime-catalog/.../rest/v1/controller/EnvironmentController.java`
- Modify: `runtime-catalog/src/test/java/.../exportimport/SystemExportImportServiceTest.java` (create if absent)
- Modify: `runtime-catalog/src/test/java/.../rest/v1/controller/EnvironmentControllerTest.java` (create if absent)

- [ ] hoist the environment count check out of the `if (INTERNAL) … else if (EXTERNAL)` chain at `:534-560` so IMPLEMENTED is covered, driving it from `type.maxEnvironments()`
- [ ] apply the same check on the create path (`prepareIntegrationSystemForCreate:850`), which has none today
- [ ] widen the `EnvironmentController:94-98` guard from INTERNAL to `type.maxEnvironments()`
- [ ] guard against a null type before dereferencing it — the column is nullable and legacy rows may carry one; today's `IntegrationSystemType.INTERNAL.equals(...)` is null-safe and `getType().maxEnvironments()` is not
- [ ] keep one shared message so all three paths report the violation identically
- [ ] write tests: a second environment is rejected for INTERNAL and IMPLEMENTED, accepted for EXTERNAL, on import-create, import-update, and the REST path
- [ ] write a test for `EnvironmentController.updateEnvironment:116-118`, which falls through to create on an unknown id — a stale id against a full service now throws where it previously created
- [ ] write a test: a service row with a null type does not crash the guard
- [ ] run tests — must pass before task 5

### Task 5: Add the file-postfix ↔ type registry

**Files:**
- Modify: `runtime-catalog/.../configuration/ApplicationJsonSchemaProperties.java`
- Modify: `runtime-catalog/src/main/resources/application.yml`
- Modify: `runtime-catalog/.../service/exportimport/ExportImportConstants.java`
- Modify: `runtime-catalog/.../service/exportimport/migrations/revert/ServiceDocumentMatcher.java`
- Create: `runtime-catalog/.../service/exportimport/ServiceTypeFiles.java`
- Create: `runtime-catalog/src/test/java/.../exportimport/ServiceTypeFilesTest.java`

- [ ] add `EXTERNAL_SERVICE_YAML_NAME_POSTFIX`, `INTERNAL_…`, `IMPLEMENTED_…` next to the existing constants
- [ ] add a component mapping file name → `IntegrationSystemType` and type → postfix; import, export, and both migrations use it, so it lives in one place
- [ ] add `externalService`, `internalService`, `implementedService` URI properties with `*_JSON_SCHEMA_URI` overrides — defaults **in the class fields**, not only `application.yml`: `TestRevertMigrations.matcher()` builds `ServiceDocumentMatcher` from `new ApplicationJsonSchemaProperties()`, so a yml-only default leaves the test matcher unwidened
- [ ] add a URI → type mapping used **only** by the revert migration, which works on documents that have no file name
- [ ] document at the mapping that `$schema` is not a reliable type source on the import path — the VS Code extension writes a project-configured value (`.config.qip.yaml.example:15-22`)
- [ ] widen `ServiceDocumentMatcher`'s URI set with the three new URIs — every revert migration is gated on it, and a post-Task-9 plain service otherwise matches nothing, silencing V105/V104/V103 at once (see Solution Overview)
- [ ] write tests: each postfix resolves to its type, `.service.` and the legacy `service-` prefix resolve to none, `.context-service.` is never mistaken for a plain service
- [ ] write a test: the matcher accepts a document carrying each of the three new URIs and still rejects a chain document
- [ ] write a test asserting the configured URIs match the `$id`s of the schemas added in Task 2
- [ ] run tests — must pass before task 6

### Task 6: Resolve the type from the file name on import

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/deserializer/ServiceDeserializer.java`
- Modify: `runtime-catalog/src/test/java/.../exportimport/deserializer/ServiceDeserializerTest.java`

- [ ] after `toInternalEntity` at `:104`, set the type from the file name when the entity has none
- [ ] resolution order: file-name postfix, then `content.integrationSystemType`, then fail — the legacy flat name carries no type, so the field remains a required fallback
- [ ] do **not** consult `$schema`; do **not** move this into `IntegrationSystemDtoMapper`, which never sees a file name
- [ ] fail loudly rather than persisting a null: the column is nullable and a null surfaces much later as an NPE in `EntityType.getSystemType:57`
- [ ] write tests: each of the three postfixes yields its type with no field present
- [ ] write a test: a legacy `service-<id>.yaml` resolves from the field
- [ ] write a test: a file whose name and field disagree is reported, not silently resolved
- [ ] write a test: neither source present is rejected with a clear message
- [ ] run tests — must pass before task 7

### Task 7: Add V105 as a compatibility barrier

**Files:**
- Create: `runtime-catalog/.../service/exportimport/migrations/system/V105ServiceImportFileMigration.java`
- Modify: `runtime-catalog/src/test/java/.../migrations/system/TestServiceMigrations.java`
- Create: `runtime-catalog/src/test/java/.../migrations/system/V105ServiceImportFileMigrationTest.java`

- [ ] implement `makeMigration` as a documented no-op: resolution lives in Task 6 and runs for every document, so this class exists only so exports stamp 105 and an older QIP refuses to mis-import
- [ ] state in the comment how far the barrier reaches: an old QIP never discovers the new plain-service names (`ExportImportUtils:287-288`), so it fires only on documents the old QIP still finds — context services and legacy-named files — as a per-service `ImportSystemStatus.ERROR` (`SystemExportImportService:449-457`), never as a rejected archive
- [ ] return `isIdempotent() == true` and register the class as a `@Component`
- [ ] add V105 to `TestServiceMigrations`, or four existing test classes keep running against a stale set
- [ ] write a test asserting the document is returned unchanged, including for a context and an MCP document
- [ ] write a test asserting a document claiming 105 is rejected by a `FileMigrationService` whose registry lacks it
- [ ] run tests — must pass before task 8

### Task 8: Add V105 revert migration

**Files:**
- Create: `runtime-catalog/.../service/exportimport/migrations/revert/V105RevertMigration.java`
- Modify: `runtime-catalog/src/test/java/.../migrations/revert/TestRevertMigrations.java`
- Create: `runtime-catalog/src/test/java/.../migrations/revert/V105RevertMigrationTest.java`

- [ ] use the **broad** `ServiceDocumentMatcher.matches` (widened with the new URIs in Task 5) for `supportsDocument`, so the 105 strip reaches context services — `ContextServiceDtoMapper:65` stamps them from the same migration list, and a kept claim makes their legacy export unimportable
- [ ] register V105Revert in `TestRevertMigrations.all()` — the parallel registry to `TestServiceMigrations`, consumed by `V103RevertMigrationTest`, `V104RevertMigrationTest`, and `ServiceSerializerTest`; without it the legacy-export tests run a chain missing V105
- [ ] gate the `content.integrationSystemType` write and the `$schema` restore **inside** `revert()` on the three new URIs, so a context service is not stamped with a service type
- [ ] strip `105` from `content.migrations` unconditionally
- [ ] write tests for all three types: field written, plain service `$schema` restored, version stripped
- [ ] write a test: a context-service document keeps its shape but loses the 105 claim
- [ ] write a test asserting `ServiceDocumentMatcher` matches the document again after this revert runs, so V104 and V103 still apply
- [ ] write a full revert-chain test over a golden exported document **containing api groups**, asserting the `apiGroups` → `specificationGroups` rename still happens
- [ ] run tests — must pass before task 9

### Task 9: Write the new file names on export

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/mapper/services/IntegrationSystemDtoMapper.java`
- Modify: `runtime-catalog/.../model/exportimport/system/IntegrationSystemContentDto.java`
- Modify: `runtime-catalog/.../service/exportimport/serializer/ExportableObjectWriterVisitor.java`
- Modify: `runtime-catalog/.../model/system/exportimport/ExportedIntegrationSystem.java`
- Modify: `runtime-catalog/.../util/ExportImportUtils.java`
- Create: `runtime-catalog/src/test/java/.../exportimport/ServiceExportFormatTest.java`

- [ ] capture a golden legacy-format export **before** changing anything, so the no-regression claim is measurable
- [ ] make `toExternalEntity:79` stamp the per-type `$schema` through the Task 5 registry instead of its `@Value` field
- [ ] suppress `integrationSystemType` with `@JsonProperty(access = WRITE_ONLY)` on the DTO field — `@Jacksonized` copies the annotation onto the builder setter, so deserialization keeps binding it. Not `@JsonIgnore`, which kills deserialization of every pre-#553 archive; not the shared `baseEntityFilter` (`MapperAutoConfiguration:125`), which is audit-field stripping shared by five DTOs
- [ ] keep the field in the legacy format, where Task 8's revert restores it
- [ ] carry the type to `ExportableObjectWriterVisitor:51` (add a field to `ExportedIntegrationSystem` or read it off the node) and pick the file name from it
- [ ] note that until Task 10 lands, a fresh export cannot be re-imported — the intermediate state is knowingly broken and the module test suite will not detect it
- [ ] write tests: each type exports to the expected file name with the expected `$schema` and no type field
- [ ] write a test: an old archive still deserializes with its type field intact
- [ ] re-run Task 8's revert-chain test over a golden document carrying the **new** `$schema` — Task 8 predates this task, so its own tests stay green on old-URI documents even if the matcher was never widened
- [ ] write a test: with `qip.export.legacy-format=true` the output is **semantically** equal to the golden file — `ObjectNode` is insertion-ordered and the revert appends restored keys last, so byte equality is unattainable
- [ ] fail exporting a null-type service with a clear message naming the service id — the file name now requires a type, and today such a row only NPEs later at `logSystemExportImport` (`:906` → `EntityType.getSystemType:57`)
- [ ] write a test: exporting a null-type service yields the message, not an NPE
- [ ] run tests — must pass before task 10

### Task 10: Read the new file names on import

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java`
- Modify: `runtime-catalog/.../util/ExportImportUtils.java`
- Modify: `runtime-catalog/src/test/java/.../exportimport/deserializer/ServiceDeserializerTest.java`

- [ ] add a multi-postfix overload of `extractSystemsFromImportDirectory` — one directory walk, one legacy-prefix check, a deduplicated result — and call it from the four `SystemExportImportService` sites (`:224,251,322,376`); calling the single-postfix version four times returns every legacy-prefix file four times (`:287` ORs the prefix in unconditionally) and imports it once per copy
- [ ] leave the existing single-postfix version to the context and MCP import services, which share it
- [ ] reject duplicate ids at the discovered-list level in `SystemExportImportService`, grouping by `extractSystemIdFromFileName` **before** the per-file transaction loop — the two files land as separate `deserializeSystem` calls in separate transactions and never see each other
- [ ] update `ServiceDeserializerTest:1098`, which hardcodes `SYSTEM_ID + ".service." + APP_NAME + ".yaml"`
- [ ] write tests: an archive of each new format imports with the right type, on both the commit path and the preview path (`:224`, the import-preview request)
- [ ] write tests: a legacy archive and a current-format pre-#553 archive both still import
- [ ] write a test: an archive containing two service files for one id is rejected rather than resolved arbitrarily
- [ ] run tests — must pass before task 11

### Task 11: Reject service-type changes

**Files:**
- Modify: `runtime-catalog/.../rest/v1/controller/SystemController.java`
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java`
- Modify: `runtime-catalog/src/test/java/.../rest/v1/controller/SystemControllerTest.java` (create if absent)

- [ ] reject an import that would change an existing service's type, naming both values
- [ ] close the `updateSystem` → `createSystem` fall-through at `SystemController:125`: an unknown id on PUT should 404, not create a service with a caller-chosen type
- [ ] check no client depends on that fall-through before removing it (UI, extension, tests)
- [ ] leave `mergeWithoutLabels` alone — it already does not map the type; add a test that pins this rather than changing the mapper
- [ ] write a test: importing an `internal-service` file over an existing EXTERNAL service is rejected and the stored entity is unchanged
- [ ] write a test: PUT with a different type on an existing service does not change it
- [ ] write a test: PUT on an unknown id no longer creates a service
- [ ] run tests — must pass before task 12

### Task 12: Round-trip verification

**Files:**
- Create: `runtime-catalog/src/test/java/.../exportimport/ServiceTypeRoundTripTest.java`

- [ ] write a round-trip test per type: create → export → import → assert the persisted type is **non-null and equal**
- [ ] write a round-trip test per type in the legacy format, with the same non-null assertion
- [ ] write a legacy round-trip test for a **context service exported alongside a plain service**, asserting it imports into a pre-#553-shaped QIP — this is the regression Task 8's predicate exists to prevent
- [ ] write a cross-format test: export legacy, import into current-format QIP
- [ ] write a test covering the create path specifically, not only update — they are separate code paths (`:477` vs `:474`)
- [ ] write a current-format test importing one archive containing all five kinds (three plain types + context + MCP) through both the preview and commit paths — it also exercises Task 10's multi-postfix dedup against real neighbours
- [ ] run the full runtime-catalog suite — must pass before task 13

### Task 13: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented
- [ ] verify the schemas reject each constraint they claim to enforce (negative samples pass)
- [ ] verify the schema constraints and the backend checks agree — nothing enforces this automatically (see Context)
- [ ] run `mvn -pl schemas -pl runtime-catalog clean install -Dgpg.skip=true`
- [ ] run `npm -w @netcracker/qip-schemas test`
- [ ] verify Checkstyle reports zero violations and coverage did not drop below the project standard

### Task 14: [Final] Update documentation

- [ ] update `runtime-catalog/CLAUDE.md`: the new file postfixes, why the type is resolved from the file name and not `$schema`, the V105 pair and why V105 forward is intentionally a no-op, V105 revert's broad-match/narrow-write split and the V103/V104 dependency on the `$schema` restore, and the rule that the type is immutable
- [ ] update `schemas/CLAUDE.md` with the new top-level schemas
- [ ] record that `service.schema.yaml` remains the current format for pre-#553 archives and must not be deleted
- [ ] record the deliberate asymmetry with plan 2: the extension keeps a type-less file visible under `Unknown` and editable, while the backend refuses it on import (`ImportSystemStatus.ERROR`)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification:**

- Export a service of each type from the local stack; confirm the file name, `$schema`, and absent type field.
- Toggle `QIP_EXPORT_LEGACY_FORMAT=true` and confirm the output still imports into a pre-#553 QIP — including an
  archive that contains a context service.
- Import an archive produced by a pre-#553 QIP and confirm all three types land correctly.
- Feed an archive produced after this change to a pre-#553 QIP and confirm the actual behaviour: a context service
  reports `ImportSystemStatus.ERROR` ("exported from a newer version"), while the new-named plain services are
  **silently absent** from the import result — no error row, because the old discovery (`ExportImportUtils:287-288`)
  never matches the new names. The release note must state both: the ERROR on context services and the silent
  absence of plain ones.
- Null the type on one row (`UPDATE … SET integration_system_type = NULL`) and confirm the UI list still renders and
  exporting that service fails with the clear Task 9 message rather than an NPE.
- Check production data for IMPLEMENTED services carrying more than one environment. Task 4 starts rejecting them on
  import and on the REST path, so they need a release note and, if any exist, a cleanup path.

**Deferred, deliberately out of scope:**

- The rollout-import converter (`ServiceConfigurationsToFilesConverter`) keeps writing `.service.` file names. Its
  input comes from stored entity content, which still carries the type, so Task 6's second resolution source handles
  it. Renaming those files would be cosmetic.

**External system updates:**

- **Plan 2** (`20260805-service-type-frontends.md`) — the UI and the VS Code extension. It depends on migration
  version 105 and the file postfixes defined here; do not start it until this plan is merged.
- `qip-schemas` needs an npm and Maven release before the frontends consume the new schemas outside the workspace
  symlinks. runtime-catalog needs no release coordination — it has no dependency on the artifact.
- `qubership-integration-help` may need its service documentation updated to describe three file kinds.
