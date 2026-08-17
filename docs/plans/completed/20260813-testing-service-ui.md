# Testing Service: UI (plan 3 of 3)

## Overview

Bring the testing feature — test cases, endpoint mocks and test runs — into the React UI at `ui/`, replacing an Angular
implementation that serves only as a specification.

Nothing is ported literally: the source is Angular 12 on a proprietary component kit with Akita stores. None of its
assets may be copied either, including the icon sprites, which belong to the vendor theme.

Depends on plan 1 (`20260813-testing-service-go-module.md`) for the API and on plan 2
(`20260813-testing-service-engine-mocking.md`) for mocks to affect chain execution.

## Context (from discovery)

- **Source**: an Angular application, directories `modules/testing-service`, `services/testing-service` and the chain
  tab under `modules/chain-catalog/chain/chain-testing`. 180 files, ~9.6k lines of TypeScript plus ~2.1k of templates.
  Five list screens carry 900–1000 lines each.
- **Placement in the source**: a single `Testing` tab on the chain page, appearing only when the service is reachable,
  containing a vertical menu with Test Cases, Endpoint Mocks and Test Case Runs. Admin tools carry a `Testing` group
  with **two** entries — Test Cases and Test Runs. A global endpoint-mocks variant exists in the component but is never
  routed. Routes themselves are guarded, not just menu entries.
- **Platform UI building blocks**: `PageWithSidebar` (already used by `/admintools`), `TablePageLayout`,
  `components/table/` including `useColumnSettingsButton`, `useColumnsWithResizeAndScroll` and `tableSearch.ts`,
  `InlineEdit`, `notifications`, `Script.tsx` for code editing, `ChainDetailsDrawer.tsx` as the detail-panel precedent.
- **Server-side filtering**: `components/table/filter/useFilter.tsx` with `EntityFilterModel`, plus a per-list hook
  under `src/hooks/filter/` (see `useSessionsFilter.ts`). Distinct from the antd `onFilter` dropdowns, which filter only
  already-loaded rows.
- **Modals**: opened with `useModalsContext().showModal` from `ui/src/Modals.tsx`; `useModalContext()` from
  `ui/src/ModalContextProvider.tsx` exposes only `closeContainingModal` and is for use *inside* a modal. Getting these
  two backwards throws at runtime.
- **API layer**: one `Api` interface with two implementations — `RestApi` (axios) and `VSCodeExtensionApi`; the
  `api` singleton picks one by `isVsCode`. The offline implementation already carries 110 "not implemented" stubs.
  Path prefixes come from `getAppName()` through `this.v1()`, never hardcoded.
- **Server state**: TanStack Query is mounted (`QueryClientProvider` is in scope in both shells) and used in four
  places; every existing list screen uses `useState`/`useEffect` with offset pagination. This plan follows the dominant
  list pattern and uses TanStack Query only for the availability check.
- **Tests**: jest, with `ui/tests/` mirroring `src/`; `tests/helpers/fakeMonaco.ts` exists for editor tests.
- **Conventions**: `.claude/skills/ui-*/SKILL.md` and `ui/AGENTS.md` (there is no `ui/CLAUDE.md` on this branch).
  Destructive actions go through `confirmAndRun`; notifications through `useNotificationService`; write actions are
  gated with `ProtectedButton`. Documentation changes additionally follow the `docs-authoring` skill, which
  `help/AGENTS.md` mandates.
- **Permissions**: `ResourceTypes` in `ui/src/permissions/types.ts` is a closed list tied to the backend, so no new
  resource type is introduced.

## Development Approach

- **testing approach**: Regular — implementation first, tests immediately after within the same task
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility

Apply the `ui-core-development`, `ui-component-patterns` and `ui-api-and-utilities` skills while implementing, and the
`docs-authoring` skill for Task 14. The sanitization rules from plan 1 apply to labels, comments and commit messages.

## Testing Strategy

- **unit tests**: jest under `ui/tests/` — API client methods, the filter-condition mapping, availability logic, the
  shared list hook, matcher editors and validity, read-only mode, permission gating. Tests touching the availability
  hook need a `QueryClientProvider` wrapper.
- **manual verification**: click through the real application in Chrome at `http://localhost:8080` (through nginx — the
  bare Vite port serves no data)
- **no e2e framework**: the workspace has none, so browser verification stays manual

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**Routing** mirrors the source, including its sub-tab segments and index redirects:

```text
/chains/:chainId/testing                      → redirect to test-cases
  ├─ test-cases            → test-cases/:id       → redirect to general
  │                          {general, request, response-validation}
  ├─ endpoint-mocks        → endpoint-mocks/:id   → redirect to general
  │                          {general, response, request-matchers}
  └─ test-case-runs        → test-case-runs/:id      errors of a case run

/admintools/testing                           → redirect to test-cases
  ├─ test-cases            → test-cases/:id        read-only editor, same sub-tabs
  ├─ endpoint-mocks        → endpoint-mocks/:id    read-only editor   (addition, see below)
  └─ test-runs             → test-runs/:runId      case runs of the run
                             test-runs/:runId/:caseRunId   errors of a case run
```

The whole subtree is **guarded at the route level**, not only hidden in menus: the source uses a `canActivate` guard
that redirects to not-found when the service is unavailable. Without it a bookmark or a back-navigation lands on a
screen firing requests at an absent service.

Editors opened without a chain context are **read-only**: the source sets `readonly = !chainId` and threads it through
every section and into the matchers table, which then hides its checkbox column and its toolbar.

**One deliberate addition.** The global endpoint-mocks list is not routed in the source, though the component supports
it. Adding it is one route plus one menu entry and makes the admin group symmetric; it is an addition, not parity.

