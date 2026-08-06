# Service types in the UI and the VS Code extension (issue #553)

> **Revision 4.** Revision 1 under-scoped the `ext.service` fan-out and claimed the backend's REST API let the type be
> mutated. Revision 2 fixed those but still had wrong line ranges for `fileApiImpl.ts`, missed a second `createService`
> path and `getFileType`, and its Task 6 as written would not compile. Revision 4 folds in what plan 1 learned while
> being implemented — see [What plan 1 settled](#what-plan-1-settled). Plan 1 touched no file under `ui/` or
> `vscode-extension/`, so every line number below still holds.

Follow-up to `20260805-service-type-schemas-and-backend.md`. That plan makes the **file name** the statement of a
service's type. This one teaches the two frontends to write that name and groups services by type in the VS Code
explorer tree.

**Why the file name and not `$schema`:** `schemaUrls` is per-project configurable — `.config.qip.yaml.example:15-22`
sets `service: http://qubership.org/schemas/product/${appName}/service`, with no `.schema.yaml` suffix and an
arbitrary app name — and `fileApiImpl.ts:801` / `serviceApiModify.ts:165` write it verbatim. So the extension keeps
writing whatever a project configures, and nothing here has to change about that. The type travels in the file name,
where only `${appName}` varies.

## Overview

Three deliverables:

1. **The VS Code extension reads and writes the new per-type files.** It reads both formats and writes only the new
   one, converting a file on its first write — the approach already proven on this branch for `.specification.` →
   `.api.`, where git records the change as a rename and no migration command is needed.
2. **The QIP explorer groups services by type.** One level between `Services` and the services themselves.
3. **The service type becomes read-only after creation.** This is a frontend-only bug: `ServiceParametersTab.tsx:186-203`
   renders a type `Select` inside VS Code, `:102` puts `type` into the update payload on both the web and VS Code
   paths, and `serviceApiModify.ts:121-131` writes whatever it receives into the file. The backend's REST API does
   **not** have this bug — `SystemMapperImpl.mergeWithoutLabels` never maps the type.

**Scope decision — the grouping is virtual.** Files stay where they are on disk; only the explorer tree gains a
level. Nothing has to be moved, no existing project needs migrating, git history stays intact, and the tree cannot
disagree with the files.

Target tree:

```
QIP
├─ Chains
└─ Services
   ├─ External
   │  ├─ 1111-Unknown-7331eb14…
   │  └─ test-HTTP-c32207d0…
   ├─ Internal
   │  └─ test2-HTTP-2924e5cf…
   ├─ Implemented
   ├─ Context
   └─ MCP
```

## Context (from discovery)

**`ext.service` is referenced across nine files.** Widening only the discovery helpers leaves the rest of the
extension blind to a new-format file. Line numbers verified in review round 2:

| File | Sites | What breaks if it is missed |
|---|---|---|
| `response/serviceApiRead.ts` | 54, 168, 191, 248, 250, 351, 353, 435, 438, 446, 506, 800, 809 | environments, api specs, operations, navigation |
| `response/file/fileApiImpl.ts` | 76, 273, **812-817**, **838-880** (852, 866) | kind detection, discovery, **the command-palette `createService`**, **`getFileType` for both files and folders** |
| `response/serviceApiModify.ts` | 121-131, 145-177 | the webview create/update path |
| `response/file/fileExtensions.ts` | 5-14, 17-28, 111, 159, 173 | the extension map itself |
| `services/FileCacheService.ts` | 186, 230, 260 | a new-format file is never cached or invalidated |
| `api-services/EnvironmentService.ts` | 191 | `findFileById(id, ext.service)` |
| `api-services/SystemService.ts` | 101 | `findFileById(id, ext.service)` |
| `extension.ts` | 580 | editor wiring |
| `qipExplorer.ts` | 217, 229-235 | tree discovery and type detection |

Note the two distinct create paths: `serviceApiModify.ts:145-177` (from the webview) and `fileApiImpl.ts:812-817`
(from the command palette, writing `$schema` and `integrationSystemType` at `:800-809`). Both must change.

Other files involved — VS Code extension:

- `services/ProjectConfigService.ts` — type declarations at 14, 23, 43, 52; `DEFAULT_SCHEMA_URLS` at 70-83;
  `buildDefaultConfig` at 490-501
- `configs/default.config.qip.yaml` and `.config.qip.yaml.example` (**two** blocks: `qip` and `pip`)
- `package.json` — `contributes.customEditors`, four `filenamePattern` entries
- `editorViewTypes.ts:21-39` — the mapping; the plain `service` branch is at `:28-30` and needs three siblings
- `response/file/serviceFileShape.ts:22-41` — write-time key order only; `fileApiImpl.ts:37-46` already falls through
  to `"service"` for anything not mcp/context
- `response/serviceApiUtils.ts:243-252` — `validateAllowedSystemProtocol`
- `api-services/servicesTypes.ts:17-22` — the extension's own `IntegrationSystemType` (four values, no MCP)
- `services/importMigrationVersions.ts:9` — `SERVICE_MIGRATIONS`, with the comment "Services and context services
  share one migration list"

Other files involved — UI:

- `api/apiTypes.ts:1079-1093` — `IntegrationSystemType` (five values) and `SystemRequest`
- `api/api.ts:396` + `api/rest/restApi.ts:1593` — `updateService(id, data: Partial<IntegrationSystem>)`; the update
  payload is `Partial<IntegrationSystem>`, **not** `SystemRequest`, which is create-only and must keep `type`
- `components/services/detail/ServiceParametersTab.tsx:102,186-203`
- `components/services/detail/ServiceEnvironmentsTab.tsx:171-175` — passes an **object literal** containing
  `type: system?.type`; this is an excess-property error against an `Omit<…, "type">` payload type
- `components/services/ServicesList.tsx:308-314` — spreads `...record` into the payload, so it compiles but still
  sends `type` over the wire
- `components/services/modals/CreateServiceModal.tsx` — type at creation (stays)
- `components/services/ServicesTreeTable.tsx:160` — a display-only `switch (record.type)`

Related patterns found:

- **Dual-format read, single-format write** is already implemented in this extension for the API level.
- **`ApiGroupService.resolveGroupFile`** is the precedent for a resource existing under two extensions: the current
  one wins, the sibling is a duplicate, and a delete removes both so the entity cannot resurrect.
- **`-service` suffixes do not collide** with `.service.` under `endsWith` or under VS Code's `*.service.qip.yaml`
  glob, which is why `.context-service.` is safe today.

Dependencies identified:

- **Plan 1 must be merged first.** This plan hardcodes migration version 105 and the file postfixes it defines.
- `@netcracker/qip-schemas` must be built before the extension (`npm run prepare-deps`).

## What plan 1 settled

Plan 1 shipped after ten review rounds, five of which rewrote its file-name parsing. The rules it converged on are
now the contract this plan writes against, and three of them contradict what this plan says elsewhere. Read this
section before Task 2 and Task 5.

### The backend refuses some service ids outright

`ExportImportUtils.requireCurrentFormatId` refuses to write a current-format name for an id that is not **one
dot-free segment**, for all three plain types and for context and MCP services. Import reads the id up to the first
dot and the postfix in the segment right after it, so a dotted id produces a name that states another id.
`requireLegacyFlatId` likewise refuses the flat name of an id whose *second* segment spells a plain-service postfix.

For this plan that means: **a service the extension creates must have a dot-free id.** The two create paths use
`crypto.randomUUID()` (`serviceApiModify.ts:151`, `fileApiImpl.ts:769`), which satisfies this, and Task 5 should pin
it rather than leave it to luck — nothing in the extension enforces it today, and a hand-edited or imported id can be
anything.

An id starting with `service-` is **fine** and must stay fine: `DiscoveryService.constructSystemId` takes the id from
the Kubernetes service name, so a cloud service named `service-orders` has one. Plan 1 refused such ids for one round
and had to undo it.

### Conversion of a dotted-id service depends on the folder name

Import discovery reads the postfix in the segment after the id **or** right after the parent directory name
(`ExportImportUtils.statesPostfix(File, String)`). The directory overload is what still finds
`services/a.b/a.b.service.qip.yaml`, which a pre-#553 Runtime Catalog wrote for a dotted id.

So when Task 5 converts such a file to `a.b.external-service.qip.yaml`, the backend finds it **only** because the
folder is named `a.b`. Task 5 must leave the service folder name alone — which it already says — and a test should
pin that, because the failure mode is a service silently absent from an import, not an error.

### `$schema` still matters, for the kind and not for the type

This plan says a custom `schemaUrls` "is no longer a problem". That is true for the **three plain types**, whose type
now travels in the file name. It is not true across the board:

- The backend decides whether a discovered file is a **context or MCP** document by name **and** `$schema`
  (`ServiceTypeFiles.isContextOrMCPServiceFile`, matching the same configured URIs `isContextServiceFile` and
  `isMCPServiceFile` already used). A project whose `schemaUrls.contextService` the backend has never seen keeps its
  context services unimportable — pre-existing, unchanged by either plan, but do not claim otherwise.
- `service-ctx.context-service.qip.yaml` is genuinely claimed by two imports, and `$schema` is what settles it.

Keep writing `$schema` from the project config, as this plan says. Just scope the claim.

### The backend is stricter than "ERROR on import"

Task 9's asymmetry note understates it. A document the commit path would refuse is now also an error row in the
**import preview**, so a user sees it before committing. And a file whose name and `content.integrationSystemType`
**disagree** raises rather than letting the name win (`ServiceDeserializer.resolveServiceType`).

Task 9's tree behaviour is still right — group by the name, keep an `Unknown` bucket — but the pairing is "tolerant
editor, strict backend" in a stronger sense than the note implies: the extension shows a file the backend rejects
outright, both in preview and on commit.

### REST behaviour this plan's UI work touches

- `PUT /v1/systems/{id}` and `PATCH` on an **unknown id now answer 404** instead of creating the service. A search of
  `ui/` and `vscode-extension/` during plan 1 found no caller relying on PUT-as-create: `RestApi.updateService`
  (`restApi.ts:1593`) is reached only from three sites that pass an already-loaded id, and creation goes through
  `createService` (`restApi.ts:1335`), a POST. No change needed here; recorded so it is not rediscovered.
- **One environment is now enforced for INTERNAL and IMPLEMENTED** on the REST path (`EnvironmentController` through
  `EnvironmentLimitUtils.validate`), not only for INTERNAL on import. `ServiceEnvironmentsTab.tsx:497` offers "Add
  Environment" for EXTERNAL only, so the limit is unreachable from the UI today. Task 6 edits that file — do not widen
  the button while you are in there.
- The type is immutable server-side and pinned by tests (`mergeWithoutLabels` and `patchMergeWithoutLabels` map no
  type; `validateServiceTypeUnchanged` refuses a type-switching import). Task 6 closes the frontend half.

### Migration version 105 is registered

`V105ServiceImportFileMigration` is a `@Component` with `getVersion() == 105`, and
`MigrationBeanRegistrationTest.revertTestRegistryIsComplete` now guards that the test registries match the scanned
beans. Task 7's `"[100, 101, 102, 103, 104, 105]"` is correct as written.

### `QIP_EXPORT_LEGACY_FORMAT` covers plain services only

Nothing discovers `context-service-<id>.yaml` or `mcp-service-<id>.yaml`, in this version or any older one. The flag
is a downgrade path for plain services and for nothing else. This matters for the Post-Completion round-trip step,
which is corrected below.

## Development Approach

- **testing approach**: Regular (code first, then tests)
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
- an existing project with old-format files must keep working without any user action

## Testing Strategy

- **unit tests**: required for every task. Jest in both workspaces; the extension mocks `vscode` through
  `tests/__mocks__/vscode.ts`.
- **UI component tests**: React Testing Library, alongside `ui/tests/components/services/`.
- **format-conversion tests**: the highest-value tests here. Read an old-format file, write it, assert the new file
  exists, the old one is gone, and every field survived.
- **custom-config fixture**: a project whose `.config.qip.yaml` sets a non-default `appName` and `schemaUrls`. The
  file name must still carry the type, and the backend must still resolve **the type** — scope the claim to that:
  group and api discovery inside the service directory is filtered by the backend's own `app.prefix`
  (`ServiceDeserializer:117,126-127`), so a custom-appName project's groups are dropped on import today. Pre-existing
  limitation, unchanged by these plans.
- **fixture workspaces (build once)**: `vscode-extension/tests/fixtures/service-projects/{new-format,old-format,mixed,custom-config}/`
  beside the existing `asyncapi/`/`openapi/` fixtures, shared by the jest suites and the Task 10 web-host suite.
  Extend existing suites rather than duplicating: `tests/services/importMigrationVersions.test.ts`,
  `tests/services/FileCacheService.test.ts`, `tests/response/serviceApiModify.test.ts`,
  `tests/web/editorViewTypes.test.ts`, `extension.deleteService.test.ts` (the both-files delete); mirror
  `tests/api-services/ApiGroupService.groupFile.test.ts` for the duplicate-file precedence cases in the new
  `serviceFileType.test.ts`.
- **integration tests**: `vscode-test-web` in chromium is an **offline** host. Anything needing a running
  runtime-catalog belongs in Post-Completion manual verification.
- **Prove each new test has teeth before trusting it.** The habit that paid off across plan 1's ten rounds: after
  writing a test, break the code it covers and confirm the test goes red. Three of plan 1's tests passed for the
  wrong reason — one compared two committed fixtures and never touched production code, one regenerated its own
  expectation, and one fed a file straight to the deserializer, bypassing the discovery it claimed to cover. The file
  naming and tree grouping here are the same shape of logic, where a wrong answer is a service that is quietly absent
  rather than a failure.

Commands: `npm -w @netcracker/qip-ui test`, `npm -w @netcracker/qip-vscode-extension test`,
`npm -w @netcracker/qip-vscode-extension run check-types`.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope

## Solution Overview

**One helper answers "is this a service file, and of what type", and all nine files route through it.**

Widening each `endsWith(ext.service)` test in place is ~45 edits across nine files and guarantees one gets missed,
with the miss showing up as a service whose environments tab is empty rather than as a failure. Task 2 introduces
`serviceFileType.ts` with `isAnyServiceFile(uri)` and `serviceTypeFromUri(uri)`, and Task 4 rewrites the call sites.
Adding a sixth service kind later then touches one file.

`serviceTypeFromUri` covers all five kinds, not just the three new ones — Task 8's tree needs `CONTEXT` and `MCP`
buckets from the same helper, and the extension's own `IntegrationSystemType` is currently missing `MCP`.

Reads accept both formats. Writes emit only the new one: on the first write of an old-format service the extension
creates `<id>.<type>-service.<app>.yaml` and deletes the `<id>.service.<app>.yaml` sibling. The service folder and
its `resources/` are untouched. Where both files exist, the new one wins and the old is a duplicate, mirroring
`ApiGroupService.resolveGroupFile`.

The explorer tree gains a `"service-group"` node type. `getServices()` returns group nodes carrying their services in
the existing `children` field; `getChildren` returns them directly. Group nodes have no `fileUri`, so `getTreeItem`
attaches no reveal command. An empty group is omitted.

## Technical Details

### Extension map additions

```ts
externalService: `.external-service.${appName}.yaml`,
internalService: `.internal-service.${appName}.yaml`,
implementedService: `.implemented-service.${appName}.yaml`,
```

These must land in **all** of: `FileExtensionsConfig` (5-14), `buildDefaultExtensions` (17-28), the three mapping
objects in `fileExtensions.ts` (111, 159, 173), the four `ProjectConfigService` type declarations (14, 23, 43, 52),
`DEFAULT_SCHEMA_URLS` (70-83), `buildDefaultConfig` (490-501), `configs/default.config.qip.yaml`, and both blocks of
`.config.qip.yaml.example`. Miss one and a project with a custom config silently falls back to defaults.

### The app-name regex

`extractAppNameFromExtension` currently matches:

```
/\.((?:context-)?service\d*|chain\d*|(?:specification|api)(?:-group)?\d*)\.([^.]+)\.yaml$/
```

The `(?:context-)?` group grows to cover the new prefixes. **Prove equivalence before and after on a fixed case
list** — including `mcp-service`, which is absent from the current alternation and resolves through the config path
instead. A regex change that quietly stops matching a file type sends every such file to the default app name.

### `ServiceFileKind`

Do **not** add three new kinds. `serviceFileShape.ts:22-41` keys only the write-time key order, and
`fileApiImpl.ts:37-46` already falls through to `"service"` for anything not mcp/context — three identical entries
buy nothing. The shared `service` order at `:23-32` still lists `integrationSystemType`; that is harmless because
absent keys are skipped, and it stays as documentation of the legacy shape.

### Migration version

`SERVICE_MIGRATIONS` becomes `"[100, 101, 102, 103, 104, 105]"` — not because the extension needs V105 to run (plan 1
resolves the type from the file name for every document), but because the backend registers 105 and the claim must
match its registry exactly. The comment in that file already explains the rule; extend it with this reasoning.

### Tree node type

`QipExplorerItem.type` gains `"service-group"`. `getChildren` returns `element.children ?? []` for it. Icons stay per
service (`globe`, `home`, `tools`, `server`, `comment-discussion`); the group reuses its type's icon.

Task 9 **inverts** today's precedence: `qipExplorer.ts:229-235` currently prefers `content.integrationSystemType` and
falls back to the file name. After this plan the file name wins. This is safe — `serviceTypeFromUri` returns nothing
for `.service.`, so old-format files still fall back to the field — but it is a behaviour change, not a refactor.

## What Goes Where

- **Implementation Steps**: extension code, UI code, tests
- **Post-Completion**: manual verification against a running backend, releases of the npm packages

## Implementation Steps

### Task 1: Add the three file extensions to the config surface

**Files:**
- Modify: `vscode-extension/src/web/response/file/fileExtensions.ts`
- Modify: `vscode-extension/src/web/services/ProjectConfigService.ts`
- Modify: `vscode-extension/configs/default.config.qip.yaml`
- Modify: `vscode-extension/.config.qip.yaml.example`
- Create: `vscode-extension/tests/fileExtensions.serviceTypes.test.ts`

- [x] add the three keys to `FileExtensionsConfig` (5-14), `buildDefaultExtensions` (17-28), and the three mapping objects at 111, 159, 173
- [x] add them to all four `ProjectConfigService` type declarations (14, 23, 43, 52), `DEFAULT_SCHEMA_URLS` (70-83), and `buildDefaultConfig` (490-501)
- [x] add the `extensions:` and `schemaUrls:` entries to the default config and to **both** blocks of the example config
- [x] extend the `extractAppNameFromExtension` regex to the new prefixes
- [x] write a test proving the new regex matches every current case identically, plus the three new ones
- [x] write a test: a project config missing the new keys falls back to defaults instead of yielding `undefined`
- [x] run `npm -w @netcracker/qip-vscode-extension test` — must pass before task 2

➕ Two extra files beyond the plan's list: `tests/services/ProjectConfigService.serviceTypes.test.ts` (the
defaults-fallback test needs the real `ProjectConfigService`, which the regex suite mocks, so the two cannot share a
file) and `tests/helpers/mocks.ts` (`QIP_FILE_EXTENSIONS` documents itself as the default set, so it grew the three
keys too). `mcp-service` stays out of the `extractAppNameFromExtension` alternation, as it has always been — the
equivalence case list pins that.

