# Merging main into migrate-services-to-new-schema

## Overview

`main` extracted roughly half of runtime-catalog into an in-repo library, `integration-build-pipeline`
(`qip-integration-build-pipeline`). `migrate-services-to-new-schema` reworked the same area from a different angle:
it renamed the `SpecificationGroup` family to `ApiGroup`, gave each service type its own file name and `$schema`, and
made operation schemas on-demand. The two lines of work overlap almost exactly, so the merge is the point where both
designs have to be reconciled rather than concatenated.

This plan merges `main` into the branch in six waves, each gated by its own tests, then verifies the result against the
running Docker stack through the REST API, the browser, and the VS Code extension.

## Verified facts

Every number below came from a real command, not an estimate. The conflict counts come from a trial merge run in a
throwaway worktree, which was then discarded.

| Fact | Value |
|---|---|
| Merge base | `2cd1fe7e5439bf0193265c18d85a62bfb62cf286` |
| `main` ahead / branch ahead | 275 / 56 commits |
| `main` diff shape | 561 renames, 272 adds, 349 modifications, 32 deletions |
| Library size | 710 files, 557 of them moved out of runtime-catalog |
| Paths both sides touched | 105 |
| **Trial merge: conflicted files / hunks** | **62 / 121** |
| Conflict statuses | 39 UU, 11 AU, 5 UD, 2 UA, 2 AA, 2 DD, 1 DU |
| Flyway version collision | none — `main` ends at `V111_000`, the branch adds `V112_000` + `V112_001` |
| Import-file-migration collision | none — the branch adds `V103`–`V105` service and revert migrations, `main` adds none in that range |
| `schemas` overlap | 0 files (branch 122, main 3) |
| `vscode-extension` overlap | 1 file (branch 134, main 2) |
| `ui` overlap | 16 files (7 conflicts) |
| `help` overlap | 12 files (5 conflicts) |
| `infrastructure` | untouched by both sides |

### Git follows the move on its own

The trial merge landed the branch's edits to the 26 relocated files at their new library paths, and
directory-rename detection also relocated 11 files the branch *added* (the `V103`–`V105` service and revert
migrations, `ServiceDocumentMatcher`, two model tests) into the library, flagging each as a conflict for
confirmation. No manual file shuffling is required for these.

### The parsers were rewritten, not moved

```java
// branch — the parser knows JPA entities
SystemModel enrichSpecificationGroup(ApiGroup, sources, oldIds, isDiscovered, withSchemas, handler)

// main — the library knows neither JPA nor the catalog
ParsedSystemModel parseSpecification(String groupId, sources, handler)
```

Everything else the old interface carried (id generation, versioning, duplicate detection, environments) now lives in
`OperationParserService`, which the branch also rewrote. The branch's parser diff therefore splits three ways:
`SpecificationGroup` → `ApiGroup` renames **evaporate** (the library never sees entities); the `withSchemas` flag
**moves up** into the adapter; only the genuine parsing fixes need porting.

### The library owns archive layout now

`io/readers/system/{IntegrationSystemReader, ContextServiceReader, McpServiceReader, SystemImportModelMapper}` read the
whole service archive, including file-name prefixes and postfixes, legacy layouts, and the file migrations. That is
precisely the area the branch redesigned. `ExportImportConstants` moved to the library, and the branch's five added
constants (`.external-service.`, `.internal-service.`, `.implemented-service.`, `.api-group.`, `.api.`) are a pure
superset of the library's copy, so the constants file merges additively — but its *consumers* are now library code.

### The library has extension points, so almost nothing has to move

Git's directory-rename detection offers to relocate seven of the branch's new migration files into the library. Taking
that offer would drag half of runtime-catalog behind them. Their imports are:

| Imported from runtime-catalog | Used by |
|---|---|
| `ApiOperationDtoMapper`, `ApiOperationDto` | `V103` service and revert migrations |
| `Operation` (a JPA entity) | `V103` migrations |
| `ApplicationJsonSchemaProperties`, `ServiceTypeFiles` | `V105` migrations |
| `TypedOperationBackfill`, `ProtocolExtractionService` | `V103`/`V104` migrations |
| `TypedOperation`, `WsdlOperation`, `OperationProtocol` | typed-operation rewriting |

A library that imports a JPA entity is the wrong answer. The right one is already in `main`: both migration mechanisms
are Spring collection injection points over a library-declared interface.

```java
// library
public IntegrationSystemReader(..., Collection<ServiceImportFileMigration> serviceImportFileMigrations)
public FileMigrationService(..., List<RevertMigration> revertMigrations)   // sorted by version, descending
```

So the branch's `V103`–`V105` service and revert migrations stay in runtime-catalog as `@Component`s implementing the
library's interfaces, and Spring hands them to the library at runtime. `ServiceTypeFiles`, `ApiOperationDto`,
`ApplicationJsonSchemaProperties`, and the typed-operation model all stay where they are. **There is no cycle, and
nothing has to be moved** — git's offer is simply declined.