**Correction to an earlier assumption.** Assembling a run from cases of several chains was *already* possible — the
source's global test-cases list has a Start button for exactly that. The admin section is parity, not new capability.

**Visibility.** Hidden in three situations: `isVsCode` (offline, no backend), the service not responding, and
`production: true` from the mode endpoint. Resolved once through TanStack Query with retries disabled.

**Permissions**, fixed once here so ten buttons do not each get an ad-hoc choice:

| Action | Chain scope | Admin scope |
|---|---|---|
| view | `chain: read` | `adminTools: read` |
| create, update, delete | `chain: update` | `adminTools: update` |
| run, restart, cancel | `chain: execute` | `adminTools: execute` |
| import, export | `chain: import` / `chain: export` | `adminTools: import` / `adminTools: export` |

## Technical Details

**Base path.** `${this.v1()}/testing-service`, built from `getAppName()` like every other prefix in `RestApi`.

**Endpoints.** The full surface, including the shapes that differ from ordinary REST:

| Purpose | Call |
|---|---|
| list | `POST /{resource}` with a selection specification body; `?return_ids=true` returns ids only |
| get one | `GET /{resource}/{id}` — exists for test cases, mocks, tests-runs and test-case-runs |
| create | `POST /{resource}/create` |
| update | `POST /{resource}/{id}` |
| delete | `DELETE /{resource}` with an id array **in the body** (axios needs `{ data: ids }`) |
| cancel | `POST /tests-runs/cancel`, `POST /test-case-runs/cancel` |
| import | `POST /{resource}/import`, multipart with a repeated `file` field |
| export | `POST /{resource}/export` for test cases, mocks, tests-runs and test-case-runs |
| run / restart | `POST /tests-runs/create` with ids; `from` is a **query parameter**, omitted when starting from test cases, and set to `tests_runs` or `test_case_runs` when restarting |
| run errors | `GET /test-case-runs/{id}/errors?withMatchers=true` — always true, not a user-facing toggle |
| errors export | `POST /test-case-runs/errors/export` |
| mode | `GET {base}/mode` |
| session by external id | sessions-management lookup, needed because a case run stores an external session id |

The resource segment is `tests-runs`; only the UI route says `test-runs`. Pagination sends `offset` only.

**Select-all.** When the user selects everything beyond the loaded page, bulk actions resolve targets server-side
through `?return_ids=true` with the current filters, not from loaded rows. The confirmation wording depends on it
("all test cases that match filters").

**Filter vocabulary.** One module owns this mapping. The tokens are the lower-cased, underscore-separated condition
names, and the backend validates them on every list call — an unknown condition is a 500:

| UI condition | Sent as |
|---|---|
| CONTAINS / DOES_NOT_CONTAIN | `contains` / `does_not_contain` |
| STARTS_WITH / ENDS_WITH | `starts_with` / `ends_with` |
| IS / IS_NOT | `is` / `is_not` |
| IN / NOT_IN | `in` / `not_in` |
| LESS_THAN / GREATER_THAN | `less_than` / `greater_than` |
| IS_BEFORE / IS_AFTER | `is_before` / `is_after` |
| IS_WITHIN | `is_within`; with only one bound it degrades to `is_after` or `is_before` |

Two places where the Angular client is wrong and must not be copied: it sends `not-in` with a hyphen, which the backend
rejects — every NOT IN filter has been erroring there; and it formats timestamps with a **12-hour** clock, so an
afternoon bound silently filters twelve hours off. The backend parses a 24-hour clock with a colon-less offset, which in
dayjs terms is `YYYY-MM-DD HH:mm:ss.SSS ZZ`.

Chain and element filters are resolved to id sets client-side and sent as `{feature: 'chain_id', condition: 'in',
values: [...]}`. An empty resolution must short-circuit to an empty result rather than being sent, since `in` requires
at least one value.

**Pagination** sends `offset` only: the page size is server-controlled, and the end of the data is "a fetch returned
zero rows". That matches the workspace's existing load-more lists.

**Sortable fields are validated per entity** and the sets differ — test runs, for instance, accept `id`, `start`,
`finish`, `status`, `errors`, `test_cases` and the created-by/at pair, but **not** the updated ones, even though the
source renders Updated When/By columns. An unsupported `sort_by` is a 400, so each list's sortable columns must be
restricted to its own validated set. Case runs default to `start` descending.

**Name resolution in global lists** follows the source: load all chains once and resolve names from that cache, falling
back to the raw id when an element is not there. Do **not** fan out one element request per chain id on the page — the
target `Api` has no cross-chain element lookup and the source never needed one. Both cells are links: chain to
`/chains/:id`, element to `/chains/:id/graph/:elementId`.

**Matchers table** — the most intricate component, shared by both editors:

- columns: name, description (expandable), matcher type, entity type, entity name, parameters, enabled
- toolbar: add, delete, bulk enable, bulk disable, local search (reuse `normalizeSearchTerm`/`matchesByFields` from
  `tableSearch.ts`)
- entity types depend on the owner: request matchers use body, header, path parameter, query parameter; response
  matchers use body, status, header
- parameter editors: a modal with a required `path` field plus a code editor writing `path` + `schema`
  (`match_json_schema`) or `path` + `sample` (`match_json`); a status-code picker when the type is `equal` **and** the
  entity type is `status`; otherwise a plain editor whose parameter **name** varies — `value` for `equal`, `contain`,
  `start_with`, `end_with`, but **`pattern`** for `match`, and none at all for `empty` and `exist`. Nothing server-side
  validates these names: the service stores whatever it is given, and the matcher then silently never fires. The
  client-side validity map built in Task 5 is the only guard, so the name map has to be exact.
- validity: name, type and entity type required; entity name additionally required for header, path parameter and
  query parameter; parameters valid for the type