### Task 2: Add the single service-file resolver

**Files:**
- Create: `vscode-extension/src/web/response/file/serviceFileType.ts`
- Modify: `vscode-extension/src/web/api-services/servicesTypes.ts`
- Create: `vscode-extension/tests/serviceFileType.test.ts`

- [x] add `serviceTypeFromUri(uri)` covering **all five** kinds — the tree in Task 8 needs `CONTEXT` and `MCP` from the same helper. Compare against the **whole extension** (`.external-service.${appName}.yaml`), the way every existing `endsWith(ext.…)` site does. That is end-anchored and includes the app name on both sides, so an id or an app name that merely contains another postfix cannot shadow it, and `.external-service.` cannot end-match `.service.` (the char before `service` is `-`) — the same reason `.context-service.` is safe today
- [x] do **not** port plan 1's position-anchored match here. The backend needs it because it compares a bare postfix against a name whose app prefix it does not know; comparing the full extension answers the same question, and swapping it for a prefix scan would reintroduce exactly the shadowing plan 1 spent five rounds closing
- [x] add `isAnyServiceFile(uri)` covering the four plain-service extensions, and `serviceExtensionForType(type)` for writes
- [x] add `MCP` to the extension's `IntegrationSystemType` (`:17-22`) so it matches the UI's five values
- [x] leave `ServiceFileKind` and `serviceFileShape.ts` alone — `fileApiImpl.ts:37-46` already falls through to `"service"`, so three identical entries add nothing
- [x] write tests for all six file kinds plus an unrelated file
- [x] write a test asserting `.context-service.` is never misread as a plain service
- [x] run tests — must pass before task 3