What genuinely belongs to the library is narrower: sibling-file discovery. `ServiceDeserializer` still locates the
service document itself (`deserializeSystem(File serviceFile)`), so the branch's `$schema` and per-type naming logic
stays catalog-side; but the reader scans the archive directory for group and model files by postfix
(`SPECIFICATION_GROUP_FILE_POSTFIX`, `SPECIFICATION_FILE_POSTFIX`, with a deprecated-prefix fallback), and that has to
learn the branch's `.api-group.` and `.api.` as the current generation while keeping the old ones readable.

## Risk register — what merges cleanly and still breaks

These are the items a conflict list does not show. They merge without a marker and fail at compile or run time.

**R1 — 17 files reference classes the branch renamed.** `main` added or modified them, the branch never touched their
paths, so they arrive silently:

- runtime-catalog main: `SystemModelCodegenAdapter`, `EnvironmentBaseService`, `ElementBaseService`
- runtime-catalog tests: `SystemModelCodegenAdapterTest`, `SpecificationParserConfigurationContextTest`,
  `EnvironmentDefaultPropertiesTest`, `ServiceDeserializerArchiveLayoutTest`, `ServiceImportContextWiringTest`,
  `SwaggerSpecificationParserTest`, `OperationParserServiceSystemModelIdentityTest`