- edit-time behavior: changing the matcher type clears parameters; changing the entity type clears the entity name when
  it is no longer required
- `readonly` hides selection and the mutation actions; local search stays available
- the source's per-column filters, sorting and column settings **inside** this table are deliberately not ported

**Pickers and defaults.** The trigger picker lists only `http-trigger` elements; the mock endpoint picker lists only
`http-sender` and `service-call` elements whose `integrationOperationProtocolType` is HTTP. The HTTP method list comes
from the trigger element's `httpMethodRestrict` property, a comma-separated string, defaulting to `GET` when absent.

Creation defaults differ between the two entities, and getting this backwards is silent:

| | Test case | Endpoint mock |
|---|---|---|
| enabled | **false** | **true** |
| other | timeout 120000, method = element's first | status 200, delay 0 |

Save gating: a test case needs name, chain, element, method and valid matchers; a mock needs name, chain, element and
valid matchers (no method). Both return to the list on success; creating navigates straight into the new entity's
editor rather than refreshing the list.

**View state.** Column visibility and order persist through the existing `useColumnSettingsButton`. Sort, filters and
search are **not** persisted — no screen in this workspace does that today, and adding a bespoke localStorage layer
here would be the largest invisible cost in the plan.

**No polling.** The source refreshes on demand only.

## What Goes Where

- **Implementation Steps**: types, API client, hooks, routing, screens, docs, jest tests, and the browser click-through
- **Post-Completion**: screenshots for the documentation pages

## Implementation Steps

### Task 1: Types and API client

**Files:**
- Modify: `ui/src/api/apiTypes.ts`, `ui/src/api/api.ts`, `ui/src/api/rest/restApi.ts`, `ui/src/api/rest/vscodeExtensionApi.ts`
- Create: `ui/tests/api/rest/restApi.testing.test.ts`

- [x] add DTOs to `apiTypes.ts` mirroring the REST payloads exactly, including run views and the import result
- [x] declare the **complete** endpoint set from Technical Details on `Api` — list with `return_ids`, get one, create, update, delete-with-body, cancel, multipart import, all four exports, run/restart with the `from` query parameter, run errors, errors export, mode, plus the sessions-management lookup by external session id
- [x] implement them in `RestApi` using `${this.v1()}/testing-service` and `getFileFromResponse` for exports
- [x] add offline stubs in `VSCodeExtensionApi` following the existing "not implemented" pattern
- [x] write tests for request shaping (filters, pagination with `offset` only, sorting, `return_ids`), the delete body, the multipart import, restart variants including the omitted `from`, and export handling
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ `TestCaseView` does not serialize its `chain_id` / `element_id` columns (`json:"-"`): they are filter and sort
features only. List rows read the chain and the element off `triggerReference`, which Task 6 has to follow.

➕ The service accepts `empty` and `not_empty` conditions beyond the table in Technical Details, and an optional `limit`
query parameter beside `offset`. Both are supersets of what this plan uses; the client keeps sending `offset` alone.

### Task 2: Availability hook

**Files:**
- Create: `ui/src/hooks/useTestingServiceAvailability.ts`
- Create: `ui/tests/hooks/useTestingServiceAvailability.test.tsx`

- [x] resolve availability from `isVsCode`, a successful mode response, and `production === false`
- [x] use TanStack Query with retries disabled and a long stale time — an absent service must not produce a retry storm
- [x] treat any network or non-200 result as unavailable rather than an error surfaced to the user
- [x] write tests for all four outcomes, wrapping the hook in a `QueryClientProvider`
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The test file is `.tsx`, not the `.ts` the plan named: the `QueryClientProvider` wrapper is JSX, as in
`tests/hooks/useActionLog.test.tsx`.

➕ The query is disabled outright under `isVsCode` rather than only having its result ignored, so the offline editor
issues no request at all. `isLoading` is likewise reported false there, since nothing is pending.

### Task 3: Filter mapping and the shared list hook

**Files:**
- Create: `ui/src/hooks/filter/useTestingFilter.ts`
- Create: `ui/src/hooks/testing/useTestingEntityList.ts`
- Create: `ui/tests/hooks/filter/useTestingFilter.test.ts`, `ui/tests/hooks/testing/useTestingEntityList.test.ts`

- [x] implement the condition table from Technical Details in one module, including `not_in`, the `IS_WITHIN` degradation and the 24-hour timestamp format — both differ from what the Angular client sends, and both of its versions are broken against this backend
- [x] define the filter features per entity, and resolve chain and element filters into id sets client-side
- [x] build the shared list hook: selection specification assembly, offset pagination, sort mapping, select-all through `return_ids`, export to file, refresh, and the chain-versus-global variance
- [x] resolve chain and element names from a single chains fetch, falling back to raw ids — no per-chain fan-out
- [x] short-circuit an empty chain or element name resolution to an empty result instead of sending an `in` with no values, which the backend rejects
- [x] write tests for every condition mapping, the timestamp format, select-all target resolution, the empty-resolution case, and the chain/global variance
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ No feature of any entity declares `empty` or `not_empty`, so neither condition is offered on a column: the
description filter uses the plain string conditions rather than `DescriptionFilterConditions`. Feature and sort-field
sets come from `internal/dao/*_repository.go`, which is also where the sortable sets differ from the filterable ones —
runs filter by chain but do not sort by it.

➕ A negated name filter (`is not`, `does not contain`) resolves the matching names and sends `not_in`, and an empty
resolution drops it instead of emptying the list; the plan settles only the positive case, where an empty resolution
still short-circuits.

➕ `/v1/catalog/chains` answers without elements, so element names resolve only inside a chain, from one
`getElements(chainId)` call. Global lists keep the raw element id, and the element filter column appears only in the
chain-scoped variant. Filter column ids are the wire feature names themselves, except the two name-resolved ones.