➕ One export beyond the plan's list: `plainServiceExtensions(extensions)` returns the four plain-service extensions with
the typed ones ahead of the legacy one. Task 4 needs that list for its `findFileById` variant and Task 5 for the
conversion write, and stating the precedence once here keeps the five call sites from each re-deriving it.

➕ Both `serviceTypeFromUri` and `isAnyServiceFile` take an optional second argument, the extension set to compare
against. Handed nothing they resolve it per file through `getExtensionsForFile`, which is what `serviceFileKind`
(`fileApiImpl.ts:37-46`) already does; a caller that has already resolved the set — the explorer loop in Task 8 — passes
it in. `serviceExtensionForType` takes the set as a required argument because both create paths already hold one.

➕ `EXTENSION_KEY_BY_TYPE` is typed `Record<IntegrationSystemType, keyof ServiceExtensions>`, so a sixth service type
cannot be added to the enum without giving it an extension — `check-types` fails first. Verified by deleting `MCP` from
the enum: `tsc` reports the missing key and the suite stops compiling.

### Task 3: Register the new custom editors

**Files:**
- Modify: `vscode-extension/package.json`
- Modify: `vscode-extension/src/web/editorViewTypes.ts`
- Modify: `vscode-extension/src/web/extension.ts`
- Modify: `vscode-extension/tests/web/editorViewTypes.test.ts` (exists — do not create a duplicate)

