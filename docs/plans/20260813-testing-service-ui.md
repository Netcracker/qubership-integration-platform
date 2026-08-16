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

```
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

- [ ] render the columns and toolbar from Technical Details, including the parameters column, bulk enable/disable and local search
- [ ] scope entity types by owner kind
- [ ] implement the three parameter editors, with the JSON one as a modal opened through `useModalsContext().showModal` and closed with `useModalContext().closeContainingModal`
- [ ] use the correct parameter name per matcher type (`value`, `pattern`, or none) — the server rejects unknown names
- [ ] clear parameters when the matcher type changes and the entity name when it stops being required
- [ ] implement the validity rules and expose validity to the owning editor
- [ ] support `readonly`, hiding selection and the toolbar
- [ ] write tests for editor selection, the parameter-name map, entity-type scoping, validity, clearing behavior, bulk enable/disable and read-only mode
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 6: Test cases list

**Files:**
- Create: `ui/src/pages/testing/TestCases.tsx`
- Create: `ui/src/components/testing/TestCaseDetailsDrawer.tsx`
- Create: `ui/tests/pages/testing/TestCases.test.tsx`

- [ ] build the list on the shared hook with server-side filters, sorting, pagination, row selection and column settings
- [ ] show name, description, chain (global variant only) and element (both variants), each as a link, plus enabled, readiness, rule counts and audit fields
- [ ] respect the source's toolbar asymmetry: the chain variant has Create but no Import; the global variant has Import but no Create, because the create dialog requires a chain
- [ ] add delete, run and export; gate write actions per the permission table and route deletions through `confirmAndRun`
- [ ] notify on a started run with a link to it
- [ ] add the details drawer, following the `ChainDetailsDrawer` precedent
- [ ] decide deliberately whether to port the readiness filter, which in the source maps to `enabled_rule_count` and carries a FIXME saying it disagrees with the readiness column
- [ ] write tests for both variants, filter-to-request mapping, select-all bulk actions, and permission gating
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 7: Create and import modals

**Files:**
- Create: `ui/src/components/modal/testing/CreateTestCaseModal.tsx`
- Create: `ui/src/components/modal/testing/ImportTestCasesModal.tsx`
- Create: `ui/tests/components/modal/testing/CreateTestCaseModal.test.tsx`, `ui/tests/components/modal/testing/ImportTestCasesModal.test.tsx`

- [ ] build the create modal with the test-case defaults from Technical Details, navigating into the new entity's editor on success
- [ ] build the import modal in two phases: multi-file upload, then a searchable result table with archive, file name, id, name, result and error columns
- [ ] refresh the list only when some result is created or updated
- [ ] write tests for the defaults, the two-phase flow, and the conditional refresh
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 8: Test case editor

**Files:**
- Create: `ui/src/pages/testing/TestCasePage.tsx`
- Create: `ui/src/components/testing/testCase/*.tsx`
- Create: `ui/tests/pages/testing/TestCasePage.test.tsx`

- [ ] build three routed sub-tabs — general, request, response-validation — with the index redirect to general
- [ ] request tab: trigger picker filtered to `http-trigger`, method list from `httpMethodRestrict`, timeout, path and query parameters, headers, body
- [ ] response-validation tab: the matchers table in response mode
- [ ] gate save on name, chain, element, method and matcher validity, returning to the list afterwards
- [ ] guard navigation away from unsaved changes
- [ ] support the read-only variant reached from the admin list
- [ ] use `Script.tsx` for the body editor and `tests/helpers/fakeMonaco.ts` in tests
- [ ] write tests for sub-tab routing, dirty-state guarding, validation gating, read-only mode and the save payload
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 9: Endpoint mocks list and modals

**Files:**
- Create: `ui/src/pages/testing/EndpointMocks.tsx`
- Create: `ui/src/components/testing/EndpointMockDetailsDrawer.tsx`
- Create: `ui/src/components/modal/testing/CreateEndpointMockModal.tsx`
- Create: `ui/src/components/modal/testing/ImportEndpointMocksModal.tsx`
- Create: `ui/tests/pages/testing/EndpointMocks.test.tsx`, `ui/tests/components/modal/testing/CreateEndpointMockModal.test.tsx`

- [ ] build the list on the shared hook, mirroring test cases including the Create/Import asymmetry
- [ ] add the mock-specific columns: response status code and response delay
- [ ] filter the endpoint picker to `http-sender` and HTTP `service-call` elements
- [ ] use the mock creation defaults — enabled **true**, status 200, delay 0 — which are the opposite of the test-case ones
- [ ] add delete, export, import and the details drawer with the same gating and confirmation rules
- [ ] write tests for the mock-specific columns, the picker filtering and the creation defaults
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 10: Endpoint mock editor

**Files:**
- Create: `ui/src/pages/testing/EndpointMockPage.tsx`
- Create: `ui/src/components/testing/endpointMock/*.tsx`
- Create: `ui/tests/pages/testing/EndpointMockPage.test.tsx`

- [ ] build three routed sub-tabs — general, response, request-matchers — with the index redirect to general
- [ ] general tab carries both the general fields and the endpoint picker
- [ ] gate save on name, chain, element and matcher validity — no method here
- [ ] guard navigation away from unsaved changes, which the source does for this editor too
- [ ] support the read-only variant
- [ ] write tests for sub-tab routing, the save payload and read-only mode
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 11: Test case runs

**Files:**
- Create: `ui/src/pages/testing/TestCaseRuns.tsx`
- Create: `ui/src/components/testing/TestCaseRunDrawer.tsx`
- Create: `ui/tests/pages/testing/TestCaseRuns.test.tsx`

- [ ] build the list in the two variants that are actually routed — scoped to a chain and scoped to a run — swapping the Tests Run column for the Chain column as the source does; there is no unscoped route, so do not build a third variant nothing renders
- [ ] show status, timings, error count and the originating test case, defaulting the sort to `start` descending
- [ ] resolve the run's **external** session id through the sessions-management lookup before linking to the session page, falling back to no link when it is not found
- [ ] link the case-run cell to the errors page and the test-case name to its editor, as the source does
- [ ] add refresh, export, cancel and restart; there is no delete for case runs
- [ ] add the run drawer
- [ ] write tests for both variants, status rendering, the session lookup including the not-found fallback, cancel and restart
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 12: Run errors page

**Files:**
- Create: `ui/src/pages/testing/TestCaseRunErrors.tsx`
- Create: `ui/tests/pages/testing/TestCaseRunErrors.test.tsx`

- [ ] render the failing matcher and message for a case run, reached from both the chain route and the admin drill-down
- [ ] request errors with `withMatchers=true`, which is constant rather than a control
- [ ] add the errors export
- [ ] write tests for rendering from both routes and for the export call
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 13: Test runs

**Files:**
- Create: `ui/src/pages/testing/TestRuns.tsx`
- Create: `ui/src/components/testing/TestRunDrawer.tsx`
- Create: `ui/tests/pages/testing/TestRuns.test.tsx`

- [ ] list run sets with id, aggregate status, timings, case count, error count and audit fields, keeping sortable columns within the entity's validated set — the updated-by/at pair is not sortable server-side
- [ ] drill into a run's case runs through the nested route, and add the drawer
- [ ] add refresh, export, delete, cancel and restart
- [ ] write tests for aggregate status rendering and drill-down navigation
- [ ] run `npm -w @netcracker/qip-ui test` - must pass before next task

### Task 14: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented
- [ ] run `npm -w @netcracker/qip-ui test`, the lint script and the type check
- [ ] confirm the library build still succeeds: `npm -w @netcracker/qip-ui run build:lib`
- [ ] bring up the full stack and open `http://localhost:8080` in Chrome — never port 4200, which serves no data
- [ ] click through: create a test case on a deployed chain, fill trigger and request, add matchers of several types including a JSON one and a `match` one (whose parameter is `pattern`), save, run it, watch the run reach a terminal state, open the errors of a failing matcher, follow the session link into the trace
- [ ] restart a finished run from both the run list and the case-run list, and cancel a running one
- [ ] click through mocks: create a mock (confirm it is enabled by default), run a case that hits it, confirm the mocked response is what the chain received
- [ ] assemble a run from cases of two different chains through the admin list, and confirm select-all beyond the loaded page acts on everything matching the filters
- [ ] open an editor from the admin list and confirm it is read-only, including the matchers table
- [ ] verify column settings survive a page reload
- [ ] navigate directly to a testing URL with the service stopped and confirm the guard redirects instead of erroring, with no retry storm in the network tab
- [ ] check the browser console for errors and verify light and dark themes on every new screen

### Task 15: [Final] Update documentation

- [ ] add `help/docs/01__Chains/8__Testing/testing.md` covering the chain tab, test cases, mocks and runs
- [ ] add `help/docs/03__Admin_Tools/9__Testing/testing.md` covering the cross-chain lists and run sets
- [ ] update the section table in `help/README.md`
- [ ] document the mock interception flow from plan 2, including that an element with no matching mock receives `404`
- [ ] document that the section only appears where the testing service is deployed and that it is not for production
- [ ] note that cases inside one run execute sequentially while separate runs execute in parallel
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**

- the browser click-through requires the whole stack: catalog, engine with mocking enabled, the testing service, and a
  deployed chain
- screenshots for the documentation pages are captured during that session

**Known limitation to document:**

- the same test case placed in two different run sets can execute concurrently; runs are isolated from each other, cases
  are not
