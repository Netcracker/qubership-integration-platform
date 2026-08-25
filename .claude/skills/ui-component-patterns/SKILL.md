---
name: ui-component-patterns
description: Component implementation patterns for QIP UI. Use when editing tables, forms, modals, notifications, labels, icons, and permissions-related UI.
---

# UI Component Patterns

Use these conventions in `ui/src/components/`.

## Tables

- Use Ant Design `Table`.
- Provide explicit record generic type for table and column definitions.
- In flex containers, add `flex-table` class.
- Use `InlineEdit` for editable cells. Its edit commits on **Enter** only: the antd `Form` inside it is rendered with
  `component={false}`, so the `onBlur` it passes has no element to attach to and clicking away discards the edit.
  `onFinish` also toggles twice, so the cell stays open after a commit. Every consumer in the workspace inherits both,
  so document the Enter-only behavior for users rather than working around it per call site, and fix
  `src/components/InlineEdit.tsx` if the behavior has to change.
- Reuse search helpers from `src/components/table/tableSearch.ts` (`normalizeSearchTerm`, `matchesByFields`).
- In mixed-type search haystacks, do not use `filter(Boolean)`; only filter `null`, `undefined`, and empty string.
- For action-button columns, use `actions-column` class and helpers/constants from `actionsColumn.ts`.

## Access control UI

- Read permissions with `usePermissions`.
- Evaluate rights with `hasPermissions`.
- Wrap guarded content with `Required`.
- Use `ProtectedButton` and `ProtectedDropdown` for protected actions.
- Use `NotAuthorized` as fallback on restricted pages.

## Modals

- Always use `useModalContext()`.
- Do not conditionally render Ant Design `<Modal>` directly in TSX.
- Open via `showModal({ component: <YourModal /> })`.
- Close via `closeContainingModal()`.
- Place modal components under `src/components/modal/`.
- Wrap destructive actions with `confirmAndRun(message, action)` from `src/misc/confirm-utils.ts`.
- Take `Upload.Dragger` off the root `antd` import. `antd/es/upload/Dragger` is untranspiled ESM and breaks the Jest
  run; `src/components/modal/ImportSessions.tsx` still imports it that way.
- A create modal for a testing entity is built on `CreateTestingEntityModal`
  (`src/components/modal/testing/`), which owns the footer wiring, the saving state, the element preselect and the
  failure notification. Pass the entity's own nouns, its element predicate, and one `create` callback; keep each
  modal's defaults in the modal.

## Details drawers

A read-only details drawer for a testing entity is built on `TestingDetailsDrawer` (`src/components/testing/`). It
takes `sections`, an array of `Descriptions` item lists rendered divider-separated, and nothing is optional through a
flag: a drawer with no audit footer passes no audit section. Reuse the item builders beside it — `idItem`, `chainItem`,
`elementItem`, `auditSection` — and `DetailsLink`, which owns the navigate idiom every link in these drawers shares.

## Unsaved-changes blockers on editor pages

An editor page that guards navigation keeps its dirty flag in a **ref**, not in state, and reads that ref from the
`useBlocker` predicate; see `src/pages/testing/TestCasePage.tsx` and `src/pages/testing/EndpointMockPage.tsx`. The
predicate also exempts navigations that stay under the editor's own path, so switching sub-tabs does not prompt.

Do not reach for `src/components/services/useUnsavedChangesWithModal.tsx` here. It reads a state flag, so a save that
clears the flag and navigates in the same tick is blocked by its own navigation: the blocker still sees the render in
which the flag was set.

## Forms

- Regular forms use Ant Design `Form`.
- Use `normalize` and `getValueProps` on `Form.Item` for non-trivial value mapping.
- Chain element parameter forms use `@rjsf/antd` from JSON schema loaded at runtime.
- Validation is based on `@rjsf/validator-ajv8`.
- Custom chain-element fields/widgets belong under:
  - `src/components/modal/chain_element/field/`
  - `src/components/modal/chain_element/widget/`

## Notifications and labels

- Always use `useNotificationService`; do not call Ant Design `notification` directly.
- Use `EntityLabels` for display and `LabelsEdit` for editing labels.

## Icons

- Prefer `@ant-design/icons` first.
- Use `lucide-react` or `react-icons` only for missing specialized icons.
- Element SVG icons come from `src/assets/` and are loaded through `IconProvider` from `src/icons/`.
- Use `OverridableIcon` for icons that support runtime override.
- Runtime SVG overrides must be normalized to `currentColor` for theme compatibility.
