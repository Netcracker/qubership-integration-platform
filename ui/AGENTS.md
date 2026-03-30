# Project Guidelines

You are an expert software engineer for this project.
Stick to the SOLID and DRY principles.
Use components, provided by Ant Design library and existing components in project if possible.
Prefer Ant Design components over raw HTML. Provide decent data model type for And Design components generics.
Solutions provided by you must have no lint or format issues.
You should ignore the issues found by linter or prettier in files that are not a part of solution proposed by you.

## Project Overview

React + TypeScript SPA for visual integration chain management.
The application targets two environments: browser and Visual Studio Code webview.
The application supports four theme modes: light, dark, high contrast, and Visual Studio Code webview mode.
All UI decisions must account for both environments and all four theme modes.

## Tools

- **Build:** `npm run build` compiles source code, outputs to /dist.
- **Test:** `npm run test` runs unit-test, must pass on solution before commits.
  - **Run a single test file:** `npm test -- --testPathPattern=tests/api/restApi`
  - **Run tests matching name:** `npm test -- --testNamePattern="should parse"`
  - Prefer `flushPromises` (act + Promise.resolve) over `waitFor`/`findBy` when possible — faster and more stable.
  - For flaky async tests (form submit, API calls): extract handlers into pure functions in a separate
    `*Handlers.ts` file, unit test them directly.
- **Linter:** `npm run lint` runs linter on source files.
- **Prettier:** `npm run format:check` runs code format check, must pass on solution before commits.

## Tech stack

- **UI**: Ant Design v5, TailwindCSS v4
- **Graph**: @xyflow/react (ReactFlow v12) + ELK.js autolayout
- **Forms**: Ant Design Form + @rjsf/antd (JSON Schema Forms)
- **Data fetching**: @tanstack/react-query (staleTime: Infinity for library data)
- **HTTP**: Axios + axios-rate-limit via `src/api/rest/restApi.ts`
- **Code editor**: @monaco-editor/react
- **Routing**: react-router-dom v7
- **Build**: Vite v6

## Project Structure cheat sheet

```text
src/
├── api/            — Api interface, REST/Visual Studio Code implementations, all business types
├── components/     — All React components
│   ├── table/      — Reusable table primitives (filters, inline edit, resize, column toggle)
│   ├── modal/      — Modals + RJSF chain element form + custom fields/widgets
│   ├── graph/      — XYFlow chain graph nodes and controls
│   ├── mapper/     — Data mapping visual graph and table views
│   ├── admin_tools/ — Import instructions, variables, access control (handlers in *Handlers.ts for unit tests)
│   └── notifications/ — Notification bar, SSE, context providers
├── hooks/          — Custom React hooks (data fetching, theme, graph)
├── misc/           — Pure utility functions
├── theme/          — Ant Design tokens, semantic colors, theme lifecycle
├── styles/         — Global CSS: theme variables, antd overrides, reactflow theme
├── pages/          — Route-level page components
└── permissions     — Access control components and functions
```

## Theme modes

| Mode                           | How it activates                          | Where colors come from                                     |
| ------------------------------ | ----------------------------------------- | ---------------------------------------------------------- |
| **Light**                      | Default (no attribute)                    | `--vscode-*` defaults in `theme-variables.css`             |
| **Dark**                       | `[data-theme="dark"]` on `:root`          | Dark overrides in `theme-variables.css`                    |
| **High Contrast**              | `[data-theme="high-contrast"]` on `:root` | HC overrides in `theme-variables.css`                      |
| **Visual Studio Code Webview** | `:root.vscode-webview` class              | Real `--vscode-*` variables injected by the extension host |

In Visual Studio Code webview mode the extension host injects the actual IDE theme colors as CSS variables,
overriding the browser fallbacks. The browser fallbacks in `theme-variables.css` exist only for
standalone browser usage.

Detect current theme in components via `useVSCodeTheme()` hook:

```typescript
const { isDark, isVSCodeWebview, palette } = useVSCodeTheme();
```

- `isDark` — use for conditional logic (chart colors, Monaco theme, icon variants).
- `isVSCodeWebview` — use to adapt behavior when running inside the Visual Studio Code extension.

## Table implementation essentials

- Use `Table` component provided by Ant Design library.
- Explicitly specify a record type parameter for `Table` and `TableColumn`.
- For tables located in flex containers use `flex-table` class name.
- Use `InlineEdit` component to implement editable table cells.
- For table text-search, use shared helpers from `src/components/table/tableSearch.ts`
  (`normalizeSearchTerm`, `matchesByFields`) instead of local duplicated logic.
- In search haystack builders for mixed types, do not use `filter(Boolean)`; keep valid
  `0`/`false` values and explicitly filter only `null`/`undefined`/`""`.

