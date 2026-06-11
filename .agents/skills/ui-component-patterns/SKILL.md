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
- Use `InlineEdit` for editable cells.
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
