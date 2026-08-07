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
   └─ Unknown
      └─ legacy-HTTP-4f0a12b7…
```

The groups are `External`, `Internal`, `Implemented`, `Context`, `MCP` and `Unknown`, always in that order. An empty
group is omitted, which is why the drawing shows three. `Unknown` holds a file whose type neither its name nor its body
states.

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

➕ [deviation] Four test files beyond the plan's list: `ui/tests/components/ServicesList.test.tsx`,
`ui/tests/components/services/detail/ServiceParametersTab.test.tsx`, `vscode-extension/tests/response/serviceApiModify.test.ts`
and `vscode-extension/tests/serviceApiModify.conversion.test.ts`. `ServicesList.test.tsx` grew a case for the
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

➕ [deviation] *(closed in Task 9)* Which group a **typed name** lands in is not asserted yet: this task widened
discovery only, and the name-over-field precedence is Task 9's change. The typed-name cases assert the service is
present in the tree, which holds before and after that inversion; the grouping cases state the type in the body. Task 9
adds the name-wins cases.

➕ Every mutation of the changed logic was checked to go red: discovery back to the legacy triple; `isTreeServiceFile`
dropping its context/mcp arms; empty groups rendered; group order taken from discovery order; services unsorted inside
a group; `serviceGroupType` returning the raw type; a group node carrying a `fileUri`; `getChildren` dropping a group's
children; the group icon hardcoded; the group count always plural.

### Task 9: Read the type from the file name in the tree

**Files:**
- Modify: `vscode-extension/src/web/qipExplorer.ts` (229-235)
- Modify: `vscode-extension/tests/qipExplorer.grouping.test.ts`

- [x] replace the inline suffix chain with `serviceTypeFromUri`, keeping `content.integrationSystemType` as the fallback — this **inverts** today's precedence, so treat it as a behaviour change, not a refactor
- [x] keep an `Unknown` bucket for a file whose type resolves from neither source, so a broken file stays visible instead of vanishing — tolerant editor, strict backend is a deliberate pairing, and the backend end of it is stricter than it first looked: such a file is an error row in the import **preview** as well as on commit, and a file whose name and `content.integrationSystemType` disagree is refused rather than resolved by the name (`ServiceDeserializer.resolveServiceType`)
- [x] write a test: an old-format file is grouped from its field
- [x] write a test: a new-format file whose body disagrees with its name is grouped by the name
- [x] write a test: an unparseable file appears under `Unknown` rather than disappearing
- [x] run tests — must pass before task 10

➕ The call is `resolveServiceType(name, serviceData, ext)` rather than `serviceTypeFromUri` plus a hand-written
fallback: Task 4 already put that exact precedence in `serviceFileType.ts`, and both read surfaces go through it.
The tree adds only the `|| UNKNOWN_SERVICE_TYPE` bucket, because `resolveServiceType` answers `""` when neither
source states a type. This closes Task 8's deviation — a service written by Task 5, which states its type in the
name and no longer writes `integrationSystemType`, grouped under `Unknown` until now.

➕ The label branch that drops the protocol for a context or MCP service now reads the resolved type instead of the
raw field, so the label and the group cannot disagree. The two string literals became
`IntegrationSystemType.CONTEXT`/`.MCP`, which the enum-typed `resolveServiceType` result requires.

➕ [decision] "Unparseable" is read as *no type from either source* — a legacy `.service.` name whose body carries
none — which is what the checkbox above it describes. A file whose YAML the parser rejects outright is dropped by
the pre-existing `catch` around `parseContentFromFile`, a level above the type detection this task changes; giving
it a tree node would mean synthesizing an id from the file name, which is neither in this task's file range nor in
its checkboxes.

➕ Four mutations checked red: the field-first precedence restored (9 cases), the field fallback dropped at the call
site (the legacy case), the `Unknown` fallback dropped (the type-less case), and the label branch reading the body
type again (the context-label case). The two `test.each` tables are the ones that hold only after the inversion —
each asserts both the group and the service description, so a name-derived type that never reaches the item shows up.

### Task 10: Offline end-to-end check in the web host

**Files:**
- Create: `vscode-extension/src/web/test/suite/serviceTypes.test.ts`

- [x] open a fixture workspace with services of all five kinds in the new format; assert each opens in its own editor
- [x] open a fixture workspace of old-format files; assert it works with no user action, then edit one and assert conversion — and that the now-mixed workspace still lists every service (tree and discovery over old + new side by side)
- [x] add a fixture with a non-default `.config.qip.yaml` and assert type resolution still works
- [x] keep the suite offline — `vscode-test-web` has no network
- [x] run `npm -w @netcracker/qip-vscode-extension run test:integration` — must pass before task 11

➕ Four fixture projects beyond the plan's file list, the ones the Testing Strategy names:
`tests/fixtures/service-projects/{new-format,old-format,mixed,custom-config}/`, eleven services in all.
`package.json`'s `test:integration` now mounts `tests/fixtures/service-projects` as the workspace — the
harness took no folder before, so the suite had no file system to read.

➕ [deviation] One mount, four projects. `vscode-test-web` opens exactly one folder per run, and the
suite runs inside that one host, so the four fixture projects are subfolders of the mounted root rather
than four separate workspaces. Discovery is workspace-wide, which makes the "old + new side by side"
assertion stronger, not weaker: every listing case asserts the full set of eleven ids.

➕ [decision] The custom-config fixture keeps its own `.config.qip.yaml` and is loaded through
`ProjectConfigService.loadConfigFromUri` — the same entry point `ConfigApiProvider` uses — because
`loadWorkspaceConfig` only ever reads the config at the workspace root. The test asserts both halves:
the type resolves from the `.acme.yaml` name before any config is loaded (the app name is read off the
name), and after loading, the extensions and the schema URL a write would stamp come from the project's
own file.

➕ [decision] The offline checkbox is pinned rather than assumed: the suite wraps `fetch` and fails if
anything leaves the local test server. It cannot assert *zero* calls — vscode-test-web serves the mounted
workspace over `http://localhost:3000/static/mount/…`, so the file system itself runs on `fetch`.