## Access control

Access to some elements of the UI can be restricted.

- Use `usePermissions` function to get user permissions from the context.
- Use `hasPermissions` function to check user permissions.
- Use `Required` component to restrict access to a nested components.
  There are `ProtectedButton` and `ProtectedDropdown` components to make a button or dropdown menu with access check.
- If you need to restrict access a page use `NotAuthorized` as a fallback component.

## Modals

- Always use `useModalContext()`. Do not render `<Modal>` conditionally in TSX.
- Use `showModal({ component: <YourModal /> })` from parent to show modal.
- Use `closeContainingModal()` to close modal.
- Add to `src/components/modal/` directory.
- Use `confirmAndRun(message, action)` from `src/misc/confirm-utils.ts` for confirmation dialog.

## Forms

There are two form kinds in the application:

- Regular forms.
- Integration chain elements' parameters forms.

Regular forms are build using Ant Design `Form` component.

Integration chain elements' parameters forms are generated from JSON schemas using `@rjsf/antd`. These
schemas are dynamically loaded at runtime:

```typescript
// Schemas from npm package @netcracker/qip-schemas
const schemaModules = import.meta.glob(
  "/node_modules/@netcracker/qip-schemas/assets/*.schema.yaml",
  { as: "raw", eager: true },
);
// Loaded by element type: e.g., "http-trigger" → "http-trigger.schema.yaml"
```

AJV JSON Schema validator (`@rjsf/validator-ajv8`) is used for validation.
Custom RJSF fields and widgets can be found in `src/components/modal/chain_element/field/` and `src/components/modal/chain_element/widget/`.

## Notifications

Always use `useNotificationService` — never call Ant Design `notification` directly.

## Labels

There are two components that are used to manage entity's labels:

- `EntityLabels` to display labels.
- `LabelsEdit` as labels input field.

Use `EntityLabels` component to display entity's labels and `LabelsEdit` component to make them editable.

## Icons

- Primary icons: `@ant-design/icons` — check here first.
- Secondary icons: `lucide-react` or `react-icons` for specialized icons.
- Custom element icons (kamelets): SVG assets in `src/assets/`, loaded via `IconProvider`
  from `src/icons/`.

## Overridable icons

Icons resolved from `IconProvider` context, which merges:

1. Common icons (Ant Design)
1. Element icons (custom)
1. Runtime overrides (from config)

```typescript
<OverridableIcon
  name="serviceCall"              // IconName type
  style={{ fontSize: 16 }}
/>
```

### Icon Override Pattern

```typescript
// appConfig.ts
configure({
  icons: {
    serviceCall: CustomReactComponent,
    httpTrigger: "<svg>...</svg>", // SVG string
  },
});
```

SVG strings are normalized: hardcoded colors → `currentColor` for theme support.

## Element Type → Icon Mapping

Node components automatically resolve icons:

```typescript
<OverridableIcon name={data.elementType as IconName} />
// "http-trigger" → HTTP icon
```

## API layer

The project uses an **interface + dual implementation** pattern:

```text
src/api/api.ts                        — Api interface
src/api/rest/restApi.ts               — HTTP (Axios) implementation
src/api/rest/vscodeExtensionApi.ts    — Visual Studio Code messaging implementation
src/api/apiTypes.ts                   — All business types (1393 lines)
```

- Always import `api` from `src/api/` — the singleton picks the right implementation automatically.
- All business types (`Chain`, `Element`, `LibraryData`, `Variable`, `IntegrationSystem`, etc.)
  are in `src/api/apiTypes.ts` — check there before creating new types.
- Base entity pattern: extend `BaseEntity` (`id`, `name`, `description`, optional audit fields).
- HTTP errors are wrapped as `RestApiError`; use `src/misc/error-utils.ts` to extract messages.

## Utility functions

All in `src/misc/`:

| File                  | Use for                                                             |
| --------------------- | ------------------------------------------------------------------- |
| `confirm-utils.ts`    | `confirmAndRun()` — wrap any destructive action in a confirm dialog |
| `format-utils.ts`     | Date formatting, file size formatting                               |
| `date-utils.ts`       | Timestamp manipulation                                              |
| `download-utils.ts`   | File download (Blob → link click)                                   |
| `file-utils.ts`       | File reading and validation                                         |
| `error-utils.ts`      | Extract human-readable messages from `RestApiError` / `Error`       |
| `json-helper.ts`      | Safe JSON parse/serialize with error handling                       |
| `tree-utils.ts`       | Tree structure traversal (folders/chains)                           |
| `clipboard-util.ts`   | Copy to clipboard                                                   |
| `log-export-utils.ts` | Export logs to Excel (exceljs)                                      |
