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

Adding a method means adding it to the `Api` interface **and** to both implementations. The VS Code
side turns method names into webview message types, so a rename there is a protocol change, not a
refactor.

## Types

- `apiTypes.ts` is the source of truth for business types (`Chain`, `Element`, `IntegrationSystem`,
  `Specification`, `SpecificationGroup`, `Variable`, and the rest). Extend what is there before
  declaring a local shape — duplicated near-identical types are the usual source of drift.
- Entities with an identity extend `BaseEntity`: `id`, `name`, `description`, and the optional audit
  fields `createdWhen`, `createdBy`, `modifiedWhen`, `modifiedBy`.
- Property names mirror the REST DTOs and the on-disk YAML, so renaming a property is a wire change
  even when TypeScript is happy. Renaming a local type is free; renaming a field is not.

## Errors

`RestApi` converts Axios failures into `RestApiError`, carrying the response code and body. Extract
what you show the user through `src/misc/error-utils.ts` rather than reading `error.message`
directly — the useful text is usually in the response body.

## Utilities

Check `src/misc/` before writing a helper. The ones worth knowing:

| File | Use for |
|---|---|
| `confirm-utils.ts` | `confirmAndRun()` — wrap a destructive action in a confirm dialog |
| `error-utils.ts` | human-readable message out of `RestApiError` / `Error` |
| `format-utils.ts`, `date-utils.ts` | date and file-size formatting, timestamp math |
| `download-utils.ts`, `file-utils.ts` | download a Blob, read and validate an uploaded file |
| `json-helper.ts` | JSON parse/serialize that does not throw |
| `tree-utils.ts` | folder and chain tree traversal |
| `clipboard-util.ts` | copy to clipboard |
| `log-export-utils.ts` | export logs to Excel (exceljs) |
| `entity-filter-utils.ts`, `group-utils.ts`, `selection-utils.ts` | list filtering, grouping, selection state |
| `chain-graph-utils.ts`, `chain-graph-swimlane-utils.ts` | chain graph geometry and swimlane layout |
| `element-code-utils.ts`, `used-properties-analyzer.ts` | element code view, property usage analysis |
| `protocol-utils.ts`, `operations-utils.ts` | service protocol and operation helpers |
| `antd-app.ts` | the `modal` / `notification` instances bound to the antd App context |

`antd-app.ts` matters more than it looks: Ant Design's static `Modal.confirm` and `notification`
APIs sit outside the React tree and lose theme context. Everything imperative goes through the
instances exported there — which is what `confirmAndRun` and `useNotificationService` already do.