### Task 4: Routing, guard and section shells

**Files:**
- Modify: `ui/src/App.tsx`, `ui/src/pages/ChainPage.tsx`, `ui/src/components/admin_tools/AdminToolsSidebar.tsx`, `ui/src/icons/IconProvider.tsx`
- Create: `ui/src/pages/testing/TestingLayout.tsx`
- Create: `ui/tests/pages/testing/TestingLayout.test.tsx`

- [x] add the `Testing` tab to the chain page, rendered only when the service is available
- [x] build the layout on `PageWithSidebar` with a vertical menu and an `Outlet`
- [x] register every route from Solution Overview — sub-tabs, index redirects, the run drill-down — wiring placeholder elements for screens later tasks create
- [x] guard the whole subtree on the availability hook, redirecting when unavailable, as the source's `canActivate` does
- [x] add the `Testing` group to the admin sidebar with a registered `OverridableIcon` name, and generalize the hardcoded `openKeys` logic, which currently recognizes only the Variables submenu
- [x] write tests for menu rendering, active entry by route, the redirect when unavailable, and the submenu open state
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ Icon names are registered in `ui/src/icons/IconDefenitions.tsx`, not in `IconProvider.tsx` as the file list said;
`testing` is added there. The admin group reuses the already registered `checkSquare`, `api` and `carryOut` for its
three entries.

➕ The vertical menu belongs to the chain scope alone. In the admin scope the admin sidebar already carries the group,
so its routes render without a second `PageWithSidebar`.

➕ Placeholders live in `ui/src/pages/testing/TestingPlaceholder.tsx`, which renders `NotImplemented` plus an `Outlet`
so the editor sub-tab routes resolve before their screens exist.

➕ The guard redirects to `/not-found`, which the existing catch-all route renders; while availability is still
resolving it renders nothing, so no screen flashes before the redirect.

➕ `ui/tests/components/admin_tools/AdminToolsSidebar.test.tsx` (new) covers the group and the generalized open keys,
and `ui/tests/pages/ChainPage.test.tsx` gains the tab visibility cases.

### Task 5: Matchers table

**Files:**
- Create: `ui/src/components/testing/MatchersTable.tsx`
- Create: `ui/src/components/testing/matcherEditors/*.tsx`
- Create: `ui/src/components/modal/testing/JsonMatcherParametersModal.tsx`
- Create: `ui/tests/components/testing/MatchersTable.test.tsx`

- [x] render the columns and toolbar from Technical Details, including the parameters column, bulk enable/disable and local search
- [x] scope entity types by owner kind
- [x] implement the three parameter editors, with the JSON one as a modal opened through `useModalsContext().showModal` and closed with `useModalContext().closeContainingModal`
- [x] use the correct parameter name per matcher type (`value`, `pattern`, or none) — the server rejects unknown names
- [x] clear parameters when the matcher type changes and the entity name when it stops being required
- [x] implement the validity rules and expose validity to the owning editor
- [x] support `readonly`, hiding selection and the toolbar
- [x] write tests for editor selection, the parameter-name map, entity-type scoping, validity, clearing behavior, bulk enable/disable and read-only mode
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The parameter names were re-read off the Go service and match the plan exactly: `value` for `equal`, `contain`,
`start_with` and `end_with`, `pattern` for `match`, `path` + `schema` for `match_json_schema`, `path` + `sample` for
`match_json`, none for `empty` and `exist` (`internal/matching/predicates/*.go`). Entity types likewise —
`internal/matching/data_getter_factory.go`. One disagreement: the service reads `path` as **optional**
(`getJsonPath` returns nothing when absent), while the plan and the client validity map require it. The client stays
stricter, so nothing it saves is rejected.

➕ The pure model — labels, the parameter-name map, entity-type scoping, validity and the clearing rules — lives in
`ui/src/components/testing/matchers.ts` so both editors and their tests share one source. The cell that picks the
editor is `matcherEditors/MatcherParametersCell.tsx`; the three editors and the shared read-only view sit beside it.

➕ Column settings, per-column filters, sorting **and** column resize stay unported: the table is a form control over
unsaved state, not a server list. Its buttons are plain antd buttons rather than `ProtectedButton`, because they
mutate local state only — the owning editor's Save is what the permission table gates.

➕ `crypto.randomUUID` keys new rows, as the source does. jsdom ships `crypto` without it, so
`ui/tests/setup/crypto-random-uuid.ts` fills it in from Node for the whole suite.

➕ `ScriptProps` now omits `onChange` from the DOM attributes it spreads: the editor reports a string, and the union
with `FormEventHandler` forced a cast on every caller. One such cast in `CustomArrayField.tsx` is dropped with it.

### Task 6: Test cases list

**Files:**
- Create: `ui/src/pages/testing/TestCases.tsx`
- Create: `ui/src/components/testing/TestCaseDetailsDrawer.tsx`
- Create: `ui/src/components/testing/testCases.ts`, `ui/src/components/testing/TestingTags.tsx`,
  `ui/src/components/testing/testingPermissions.ts`, `ui/src/hooks/useTableInfiniteScroll.ts`
- Modify: `ui/src/App.tsx`, `ui/src/icons/IconDefenitions.tsx`, `ui/src/hooks/useNotificationService.tsx`,
  `ui/tests/__mocks__/LightweightTable.tsx`
- Create: `ui/tests/pages/testing/TestCases.test.tsx`