- [x] add three `customEditors` entries with `*.external-service.qip.yaml`-style patterns
- [x] add three siblings to the plain `service` branch at `editorViewTypes.ts:28-30`
- [x] register the editors in `activate()` alongside the existing four, and update `extension.ts:580`
- [x] write tests: each of the six file kinds resolves to its own view type
- [x] write a test asserting no pattern shadows another (a `.context-service.` path must not resolve to the plain service editor)
- [x] run tests — must pass before task 4

➕ The glob question is settled: `*.service.qip.yaml` does **not** claim a typed name. VS Code matches a
slash-free `filenamePattern` against the file name with `*` standing for any run of characters inside one segment,
so a match needs the literal `.service.qip.yaml` at the end, and `external-service.qip.yaml` offers `-service…`.
Same rule, different matcher, same answer as the resolver's `endsWith`. The suite pins it from package.json rather
than from a copy of the patterns.

➕ The three typed branches go **before** the plain `service` one, not after it as the plan's "siblings at
`:28-30`" reads. The default names cannot shadow each other, but a project is free to configure
`service: ".svc.qip.yaml"` alongside `externalService: ".external.svc.qip.yaml"`, which the plain branch would
swallow. `plainServiceExtensions` already orders itself this way for the same reason.

➕ `extension.ts` had a second, unlisted editor-wiring site: `qip.revealInExplorer` picked the view type from its
own `endsWith` chain and **defaulted to the chain editor**, so a typed service file opened from the tree would have
rendered as a chain. It now calls `getEditorViewTypeForUri`, and a file no custom editor claims falls through the
existing catch to the text editor instead of to the chain editor. [decision] Behaviour change, taken deliberately:
the tree holds only chains and services, so the throw path is unreachable from the command, and the text editor is
the honest fallback for anything else.

