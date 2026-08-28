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
- Editable cells use the `InlineEdit` component. Its edit commits on **Enter** only: the antd `Form`
  inside it is rendered with `component={false}`, so the `onBlur` it passes has no element to attach
  to and clicking away discards the edit. `onFinish` also toggles twice, so the cell stays open after
  a commit. Every consumer in the workspace inherits both, so document the Enter-only behavior for
  users rather than working around it per call site, and fix `src/components/InlineEdit.tsx` if the
  behavior has to change.
- Text search reuses `normalizeSearchTerm` and `matchesByFields` from
  `src/components/table/tableSearch.ts` instead of a local filter.
- Building a search haystack over mixed types, do not use `filter(Boolean)` — it drops valid `0` and
  `false`. Filter `null`, `undefined`, and `""` explicitly.
- Action-button columns take the `actions-column` class; use `ACTIONS_COLUMN_CLASS` and the helpers
  in `actionsColumn.ts`.

### Horizontal scroll

Take the `scroll` prop from `tableScroll(scrollX, rowCount)` in
`src/components/table/tableScroll.ts` rather than writing the ternary inline. antd v6
(`@rc-component/table`) treats `y: ""` as a *defined* value, which splits the header from a
fixed-height body — the sticky header we want — while `undefined` does not. An empty table has to
drop `y` entirely, or the empty-state placeholder ends up trapped inside that split body. Omitting
`y` and passing `y: ""` are therefore not interchangeable.

## Access control

- Read permissions with `usePermissions` (`src/permissions/usePermissions.tsx`).
- Evaluate them with `hasPermissions` (`src/permissions/funcs.ts`).
- Wrap guarded content in the `Require` component — the file is `src/permissions/Require.tsx` and
  the export is `Require`, not `Required`.
- Protected actions use `ProtectedButton` and `ProtectedDropdown`.
- A restricted page falls back to `NotAuthorized`.

## Modals

- Always go through `useModalContext()`. Do not render an Ant Design `<Modal>` conditionally in TSX.
- Open with `showModal({ component: <YourModal /> })` from the parent; close with
  `closeContainingModal()`.
- Modal components live under `src/components/modal/`.
- Confirmation dialogs use `confirmAndRun` from `src/misc/confirm-utils.ts`. It takes an options
  object — `{ title, onOk, content?, okText?, okType?, okButtonProps?, ... }` — and wraps
  `modal.confirm`. Returning a promise from `onOk` keeps the dialog in its loading state until the
  action settles, which is what you want for a destructive call.
- Take `Upload.Dragger` off the root `antd` import. `antd/es/upload/Dragger` is untranspiled ESM and
  breaks the Jest run; `src/components/modal/ImportSessions.tsx` still imports it that way.
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
ref from the `useBlocker` predicate; see `src/pages/testing/TestCasePage.tsx` and
`src/pages/testing/EndpointMockPage.tsx`. The predicate also exempts navigations that stay under the
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
  `@netcracker/qip-schemas` and re-exported through
  `src/components/modal/chain_element/chainElementSchemaModules.ts`; the element type is the key
  (`http-trigger`). Custom fields and widgets belong under
  `src/components/modal/chain_element/field/` and `.../widget/`.

## Notifications and labels

- Always use `useNotificationService`; never call the Ant Design `notification` API directly.
- `EntityLabels` displays labels, `LabelsEdit` edits them.

## Icons

- Reach for `@ant-design/icons` first; use `lucide-react` or `react-icons` only for an icon that
  library lacks.
- Element (kamelet) icons are SVG assets in `src/assets/`, served through `IconProvider`
  (`src/icons/`).
- Render through `OverridableIcon` so a runtime override can replace the icon:

```tsx
<OverridableIcon name={data.elementType as IconName} style={{ fontSize: 16 }} />
```

`IconProvider` merges three sources in order: common Ant Design icons, custom element icons, then
runtime overrides from app config. An override may be a React component or an SVG string; SVG
strings are normalized so hardcoded colors become `currentColor` and the icon follows the theme.