- [x] build the list on the shared hook with server-side filters, sorting, pagination, row selection and column settings
- [x] show name, description, chain (global variant only) and element (both variants), each as a link, plus enabled, readiness, rule counts and audit fields
- [x] respect the source's toolbar asymmetry: the chain variant has Create but no Import; the global variant has Import but no Create, because the create dialog requires a chain
- [x] add delete, run and export; gate write actions per the permission table and route deletions through `confirmAndRun`
- [x] notify on a started run with a link to it
- [x] add the details drawer, following the `ChainDetailsDrawer` precedent
- [x] decide deliberately whether to port the readiness filter, which in the source maps to `enabled_rule_count` and carries a FIXME saying it disagrees with the readiness column
- [x] write tests for both variants, filter-to-request mapping, select-all bulk actions, and permission gating
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The readiness filter is **not** ported. Its `enabled_rule_count` mapping disagrees with the readiness column,
which also requires a trigger reference and request settings, and no feature covers either — so a correct server-side
readiness filter cannot be built. Readiness stays a display-only column, and the honest `enabled_rule_count` filter
("Enabled Rules") already covers what the service can answer.

➕ Permissions live in `ui/src/components/testing/testingPermissions.ts`, keyed by scope, so the ten buttons of this
and later screens share one table. The chain variant registers its toolbar through `useRegisterChainHeaderActions`, as
`Sessions` does; the registration deps are the state the toolbar reads, not the toolbar node, which is a fresh element
on every render and would loop through the header's own re-render.

➕ The infinite-scroll sentinel is extracted into `ui/src/hooks/useTableInfiniteScroll.ts`, ready for the four
testing lists still to come. `Sessions` keeps its inline copy: rewriting it is out of this task's scope.

➕ Select-all beyond the loaded page is an antd `rowSelection.selections` entry, offered only while rows remain
unloaded. `tests/__mocks__/LightweightTable.tsx` renders custom selections so it can be exercised.

➕ `useNotificationService.info` and `.warning` now take a `ReactNode` description, which the started-run
notification needs for its link; `NotificationItem` already allowed one.

⚠️ The Create and Import buttons are in place but inert until task 7 builds their modals. *(Resolved in task 7.)*

### Task 7: Create and import modals

**Files:**
- Create: `ui/src/components/modal/testing/CreateTestCaseModal.tsx`
- Create: `ui/src/components/modal/testing/ImportTestCasesModal.tsx`, `ui/src/components/modal/testing/TestingImportModal.tsx`
- Create: `ui/src/components/testing/testingElements.ts`
- Modify: `ui/src/pages/testing/TestCases.tsx`, `ui/src/components/testing/TestingTags.tsx`
- Create: `ui/tests/components/modal/testing/CreateTestCaseModal.test.tsx`, `ui/tests/components/modal/testing/ImportTestCasesModal.test.tsx`
- Modify: `ui/tests/pages/testing/TestCases.test.tsx`

- [x] build the create modal with the test-case defaults from Technical Details, navigating into the new entity's editor on success
- [x] build the import modal in two phases: multi-file upload, then a searchable result table with archive, file name, id, name, result and error columns
- [x] refresh the list only when some result is created or updated
- [x] write tests for the defaults, the two-phase flow, and the conditional refresh
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The import result shape was re-read off the Go service and agrees with the plan: `archive`, `fileName`, `entityId`,
`entityName`, `result`, `message`, with `created`, `updated` and `error` as the per-file statuses
(`internal/model/importexport.go`). A failure to read the archive itself comes back as one `error` row rather than as
an error response, so the two-phase flow always has a table to show.

➕ The result table, the search and the two phases live in `TestingImportModal.tsx`, which
`ImportTestCasesModal.tsx` fills in with a title and the API call. Task 9's mock import is the second caller.

➕ `Upload.Dragger` off the root `antd` import, not `antd/es/upload/Dragger` as `ImportSessions.tsx` uses: the `es`
path is untranspiled ESM and breaks the jest run.

➕ A case created with no trigger picked still carries `triggerReference` with the chain and an empty element id.
The reference is what scopes a case to its chain, so dropping it would hide the new case from the list it was created
in. `ui/src/components/testing/testingElements.ts` holds the trigger predicate and the `httpMethodRestrict` parsing
that the request tab of task 8 needs as well.

### Task 8: Test case editor

**Files:**
- Create: `ui/src/pages/testing/TestCasePage.tsx`
- Create: `ui/src/components/testing/testCase/*.tsx`
- Create: `ui/tests/pages/testing/TestCasePage.test.tsx`

- [x] build three routed sub-tabs — general, request, response-validation — with the index redirect to general
- [x] request tab: trigger picker filtered to `http-trigger`, method list from `httpMethodRestrict`, timeout, path and query parameters, headers, body
- [x] response-validation tab: the matchers table in response mode
- [x] gate save on name, chain, element, method and matcher validity, returning to the list afterwards
- [x] guard navigation away from unsaved changes
- [x] support the read-only variant reached from the admin list
- [x] use `Script.tsx` for the body editor and `tests/helpers/fakeMonaco.ts` in tests
- [x] write tests for sub-tab routing, dirty-state guarding, validation gating, read-only mode and the save payload
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The page owns the draft and hands it to the routed sub-tabs through the router outlet context
(`useTestCaseEditor`), so a tab switch is a navigation rather than a remount of the editor. Save sends the whole
entity, which is what `POST /test-cases/{id}` takes: the service replaces the trigger reference, the request settings
and every matcher from the body (`internal/services/test_cases_service.go`), so client-side matcher ids are dropped
and reassigned. The request-settings shape re-read off `internal/dao/model.go` agrees with the plan and with
`apiTypes.ts` field for field.

➕ The unsaved-changes guard is written inline rather than through
`components/services/useUnsavedChangesWithModal.tsx`: that hook reads a state flag, and a save that clears the flag
and navigates in the same tick would still be blocked by its own navigation. The blocker here reads a ref, which the
save clears before leaving.