➕ Two extra test files beyond the plan's list: `tests/extension.test.ts` (the seven registrations, and the reveal
command per file kind) and `tests/extension.deleteService.test.ts` (a sibling under a typed name keeps `resources/`
alive — the `isServiceFileName` half of `extension.ts:580`). `DEFAULT_EDITOR_VIEW_TYPES` is exported so the suite
can check every view type the resolver returns against the manifest.

### Task 4: Route every service-file site through the resolver

**Files:**
- Modify: `vscode-extension/src/web/response/serviceApiRead.ts` (13 sites)
- Modify: `vscode-extension/src/web/response/file/fileApiImpl.ts` (76, 273, 838-880)
- Modify: `vscode-extension/src/web/services/FileCacheService.ts` (186, 230, 260)
- Modify: `vscode-extension/src/web/api-services/EnvironmentService.ts` (191)
- Modify: `vscode-extension/src/web/api-services/SystemService.ts` (101)
- Modify: `vscode-extension/src/web/api-services/SpecificationImportService.ts` (178)
- Modify: `vscode-extension/tests/serviceApiRead.test.ts` (create if absent)

- [x] replace every `endsWith(ext.service)` test with `isAnyServiceFile`, and every `findFileById(id, ext.service)` with a variant searching all four extensions
- [x] extend `getFileType` (`:838-880`): the file branch at `:852` (a new-format file returns `UNKNOWN` today) and the directory-inference branch at `:863-867` (a folder holding only `<id>.external-service.qip.yaml` falls through to plain `FOLDER` instead of being classified as a service folder)
- [x] extend `FileCacheService` indexing and invalidation — a file that is never invalidated serves stale content after an edit
- [x] derive the type through `serviceTypeFromUri`, falling back to `content.integrationSystemType` for old-format files
- [x] apply the `resolveGroupFile` precedence rule when both files exist: the new one wins, the old is a duplicate
- [x] extend `SERVICE_ROUTES` handling so navigation paths resolve against any service extension
- [x] write tests: reading each new format returns the right type, with environments and api specs intact
- [x] write tests: reading an old-format file still works and its type comes from the field
- [x] write a test: a new-format file is not `UNKNOWN`, and a folder containing only one is classified as a service folder, not plain `FOLDER`
- [x] write a test: editing a new-format file invalidates its cache entry
- [x] write a test for the duplicate case — exactly one service is listed
- [x] run tests — must pass before task 5

➕ One module beyond the plan's list: `response/file/serviceFileLookup.ts`, holding `findServiceFileById(id, ext?)`
and `findServiceFiles(ext?)` — the "variant searching all four extensions" the first checkbox asks for, shared by
`serviceApiRead`, `SystemService` and `EnvironmentService`. It stays out of `serviceFileType.ts` so the resolver keeps
its single dependency on file names and never reaches for `fileApi`.

➕ `resolveServiceType(fileRef, service, extensions?)` joins `serviceFileType.ts`: the name wins, `content.integrationSystemType`
is the legacy fallback, empty when neither states one. Both read surfaces (`serviceApiRead.getService`,
`SystemService.getSystemById`) call it, and Task 9's tree needs the same rule — stating it once is what keeps the
three from drifting.

➕ [deviation] `api-services/SpecificationImportService.ts` needed no edit after all. Its `:178` site reads
`params.system.integrationSystemType`, and that object comes from `SystemService.getSystemById`, which this task fixes;
the file holds no service-extension test of its own, and its `getBaseFolder` goes through `fileApi.getFileType`, also
fixed here. Covered by `tests/api-services/SystemService.serviceTypes.test.ts` rather than by a test of its own.

➕ [deviation] Five test files instead of the plan's one. `tests/serviceApiRead.test.ts` already exists as
`tests/web/response/serviceApiRead.test.ts` (the model-read suite), so its `fileExtensions` stub was widened —
`serviceFileType` resolves a name through that module too — and the new cases went into
`tests/web/response/serviceApiRead.serviceTypes.test.ts`, `tests/web/response/fileApiImpl.serviceTypes.test.ts` and
`tests/api-services/SystemService.serviceTypes.test.ts`, with `tests/services/FileCacheService.test.ts`,
`tests/api-services/EnvironmentService.test.ts` and `tests/serviceFileType.test.ts` extended in place.

➕ [decision] `getServices` now walks the workspace once per plain-service extension rather than once, the four walks
issued concurrently. A single walk would need a `findFiles(extensions[])` on the `FileApi` interface, its provider
facade and every mock of it; the parse count is unchanged, and only the directory reads repeat.

➕ Every mutation of the changed logic was checked to go red: `getFileType`'s file and directory branches back to
`extensions.service`, the navigation fan-out back to one extension, `resolveServiceType`'s precedence inverted,
`plainServiceExtensions` reordered legacy-first, the `getServices` dedup dropped, `getServices` scanning one extension,
`getApiSpecifications` back to `endsWith(ext.service)`, both `FileCacheService` dispatch arms back to the legacy-only
test, and `SystemService`/`EnvironmentService` back to `findFileById(id, ext.service)`.

### Task 5: Write the new format from both create paths

**Files:**
- Modify: `vscode-extension/src/web/response/serviceApiModify.ts` (145-177)
- Modify: `vscode-extension/src/web/response/file/fileApiImpl.ts` (800-817)
- Create: `vscode-extension/tests/serviceApiModify.conversion.test.ts`

