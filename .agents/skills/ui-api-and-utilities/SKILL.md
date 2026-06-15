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
- `format-utils.ts`: date/file-size formatting
- `date-utils.ts`: timestamp operations
- `download-utils.ts`: file download flow (Blob to link click)
- `file-utils.ts`: file reading and validation
- `error-utils.ts`: error message extraction
- `json-helper.ts`: safe JSON parse/serialize
- `tree-utils.ts`: folder/chain tree traversal
- `clipboard-util.ts`: clipboard copy helper
- `log-export-utils.ts`: Excel log export

When adding new helper logic, first verify an existing utility does not already solve the same concern.