➕ Name and value pairs — path parameters, query parameters and headers — share
`ui/src/components/testing/NameValueTable.tsx` rather than living under `testCase/`, since the mock editor of task 10
needs the same control for its response headers.

➕ `Script` is stubbed in the page test instead of `tests/helpers/fakeMonaco.ts`: the helper fakes the Monaco module
for `Script`'s own test, while this suite exercises the editor around the body field. The body editor uses the `json`
mode, the only alternative to `groovy` that `Script` offers.

➕ A jsdom test cannot navigate a react-router data router — `useBlocker` needs one — because jsdom ships no fetch
API and every navigation builds a `Request`. `ui/tests/helpers/dataRouterGlobals.ts` installs a minimal one for the
suites that render `createMemoryRouter`.

### Task 9: Endpoint mocks list and modals

**Files:**
- Create: `ui/src/pages/testing/EndpointMocks.tsx`
- Create: `ui/src/components/testing/EndpointMockDetailsDrawer.tsx`
- Create: `ui/src/components/modal/testing/CreateEndpointMockModal.tsx`
- Create: `ui/src/components/modal/testing/ImportEndpointMocksModal.tsx`
- Create: `ui/tests/pages/testing/EndpointMocks.test.tsx`, `ui/tests/components/modal/testing/CreateEndpointMockModal.test.tsx`

- [x] build the list on the shared hook, mirroring test cases including the Create/Import asymmetry
- [x] add the mock-specific columns: response status code and response delay
- [x] filter the endpoint picker to `http-sender` and HTTP `service-call` elements
- [x] use the mock creation defaults — enabled **true**, status 200, delay 0 — which are the opposite of the test-case ones
- [x] add delete, export, import and the details drawer with the same gating and confirmation rules
- [x] write tests for the mock-specific columns, the picker filtering and the creation defaults
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The mock payload, its filterable features and its sortable fields were re-read off the Go service
(`internal/dao/model.go`, `internal/dao/endpoint_mocks_repository.go`) and agree with the plan and with `apiTypes.ts`
field for field. The list returns the whole entity — there is no view type with rule counts, unlike test cases — so the
drawer counts `requestMatchers` itself and the list carries no rule columns.

➕ `formatOptional` hides a zero, and zero is the delay a mock is created with. `components/testing/endpointMocks.ts`
holds `formatMockNumber`, which renders the status and the delay and keeps `0` visible.

➕ The picker takes `service-call` only at protocol `http`, matching the source. The project's `isHttpProtocol` also
accepts `soap`, which would offer SOAP calls the mock has no response shape for, so the check is written against
`normalizeProtocol` directly.

➕ The picker flattens the chain elements before filtering: a sender or a service call can sit inside a container, and
`getElements` answers a tree. `flattenElements` moved to `components/testing/testingElements.ts`, where the trigger
picker of task 7 now reads it as well, and `useTestingEntityList` reuses it for its name cache.

➕ Mocks have no run action — a mock is exercised by the case that hits it — so the toolbar is refresh, export,
import or create, and delete.

### Task 10: Endpoint mock editor

**Files:**
- Create: `ui/src/pages/testing/EndpointMockPage.tsx`
- Create: `ui/src/components/testing/endpointMock/*.tsx`
- Modify: `ui/src/App.tsx`
- Create: `ui/tests/pages/testing/EndpointMockPage.test.tsx`

- [x] build three routed sub-tabs — general, response, request-matchers — with the index redirect to general
- [x] general tab carries both the general fields and the endpoint picker
- [x] gate save on name, chain, element and matcher validity — no method here
- [x] guard navigation away from unsaved changes, which the source does for this editor too
- [x] support the read-only variant
- [x] write tests for sub-tab routing, the save payload and read-only mode
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The update payload was re-read off the Go service and agrees with the plan: `POST /endpoint-mocks/{id}` replaces
the endpoint reference, the response settings and every matcher from the body
(`internal/services/endpoint_mocks_service.go`), so the editor sends the whole entity and the service reassigns
matcher ids. Response settings are `message` (body plus headers), `status` and `delay` — `internal/dao/model.go`,
field for field what `apiTypes.ts` already declares.

➕ One thing the plan does not state: the service rejects a response status outside 100–599, tolerating only a stored
zero, which it answers as 200 (`endpointMockViolations`). The status field is bounded to that range rather than left
open, so nothing the editor saves comes back a violation.

➕ The response tab reuses `NameValueTable` for the headers and `Script` in `json` mode for the body, as the request
tab of task 8 does. The endpoint picker sits on the general tab, where the plan puts it, and reuses `flattenElements`
plus `isHttpEndpoint` from task 9 — a mock endpoint can be nested in a container.

➕ The page mirrors `TestCasePage` throughout: the outlet-context draft, the routed sub-tabs, and the ref-based
unsaved-changes blocker, which `useUnsavedChangesWithModal` cannot replace because it reads a state flag and would
prompt on the editor's own post-save navigation.

### Task 11: Test case runs

**Files:**
- Create: `ui/src/pages/testing/TestCaseRuns.tsx`
- Create: `ui/src/components/testing/TestCaseRunDrawer.tsx`
- Create: `ui/tests/pages/testing/TestCaseRuns.test.tsx`