⚠️ *(Task 5's warning confirmed, and it is the milder of the two possible outcomes.)* After a conversion
the editor tab still points at the deleted legacy file; VS Code does not follow the rename. The suite
pins both halves — the tab holds the old path, and no tab holds the new one — so a future change in
either direction shows up. Reopening the service is the fix, and the Post-Completion manual step covers
what the user sees. Nothing throws, and the response that triggered the conversion is still correct.

➕ The suite found a live defect and Task 10 fixes it: `sendThemeToWebview` threw
`Error: Webview is disposed` whenever a QIP editor was closed within 300 ms of opening, because
`enrichWebview` schedules a delayed theme push and nothing checked the panel first. It reached the host
as an uncaught error. The post now goes through a `try`/`catch` — a disposed panel has nothing left to
theme. Out of the plan's file list, but the suite could not stay green around it.

➕ Every test was proved to have teeth by mutating the code it covers and confirming red: the
`*.external-service.qip.yaml` selector removed from `contributes.customEditors` (the editor test, which
therefore exercises VS Code's own matcher rather than `getEditorViewTypeForUri`); the conversion skipping
its delete (the conversion and the re-listing tests); empty groups rendered instead of omitted (the tree
test); `serviceSchemaUrlForType` collapsed to the legacy URL (the conversion and custom-config tests);
and `plainServiceExtensions` dropping the legacy name (the old-format and re-listing tests).

### Task 11: Verify acceptance criteria

- [x] verify all requirements from Overview are implemented
- [x] verify the explorer tree matches the target layout, including empty-group and unknown-type edge cases
- [x] verify the UI service list and tree render all five types and expose no type control (`ServicesTreeTable.tsx:160` is display-only and should need no change — confirm)
- [x] run `npm test --workspaces --if-present`
- [x] run `npm -w @netcracker/qip-vscode-extension run check-types` and `run lint`
- [x] run the integration suite
- [x] verify coverage did not drop below the project standard in either workspace — neither workspace configures one, so this is recorded as a measurement rather than a pass

**Deliverable 1 — the extension reads and writes the new per-type files.** Reads: `plainServiceExtensions` and
`allServiceExtensions` (`serviceFileType.ts:143,155`) put the typed names ahead of the legacy one, and
`findServiceFileById` / `findServiceFiles` (`serviceFileLookup.ts`) search the whole set. `getFileType`
(`fileApiImpl.ts:858-895`) classifies a typed file and a folder holding one. No `endsWith(ext.service)` and no
`findFileById(id, ext.service)` remain anywhere under `src/web/`. Writes: both create paths — `serviceApiModify.createService`
and `fileApiImpl.createEmptyService` (`:835-846`) — build the name through `serviceExtensionForType` and stamp
`serviceSchemaUrlForType`, and `writeServiceInCurrentFormat` (`serviceFileWrite.ts`) is the conversion: it writes the typed
file, deletes the legacy sibling, drops `content.integrationSystemType`, and leaves the folder name alone. The config
surface carries all six extension keys and all six `schemaUrls` in `configs/default.config.qip.yaml` and in **both** blocks
of `.config.qip.yaml.example`, and `package.json` registers all seven custom editors.

**Deliverable 2 — the QIP explorer groups services by type.** `QipExplorerItem.type` has `"service-group"`
(`qipExplorer.ts:21`), `getChildren` returns `element.children ?? []` for it (`:119-120`), `SERVICE_GROUPS` fixes the order
(`:28-35`), and `buildServiceGroups` (`:255-281`) builds the level.

**Deliverable 3 — the service type is read-only after creation.** Extension: `serviceApiModify.updateService` has no
`type` or `integrationSystemType` write path left, and `validateAllowedSystemProtocol` now sits on the protocol branch,
checking the incoming protocol against `resolveServiceType(serviceFileUri, service)`. UI: `SystemUpdateRequest`
(`apiTypes.ts:1096`) is the payload type in `api.ts:399`, `restApi.ts:1596` and `vscodeExtensionApi.ts:499`;
`ServiceParametersTab.tsx:96` and `ServicesList.tsx:312` both destructure the type out; `ServiceParametersTab.tsx:175-179`
renders it as a read-only `Descriptions.Item` with a `-` fallback. A search of `ui/src/components/services/` finds no
`Form.Item` bound to `type` — `CreateServiceModal` imports neither `Select` nor `Radio`, and its form values carry no
`type`. Creation still sets it: `ServicesList.getSystemType(tab)` feeds `api.createService({ name, description, type })`
(`:377-404`).

**Plan 1's constraints, each against the code or test that holds it.** A created service gets a dot-free id
(`tests/serviceApiModify.conversion.test.ts:196`). A converted dotted-id service keeps its folder name (`:301`). `$schema`
still comes from the project config and is rewritten only when the name changes (`serviceFileWrite.ts:50-55`).
`SERVICE_MIGRATIONS` is `"[100, 101, 102, 103, 104, 105]"` and `MCP_SERVICE_MIGRATIONS` is untouched at `"[100]"`
(`importMigrationVersions.ts:17,19`). The "Add Environment" button is still EXTERNAL-only
(`ServiceEnvironmentsTab.tsx:496`). No caller relies on PUT-as-create: `updateService` is reached only from sites holding
a loaded id.

➕ The tree matches the target layout, with two divergences from the Overview's drawing that the plan's own later text
already settles. The drawing shows `Implemented`, `Context` and `MCP` as childless nodes, but the Solution Overview and
Task 8 both say an empty group is omitted; the code omits (`qipExplorer.ts:262-264`) and
`tests/qipExplorer.grouping.test.ts:223` pins it. The drawing also has no `Unknown` group, which Task 8 added and Task 9
needs; `SERVICE_GROUPS:34` places it last, after `MCP`. The normative rules win — the drawing states the group set, not the
render rule. Unknown-type edge cases hold: a type no group claims is bucketed by `serviceGroupType` (`:51-55`) while the
item keeps stating the raw value in its description, covered by `:359` and `:377`.

➕ `ServicesTreeTable.tsx:160` confirmed display-only and unchanged, and the confirmation goes further than the plan
assumed. The `switch (record.type)` sits inside `getIcon` and only picks an icon; the table declares no `type` column among
its twenty and no control bound to one. `ServicesTreeTable` is rendered from exactly two places — `ServicesList`, which
filters rows to `s.type === getSystemType(tab)` for the three plain tabs, and `ServiceApiSpecsTab`, whose rows are
`ApiGroup` and `Api`. `ContextServiceList` and `McpServiceList` import only the `ServiceEntity` type and render their own
tables. So neither `MCP` nor `CONTEXT` ever reaches that switch: the missing `MCP` arm is unreachable rather than a defect,
and the `CONTEXT` arm at `:167` is already dead. Pre-existing, out of scope, left alone. All five types render through
`ServiceListPage.tsx:9-20`, which routes the location hash: `mcp` to `McpServiceList`, `external`/`internal`/`implemented`
to `ServicesList`, `context` to `ContextServiceList`.

➕ [decision] Coverage is reported as a measurement, not as a pass. Neither `ui/jest.config.ts` nor
`vscode-extension/jest.config.cjs` sets `coverageThreshold` — the UI file leaves it commented out at `:49` — so there is no
local gate to drop below. The enforced standard is SonarCloud's quality gate, which both `ui-build.yaml` and
`vscode-extension-build.yaml` wait on (`-Dsonar.qualitygate.wait=true`), and that gate is remote and unevaluable offline.
Measured: UI 58.34% statements / 48.68% branches / 48.79% functions / 58.71% lines; extension 58.49 / 49.3 / 56.9 / 58.56.
This plan's new modules are at or near full coverage — `serviceFileType.ts`, `importMigrationVersions.ts` and
`editorViewTypes.ts` at 100% on all four counters, `serviceFileLookup.ts` at 100/75/100/100, `serviceFileWrite.ts` at
95.65/100/100/95.65, `ServiceParametersTab.tsx` at 89.88/81.25/89.47/93.9. No test file was deleted across Tasks 1-10
(`git diff --diff-filter=D 16cfbc92d~1..HEAD` over `ui/` and `vscode-extension/` is empty), so no line that was covered
before lost its test.

➕ [finding] `npm test --workspaces` reports two failures, both in `@netcracker/qip-schemas` and neither from this plan:
`chain/context-storage-ttl__SHOULD_FAIL.yaml` and `chain/scs-sender-ttl__SHOULD_FAIL.yaml` validate clean against
`chain.schema.yaml`. Both sample files are **untracked** in the working tree, and Tasks 1-10 touched no file under
`schemas/`. Re-running the workspace with only those two files moved aside gives 130/130 passing, so the committed tree is
green. Left in place as someone else's work in progress.

### Task 12: [Final] Update documentation

- [x] update `vscode-extension/CLAUDE.md`: the new extensions, `serviceFileType.ts` as the single resolver, the two create paths, the conversion-on-first-write rule, the tree grouping, why the file name rather than `$schema` carries the type, and the deliberate asymmetry — a type-less file stays visible under `Unknown` here while the backend refuses it on import
- [x] update `ui/CLAUDE.md` if the service-type model changed anything a reader would not expect
- [x] update `vscode-extension/README.md` and both blocks of `.config.qip.yaml.example`
- [x] move this plan to `docs/plans/completed/` — done by the harness after the last task, so the file is still under
  `docs/plans/` while the plan is being worked on

➕ `vscode-extension/CLAUDE.md` gained four blocks in **Architecture** — "Service types live in the file name" (the
resolver and its whole-extension compare), "Conversion on first write" (the three call sites), the known stale-tab
behaviour, and "The explorer groups services by type" — plus the seven custom editors and `getEditorViewTypeForUri` in
the editors paragraph, `editorViewTypes.ts` / `serviceFileType.ts` / `serviceFileLookup.ts` / `serviceFileWrite.ts` in
**Project Structure**, the six service keys and the `plainServiceExtensions` precedence in **Platform Context**, and the
disposed-webview guard in the messaging paragraph. Written against the code rather than against this plan: five claims
were re-read at the source and two were corrected before commit (below).

➕ [deviation] `.config.qip.yaml.example` needed no edit — Task 1 already added the three `extensions:` and three
`schemaUrls:` entries to **both** the `qip` and `pip` blocks, and `configs/default.config.qip.yaml` likewise. The
checkbox is satisfied by that work; re-stating it here would have been a no-op diff.

➕ `vscode-extension/README.md` had no file-format content at all, so it gained a short user-facing **Service files**
section: the five typed names, the type being read-only after creation, the tree grouping, the legacy name and its
convert-on-edit rename, the stale editor tab, and the pointer to `.config.qip.yaml.example`.

➕ `ui/CLAUDE.md` gained two bullets under **API layer**. The first pins that the type is immutable after creation and
that `SystemUpdateRequest` only catches an *object literal* restating it — a spread still compiles and still sends the
field, which is why `ServiceParametersTab` and `ServicesList` each destructure `type` out. The second records that
`ServicesTreeTable.getIcon`'s `switch` never receives `MCP` or `CONTEXT`, so its missing `MCP` arm is unreachable rather
than a defect.

➕ [decision] The module `CLAUDE.md` files were edited on disk despite `.claude/rules/apm-authoring.md` reserving
`CLAUDE.md` for `apm compile`. They are not APM output here: `apm.yml` includes only `.apm/instructions/` and
`.apm/skills/` (which compile to `.claude/rules/`), the repo has no `AGENTS.md`, and no module `CLAUDE.md` carries a
generated marker. `vscode-extension/CLAUDE.md` is additionally listed in `.git/info/exclude`, so it stays out of the
commit — recorded here rather than forced in.

➕ Two claims drifted between the plan text and the code, and were fixed against the source: `CreateServiceModal` binds
no `Form.Item` to `type` at all (creation reads the type off the active tab through `ServicesList.getSystemType(tab)`),
and `$schema` decides a context or MCP document only **together with** the file name
(`ServiceTypeFiles.isContextOrMCPServiceFile`) rather than on its own. Also verified at the source before writing: the
seven `contributes.customEditors` patterns, the three `writeServiceInCurrentFormat` call sites and their enclosing
method names, `SERVICE_GROUPS`' six-entry order, `sendThemeToWebview`'s `try`/`catch` against `enrichWebview`'s 300 ms
repeat, `collectServiceOwnedFiles` collecting the same-id sibling, and `SystemUpdateRequest`'s three consumers.

### Task 13: [Review] Address the code-review findings

- [x] **the stale-uri family.** A conversion deletes the file the caller is holding. Three sites now follow it:
  `SystemService.saveSystem` returns the file the service landed in, `SpecificationImportService` re-points itself and
  its `ApiGroupService` from that return value (its own protocol write is what converts the file mid-import, so the
  failure fired on the first spec import into any legacy service), and `enrichWebview` subscribes to the new
  `onServiceFileMoved` so a tab dispatches later messages against the file the service moved to. Independently,
  `serviceApiRead.readServiceFile` retries a failed read against the file the id resolves to, which covers any caller
  holding a stale uri.
- [x] **dotted ids.** `a.b.external-service.qip.yaml` states the id `a` and resolves no type, while the same write
  dropped `content.integrationSystemType` — a document the backend refuses in the preview and on commit. Such a service
  now keeps the legacy name **and** the field: `fileNameStatesType` is `fitsCurrentFormatFileName` (one dot-free
  segment), it gates both the rename and the field drop, and the test that pinned the rename was inverted.
- [x] **`isServiceType` used `in`**, so `toString`, `constructor` and `__proto__` passed as service types and produced a
  file named `svc-1undefined` with `$schema: undefined`, deleting the original after it. Now
  `Object.prototype.hasOwnProperty.call`, with the inherited keys in the test tables.
- [x] **tests that covered nothing.** Four `serviceApiRead` sites could revert to the pre-#553 blind spot with the whole
  suite green, because no fixture paired a typed service name with `resources/`, an api group and an api. That fixture
  now exists and drives `getEnvironment`, `getSpecificationModel`, `getOperations` and `getOperationInfo`;
  `test:integration` is wired into `vscode-extension-build.yaml`, and `qipExplorer.ts` into `collectCoverageFrom`.

➕ [deviation] The review's own gate — "break the code and confirm the test goes red" — found a regression the review
fixes had introduced. Gating the **resolved** type through `isPlainServiceType` also caught a name-stated `CONTEXT` or
`MCP`, so `writeServiceInCurrentFormat` renamed every context and MCP file to `.service.` and deleted the original on
the next edit, reachable from `updateContextService` and `updateMcpService`. The gate now applies to the
**body**-stated type only: the name wins whenever it states a kind, and only a body-stated type may promote a legacy
name, to one of the three plain types. Two cases added to `tests/serviceApiModify.conversion.test.ts`; both go red
under the previous shape, while the "a plain service whose body claims CONTEXT or MCP stays legacy" cases stay green,
which is what shows the original intent survived.

➕ Mutations checked red for every behaviour these fixes claim: the importer keeping its stale uri; the webview
dispatching on the uri it was opened with; `readServiceFile` rethrowing instead of resolving by id;
`serviceFileNameForType` renaming a dotted id anyway; `fileNameStatesType` ignoring dots; `isServiceType` back to `in`;
`findServiceFileById` and `isAnyServiceFile` back to the legacy extension alone (15 and 5 failures — the blind spot
finding 4 named); the explorer dedup dropped; `SystemService.saveSystem` returning the file it read rather than the one
it wrote; and the context/MCP gate above.

➕ The README and the five help pages said to reopen a service after its first edit. That was true before the webview
re-point and is not now, so all six say the stale tab name is cosmetic instead.

## Review phase 2 — code smells, conventions, style

*Findings from the smells pass over `4e73fd235..2afaa41df`. Two MAJOR, 21 MINOR; the dispositions below are the
authoritative record of what changed and what was declined.*

➕ [decision] `resolveServiceType` now returns `IntegrationSystemType | undefined` instead of casting `""` into the
enum. It also validates a body-stated type against `EXTENSION_KEY_BY_TYPE`, so a legacy document whose
`content.integrationSystemType` holds an unrecognized string reads as untyped rather than leaking that string into the
tree label and the DTO. Every caller was checked, not only the two the review named: `qipExplorer`, `serviceApiModify`
(the protocol validation, whose parameter is already optional), `serviceApiRead.getService`,
`SystemService.getSystemById`, plus the integration suite and the unit tests. `IntegrationSystem.integrationSystemType`
became optional to match — the extension really can hold a service with no type, which is the whole point of the
`Unknown` group.

➕ [decision] Comment trimming kept every invariant four review rounds established and cut the prose around it:
the whole-extension end-anchored compare, the dotted-id legacy name, the context/MCP gate on the **body**-stated type
(the data-loss bug already made once), the write-then-delete order, and the per-app `$schema` source all survive as
one- or two-line notes. Where `vscode-extension/CLAUDE.md` already carries the rationale, the module now points at it
rather than keeping a second copy that would drift.

➕ [decision] The `readServiceFile` consolidation was taken only for the three **read** sites. `getService`,
`getEnvironment` and `getEnvironments` now share a module-local `readServiceFileById`, which is `readServiceFile` plus
the id-mismatch retry the three repeated verbatim. `updateService` keeps the plain `readServiceFile` and its own
`throw`: folding the retry in there would silently redirect a **write** to another file, which is a behaviour change,
not a smell fix. The stale-uri family was re-run afterwards — unit, integration, and the conversion suite all green.

➕ [decision] `SERVICE_ICONS` is now `Record<ServiceGroupType, string>`, the same enum-keyed guard `serviceFileType.ts`
carries, and `Unknown` gained its own `question` icon instead of reusing `server` and reading as a second Context group.
`serviceGroupType` collapsed to `serviceType ?? UNKNOWN_SERVICE_TYPE` now that `resolveServiceType` cannot return an
out-of-enum value.

➕ [decision] The eight `registerCustomEditorProvider` blocks became one loop over `DEFAULT_EDITOR_VIEW_TYPES`, which
also makes that export earn itself — it had no production consumer, only the test. `activate()` therefore drops out of
the "four surfaces with no compile-time link to the type map" list in CLAUDE.md.

➕ [decision] The duplicate `isTreeServiceFile` / `isServiceFileName` predicates were replaced by one exported
`isServiceFileOfAnyKind` on `serviceFileType.ts`, the module CLAUDE.md declares the single service-file resolver.

➕ [decision] `validateAllowedSystemProtocol` takes `protocol?: string` now. The `protocol as ApiSpecificationType`
cast at the call site asserted a validation that had not happened, on the line whose job is to validate; the membership
check inside the function is what decides, and it works on a plain string.

➕ Three test files moved to the src-mirroring layout: `tests/response/file/serviceFileType.test.ts`,
`tests/response/serviceApiModify.conversion.test.ts`, `tests/web/response/fileExtensions.serviceTypes.test.ts`. Jest
picks them all up (61 suites, unchanged); the relative depths and the `__dirname`-based config-file reads were fixed
with them.

➕ `QIP_SCHEMA_URLS` joins `QIP_FILE_EXTENSIONS` in `tests/helpers/mocks.ts` and replaces three copies of the same
six-entry map. `stubProjectConfigService` spreads it and keeps its own `service: ""` override, so no stubbed value
changed.

➕ The integration suite restores `globalThis.fetch` in `suiteTeardown`, **before** the offline assertion, so a failing
assertion cannot leave the wrapper on the host for the suites that run after it.

➕ [decision] The offline assertion itself stays in `serviceTypes.test.ts` rather than moving. Task 9 pinned it there
deliberately, and the restore is what made it safe; moving it would be churn against an earlier decision.

➕ `ui/tests/components/services/detail/ServiceParametersTab.type.test.tsx` was folded into the sibling
`ServiceParametersTab.test.tsx`, which already mocked everything it needed, and the cross-file pointer comment left in
the sibling went with it. UI suites 229 → 228, test count unchanged at 2896 — all three cases moved, none dropped.

⚠️ `npm -w @netcracker/qip-vscode-extension run format:check` still exits non-zero: 33 files fail Prettier, and the
same 33 fail at the plan's base commit `4e73fd235`. The one *fresh* regression, `fileApiImpl.ts`, is fixed, and the new
lines in `extension.ts` were hand-formatted to match Prettier's output. Reformatting the other 33 is unrelated churn
and was not taken.

➕ [deviation] `vscode-extension/CLAUDE.md` was edited on disk (untracked, git-excluded, so it cannot be committed):
the `resolveServiceType` contract, the `isServiceFileOfAnyKind` predicate, the second enum-keyed guard, the
`readServiceFileById` split, and the `Unknown` icon.

## Review phase 3 — external cross-review

*One MAJOR finding from a Codex (gpt-5.6-luna) pass over the smells-round head `1613e02e3`.*

➕ **CONFIRMED: the api/specification reads took any plain-service uri as canonical.** The two earlier rounds covered
`getService`, `getEnvironment`, `getEnvironments` (`readServiceFileById`) and `updateService` (`readServiceFile`), but
`getApiSpecifications`, `getSpecificationModel`, `getOperations` and `getOperationInfo` each decided the file with
`isAnyServiceFile`, which answers `true` for the legacy `.service.` name as well. Reproduced against the post-conversion
state — the typed file on disk, the legacy sibling deleted, a webview still holding the legacy uri: `getApiSpecifications`
and `getOperationInfo` threw `EntryNotFound` from `getMainService` on the deleted path, and `getSpecificationModel` and
`getOperations` silently read on through the stale uri. All four now route through one `resolveServiceFileUri`: the id
resolves through `findServiceFileById`'s typed-wins order and the held uri is only the fallback for an id nothing
resolves, which is what keeps a read that starts from a chain or an api file in the folder it came from.
`getApiSpecifications` also drops its hand-rolled id check for `readServiceFileById`, the same guard the other read
sites share.

➕ [decision] The resolution is unconditional rather than skipped for a uri that already carries a typed name. Two
tests asserted that fast path ("without resolving it again"); both were rewritten to assert the resolved file instead.
A conditional trust rule needs the uri, the name it carries and the id it states to agree, and getting any of the three
wrong reopens exactly this finding — the four sites are identical now, which is the point. `findFileById` is cache-backed
and stats the convention path `<root>/<id>/<id><ext>` before scanning, and each of these functions already walks every
chain in the workspace, so the extra lookup is not what costs.

➕ [decision] `getOperations` and `getOperationInfo` shared a copy of "the service id is the first five dash-separated
parts of the entity id"; that is `serviceIdFromEntityId` now. `getOperationInfo` no longer reads the service document
just to decide whether to re-resolve, which is what made it throw on a deleted path.

➕ Mutations checked red, one per site: the pre-fix "any plain-service uri is canonical" rule (4 cases);
`getApiSpecifications`, `getSpecificationModel`, `getOperations` and `getOperationInfo` each back to the uri they were
handed (2 cases each, one new and one from the typed-subtree fixture); and `serviceIdFromEntityId` never deriving an id
(4 cases). The integration assertion was mutated too — `getApiSpecifications` back to trusting the uri fails the
conversion test in the real web host.

➕ `tests/web/response/serviceApiRead.test.ts` stubbed `findFileById` with no implementation while its fixture uri
carries the legacy name, so the new lookup would have resolved `undefined` and the suite would still have passed. The
stub now answers for `.service.` alone, which is what that fixture has on disk.

## Review phase 4 — external cross-review

*Two MAJOR and two MINOR findings from a second Codex (gpt-5.6-luna) pass over the phase-3 head. Phase 3 fixed the api
level; all four findings sit one level below it, on the service reads themselves.*

➕ **CONFIRMED: the service-level reads still took the held uri as canonical.** `readServiceFileById` read the passed uri
first and resolved by id only after that read failed or came back with another id, so it recovered a **deleted** legacy
file but not a legacy file that is still there. Reproduced with both files on disk — the state `deleteLegacySibling`
leaves behind when the delete fails, which it swallows on purpose, and the state `getServices` and the explorer already
dedup for: handed the legacy uri, `getService` answered `INTERNAL` from the superseded body while the list showed
`EXTERNAL` from the typed file, and `getEnvironments`, `getEnvironment` and the single-file branch of `getServices`
followed it. `readServiceFileById` now resolves through `resolveServiceFileUri` first and reads that file, which is the
rule phase 3 gave the api level; `getApiSpecifications` drops the nested `resolveServiceFileUri` call it needed while the
two disagreed.

➕ **CONFIRMED in part: the two reads that start without an id.** The finding named nine direct reads; seven are correct
and were left alone. `getContextService`, `getContextServices`, `getMcpService` and `getMcpServices` (lines 168, 183,
193, 206, 215, 228) read a context or an MCP file, and the name wins for those kinds, so no conversion ever moves one —
their enumeration branches are `findFiles` scans that must use the file they found. The two that were wrong are
`getCurrentServiceId` and the single-file branch of `getServices`: both learn the id **from** the document, so a deleted
path had nothing to resolve by and threw. Reproduced: `getCurrentServiceId` rejected with `EntryNotFound`, taking
`navigateToSpecifications` and `navigateToOperations` down with it (`getNavigateUri` stats the file first, so that caller
was already safe). Both now go through `readServiceFileByName`, which reads the uri and recovers a failed read through
the id the file name states — `serviceIdFromFileName` in `serviceFileType.ts`, built on the same whole-extension compare
as the rest of that module.

➕ **CONFIRMED: `findServiceFileById` reported only its last failure.** A malformed file anywhere in the workspace makes
the scan throw rather than come back empty, and that reason was overwritten by the next name's plain miss. It now
collects every failure into one `ServiceFileNotFoundError` that names them all.

➕ **CONFIRMED: the fallback handed back a uri that no longer exists.** `resolveServiceFileUri` returned `currentFile`
for any lookup failure, including one where `currentFile` is the path the conversion deleted, which turned the lookup
failure into an `EntryNotFound` on a stale path further down. The fallback is now taken only when `currentFile` still
resolves; otherwise the lookup error propagates.

➕ [decision] Telling "not found" from "the scan broke" by error type was declined. `FileApi` has no typed error
channel — `findFile` throws a plain `Error` for a miss, `parseFile` throws a plain `Error` for a broken file, and every
unit-test double throws a plain `Error` too — so a classification would be message-sniffing that lies for any
implementation but ours. The aggregate error plus the existence check on the fallback cover the same ground without one:
no failure is hidden, and the fallback is only taken when it stands for something.

➕ [decision] `getServices` enumeration builds its result from the document it already read
(`toIntegrationSystem`, split out of `getService`) rather than calling `getService` per file. `findServiceFiles` already
applies the typed-wins order and the loop already dedups on it, so re-resolving each id would rescan the workspace once
per service. This is also why the fix does not slow enumeration down: it removes a per-service read rather than adding a
per-service lookup.

➕ [decision] `readServiceFile` is untouched, and `updateService` still uses it. Redirecting a **write** by an id retry
stays declined, as in phase 2. `readServiceFileByName` recovers by the name-stated id only after the direct read fails,
so a hand-authored file whose name and id disagree still reads the way it always did.

➕ Mutations checked red, one per fix: `readServiceFileById` back to trusting the held uri (13 unit cases, and the
integration conversion test in the real web host); `getCurrentServiceId` and the `getServices` branch back to their
direct reads (1 case each); the fallback without its existence check (1 case); the lookup rethrowing only its last
failure (2 cases); `serviceIdFromFileName` reading only the first dot-free segment (1 case); and the enumeration
building from the uri it was handed rather than the file it found (2 cases).

➕ Eight cases in `serviceApiRead.serviceTypes.test.ts` left `findFileById` unstubbed, the same blind spot phase 3 found
in `serviceApiRead.test.ts`. They now declare which file is on disk through one shared `onlyOnDisk` helper, so they pin
the resolved file rather than the uri they were handed.

➕ [deviation] `vscode-extension/CLAUDE.md` was edited on disk again (untracked, git-excluded, so it cannot be
committed): the read-path contract now says the id decides on every read, names `readServiceFileByName` and
`serviceIdFromFileName`, and records the `ServiceFileNotFoundError` aggregate.

## Review phase 5 — external cross-review

*Two MAJOR and two MINOR findings from a third Codex (gpt-5.6-luna) pass. Phase 4 settled which file a **read** works
from; all four sit on what phase 4 deliberately left alone — the write, and the two reads that hold no id.*

➕ **CONFIRMED: the write followed the uri it was handed.** `readServiceFile` returned the passed uri whenever it read,
so `updateService` and the three environment writes worked from the superseded legacy sibling in the both-files state.
Reproduced against an in-memory disk with both files present: the write read the legacy body (`description: superseded`),
applied the edit to it, and — because the conversion recomputes the name from the type — wrote **that** over the typed
file, so the typed file's `description: current` and everything else saved since the conversion were reverted, and the
response the webview rendered came back stating the reverted values. The user's own edit landed; every earlier edit
vanished. `readServiceFile` is now the resolve-first read `readServiceFileById` was, and the two are one function.

➕ [decision] **A write resolves by id, the same rule a read follows.** This overturns the phase-2 and phase-4 decision
to leave the write on the held uri. That decision protected "a write lands in the file the caller opened", an invariant
the write path does not have and never had: `writeServiceInCurrentFormat` recomputes the target name from the type, so a
save through a legacy uri already moved the document to the typed file. What was left was the choice of *content*, and
following the uri chose the superseded body. Weighing the two failures: redirecting a write can save an editor opened on
file A into file B, which is visible, recoverable, and only reachable in a state the user was already warned about;
following the uri silently reverts saved work, which is invisible until someone notices the data is gone. Silent data
loss loses. What this accepts: (1) an edit typed straight into the superseded YAML file is ignored — that file is
already invisible to the list, the tree and every read; (2) the half-converted state is no longer cleaned up
incidentally, because a save through the legacy uri used to retry the delete that failed. Deleting it from the read side
was declined — a read must not delete, and a same-folder name match is not proof of a same-id file (a hand-authored
`foo.service.` and `foo.external-service.` can hold different services). The warning `deleteLegacySibling` raises
already tells the user to delete it by hand.

➕ **FALSE POSITIVE: `getCurrentServiceId` reading the legacy sibling.** It returns an id, and both files of one service
carry the same id — that is what makes them siblings and what `findServiceFileById` matches on, so the legacy sibling
cannot answer with a different id. A file whose body states an id its name does not is not a sibling of anything, and
reading its own id from it is the correct answer. Routing it through the lookup would have been actively wrong: it is
handed context and MCP uris too (`navigateToContextService`, `navigateToMcpService`), which no plain-service scan should
resolve. Scoped to what was real: `readServiceFileByName` split into `readServiceIdentity` (the id, no lookup) for this
caller, and the resolution for the one caller that needs a document.

➕ **CONFIRMED in part: the single-file branch of `getServices`.** The finding's "reads the wrong document" half is
false — it already resolved by id through `getService`, which phase 4 pinned. The redundancy half is real but was
mis-located: the lookup is deliberate (phase 3 declined a conditional trust rule, and it is what makes this branch land
on the typed file), while the second **read** of a document already in hand was pure waste. The branch now resolves the
id and reuses the document it read whenever the id resolves back to the same path, which is every service that has one
file — one read instead of two, with no trust rule.

➕ **CONFIRMED: a malformed file decided the precedence.** `collectFiles` let `parseFile` throw, which aborted the scan
for that extension, so one broken `.external-service.` file anywhere in the workspace made the typed pass fail and
handed the lookup to the legacy sibling. Fixed at the root rather than in the lookup: `collectFiles` parses only to
answer a predicate and treats an unparseable file as no match, because a file the parser chokes on cannot be the file
being searched for. Making `findServiceFileById` strict instead is impossible — `findFile` reports a plain miss and a
broken scan as the same `Error`, and every lookup legitimately misses on the names the service is not stored under, so
"an earlier scan failed" is the normal case. The phase-4 decision against classifying by error type stands.

➕ [decision] The predicate-free branch of `collectFiles` now skips parsing altogether. Nothing reads the content there
— `findFiles(extension)` is a listing — and it removes a parse of every matching file from all four passes of
`findServiceFiles`. An unparseable file is listed by name, as before; the caller that then reads it reports the failure,
which is where it belongs.

➕ Mutations checked red, one per fix: `readServiceFile` back to trusting the held uri (3 of the 5 new write cases, plus
12 existing read cases, plus the integration conversion test in the real web host); `updateService` back to a direct
`getMainService` (2 integration assertions, reads passing and the write failing); the `getServices` branch back to
`getService` (1 case); `getCurrentServiceId` routed through the lookup (1 case); and `collectFiles` back to letting
`parseFile` throw (1 case, the legacy sibling winning again).

➕ Two new suites. `tests/web/response/serviceApiModify.canonicalFile.test.ts` runs the real `serviceApiRead` and
`serviceFileWrite` against an in-memory disk — the sibling modify suites stub `readServiceFile`, so none of them could
see this — and `tests/web/response/fileApiImpl.brokenScan.test.ts` runs the real `VSCodeFileApi` and the real lookup over
a mocked directory tree. The integration conversion test gained the write half of its both-files case.

➕ [deviation] `vscode-extension/CLAUDE.md` was edited on disk once more (untracked, git-excluded, so it cannot be
committed): the write now resolves by id and says what that accepts, `readServiceIdentity` replaces
`readServiceFileByName`, and the scan's tolerance for a malformed file is recorded next to the lookup's error aggregate.

## Review phase 6 — external cross-review

*One MAJOR finding from a fourth Codex (gpt-5.6-luna) pass, on the phase-5 fix itself.*

➕ **CONFIRMED: the phase-5 fix reopened the phase-5 data loss through a different door.** Making `collectFiles` treat an
unparseable file as no match turned "the typed pass aborts" into "the typed file is invisible", which lands in the same
place. Reproduced with the real `VSCodeFileApi`, the real lookup and the real read and write paths over an in-memory
disk, with an unreadable `<id>.external-service.` file and a valid `<id>.service.` sibling beside it:
`findServiceFileById` answered with the legacy file, `getService` served its superseded body, and `updateService`
succeeded — leaving **one** file on disk, the typed one, holding the legacy body. The unreadable file's content was
gone and the legacy file deleted, exactly the loss phase 5 closed.

➕ [decision] **A file the scan cannot read is neither a match nor a miss, and the layer that parsed it says so.**
`collectFiles` records it, `findFile` reports `UnreadableFileError` when nothing else matched, and `findServiceFileById`
refuses with `UnreadableServiceFileError` when the name it resolved may be that file's sibling. This does not reopen the
phase-4 decision against classifying by error type: that one was about *sniffing* a plain `Error`, and this is a typed
channel raised by the code that just tried to parse. Every double still throws plain `Error`s, which read as plain
misses, so no unit-test double had to change.

➕ [decision] The refusal is scoped to a possible sibling — same folder, same name-stated id — rather than to any
unreadable file under a higher-precedence name. The strict rule guarantees the invariant too, but one broken file makes
every service *not* stored under that name unresolvable, turning a one-file problem into a workspace-wide outage. The
scope is not a heuristic: `writeServiceInCurrentFormat` writes the recomputed name into the folder of the file the
lookup resolved, so a same-folder, same-base pair is the only pair a write can overwrite, and a conversion produces
exactly that pair. What this accepts: two hand-authored files sharing a folder and a base but holding different
services refuse rather than resolve while one of them is unreadable — conservative, loud, and named.

➕ [decision] `resolveServiceFileUri` does not fall back to the held uri for that error. The fallback exists for an id
nothing resolves; here the held uri is the sibling itself, so falling back would restore the very read and write the
refusal exists to stop.

➕ [decision] `findFileByNavigationPath` keeps its plain last-error loop, and `findFileById`'s extension-less pass keeps
`continue`. Neither writes anything: navigation opens an editor, and every read and write behind it resolves by id and
refuses there. Widening the refusal to them would add the strict rule's blast radius for no data-loss coverage.

➕ **The predicate-free branch of `collectFiles` is correct for every caller.** `findFiles` is a listing by name — no
caller passes a predicate, each one re-reads the file it picks, and the explorer walks the tree itself and skips a file
per file. The gap is latent rather than live: a predicate passed to `findFiles` would drop an unreadable file silently,
which is the shape of this bug at list level, so the method now says so in its own doc comment. Unrelated and untouched:
`getServices`'s enumeration branch reads every listed file, so one unparseable plain-service file still fails the whole
list rather than that one entry.

➕ New suite `tests/web/response/unreadableCanonicalFile.test.ts`: the real file api, lookup, read and write over one
in-memory disk. Three cases pin the refusal (lookup, read, write, with the write asserting that neither file was
touched) and four pin the tolerance (a broken file in another folder blocks neither a typed nor a legacy-only service,
nor the conversion of one). No integration case was added — the harness asserts the full set of service ids in its
single workspace, so a malformed fixture would rewrite assertions across the suite for a state the unit suite covers
against the real scan.

➕ Mutations checked red, one per part: `collectFiles` back to swallowing the parse failure (4 cases); the lookup
without its sibling check (4 cases); the sibling check always true (2 tolerance cases); and `resolveServiceFileUri`
without its guard for the new error (the read and the write cases).

➕ [deviation] `vscode-extension/CLAUDE.md` was edited on disk again (untracked, git-excluded, so it cannot be
committed): the scan paragraph now says what an unreadable file is, where the refusal is decided, and how far it reaches.

## Review phase 7 — external cross-review

*Five findings from a fifth Codex (gpt-5.6-luna) pass, all of one shape: the unreadable outcome that phase 6 introduced
is created at one layer and collapsed back into a miss at the layers above it. Fixed as a contract rather than as five
patches.*

➕ **CONFIRMED: `findFileByNavigationPath` (fileApiImpl.ts:101).** Reproduced against the real `VSCodeFileApi` over an
in-memory disk: with an unreadable `<id>.external-service.` file and a readable `<id>.service.` sibling, navigation
answered with the legacy file. No data loss follows (every read and write behind it resolves by id and refuses), but the
user is put in front of the superseded document as if it were the current one. The phase-6 `[decision]` that left this
alone is reversed: it rested on the strict rule's blast radius, and the sibling-scoped rule has none.

➕ **CONFIRMED: the extension-less `findFileById` (fileApiImpl.ts:309).** Same shape, same fix. It has no in-repo caller
today, so it is a contract hole on the published `FileApi` surface rather than a live bug.

➕ **FALSE POSITIVE as a bug, CONFIRMED as a contract violation: the convention-path parse failure
(fileApiImpl.ts:275).** The signal is not actually lost: the convention path is always inside the scanned root, so the
scan that follows visits the same file and records it. And a match under the *same* extension can never be an unreadable
file's sibling, because a sibling shares the folder and the name, which under one extension makes it the same file. The
blanket `catch {}` is gone regardless: `findFileWithExtension` now separates "no file at the convention path" from "the
convention file could not be parsed" and re-raises the second as `UnreadableFileError` when the scan reported a plain
miss. No test can distinguish the two implementations; the invariant that makes them equivalent is pinned instead.

➕ **CONFIRMED as latent: the predicate-free `findFiles` (serviceFileLookup.ts:118).** No caller passes a predicate, so
nothing parses and nothing is dropped today. `findFiles` now collects unreadable files and reports them, so the hole
closes before a caller opens it.

➕ **CONFIRMED: the service listing (serviceApiRead.ts:864).** A listed file that cannot be read escaped as the parser's
own error, naming neither the file nor the fact that the listing would otherwise show its sibling in its place.

➕ [decision] **The contract lives in one module, `response/file/lookupOutcome.ts`**, stated at the top of the file: three
outcomes, who may narrow the third, and under what condition. `refuseUnreadableSibling` is the only narrowing rule, and
it is now shared rather than copied — the service lookup, navigation, the extension-less lookup, the model lookup and the
group lookup all call it, each with the extension set it scans. `mayBeSameEntity` generalizes the phase-6
`mayBeSameService` (same folder, same name once the entity extension is stripped) to every entity that can be stored
under two names. `findServiceFileById` keeps its second narrowing — a total miss stays a miss — because with nothing
resolved there is no sibling for a write to land beside; the unreadable files are named among its causes.

➕ [decision] **`resolveFirstCandidate` replaces every hand-written candidate loop, and its `onUnreadable` handler is
required.** The bug this round exists to stop is a `catch` that continues to a lower-precedence name, so the type system
now makes each caller state what an outstanding unreadable file means for it. `noMatchError` is the matching default for
the other half: when nothing matched, an unreadable file is reported over a plain miss.

➕ **The enumeration found four sites beyond the five.** `serviceApiRead.findModelFileById` and
`ApiGroupService.findGroupFileById` were the same swallowing loop one level down, on the `.specification.` → `.api.` and
`.specification-group.` → `.api-group.` pairs — both pairs a re-save overwrites, so both now refuse.
`getContextServices` and `getMcpServices` reparse a listed file exactly the way `getServices` did.
`EnvironmentService.saveSystem` held a `try { … } catch (error) { throw error; }` that did nothing and is gone.
`SystemService.getSystemById` / `getRawServiceById` and `EnvironmentService.getEnvironmentsForSystem` turned the refusal
into `null` or an empty list, which reads as "no such service" and names no file to fix; they now rethrow the refusal and
keep answering `null` for a plain miss.

➕ [decision] **The guard is `tests/web/response/lookupOutcomeContract.test.ts`**, a TypeScript-AST scan of `src/web`. It
fails on any `try`/`catch` or `.catch(` around a call to one of the lookups, unless `<file>#<function>` is on an
allowlist that states why catching there does not collapse the outcome. Twelve entries carry a reason; a thirteenth is a
decision a future author has to write down. A second case fails when an allowlist entry outlives the site it covers.
Reverting each of the five findings turns it red, which is what a sixth site would do.

➕ [decision] The guard covers resolving *which file an id owns* and reading a file a listing handed back, not content
scans. `getApiSpecifications`, `getSpecificationModel`, `getOperations` and `getOperationInfo` skip a file they cannot
parse while walking a service folder, and that tolerance is deliberate: they list what they can read rather than
resolving one entity across candidate names. `qipExplorer.findServiceFilesRecursively` walks the tree itself and calls no
lookup, so it stays outside the guard as well; a broken file drops out of the tree and the read behind it refuses.

➕ [deviation] The four navigation route arrays moved from `apiRouter.ts` to `response/navigationRoutes.ts`, re-exported
from `apiRouter` so no importer changed. The file layer had been pulling the whole message dispatch in to name them,
which is also why the phase-6 test suite had to stub the router out.

➕ New suites: `tests/response/file/lookupOutcome.test.ts` (13 cases on the contract primitives) and
`tests/web/response/lookupOutcomeContract.test.ts` (the guard). `unreadableCanonicalFile.test.ts` gained nine cases —
navigation, the extension-less lookup, the three listings, the model pair, and the tolerance each of them keeps — and its
ids are uuids now, because a navigation path carries one.

➕ Mutations checked red, one per fix: the sibling rule always passing (12 cases); the sibling rule ignoring the folder
(2 tolerance cases); navigation back to the last-error loop (1 case plus the guard); the extension-less lookup back to
`continue` (1 case plus the guard); `findFiles` dropping its report (1 case); the three listings back to a raw read
(3 cases); the model lookup back to the bare fallback (1 case plus the guard); the group lookup back to the last-error
loop (1 case plus the guard); and the two accessors swallowing the refusal again (3 cases).

➕ [deviation] `vscode-extension/CLAUDE.md` was edited on disk once more (untracked, git-excluded, so it cannot be
committed): the scan paragraph now points at `lookupOutcome.ts` as the single contract and names the guard.

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