- [x] make **both** create paths write `<id>.<type>-service.<app>.yaml` with no `integrationSystemType`: the webview one at `serviceApiModify.ts:145-177` and the command-palette one at `fileApiImpl.ts:812-817`
- [x] keep writing `$schema` from the project config — the backend resolves the **plain-service type** from the file name, so a custom `schemaUrls` no longer affects it. It still decides whether a file is a context or MCP document (see [What plan 1 settled](#what-plan-1-settled)), so do not widen that claim
- [x] on updating an old-format service, write the new file and delete the old sibling
- [x] leave the service folder name alone: the backend finds a converted **dotted-id** service only because the folder still carries that id (`ExportImportUtils.statesPostfix(File, String)`), and the failure mode is a silently missing service, not an error
- [x] make a delete remove both files, so a converted-then-deleted service cannot resurrect
- [x] write tests: each create path produces the expected file name and body, for each type
- [x] write tests: updating an old-format file creates the new one, removes the old one, and preserves every field
- [x] write a test with a non-default `appName` and `schemaUrls`, asserting the file name still carries the type
- [x] write a test: deleting a service that has both files removes both
- [x] write a test pinning that a created service gets a dot-free id — `crypto.randomUUID()` satisfies the backend's `fitsCurrentFormatFileName` rule today, but nothing enforces it, and a dotted id produces a name whose leading segment reads back as a different service
- [x] write a test: converting a service whose id contains a dot keeps the folder name, so the backend's directory-anchored discovery still finds the file
- [x] run tests — must pass before task 6

➕ One module beyond the plan's list: `response/file/serviceFileWrite.ts`, holding
`writeServiceInCurrentFormat(uri, service)` — the conversion itself, returning the file the service
landed in. Three call sites route through it, not the one the plan names: `serviceApiModify`'s local
`writeMainService` (so every update, environment edits included), `SystemService.saveSystem` and
`EnvironmentService.saveSystem`. Those last two are what the services list saves through, and leaving
them on `fileApi.writeMainService` would migrate a service or not depending on which screen edited it.

➕ Three helpers beyond Task 2's set, all on `serviceFileType.ts` so the name rules stay in one file:
`serviceSchemaUrlForType` (the `schemaUrls` half of the same type map), `serviceFileNameForType` (the
target name — only the extension moves, so the base name and the folder keep the id) and
`allServiceExtensions` (all five typed names ahead of the legacy one, the order that keeps a
configured extension from swallowing a longer one).

➕ [decision] A typed write drops `content.integrationSystemType` whether or not the name changed,
not only on a conversion. `typed-service-content.schema.yaml` states `not: required:
[integrationSystemType]`, and `SystemService.saveSystem` assigns the field unconditionally — without
the guard, editing a typed service through the services list writes a document the extension's own
schemas reject.

➕ [decision] `$schema` is rewritten only when the name changes. Rewriting it on every typed write
would stamp the current config's url on a file belonging to another app in a multi-app workspace,
and `ProjectConfigService.getConfig()` has no per-file variant.

⚠️ A conversion deletes the document the service editor has open, so that webview's `fileUri` is
stale for any further request. `updateService` re-reads through the returned uri, so the response
that triggered the conversion is correct, but the panel is left pointing at a deleted file. Reopening
the editor is not in this task's checkboxes and cannot be exercised from the jest host; the
Post-Completion step that edits an old-format service covers it.

➕ Two test files beyond the plan's one: the plan's `tests/serviceApiModify.conversion.test.ts` covers
the webview create path and every conversion case, while the command-palette create path went into the
existing `tests/web/response/fileApiImpl.serviceTypes.test.ts` and the two saveSystem paths into
`tests/api-services/SystemService.serviceTypes.test.ts` and `tests/api-services/EnvironmentService.test.ts`.
`tests/extension.deleteService.test.ts` grew the both-files delete, and `tests/serviceFileType.test.ts`
the three new helpers. `tests/helpers/mocks.ts` gained `joinUriPath` (a `Uri.joinPath` stub that
resolves `..`, which the writer needs) and the typed `schemaUrls` entries.

➕ Every mutation of the changed logic was checked to go red: both create paths back to
`extensions.service`, both create paths restating the type in the content, both stamping the legacy
schema url, the conversion skipping its delete, keeping `integrationSystemType`, returning the file it
came from, and keeping the stale `$schema`; the target name built from the first dot-free segment;
`serviceSchemaUrlForType` collapsed to the legacy url; `allServiceExtensions` reordered legacy-first;
the delete no longer collecting same-id siblings, and collecting siblings regardless of id; and
`SystemService`/`EnvironmentService` back to `fileApi.writeMainService`.

### Task 6: Make the service type immutable

**Files:**
- Modify: `vscode-extension/src/web/response/serviceApiModify.ts` (121-134)
- Modify: `ui/src/api/apiTypes.ts`
- Modify: `ui/src/api/api.ts` (396)
- Modify: `ui/src/api/rest/restApi.ts` (1593)
- Modify: `ui/src/components/services/detail/ServiceParametersTab.tsx` (102, 186-203)
- Modify: `ui/src/components/services/detail/ServiceEnvironmentsTab.tsx` (171-175)
- Modify: `ui/src/components/services/ServicesList.tsx` (308-314)
- Create: `ui/tests/components/services/detail/ServiceParametersTab.type.test.tsx`

- [x] drop the `integrationSystemType` and `type` write paths from the extension's `updateService` (`:121-131`)
- [x] **keep protocol validation alive**: `validateAllowedSystemProtocol` runs only inside the `type` branch today, and the `protocol` branch at `:132-134` is unchecked — move the call onto the protocol path
- [x] add `SystemUpdateRequest = Omit<Partial<IntegrationSystem>, "type">` and use it for `updateService`; leave `SystemRequest` alone, it is create-only and needs `type`
- [x] while in `ServiceEnvironmentsTab.tsx`, leave the EXTERNAL-only "Add Environment" gate at `:497` as it is — the backend now enforces one environment for INTERNAL and IMPLEMENTED on the REST path too, and that gate is what keeps the limit unreachable from the UI
- [x] remove `type` from the payload at `ServiceParametersTab.tsx:102` on both paths
- [x] remove `type` from the object literal at `ServiceEnvironmentsTab.tsx:171-175` — as an excess property it is a hard `check-types` failure, not a warning
- [x] stop `ServicesList.tsx:308-314` from spreading `type` into the payload; it compiles today but still sends the field
- [x] replace the VS Code-only type `Select` (`:186-203`) with a read-only display; the web build renders no type field at all today, so pick the presentation deliberately rather than copying it
- [x] verify `CreateServiceModal` still sets the type at creation — that path stays
- [x] write a test: the parameters form renders the type read-only and submits no type field, on both the web and VS Code paths
- [x] write a test: a null type renders as a dash/Unknown in the read-only display, not a crash or an empty control — a mixed backend DB can hold pre-plan rows
- [x] write a test: the extension ignores a type in an update payload and leaves the file's type unchanged
- [x] write a test: an update with a protocol the service type forbids is rejected
- [x] run `check-types` in the UI workspace, then tests in both — must pass before task 7

➕ [decision] The read-only presentation is a `Descriptions.Item` labelled `Type`, placed beside the existing
read-only `Protocol` item and above the labels field. The two fields are the pair the backend validates together, so
they read as one block, and an absent type falls back to the same `-` the protocol item already uses. Both frontends
render it — the web build showed no type at all before, which left the services list as the only place the type was
visible.

➕ [deviation] One test file beyond the plan's list: `ui/tests/components/ServicesList.test.tsx` grew a case for the
label-edit payload. The plan asks to stop that path from spreading `type`, which is a behaviour change and needs a
test; the file's `ServicesTreeTable` mock now captures the options it is handed so the test can call `onUpdateLabels`
the way the real table would.

➕ `ui/src/api/rest/vscodeExtensionApi.ts` also moved to `SystemUpdateRequest`, beyond the plan's file list. Both
`ApiClient` implementations have to match the interface, and leaving the webview one on `Partial<IntegrationSystem>`
would keep the wider payload compiling on the VS Code path.

➕ Every mutation of the changed logic was checked to go red: the parameters payload restating `system.type`; the
type display losing its null guard, and dropped entirely; the VS Code type `Select` restored; `ServicesList`
spreading the whole record again; `ServiceEnvironmentsTab` restating `type` (a `check-types` failure — and with
`SystemUpdateRequest` widened back to `Partial<IntegrationSystem>` it compiles again, so the `Omit` is what catches
it); the extension writing `integrationSystemType` from the request again; the `validateAllowedSystemProtocol` call
removed; the type read from `content.integrationSystemType` instead of `resolveServiceType`; and the stored protocol
validated instead of the incoming one.

### Task 7: Bump the claimed migration version

**Files:**
- Modify: `vscode-extension/src/web/services/importMigrationVersions.ts`
- Modify: `vscode-extension/tests/services/importMigrationVersions.test.ts` (exists — extend, do not create a duplicate)

- [x] confirm plan 1 registered `V105ServiceImportFileMigration` before touching this file
- [x] set `SERVICE_MIGRATIONS` to `"[100, 101, 102, 103, 104, 105]"`
- [x] extend the comment: 105 is a compatibility barrier, not a transformation, so the claim must match the backend's registry exactly
- [x] leave `MCP_SERVICE_MIGRATIONS` and `CHAIN_MIGRATIONS` alone
- [x] write a test asserting a newly written service file claims the full list
- [x] extend the existing `repairMigrationsClaim` suite — it already covers claim preservation; add only the `[100..105]` case rather than duplicating its tests
- [x] run tests — must pass before task 8

➕ Verified rather than assumed: `V105ServiceImportFileMigration` is a `@Component` with `getVersion() == 105` and
`isIdempotent() == true`, and `MigrationBeanRegistrationTest.serviceMigrationsAreRegistered` scans the classpath for
every `ServiceImportFileMigration` bean, so V100–V105 is the registry the claim now mirrors.
`FileMigrationService.migrate` is what makes an exact match mandatory: it throws on
`documentVersions − migrationVersions` and runs `migrationVersions − documentVersions`.

➕ [deviation] Two test files beyond the plan's one. The "newly written service file" checkbox needs a write site, and
the two create paths share no code: the webview one went into `tests/serviceApiModify.conversion.test.ts` and the
command-palette one into the existing `createEmptyService` case in
`tests/web/response/fileApiImpl.serviceTypes.test.ts` (one line, covering all four types it writes). Both assert the
constant; the literal `[100..105]` is pinned once, in the `repairMigrationsClaim` suite.

➕ A conversion still preserves an older claim — `repairMigrationsClaim` only fills a missing or empty one, and
`tests/serviceApiModify.conversion.test.ts` already pins that a converted legacy file keeps its `"[100, 101]"`, so the
backend migrates it through 102–105.

➕ Every mutation of the changed logic was checked to go red: `SERVICE_MIGRATIONS` back to `[100..104]`; the webview
create path stamping an empty claim; the command-palette create path stamping `MCP_SERVICE_MIGRATIONS`.

### Task 8: Group services by type in the explorer

**Files:**
- Modify: `vscode-extension/src/web/qipExplorer.ts`
- Create: `vscode-extension/tests/qipExplorer.grouping.test.ts`

- [x] add `"service-group"` to `QipExplorerItem.type` and return `element.children ?? []` for it in `getChildren`
- [x] make `getServices()` return group nodes, each holding its services
- [x] omit an empty group rather than rendering it
- [x] extend service-file discovery at `:217` to the three new postfixes via `isAnyServiceFile`
- [x] keep sorting stable: groups in a fixed order, services by label within a group
- [x] write tests: services land in the right groups, empty groups are absent, ordering is deterministic
- [x] write a test: a group node carries no `fileUri`, so no reveal command is attached to it
- [x] run tests — must pass before task 9

➕ Group labels are `External`, `Internal`, `Implemented`, `Context`, `MCP` — the plan's target tree — plus the
`Unknown` bucket Task 9 keeps. Unknown has to exist from this task on: the walker already resolves a type-less
`.service.` file to `"Unknown"`, and a bucket-less grouping would drop it. A type no group claims (a hand-edited
`integrationSystemType`) lands there too, while the service item keeps stating the raw value in its description.

➕ [decision] Discovery reads `isAnyServiceFile(name, ext) || endsWith(contextService) || endsWith(mcpService)`.
`isAnyServiceFile` covers the four plain names only, and the tree lists all five kinds; `allServiceExtensions` would
answer the same question in one call, but spelling the two special kinds out keeps the tree's coverage visible at the
call site.

➕ [decision] A group is `Expanded` and carries a `N services` count as its description. Grouping otherwise costs a
second click to reach any service, and the count is the same shape the chain nodes already use.

➕ [decision] The per-service icon `switch` became a `SERVICE_ICONS` map with the same values and the same `server`
default, so a group and its services cannot drift apart. The `Unknown` group therefore shows `server`, as its services
already did — no separate unknown icon.

➕ [decision] `qipExplorer.ts` stays out of `collectCoverageFrom` in `jest.config.cjs`. No threshold is configured, and
adding a file the suite only partly covers would lower the reported number without telling anyone anything.

➕ [deviation] Which group a **typed name** lands in is not asserted yet: this task widened discovery only, and the
name-over-field precedence is Task 9's change. The typed-name cases assert the service is present in the tree, which
holds before and after that inversion; the grouping cases state the type in the body. Task 9 adds the name-wins cases.

➕ Every mutation of the changed logic was checked to go red: discovery back to the legacy triple; `isTreeServiceFile`
dropping its context/mcp arms; empty groups rendered; group order taken from discovery order; services unsorted inside
a group; `serviceGroupType` returning the raw type; a group node carrying a `fileUri`; `getChildren` dropping a group's
children; the group icon hardcoded; the group count always plural.

### Task 9: Read the type from the file name in the tree

**Files:**
- Modify: `vscode-extension/src/web/qipExplorer.ts` (229-235)
- Modify: `vscode-extension/tests/qipExplorer.grouping.test.ts`

- [ ] replace the inline suffix chain with `serviceTypeFromUri`, keeping `content.integrationSystemType` as the fallback — this **inverts** today's precedence, so treat it as a behaviour change, not a refactor
- [ ] keep an `Unknown` bucket for a file whose type resolves from neither source, so a broken file stays visible instead of vanishing — tolerant editor, strict backend is a deliberate pairing, and the backend end of it is stricter than it first looked: such a file is an error row in the import **preview** as well as on commit, and a file whose name and `content.integrationSystemType` disagree is refused rather than resolved by the name (`ServiceDeserializer.resolveServiceType`)
- [ ] write a test: an old-format file is grouped from its field
- [ ] write a test: a new-format file whose body disagrees with its name is grouped by the name
- [ ] write a test: an unparseable file appears under `Unknown` rather than disappearing
- [ ] run tests — must pass before task 10

### Task 10: Offline end-to-end check in the web host

**Files:**
- Create: `vscode-extension/src/web/test/suite/serviceTypes.test.ts`

- [ ] open a fixture workspace with services of all five kinds in the new format; assert each opens in its own editor
- [ ] open a fixture workspace of old-format files; assert it works with no user action, then edit one and assert conversion — and that the now-mixed workspace still lists every service (tree and discovery over old + new side by side)
- [ ] add a fixture with a non-default `.config.qip.yaml` and assert type resolution still works
- [ ] keep the suite offline — `vscode-test-web` has no network
- [ ] run `npm -w @netcracker/qip-vscode-extension run test:integration` — must pass before task 11

### Task 11: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented
- [ ] verify the explorer tree matches the target layout, including empty-group and unknown-type edge cases
- [ ] verify the UI service list and tree render all five types and expose no type control (`ServicesTreeTable.tsx:160` is display-only and should need no change — confirm)
- [ ] run `npm test --workspaces --if-present`
- [ ] run `npm -w @netcracker/qip-vscode-extension run check-types` and `run lint`
- [ ] run the integration suite
- [ ] verify coverage did not drop below the project standard in either workspace

### Task 12: [Final] Update documentation

- [ ] update `vscode-extension/CLAUDE.md`: the new extensions, `serviceFileType.ts` as the single resolver, the two create paths, the conversion-on-first-write rule, the tree grouping, why the file name rather than `$schema` carries the type, and the deliberate asymmetry — a type-less file stays visible under `Unknown` here while the backend refuses it on import
- [ ] update `ui/CLAUDE.md` if the service-type model changed anything a reader would not expect
- [ ] update `vscode-extension/README.md` and both blocks of `.config.qip.yaml.example`
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification:**

- Export services of all three types from a running runtime-catalog, open the archive in the extension, re-write one,
  and import it back. This cannot live in the integration suite — that harness is offline. Use the **current** export
  format: `QIP_EXPORT_LEGACY_FORMAT=true` is a downgrade path for plain services only, and nothing discovers a legacy
  flat context or MCP name.
- Import a service whose id starts with `service-` (autodiscovery mints those from Kubernetes service names), edit it
  in the extension, and import it back — the file name is `service-orders.external-service.qip.yaml`, which both
  sides read as current-format.
- Open a real workspace with services of all five kinds; confirm the tree groups them and each opens in its own editor.
- Open a project with only old-format files; confirm nothing breaks, then edit one service and confirm git shows a
  rename rather than an add plus a delete.
- Open a project with a custom `appName` and confirm both the tree and the backend import resolve the type (type
  only — group discovery is appName-scoped on the backend, a pre-existing limitation).
- Confirm the type is not editable anywhere in either frontend.

**External system updates:**

- `@netcracker/qip-schemas`, `@netcracker/qip-ui`, and `@netcracker/qip-vscode-extension` need releases in that order
  before consumers outside the monorepo see the change.
- `qubership-integration-help` may need screenshots updated for the grouped services tree.