- [x] build the list in the two variants that are actually routed — scoped to a chain and scoped to a run — swapping the Tests Run column for the Chain column as the source does; there is no unscoped route, so do not build a third variant nothing renders
- [x] show status, timings, error count and the originating test case, defaulting the sort to `start` descending
- [x] resolve the run's **external** session id through the sessions-management lookup before linking to the session page, falling back to no link when it is not found
- [x] link the case-run cell to the errors page and the test-case name to its editor, as the source does
- [x] add refresh, export, cancel and restart; there is no delete for case runs
- [x] add the run drawer
- [x] write tests for both variants, status rendering, the session lookup including the not-found fallback, cancel and restart
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The view was re-read off the Go service (`internal/dao/model.go`): a case run carries `testsRunId`, `testCaseId`,
`testCaseName`, `testCaseDescription`, `chainId`, `start`, `finish`, `status`, `sessionId`, `ordinal` and `errors`, and
**no audit fields** — it embeds `TestCaseRun`, not `Metadata` — so the list has no created/updated columns. Its sortable
set (`id`, `test_case_name`, `chain_id`, `start`, `finish`, `status`, `errors`) matches the plan;
`tests_run_id` filters but does not sort, so the Test Run column carries no sorter.

⚠️ One disagreement with the plan. Cancel reaches only the cases that have **not** started: `cancelTestCaseRuns`
selects `status = pending` and leaves a running case alone (`internal/services/test_case_runs_service.go`). The
confirmation says as much rather than promising to stop a case in flight. Restart agrees with the plan — it posts to
`tests-runs/create` with `from=test_case_runs`, which resolves the cases behind the ids.

➕ The cell that links to the errors page is the **Id** column, the only cell that names the case run itself; the test
case name links to its editor beside it. The session cell renders the external id as plain text until the lookup
resolves it, and keeps it plain when nothing was found.

➕ `RunStatusTag` joins `TestingTags.tsx`, ready for the aggregate status of task 13.

➕ `TestingListSource` gains `usesElementNames`. The run lists name no element, so inside a chain they now fetch
nothing at all instead of pulling the chain's whole element tree for a cache no column reads.

### Task 12: Run errors page

**Files:**
- Create: `ui/src/pages/testing/TestCaseRunErrors.tsx`
- Create: `ui/tests/pages/testing/TestCaseRunErrors.test.tsx`

- [x] render the failing matcher and message for a case run, reached from both the chain route and the admin drill-down
- [x] request errors with `withMatchers=true`, which is constant rather than a control
- [x] add the errors export
- [x] write tests for rendering from both routes and for the export call
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The errors export takes **validation error** ids, not case-run ids (`BulkExport` in
`internal/controllers/test_case_run_errors_controller.go`), so the page exports the rows the user checked. The Angular
source names its action "export selected" and then sends every loaded row; this port exports the selection, as every
other testing list does.

➕ The payload re-read off the Go service is `id`, `testCaseRunId`, `matcherId`, `matcher`, `message`
(`ValidationError` in `internal/dao/model.go`), and `matcher` is filled in only under `withMatchers=true`. The Rule cell
therefore falls back to `matcherId` whenever the matcher is absent — an error outlives the rule it was recorded for.

➕ The page reads the case run beside the errors: it is the only source of `testCaseId`, which the rule link and the
breadcrumb need, and it carries the status and the timings the toolbar shows. Scope comes from the `chainId` route
parameter rather than a variant prop, since only the chain route has one.

➕ The source's per-column filters, its sorting and its localStorage view state stay unported, as they do in the
matchers table of task 5. Local search, column visibility and column order remain, the last two through
`useColumnSettingsButton`.

### Task 13: Test runs

**Files:**
- Create: `ui/src/pages/testing/TestRuns.tsx`
- Create: `ui/src/components/testing/TestRunDrawer.tsx`
- Create: `ui/tests/pages/testing/TestRuns.test.tsx`

- [x] list run sets with id, aggregate status, timings, case count, error count and audit fields, keeping sortable columns within the entity's validated set — the updated-by/at pair is not sortable server-side
- [x] drill into a run's case runs through the nested route, and add the drawer
- [x] add refresh, export, delete, cancel and restart
- [x] write tests for aggregate status rendering and drill-down navigation
- [x] run `npm -w @netcracker/qip-ui test` - must pass before next task

➕ The sortable set was re-read off the Go service (`internal/dao/tests_runs_repository.go`) and matches the plan:
`id`, `start`, `finish`, `status`, `errors`, `test_cases` and the created pair. The updated columns render without a
sorter, and the shared list hook drops an out-of-set `sort_by` before the request, so nothing reaches the service as a
400. The filterable features stop at the created pair too, which task 3 already had right.

➕ The aggregate `errors` counts the test **cases** that failed, not the validation errors they recorded — the view
joins one error per case run (`tests_runs_view` in `migrations/00000000000100__init.tx.up.sql`). The column reads
"Test Cases With Errors", as the source's does, and the filter column of task 3 is renamed to match.

⚠️ Two contract disagreements with the plan. Cancel reaches only the cases still pending: it delegates to
`CancelByTestsRuns`, which selects `status = pending` (`internal/services/test_case_runs_service.go`), so the
confirmation promises no more than the case-run list's does. Delete carries no such guard — `BulkDelete` removes a run
whatever its state and takes its case runs with it, which the confirmation names.

➕ The list has no variant: only the admin route exists, so the page takes no props, gates on admin tools alone and
needs no chain-header registration. `TestingPlaceholder.tsx`, the scaffolding of task 4, lost its last caller with this
route and is deleted.

➕ Restarting refreshes the list, which the case-run list's restart does not need to: the new run set lands in this
very list.

### Task 14: Verify acceptance criteria

