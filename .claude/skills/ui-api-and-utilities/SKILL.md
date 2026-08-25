---
name: ui-api-and-utilities
description: API integration and utility usage conventions in QIP UI. Use when editing src/api, src/misc, or code that consumes these layers.
---

# UI API and Utilities

Use this skill when touching the API layer or shared helpers.

## API architecture

The UI uses interface + dual implementation:

- `src/api/api.ts`: API interface
- `src/api/rest/restApi.ts`: Axios HTTP implementation
- `src/api/rest/vscodeExtensionApi.ts`: VS Code messaging implementation
- `src/api/apiTypes.ts`: business type source of truth

## API usage rules

- Import `api` from `src/api/`; this singleton selects correct implementation by runtime.
- Reuse and extend existing types from `src/api/apiTypes.ts` before creating new local duplicates.
- Use `BaseEntity` pattern (`id`, `name`, `description`, optional audit fields) for compatible business types.
- Treat REST failures as `RestApiError` and extract user-facing messages via `src/misc/error-utils.ts`.

## Utilities catalog

Prefer existing helpers under `src/misc/`:

- `confirm-utils.ts`: `confirmAndRun()` for destructive action confirmation
- `format-utils.ts`: date/file-size formatting, and `formatOptional()`. `formatOptional` tests the value for
  truthiness, so it renders a **zero** as the empty placeholder. A numeric field whose zero is meaningful needs its own
  formatter; `src/components/testing/endpointMocks.ts` has `formatMockNumber` for the mock delay, which is created as
  `0`.
- `date-utils.ts`: timestamp operations
- `download-utils.ts`: file download flow (Blob to link click)
- `file-utils.ts`: file reading and validation
- `error-utils.ts`: error message extraction
- `json-helper.ts`: safe JSON parse/serialize
- `tree-utils.ts`: folder/chain tree traversal
- `clipboard-util.ts`: clipboard copy helper
- `log-export-utils.ts`: Excel log export
- `protocol-utils.ts`: protocol classification. `isHttpProtocol()` accepts **soap** as well as http, so a check that
  has to mean HTTP alone compares `normalizeProtocol()` against `"http"` itself, the way
  `src/components/testing/testingElements.ts` does.

When adding new helper logic, first verify an existing utility does not already solve the same concern.

## The testing-service list contract

Every list request the testing service serves is validated per entity, and a mismatch is a 400 rather than a silently
ignored parameter:

- `sort_by` is checked against the fields that entity declares (`internal/dao/sorting.go`).
- A filter is checked for a known feature, a condition declared for that feature, and the value count that condition
  takes (`internal/dao/filtering.go`).

The client mirrors both in `src/hooks/filter/useTestingFilter.ts` and `src/hooks/testing/useTestingEntityList.ts`.
**A new sortable column needs its wire name added to that entity's sort-field tuple first.** Without it, every sorted
request on that list comes back 400. The same holds for a new filter column and its conditions.

## Hooks behind a testing list

- A search box that drives a **request** is debounced with `useDebouncedValue` (`src/hooks/`), so a burst of keystrokes
  issues one request rather than one per character. It returns `[value, flush]`; hand the flush to `CompactSearch` as
  `onSearchConfirm`, or Enter and the search button wait out the delay instead of searching immediately. A box that
  filters rows already in memory needs none of this.
- A list page composes `useTestingEntityList` with `useTestingBulkActions` (`src/hooks/testing/`) rather than repeating
  the refresh, export and confirm callbacks. The list hook owns the selection, the paging and the name lookups; the
  bulk-actions hook takes the pieces an action works on and returns only the handlers the page passed an action for.
  The plural noun both of them name comes from the list source (`testCasesListSource.entityName`), so it is written
  once.
- A screen with no `useTestingEntityList` — `src/pages/testing/TestCaseRunErrors.tsx` loads its list in one request —
  still uses the bulk-actions hook, passing its own selection and callbacks.
