---
name: ui-api-and-utilities
description: API integration and utility usage conventions in QIP UI. Use when editing src/api, src/misc, or code that consumes these layers.
---

# UI API and Utilities

The API layer and the shared helpers under `src/misc/`.

## One interface, two transports

```text
src/api/api.ts              — the Api interface, plus the exported `api` singleton
src/api/rest/restApi.ts     — Axios implementation (browser)
src/api/rest/vscodeExtensionApi.ts — VS Code messaging implementation (webview)
src/api/apiTypes.ts         — every business type
```

`api.ts` ends with `export const api: Api = isVsCode ? new VSCodeExtensionApi() : new RestApi()`.
Import that singleton (`import { api } from ".../api/api.ts"`) and let it pick the transport —
never instantiate `RestApi` or `VSCodeExtensionApi` yourself, or the code breaks in the webview.

Adding a method means adding it to the `Api` interface **and** to both implementations. A
`VSCodeExtensionApi` method sends a webview message whose type is a string literal, by convention the
method name (`sendMessageToExtension("getChain", payload)`). That string has to match the `case` the
extension dispatches on in `vscode-extension/src/web/response/apiRouter.ts`, so renaming it is a
protocol change, not a refactor. Many methods have no extension counterpart and throw
`Method <name> not implemented.`; such a stub is acceptable when the extension lacks the feature.

## Types

- `apiTypes.ts` is the source of truth for business types (`Chain`, `Element`, `IntegrationSystem`,
  `Specification`, `SpecificationGroup`, `Variable`, and the rest). Extend what is there before
  declaring a local shape — duplicated near-identical types are the usual source of drift.
- Entities with an identity extend `BaseEntity`: `id`, `name`, `description`, and the optional audit
  fields `createdWhen`, `createdBy`, `modifiedWhen`, `modifiedBy`.
- Property names mirror the REST DTOs and the on-disk YAML, so renaming a property is a wire change
  even when TypeScript is happy. Renaming a local type is free; renaming a field is not.

## Errors

`RestApi` converts Axios failures into `RestApiError`, carrying the response code and body. When the
body is an `ErrorResponse`, it copies the body's `errorMessage` into the error's message; for any
other body the message is usually empty. Read the text you show the user with
`getErrorMessage(error, fallback)` from `src/misc/error-utils.ts`. It returns the fallback only when
the error has no string `message`, so an empty message comes back empty.

## Utilities

Check `src/misc/` before writing a helper. The ones worth knowing:

| File | Use for |
|---|---|
| `confirm-utils.ts` | `confirmAndRun()` — wrap a destructive action in a confirm dialog |
| `error-utils.ts` | `getErrorMessage()` — the message of a `RestApiError` or an `Error`, or a fallback |
| `format-utils.ts`, `date-utils.ts` | timestamp, date, and duration formatting; Dayjs and epoch-millisecond conversion |
| `download-utils.ts` | download a `File`, merge ZIP archives, build a `File` from a response |
| `file-utils.ts` | `getFileExtension()` and `removeExtension()` on a file name |
| `json-helper.ts` | `parseJson()`, which throws on invalid JSON and with a `TypeError` when a guard rejects the result; `parseJsonOrDefault()`, which returns the default instead |
| `tree-utils.ts` | `traverseElementsDepthFirst()`, a depth-first walk over any tree linked by `children` |
| `clipboard-util.ts` | copy to clipboard |
| `log-export-utils.ts` | export logs to Excel (exceljs) |
| `entity-filter-utils.ts`, `group-utils.ts`, `selection-utils.ts` | list filtering, grouping, selection state |
| `chain-graph-utils.ts`, `chain-graph-swimlane-utils.ts` | chain graph geometry and swimlane layout |
| `element-code-utils.ts`, `used-properties-analyzer.ts` | element code view, property usage analysis |
| `protocol-utils.ts`, `operations-utils.ts` | service protocol and operation helpers |
| `antd-app.ts` | the `message` and `modal` instances bound to the antd App context |

`antd-app.ts` matters more than it looks: Ant Design's static `Modal` and `message` methods render
outside the React tree and lose theme context. Imperative modal and message calls go through the
instances exported there. Notifications take another path: `useNotificationService` reads the
instance that `EventNotification` creates with `notification.useNotification()` and provides through
`NotificationApiContext`.

`confirmAndRun` takes an options object, `{ title, onOk, content?, okText?, okType?, okButtonProps?,
... }`, and wraps `modal.confirm` from `antd-app.ts`. Returning a promise from `onOk` keeps the
dialog in its loading state until the action settles, which is what you want for a destructive call.

Two of them read innocently and are not:

- `formatOptional` in `format-utils.ts` tests the value for truthiness, so it renders a **zero** as
  the empty placeholder. A numeric field whose zero is meaningful needs its own formatter;
  `src/components/testing/endpointMocks.ts` has `formatMockNumber` for the mock delay, which is
  created as `0`.
- `isHttpProtocol` in `protocol-utils.ts` accepts **soap** as well as http, so a check that has to
  mean HTTP alone compares `normalizeProtocol()` against `"http"` itself, the way
  `src/components/testing/testingElements.ts` does.

## The testing-service list contract

Every list request the testing service serves is validated per entity, and a mismatch is a 400 rather
than a silently ignored parameter:

- `sort_by` is checked against the fields that entity declares (`internal/dao/sorting.go`).
- A filter is checked for a known feature, a condition declared for that feature, and the value count
  that condition takes (`internal/dao/filtering.go`).

The client mirrors both in `src/hooks/filter/useTestingFilter.ts` and
`src/hooks/testing/useTestingEntityList.ts`. **A new sortable column needs its wire name added to
that entity's sort-field tuple first.** Without it, every sorted request on that list comes back 400.
The same holds for a new filter column and its conditions.

## Hooks behind a testing list

- A search box that drives a **request** is debounced with `useDebouncedValue` (`src/hooks/`), so a
  burst of keystrokes issues one request rather than one per character. It returns `[value, flush]`;
  hand the flush to `CompactSearch` as `onSearchConfirm`, or Enter and the search button wait out the
  delay instead of searching immediately. A box that filters rows already in memory needs none of
  this.
- A list page composes `useTestingEntityList` with `useTestingBulkActions` (`src/hooks/testing/`)
  rather than repeating the refresh, export and confirm callbacks. The list hook owns the selection,
  the paging and the name lookups; the bulk-actions hook takes the pieces an action works on and
  returns only the handlers the page passed an action for. The plural noun both of them name comes
  from the list source (`testCasesListSource.entityName`), so it is written once.
- A screen with no `useTestingEntityList` — `src/pages/testing/TestCaseRunErrors.tsx` loads its list
  in one request — still uses the bulk-actions hook, passing its own selection and callbacks.