- [x] verify all requirements from Overview are implemented
- [x] run `npm -w @netcracker/qip-ui test`, the lint script and the type check — 239 suites / 3210 tests pass, eslint reports 0 errors (74 pre-existing warnings, none in testing files), `tsc --noEmit` clean
- [x] confirm the library build still succeeds: `npm -w @netcracker/qip-ui run build:lib` — succeeds once `@netcracker/qip-schemas` is built; the schemas workspace is a prerequisite, not a regression
- [x] bring up the full stack and open `http://localhost:8080` in Chrome — never port 4200, which serves no data — the Testing tab and the admin Testing group both render
- [x] click through: create a test case on a deployed chain, fill trigger and request, add matchers of several types including a JSON one and a `match` one (whose parameter is `pattern`), save, run it, watch the run reach a terminal state, open the errors of a failing matcher, follow the session link into the trace — the saved payload carries `value` / `pattern` / `path`+`sample` exactly per matcher type, the run reached Finished, the errors page rendered the failing rule, and the session link resolved the external id into the trace
- [x] restart a finished run from both the run list and the case-run list, and cancel a running one — both restarts produced a new run set (the run list refreshed itself); cancel turned the pending case Canceled and left the started one Running, as its confirmation promises
- [x] click through mocks: create a mock (confirm it is enabled by default), run a case that hits it, confirm the mocked response is what the chain received — the mock defaulted to enabled/200/0, and the session trace records the sender's body as the mocked one although the sender points at an unreachable host
- [x] assemble a run from cases of two different chains through the admin list, and confirm select-all beyond the loaded page acts on everything matching the filters — the run held case runs of two chains, and select-all deleted all 26 cases while only 20 were loaded
- [x] open an editor from the admin list and confirm it is read-only, including the matchers table — fields disabled, no save toolbar, and the matchers table drops its selection column and its toolbar while keeping local search
- [x] verify column settings survive a page reload — a column enabled in the settings panel was still there after a full reload
- [x] navigate directly to a testing URL with the service stopped and confirm the guard redirects instead of erroring, with no retry storm in the network tab — with the mode call failing the guard landed on `/not-found`, the chain page dropped its Testing tab, and exactly one mode request was made
- [x] check the browser console for errors and verify light and dark themes on every new screen — both themes render every screen legibly, and the console is clean after the two fixes below

➕ Two defects were found and fixed during this pass. The unsaved-changes blocker of both editors fired on their **own** sub-tab navigation, so moving from General to Request offered to discard the edit; it now blocks only a navigation that leaves the editor, with regression tests in both page suites. `NameValueTable` keyed its rows through antd's deprecated `rowKey` index parameter, which warned on every render; it now keys off a keyed copy of the pairs.

⚠️ A **service-side** defect this UI only reports: `test_cases_view` joins `matchers` twice, so `validation_rule_count` and `enabled_rule_count` come back squared — three matchers are shown as nine Rules. The list renders what the service returns; the fix belongs to the Go module of plan 1
(`migrations/00000000000100__init.tx.up.sql`).

⚠️ The shared `InlineEdit` commits only on Enter — its antd `Form` renders no element (`component={false}`), so the `onBlur` it passes never fires, and its `onFinish` toggles twice and leaves the cell open. Every consumer in the workspace behaves this way, and the component is untouched by this branch, so the matchers table inherits it rather than causing it.

➕ The testing service resolves an `http-trigger` through its `contextPath` property, which specification-driven triggers do not carry. The click-through therefore ran on a purpose-built chain (`Testing verification chain`) with a plain trigger plus an `http-sender` aimed at an unreachable host, which also makes the mock interception unambiguous.

### Task 15: [Final] Update documentation

- [x] add `help/docs/01__Chains/8__Testing/testing.md` covering the chain tab, test cases, mocks and runs
- [x] add `help/docs/03__Admin_Tools/9__Testing/testing.md` covering the cross-chain lists and run sets
- [x] update the section table in `help/README.md`
- [x] document the mock interception flow from plan 2, including that an element with no matching mock receives `404`
- [x] document that the section only appears where the testing service is deployed and that it is not for production
- [x] note that cases inside one run execute sequentially while separate runs execute in parallel
- [x] move this plan to `docs/plans/completed/`

➕ `help/docs/03__Admin_Tools/admin_tools.md` gains a **Testing** entry too: that page is the section's landing list, and
a new folder that no landing page names is reachable only through the sidebar.

➕ The chain page carries the matchers table, the run flow and the mocking chapter in full, and the admin page links to
them rather than repeating them. The admin page owns what only it has: the cross-chain lists, the import dialog, the run
sets and the execution-order chapter.

➕ Two facts the docs state that the plan does not. A **disabled** test case is still queued and its case run finishes
**Skipped** rather than being dropped (`test_execution_service.go`). And a `graphql-sender`, plus a `service-call` over
GraphQL, is intercepted by the engine (`HttpSenderDependencyBinder.isHttpChainElement`) while the UI mock picker offers
neither, so its calls are answered `404` with no way to configure a mock — documented as a note, since a reader would
otherwise read it as a defect.

➕ Numbering follows the plan (`8__Testing`, `9__Testing`) rather than the tab order, where Testing sits between Logging
and Masking. Renumbering four existing folders to match would churn every link into them for no reader benefit.

➕ No images: screenshots are Post-Completion work, and a page referencing files that do not exist renders broken.

## Post-Completion

*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**

- the browser click-through requires the whole stack: catalog, engine with mocking enabled, the testing service, and a
  deployed chain
- screenshots for the documentation pages are captured during that session

**Known limitations, all four now documented on the help pages:**

- the same test case placed in two different run sets can execute concurrently; runs are isolated from each other, cases
  are not
- `test_cases_view` squares the two rule counts, so the **Rules** and **Active Rules** columns and the test case details
  panel overcount; carried into `testing-service/AGENTS.md` as a known defect, with the fix it needs
- the shared `InlineEdit` commits on **Enter** only, product-wide; carried into the `ui-component-patterns` skill source
  under `.apm/skills/`
- the run aggregate status is a lexicographic minimum over the case-run statuses, so one canceled case run reads as a
  canceled run and one finished case run reads as a finished run; the admin help page states the resolution order

➕ The unflattened trigger picker was fixed in review rather than documented as a limitation: both pickers now flatten
the element tree, which is what the help page already promised.
