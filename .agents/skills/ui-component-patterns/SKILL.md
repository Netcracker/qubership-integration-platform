---
name: ui-component-patterns
description: Component implementation patterns for QIP UI. Use when editing tables, forms, modals, notifications, labels, icons, and permissions-related UI.
---

# UI Component Patterns

Conventions for `ui/src/components/`. Each section names the shared primitive to reuse — writing a
local variant of one of these is the most common thing a reviewer sends back.

## Tables

- Use the Ant Design `Table`, with an explicit record generic on both the table and its columns.
- In a flex container, add the `flex-table` class.
- Editable cells use the `InlineEdit` component (`src/components/InlineEdit.tsx`). It submits its
  form when focus leaves the editor, and the editors in `src/components/table/`, such as
  `TextValueEdit`, submit it on **Enter**. The cell closes after a successful submit and stays open
  when `onSubmit` throws or its promise rejects. `values` is compared by content, so a caller that
  passes a new object on every render keeps what the user typed. Change the component rather than
  working around it per call site; `tests/components/InlineEdit.test.tsx` covers each of these.
- Text search reuses `normalizeSearchTerm` and `matchesByFields` from
  `src/components/table/tableSearch.ts` instead of a local filter.
- Building a search haystack over mixed types, do not use `filter(Boolean)` — it drops valid `0` and
  `false`. Filter `null`, `undefined`, and `""` explicitly.
- Action-button columns take the `actions-column` class; use `ACTIONS_COLUMN_CLASS` and the helpers
  in `actionsColumn.ts`.
- A table whose rows open a details drawer takes its `onRow` from `rowClickProps(open)` in
  `src/components/table/rowClick.ts`, so a click on a link, a button, or the selection cell does not
  open the drawer behind what it activated. A table that builds `onRow` itself, as `Chains` does for
  drag and drop, calls the `shouldIgnoreRowClick` guard beside it instead.

### Horizontal scroll

Take the `scroll` prop from `tableScroll(scrollX, rowCount)` in
`src/components/table/tableScroll.ts` rather than writing the ternary inline. antd v6
(`@rc-component/table`) treats `y: ""` as a *defined* value, which splits the header from a
fixed-height body — the sticky header we want — while `undefined` does not. An empty table has to
drop `y` entirely, or the empty-state placeholder ends up trapped inside that split body. Omitting
`y` and passing `y: ""` are therefore not interchangeable.

### Key-value pairs

Edit a list of key-value pairs (environment properties, dictionary rules, headers) with
`KeyValuePairsEditor` from `src/components/table/kv/` rather than a local table or text area. It
shows a table tab and a text tab over one `KeyValuePair[]` value and takes `value` and `onChange`, so
it binds to a `Form.Item` directly. The table keeps the first pair of a duplicated key and rejects a
key edit that would duplicate one.