- library: `IntegrationSystemReader`, `SystemImportModelMapper`, `SystemImportModelMapperTest` (these reference the
  library's own `SpecificationGroupDto`/`ContentDto`, which decision 1 renames)
- library, naming only: `chain/model/ImportSpecificationGroup`, `chain/impl/ImportSpecificationGroupImpl`,
  `chain/model/ImportSystem`, `chain/impl/ImportSystemImpl` — library-internal names, no compile break, rename only
  if we choose consistency

Identifier frequency across those files: `SpecificationGroup` 27, `SpecificationGroupDto` 13,
`SpecificationGroupContentDto` 7, `SpecificationGroupRepository` 5, `SpecificationGroupDtoMapper` 5.

**R2 — the entity field and DB column were both renamed.** `SystemModel.specificationGroup` →
`apiGroup`, `@JoinColumn(name = "specification_group_id")` → `api_group_id`. Any `main` code calling
`getSpecificationGroup()` breaks, and it breaks at the accessor, which no class-name grep finds.

**R3 — the committed OpenAPI contract goes stale, but only in its schema names.** `main` added
`runtime-catalog/api-spec/openapi.yaml` (the branch has no `api-spec/` at all) plus `ensure-openapi-spec: true` in the
CI wrapper; that job runs `OpenApiSpecGeneratorTest` and fails if the regenerated file differs from the committed one.
The endpoint paths are safe: the branch deliberately kept `@RequestMapping("/v1/specificationGroups")` on the renamed
`ApiGroupController` and on `ApiGroupImportController`, and `ApiGroupWireCompatibilityTest` already guards both
spellings of every JSON key. What changes is `components.schemas`, which springdoc names after DTO classes —
`SpecificationGroupDTO`, `SpecificationGroupRequestDTO`, `SpecificationGroupCreationRequestDTO`,
`SpecificationGroupLabelDTO`, `ChainsBySpecificationGroup`. Regenerate and commit; no client breaks.

**R4 — six parser tests the branch added stay behind.** `GraphqlSpecificationParserTest`,
`ParserCoreExceptionChainingTest`, `ProtobufSpecificationParserCoreTest`, `ProtobufSpecificationParserImportTest`,
`SwaggerSpecificationParserCoreTest`, `WSDLSpecificationParserTest` are new files for git, so directory-rename
detection leaves them in runtime-catalog while the classes they exercise are in the library. They will not compile.

**R5 — `WSDLSpecificationParser` was renamed to `WsdlSpecificationParser`.** Class name plus package changed, so this
one is a delete/add pair rather than a rename on both sides.

**R6 — two DTOs collide rename-against-move.** `SpecificationGroupDto` and `SpecificationGroupContentDto` were
renamed by the branch and moved by `main`, and are the only files in that state.

**R7 — two chain files merge automatically and still need reading.** `ChainExternalEntityMapper` and
`ChainConfigurationsToFilesConverter` are touched by both sides yet produce no conflict marker. `main` rewired chain
building onto the library's `ImportChain` model (`ab29ffa31`) while the branch changed rollout routing in the same
files. A clean auto-merge means git found no textual overlap, not that the result is coherent.

**R9 — the branch is on the pre-unification version scheme.** Root `<revision>` is `0.5.1` on the branch and `1.2.1`
on `main`; `parent/pom.xml` and the root pom are `main`-only, so its scheme wins, which is correct. The catch is
`runtime-catalog/pom.xml`: both sides edited it and it still auto-merged, meaning the edits sat in different regions.
Its parent pin, its `qip-integration-build-pipeline.version`, and the branch's `<testResource>` additions all have to
be checked by hand afterwards — `scripts/check-version-invariants.sh` is the oracle.

**R8 — the library needs `qip.export.legacy-format` in its own Spring context.** `FileMigrationService` moved to the
library carrying `@Value("${qip.export.legacy-format}")`. Any library test that boots it now has to supply the property
that used to come from runtime-catalog's `application.yml`.

## Decisions taken

**D1 — the `Specification` → `API` vocabulary is pushed into the library.** The library reads service files whose
format is already `api.schema.yaml`; leaving `SpecificationGroupDto` in a shared artifact would freeze a vocabulary the
product just removed. The library has had one release and exactly one consumer, so the cost is at its minimum now and
only grows.

**D2 — `withSchemas` and on-demand extraction live in the adapter**, not in the library contract. The library parses
and returns; declining to materialize schemas is consumer policy. This keeps the real benefit (schemas never reach the
database, extraction happens on request) and gives up only the parse-time saving. If that saving turns out to matter, a
`ParseOptions` argument can be added to the library later, on its own.

## Not a blocker

CI builds each module with `-pl <module> -am`, so the library compiles from source in the reactor. **No library
release is needed before the PR.** `scripts/check-version-invariants.sh` only requires that
`runtime-catalog`'s `qip-integration-build-pipeline.version` equals the library's `<revision>`; leave both at `1.2.1`
and it stays satisfied.

## Development approach

- Merge, never rebase. Replaying 56 commits across 561 renames repeats every conflict 56 times.
- Work on a dedicated branch, `merge/main-into-migrate-services`, so the original stays intact as a reference.
- One wave, one green gate. Waves are **not** separate commits: git refuses to commit while any path is unmerged, so
  the merge stays open until the last wave and lands as a single merge commit (Task 8). Each wave instead ends with the
  index staged, a checkpoint patch written to the scratchpad, and its suites green. `rerere` replays every resolution if
  the merge has to be restarted. Split the history into per-wave commits afterwards only if the reviewer asks.
- Enable `rerere` before starting so a re-run of the merge replays resolutions already made.
- Resolve semantics, not text: for the parsers, take `main`'s file wholesale and re-apply the branch's behavior on top,
  rather than reconciling 121 hunks of two competing refactors.
- Record every deliberate behavior change in the commit body — the branch already carries decisions that a naive
  resolution would silently undo.

## Implementation steps

### Task 1: Prepare the merge branch and safety net

**Files:**
- Create: `docs/plans/20260824-merge-main-into-migrate-services.md` (this file)

- [x] `git config rerere.enabled true` and `git config rerere.autoupdate true`
- [x] tag the pre-merge tip: `git tag premerge/migrate-services-20260824`
- [x] create `merge/main-into-migrate-services` off `migrate-services-to-new-schema`
- [x] capture the baseline, measured 2026-08-24 on `9130a4761`: runtime-catalog **1544/0** (`mvn -pl runtime-catalog
      -am clean test`), schemas **130/130**, ui **2911/2911** in 228 suites, vscode-extension **1104 passed + 2 skipped**
      in 73 suites. Use `test`, not `install`: the javadoc plugin fails locally on Lombok-generated builders
      (`AbstractSystemEntityBuilder`, `TemplateChainElementBuilder`), which is unrelated to this merge
- [x] start the merge with `git -c merge.renameLimit=20000 merge --no-commit --no-ff main` and keep it open across waves
- [x] confirm the conflict inventory matches this plan — reproduced exactly: 62 files, 121 hunks, 39 UU / 11 AU / 5 UD / 2 UA / 2 AA / 2 DD / 1 DU

### Task 2: Wave 0 — areas untouched by the library extraction

**Files:**
- Modify: `ui/src/components/admin_tools/ActionsLog.tsx`, `ui/src/components/services/detail/ServiceParametersTab.tsx`,
  `ui/src/components/services/ServicesTreeTable.tsx`, `ui/src/styles/antd-overrides.css`,
  `ui/src/styles/theme-variables.css`, `ui/src/theme/antdTokens.ts`,
  `ui/tests/components/services/detail/ServiceParametersTab.test.tsx`
- Modify: `help/docs/02__Services/{1__External,2__Inner_Cloud,3__Implemented}/*.md`,
  `help/docs/03__Admin_Tools/{3__Audit,4__Import_Instructions}/*.md`
- Modify: `.github/workflows/runtime-catalog-build.yaml`, `package-lock.json`

- [x] resolve the 7 UI conflicts, keeping the branch's column reconciliation and the `main` theme token changes
- [x] resolve the 5 help-doc conflicts, keeping the branch's rewritten export warnings and `$schema` description
- [x] `runtime-catalog-build.yaml` auto-merged into exactly the wanted union — both inputs and all four path filters
- [x] regenerate `package-lock.json` with `npm install` rather than resolving it by hand
- [x] run `npm -w @netcracker/qip-ui run build`, `check-types`, `lint`, and the UI test suite
- [x] wave 0 gate green: schemas 130/130, ui **3014/3014** in 232 suites, vscode-extension 1104 passed + 2 skipped,
      eslint 0 errors (74 pre-existing warnings), `tsc --noEmit` clean, `npm install` left the merged lockfile untouched
- [x] **R7 confirmed in practice**: `ServiceParametersTab.tsx` auto-merged with no marker and ended up rendering the
      `Type` descriptions row twice — `main`'s unconditional row plus the branch's `isVsCode` one. Two tests failed on
      "Found multiple elements". The branch's duplicate was dropped; `main`'s row covers both frontends
- [x] deliberate reversal to record in the merge body: the branch had **deleted** the antd Select CSS overrides,
      trusting the component tokens. `main` modernized them to the v6 `.ant-select-outlined` selector instead, and
      `main` is right — the Select token reads `--vscode-input-border`, which VS Code often leaves unregistered, while
      the neighbouring `.ant-input` rule already follows `--vscode-editorGroup-border`. `main`'s rules were kept
- [x] deliberate reversal the other way: `main`'s test "VS Code save sends original system type in payload" was
      dropped. The branch types the payload as `SystemUpdateRequest = Omit<Partial<IntegrationSystem>, "type">`, the
      extension ignores `type` in the request explicitly, and its own suite guards the behaviour more strongly with
      "keeps the type the stored file states, whatever the request says"
- [x] `formatEntityType` extracted to `ui/src/misc/entityTypeLabels.ts` and wired into `main`'s new
      `useActionLogFilter`, which otherwise re-introduced the "Api group" label the branch had fixed

### Task 3: Wave 1 — decline the relocation, rewire to the library interfaces

**Files:**
- Keep in runtime-catalog: `.../service/exportimport/migrations/system/V10{3,4,5}ServiceImportFileMigration.java`
- Keep in runtime-catalog: `.../service/exportimport/migrations/revert/{ServiceDocumentMatcher,V103RevertMigration,V104RevertMigration,V105RevertMigration}.java`
- Keep in runtime-catalog: `ServiceTypeFiles.java`, `ApiOperationDto.java`, `ApplicationJsonSchemaProperties.java`
- Keep in runtime-catalog: the two model tests git offered to move (`IntegrationSystemTypeTest`, `TypedOperationTest`)
- Modify: the same files' `import` lines only

- [x] resolve each of the 11 AU conflicts by keeping the branch's file at its **runtime-catalog** path and removing the
      library-side copy git proposed
- [x] re-point their imports at the library. Two classes of breakage, not one: the `import` lines a script can rewrite,
      and the **same-package references that carried no import at all** — `ServiceImportFileMigration` and
      `RevertMigration` used to sit in the very package these files live in, so moving the interfaces left six classes
      referencing a type with nothing to resolve it. Static imports of `ExportImportConstants` and `MigrationUtil`
      needed the same treatment
- [x] confirm each migration is a `@Component` so the library's `Collection<ServiceImportFileMigration>` and
      `List<RevertMigration>` injection picks it up
- [ ] add a context test asserting the library's `IntegrationSystemReader` receives `V100`–`V105` and that
      `FileMigrationService` receives the revert migrations sorted by descending version — the sort is what makes
      `V105` run before `V104`, and nothing else guards it
- [x] verify the boundary holds: the 80 remaining `runtime.catalog` references in the library all sit **inside**
      still-unresolved conflict markers in 12 files, every one of them scheduled for waves 2–3. No resolved library
      file references the catalog
- [x] every `org.qubership.*` import in the nine moved-back files resolves against a real class (static check)
- [ ] ⚠️ **the Maven gate cannot run per wave.** Waves 1–5 all touch runtime-catalog, and nothing in either module
      compiles until the last conflict is resolved. The per-wave Java gate is therefore structural — no markers left,
      every import resolvable — and the compile-and-test gate moves to Task 8
- [ ] add the injection test (library reader receives `V100`–`V105`; `FileMigrationService` receives the revert
      migrations sorted by descending version, which is what makes `V105` run before `V104`) — deferred until the
      tree compiles

### Task 4: Wave 2 — apply the Specification → API rename inside the library (D1)

**Files:**
- Rename: `integration-build-pipeline/.../io/model/exportimport/system/SpecificationGroupDto.java` → `ApiGroupDto.java`
- Rename: `.../SpecificationGroupContentDto.java` → `ApiGroupContentDto.java`
- Modify: `.../io/readers/system/{IntegrationSystemReader,SystemImportModelMapper}.java`, `.../IntegrationSystemContentDto.java`
- Modify: `integration-build-pipeline/src/test/.../SystemImportModelMapperTest.java`
- Delete: `runtime-catalog/.../model/exportimport/system/{SpecificationGroupDto,SpecificationGroupContentDto}.java`
- Modify: `runtime-catalog/.../service/exportimport/mapper/services/ApiGroupDtoMapper.java`

- [ ] resolve the DD/UA/AU quartet: the library keeps one DTO pair, named `ApiGroupDto` / `ApiGroupContentDto`,
      carrying the branch's field set
- [ ] resolve `SpecificationGroupDtoMapper` (DU) in favor of the branch's `ApiGroupDtoMapper`
- [ ] teach the library reader both file postfixes at its two `getFilesData` call sites — write `.api-group.` /
      `.api.`, read those **and** the legacy `.specification-group.` / `.specification.`, keeping the existing
      deprecated-prefix fallback — this is the compatibility contract, not an either-or
- [ ] mirror the branch's current-versus-legacy naming design rather than inventing a second one: the reader declares
      which generation it writes and which it merely reads, the same way `PLAIN_SERVICE_POSTFIXES` does catalog-side
- [ ] update the four library `chain/*` classes only if we take the naming through (`ImportSpecificationGroup` is a
      library-internal name and does not break the build); decide once and note the choice in the commit body
- [ ] add a library test that reads one archive written in each of the two naming generations
- [ ] `mvn -pl integration-build-pipeline clean test` green

### Task 5: Wave 3 — re-derive the parser changes on the library contract

**Files:**
- Modify: `integration-build-pipeline/.../parsers/impl/{SwaggerSpecificationParser,GraphqlSpecificationParser,ProtobufSpecificationParser,AsyncapiSpecificationParser,WsdlSpecificationParser}.java`
- Modify: `integration-build-pipeline/.../parsers/resolvers/async/{AsyncConstants,impl/AMQPSpecificationResolver,impl/KafkaSpecificationResolver}.java`
- Modify: `integration-build-pipeline/src/test/.../parsers/impl/SwaggerSpecificationParser3{0,1,2}Test.java`, `AsyncapiSpecificationParserV3Test.java`
- Move: the six branch-added parser tests from runtime-catalog into the library (R4)

- [ ] for each of the five parsers, take `main`'s version wholesale (`git checkout --theirs`), then re-apply the
      branch's behavioral delta on the `parseSpecification` contract — one commit per parser, each with its test
- [ ] drop every `SpecificationGroup` → `ApiGroup` hunk in these files; the library has no entities, the rename is moot
- [ ] do **not** carry `withSchemas` into the parsers — per D2 it belongs to the adapter (Task 6)
- [ ] handle R5 by hand: the branch's `WSDLSpecificationParser` edits go into `WsdlSpecificationParser`
- [ ] relocate the six branch-added parser tests and re-point their packages, imports, and fixtures
- [ ] confirm the branch's parser fixes survived: OpenAPI 3.0 / 3.1 / 3.2, Swagger 2.0, AsyncAPI 2.6 / 3.0 for Kafka
      and AMQP, GraphQL SDL, protobuf, WSDL — each must have a test that fails without the fix
- [ ] `mvn -pl integration-build-pipeline clean test` green

### Task 6: Wave 4 — re-host the adapter

**Files:**
- Modify: `runtime-catalog/.../service/parsers/OperationParserService.java` (9 hunks — the largest single conflict)
- Modify: `runtime-catalog/.../service/parsers/ParserUtils.java`
- Modify: `runtime-catalog/.../service/parsers/OperationParserServiceTest.java` (AA — both sides added it)
- Modify: `runtime-catalog/.../adapters/SystemModelCodegenAdapter.java`, `.../service/EnvironmentBaseService.java`,
  `.../service/ElementBaseService.java` (R1, R2)

- [ ] start from `main`'s `OperationParserService` — it already owns id generation, versioning, duplicate detection,
      and environment reconciliation, which the branch's version still delegated to the parser interface
- [ ] re-apply the branch's `withSchemas` policy on top: the library returns schemas, the adapter declines to persist
      them when `IMPORT_WITH_SCHEMAS` is false
- [ ] re-apply `warnWhenSchemasCannotBeRebuilt` and `protocolOf`, keeping the warning path (never a failure) the branch
      chose deliberately
- [ ] merge the two `OperationParserServiceTest` files rather than picking one; both sides added real cases
- [ ] fix R1/R2 in the three `main` classes: `getSpecificationGroup()` → `getApiGroup()`, `SpecificationGroup` →
      `ApiGroup`, `SpecificationGroupRepository` → `ApiGroupRepository`
- [ ] verify `dce30cdd0` ("prevent duplicate SystemModel instance on specification import") survived the resolution;
      it is a `main` fix in exactly this code
- [ ] `mvn -pl runtime-catalog -am clean test` compiles; parser and adapter suites green

### Task 7: Wave 5 — the export/import seam in runtime-catalog

**Files:**
- Modify: `runtime-catalog/.../service/exportimport/deserializer/ServiceDeserializer.java` (+ `ServiceDeserializerTest`, 10 hunks)
- Modify: `runtime-catalog/.../service/exportimport/serializer/ServiceSerializer.java`
- Modify: `runtime-catalog/.../service/exportimport/mapper/services/{IntegrationSystemDtoMapper,SystemModelDtoMapper}.java`
- Modify: `runtime-catalog/.../service/exportimport/SystemExportImportService.java` (+ its test, AA)
- Modify: `runtime-catalog/.../util/ExportImportUtils.java` (+ its test)
- Modify: `runtime-catalog/.../service/rolloutimport/converter/ServiceConfigurationsToFilesConverter.java`
- Modify: `runtime-catalog/.../service/exportimport/ApiSpecificationExportServiceMergeTest.java`
- Modify: `runtime-catalog/.../cr/.../MaasClassifierHelper.java` (UD), `.../service/deployment/properties/MaasPropertiesUtils.java`

- [ ] resolve `ServiceDeserializer` against `main`'s version, which now delegates archive reading to the library
      reader — the branch's per-type discovery logic belongs on the library side (Task 4), not duplicated here
- [ ] keep the branch's dotted-id extraction fix in `ExportImportUtils.extractSystemIdFromFileName`
- [ ] keep the branch's two-layer `$schema` routing in `ImportConfigFactory` and its
      `ServiceConfigurationsToFilesConverter` counterpart
- [ ] resolve `MaasClassifierHelper` (UD): the class moved to `camelk/...` in the library; the branch's edits go there
- [ ] merge both `SystemExportImportServiceTest` variants; keep the branch's `createdSystems(int)`/`createdTypes(int)`
      overloads
- [ ] read `ChainExternalEntityMapper` and `ChainConfigurationsToFilesConverter` line by line even though they
      auto-merged (R7): `main` rewired chain building onto the library `ImportChain` model in the same files the branch
      changed for rollout routing
- [ ] supply `qip.export.legacy-format` to any library test context that boots `FileMigrationService` (R8)
- [ ] fix the remaining R1 test files: `ServiceDeserializerArchiveLayoutTest`, `ServiceImportContextWiringTest`,
      `SpecificationParserConfigurationContextTest`, `EnvironmentDefaultPropertiesTest`,
      `SystemModelCodegenAdapterTest`, `OperationParserServiceSystemModelIdentityTest`, `SwaggerSpecificationParserTest`
- [ ] `mvn -pl runtime-catalog -am clean test` fully green

### Task 8: Close the merge and satisfy the CI gates

**Files:**
- Create: `runtime-catalog/api-spec/openapi.yaml` (regenerated)
- Modify: `.github/workflows/runtime-catalog-build.yaml` if the path filter needs `integration-build-pipeline/**`

- [ ] reconcile `runtime-catalog/pom.xml` by hand (R9): parent pinned to `1.2.1-SNAPSHOT`, the library version
      property equal to the library's `<revision>`, and the branch's `<testResource>` entries intact
- [ ] run `scripts/check-version-invariants.sh` and fix anything it reports
- [ ] regenerate the OpenAPI contract (R3): `mvn -pl runtime-catalog test -Dtest=OpenApiSpecGeneratorTest
      -DfailIfNoTests=false`, then commit `api-spec/openapi.yaml` and confirm it now describes `apiGroup` endpoints
- [ ] full reactor build: `mvn clean install -Dgpg.skip=true`
- [ ] full npm chain: `npm install`, `npm run build`, `npm test --workspaces --if-present`
- [ ] confirm checkstyle is clean in every Java module
- [ ] `git commit` the merge with a body listing each deliberate resolution and both decisions (D1, D2)

### Task 9: Verify acceptance criteria

- [ ] every conflict from the trial inventory is resolved deliberately, none by `-X ours`/`-X theirs` wholesale
- [ ] `grep -rn "SpecificationGroup" runtime-catalog/src integration-build-pipeline/src` returns only intentional
      legacy-compatibility references (reading old archives), never live entity or DTO usage
- [ ] `grep -rn "runtime\.catalog" integration-build-pipeline/src` is empty
- [ ] every branch decision recorded in `docs/plans/completed/` still holds, or its reversal is written down
- [ ] run the full verification below

### Task 10: [Final] Update documentation

- [ ] update `runtime-catalog/CLAUDE.md` with the library seam and where parsers now live
- [ ] add a module note to the root `CLAUDE.md` for `integration-build-pipeline`
- [ ] note in `help/docs` if any user-visible naming changed
- [ ] move this plan to `docs/plans/completed/`

## Post-merge verification

The local stack is already running (`qip-runtime-catalog`, `qip-engine`, `qip-sessions-management`, `postgreSQL`,
`consul`, `opensearch`, `ui-proxy`). Existing harness, all local-only:
`.claude/skills/runtime-catalog-api-testing/scripts/` — `branch_regression_e2e.py` (19 task functions, 11 seeded
services covering every spec format), `rc_e2e.py` (chain create/export/import/snapshot/deploy plus Postgres and Consul
helpers), `verify_snapshot.sh`, `verify_migration_apply.sh`, `verify_backfill_audit.{sh,sql}`,
`verify_constraint_edge.sh`, and `infrastructure/test-service-type-roundtrip.sh`.

### Layer 0 — build, migration, contract

- [ ] full reactor build from clean, plus every npm workspace
- [ ] `scripts/check-version-invariants.sh` passes
- [ ] `OpenApiSpecGeneratorTest` leaves `api-spec/` unchanged (the exact CI gate)
- [ ] `verify_migration_apply.sh`: `V112_000` + `V112_001` apply to a scratch DB restored from a pre-V112 dump
- [ ] `verify_constraint_edge.sh`: `V112_000` still refuses a database holding duplicate pairs, naming them
- [ ] `verify_backfill_audit.sh`: every row backfilled by `V112_001`, SOAP rows keep `method='POST'`/`path=''`
- [ ] the dump `verify_migration_apply.sh` restores must be taken at `V111_000`, the merged pre-`V112` state; a dump
      from the branch's older baseline would skip `main`'s `V101_000`, `V110_000`, and `V111_000`
- [ ] boot the library's own Spring test context and confirm `qip.export.legacy-format` resolves (R8)
- [ ] rebuild the runtime-catalog image (`mvn -pl runtime-catalog -am package` first — the Dockerfile only copies the
      exec jar) and bring the stack up healthy

### Layer 1 — REST round-trip

Run `branch_regression_e2e.py` end to end, then the merge-specific checks below.

- [ ] all 19 harness tasks pass at their previous counts; any changed count is explained, not accepted
- [ ] every spec format still imports and parses to the recorded operation count: OpenAPI 3.0/3.1/3.2, Swagger 2.0,
      AsyncAPI 2.6 and 3.0 (Kafka and AMQP), GraphQL SDL, WSDL/SOAP, protobuf/gRPC
- [ ] schemas are **not** materialized at import (`IMPORT_WITH_SCHEMAS=false`) and on-demand extraction returns them
- [ ] create one service of each type — EXTERNAL, INTERNAL, IMPLEMENTED, CONTEXT, MCP — and export each; assert the
      file names are per-type (`<id>.external-service.yaml`, `.api-group.`, `.api.`) and each document carries `$schema`
- [ ] re-import every exported archive; the resulting state matches field for field
- [ ] legacy export: restart runtime-catalog with `QIP_EXPORT_LEGACY_FORMAT=true`, export, assert the old flat names and
      that the `V103`–`V105` revert migrations ran; confirm the documented behavior that MCP services are dropped
- [ ] import a pre-#553 archive from the golden corpus and confirm `V103`–`V105` upgrade it in place
- [ ] rollout import: post a package with chain, service, api-group, api, context, and MCP items; assert the two-layer
      `$schema` routing lands each in its bucket, that MCP is skipped with a warning, and that an unknown schema logs an
      error without failing the rollout
- [ ] `PATCH /v1/api-groups/{unknown}` returns 404, not 500
- [ ] operations list issues one batched query, not N+1 — count statements in the runtime-catalog log
- [ ] type-to-protocol restrictions still hold: an IMPLEMENTED service refuses a protocol outside
      `IMPLEMENTED_PROTOCOLS`, and EXTERNAL/INTERNAL keep their own sets — the guards live in `SystemBaseService`,
      `SystemExportImportService`, and `ServiceSerializer`, and the last two are conflicted files
- [ ] environment count limits still apply (`EnvironmentLimitUtils`): an unbounded type accepts many environments, a
      bounded one rejects the one over the limit, through `EnvironmentController` and through import
- [ ] discovered services: import with `isDiscovered=true` and confirm the `synchronization` flag and the discovered
      marker survive — `isDiscovered` moved from the parser interface up into `OperationParserService.parse`
- [ ] AsyncAPI environments: `main` now emits environments from the parsed model (`cc83a5ffc`); import a Kafka and an
      AMQP spec and confirm the environments materialize once and are not capped away by the limit check
- [ ] regression for `main`'s `dce30cdd0`: importing a specification twice must not leave a duplicate `SystemModel`
- [ ] inline-group archive shape: import a service archive with groups embedded in the system document rather than in
      separate files — `main` rewrote 141 lines of `IntegrationSystemReader` for exactly this (`392e327e4`), and it
      lands in the same file the branch's per-type discovery has to be re-hosted in
- [ ] `infrastructure/test-service-type-roundtrip.sh` passes unchanged

### Layer 1b — the paths the library extraction put at risk

Chains and codegen are not what this branch is about, which is exactly why they need testing: the library extraction
moved their machinery too, and nothing in the branch's own suites watches them.

- [ ] chain export/import round-trip through the library reader: export a chain, re-import it, compare element for
      element — `ChainReader` and `ChainModelMapper` are library code now
- [ ] import a chain archive written in an older format and confirm the chain file migrations `V100`–`V108` still run
      in order from their new library home
- [ ] revert migrations across both families: export with `QIP_EXPORT_LEGACY_FORMAT=true` a package holding a chain and
      a service, and confirm the chain reverts (`V101`, `V108`) and the service reverts (`V103`–`V105`) each fire
      through the single `revertMigrationIfNeeded` path without interfering
- [ ] chain-to-group linkage: `GET /v1/chains/{systemId}/specificationGroup` and
      `GET /v1/chains/{systemId}/specificationGroup/{groupId}` still answer, and the renamed `ChainsByApiGroup` payload
      keeps its wire shape
- [ ] rollout import of a chain package (`ChainConfigurationsToFilesConverter`, R7) lands every item
- [ ] codegen: `GET /v1/models/{modelId}/dto/jar` compiles and returns a library for a gRPC model — `PackageNameUtil`
      now takes `CodegenSystemModel` and `SystemModelCodegenAdapter` bridges the JPA entity to it
- [ ] the gRPC `javaPackage` round-trip still holds: a `.proto` with `option java_package` keeps the package through
      export and re-import (the seeded `df8ce19e-…` model is deliberately sourceless; a fresh gRPC import needs a
      protoc-provisioned image)
- [ ] context and MCP services read through their own library readers (`ContextServiceReader`, `McpServiceReader`) in
      both naming generations

### Layer 2 — UI in the browser

Always through `http://localhost:8080` (nginx); port 4200 alone has no backend.

- [ ] services list renders every type; the API Format columns appear in the column picker
- [ ] with a legacy `localStorage` payload seeded, the new columns are enablable and the reconciled list persists
      across a reload; a column the user hid stays hidden
- [ ] corrupt the stored JSON deliberately — the table renders instead of crashing
- [ ] service detail: parameters tab, operations tab, and the protocol discriminator in `SystemOperationField` show the
      right protocol after switching services without a reload
- [ ] edit labels on a service and on an API group; verify over REST that `description` and `activeEnvironmentId`
      survive the service PUT and that the group's `synchronization` flag is unchanged — this is the regression the
      slimmed payloads were written to prevent
- [ ] import and export a service through the UI; the downloaded archive matches the REST-produced one
- [ ] capture the run as a GIF for the PR

### Layer 3 — VS Code extension, offline

- [ ] open a service project written in the new per-type naming; every service is listed exactly once
- [ ] open a multi-app tree; per-file config resolution shows every app
- [ ] open a project in the legacy flat naming; it still reads, and writing back produces current names
- [ ] a file whose `$schema` is a mapping, a number, or missing reads as untyped instead of throwing
- [ ] `npm -w @netcracker/qip-vscode-extension run build` (schemas → ui lib → extension) succeeds and the integration
      tests pass

### Layer 4 — engine smoke test

The library also carries the Camel DSL generation (`camelk/sources/builders`), so the deployment path has to be
exercised, not assumed.

- [ ] build a snapshot for a chain that calls a service operation; inspect the generated Camel XML for the service call
      and its MaaS classifier
- [ ] deploy it and confirm the descriptor reaches Consul KV
- [ ] confirm `qip-engine` picks it up: `GET /v1/engine/live-exchanges` and a healthy actuator
- [ ] trigger the chain and confirm a session is recorded in OpenSearch and readable through sessions-management
- [ ] check `docker logs qip-runtime-catalog` and `qip-engine` for new errors; the only tolerated noise is the known
      pre-existing gRPC single-file import `ERROR "Can't find Main specification source"` (blamed to `490e5759`)

### Layer 5 — regression sweep against recorded decisions

- [ ] re-read `docs/plans/completed/20260805-service-type-*.md` and confirm each locked decision still holds
- [ ] the three frozen compatibility copies in the naming package still compile and still measure what they were
      written to measure (an old build reading a current archive)
- [ ] the shared naming corpus (`schemas/src/test/resources/naming/service-file-names.yaml`) is read by all three
      consumers and every divergence entry still has written justification

## Rollback

The pre-merge tip is tagged `premerge/migrate-services-20260824` and `migrate-services-to-new-schema` is never moved
during this work. Abandoning the merge is `git checkout migrate-services-to-new-schema` plus deleting
`merge/main-into-migrate-services`. Because `rerere` records every resolution, restarting the merge from scratch
replays the waves already completed instead of redoing them.

## Post-completion

*Manual and external steps, informational only.*

- Open the PR from `merge/main-into-migrate-services`; `main` and `release/*` are protected, so the push goes to a new
  branch with no upstream to the base.
- The PR description needs a Compatibility section covering both naming generations and the legacy-export flag.
- A library release is not required for CI, but the first consumer release after this merge will need
  `integration-build-pipeline` published at a version matching `runtime-catalog`'s pin.
- Sonar quality gate on the PR: the moved code counts as new code in the library project and may need a coverage pass.