- Use `KeyValuePairsTable` alone where the text form is not wanted, and `KeyValuePairsTextEditor`
  alone for the text form. The text form is `key=value;` per pair, with `=`, `;`, and `\` escaped by
  a backslash.
- Pass `tableColumnNames` to rename the columns and `tableHeader` to put buttons above the table.
- `actionRef` exposes `add`, `clear`, and `activateTab`, so a toolbar outside the editor can drive
  it. `EnvironmentParamsModal` hides the tab bar with `renderTabBar` and switches tabs from its own
  radio group.
- Convert at the edges with the helpers in `src/misc/key-value-text-util.ts`:
  `buildKeyValuePairsFromObject` and `buildObjectFromKeyValuePairs` for a `Record` the API stores,
  `makeArray` and `parse` for the text form.

`KeyValuePairsTextEditor` imports only types from `monaco-editor` and reads `MarkerSeverity` from
the `monaco` instance passed to `onMount`. Keep it that way: a runtime import loads Monaco's AMD
bundle into every Jest suite that reaches the editor, and those suites fail with
`define is not defined`. A test that renders the text tab mocks `@monaco-editor/react`; see
`tests/components/table/kv/KeyValuePairsTextEditor.test.tsx`.

## Access control

- Read permissions with `usePermissions` (`src/permissions/usePermissions.tsx`).
- Evaluate them with `hasPermissions` (`src/permissions/funcs.ts`).
- Wrap guarded content in the `Require` component — the file is `src/permissions/Require.tsx` and
  the export is `Require`, not `Required`.
- Protected actions use `ProtectedButton` and `ProtectedDropdown`.
- A restricted page falls back to `NotAuthorized`.

## Modals

- Open a modal through the modal context. Do not render an Ant Design `<Modal>` conditionally in TSX.
- Open with `showModal({ component: <YourModal /> })`, taken from `useModalsContext()`
  (`src/Modals.tsx`). Inside the modal, close it with `closeContainingModal()` from
  `useModalContext()` (`src/ModalContextProvider.tsx`), which returns nothing else.
- Modal components live under `src/components/modal/`.
- Confirmation dialogs use `confirmAndRun`; the `ui-api-and-utilities` skill describes it.
- Take `Upload.Dragger` off the root `antd` import. `antd/es/upload/Dragger` is untranspiled ESM and
  breaks the Jest run. Four files still import it that way: `ImportSessions.tsx` and
  `ImportChains.tsx` under `src/components/modal/`, `src/components/admin_tools/ImportInstructions.tsx`,
  and `src/components/admin_tools/design-templates/CreateDesignTemplateModal.tsx`.
- A create modal for a testing entity is built on `CreateTestingEntityModal`
  (`src/components/modal/testing/`), which owns the footer wiring, the saving state, the element
  preselect and the failure notification. Pass the entity's own nouns, its element predicate, and one
  `create` callback; keep each modal's defaults in the modal.

## Details drawers

A read-only details drawer for a testing entity is built on `TestingDetailsDrawer`
(`src/components/testing/`). It takes `sections`, an array of `Descriptions` item lists rendered
divider-separated, and nothing is optional through a flag: a drawer with no audit footer passes no
audit section. Reuse the item builders beside it — `idItem`, `chainItem`, `elementItem`,
`auditSection` — and `DetailsLink`, which owns the navigate idiom every link in these drawers shares.

## Unsaved-changes blockers on editor pages

An editor page that guards navigation keeps its dirty flag in a **ref**, not in state, and reads that
ref from the `useBlocker` predicate. `useTestingEntityEditor` (`src/hooks/testing/`) does this for
`TestCasePage` and `EndpointMockPage`. Its predicate also exempts navigations that stay under the
editor's own path, so switching sub-tabs does not prompt.

Do not reach for `src/components/services/useUnsavedChangesWithModal.tsx` here. It reads a state
flag, so a save that clears the flag and navigates in the same tick is blocked by its own navigation:
the blocker still sees the render in which the flag was set.

## Forms

Two kinds of form:

- **Regular forms** use the Ant Design `Form`. Non-trivial value mapping goes through `normalize`
  and `getValueProps` on `Form.Item`.
- **Chain element parameter forms** are generated from JSON Schema with `@rjsf/antd`, validated by
  `@rjsf/validator-ajv8`. The schemas come from the `schemasByType` map exported by
  `@netcracker/qip-schemas` and surfaced through
  `src/components/modal/chain_element/chainElementSchemaModules.ts`; the element type is the key
  (`http-trigger`). Custom fields and widgets belong under
  `src/components/modal/chain_element/field/` and `.../widget/`.

## Notifications and labels

- Always use `useNotificationService`; never call the Ant Design `notification` API directly.
- `EntityLabels` displays labels, `LabelsEdit` edits them.

## Icons

- Use `@ant-design/icons`. It is the only icon library the UI depends on, so an icon from another
  library needs a decision to add that dependency.
- Element (kamelet) icons are entries in `elementIcons` in `src/icons/IconDefenitions.tsx`, keyed by
  element type, and `IconProvider` serves them. Each entry is an `@ant-design/icons` component except
  `mcp-trigger`, which is the raw SVG of `src/assets/mcp.svg`.
- Render through `OverridableIcon` so a runtime override can replace the icon:

```tsx
<OverridableIcon name={data.elementType as IconName} style={{ fontSize: 16 }} />
```

`IconProvider` merges three sources in order: `commonIcons`, `elementIcons`, then runtime overrides
from app config. An override is an `IconSource`: a React component, a React element, or an SVG
string. A monochrome SVG, as an element or a string, is normalized so its hardcoded color becomes
`currentColor` and the icon follows the theme; a multicolor one keeps its colors.
