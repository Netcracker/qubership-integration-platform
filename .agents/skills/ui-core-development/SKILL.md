---
name: ui-core-development
description: Core development standards for the Qubership Integration Platform UI. Use when implementing, refactoring, or testing code in ui/.
---

# UI Core Development

Use this skill for everyday development in `ui/`.

## Engineering principles

- Treat code as production-quality: follow SOLID and DRY.
- Reuse existing project components and abstractions before introducing new ones.
- Prefer Ant Design components over raw HTML when equivalent UI exists.
- For Ant Design tables and similar typed APIs, use explicit and meaningful generic types.

## Quality gates

- Keep changes free of lint and formatting issues.
- If linter/prettier reports unrelated pre-existing violations outside your change, do not expand scope to fix them unless requested.

## Project context

- The UI is a React + TypeScript SPA for integration-chain management.
- Runtime targets are browser and VS Code webview.
- All UI decisions must be compatible with light, dark, high-contrast, and VS Code webview modes.

## Common commands

- Build: `npm run build`
- Test: `npm run test`
- Lint: `npm run lint`
- Format check: `npm run format:check`

Use targeted tests when possible:

- Single file: `npm test -- --testPathPattern=tests/api/restApi`
- By test name: `npm test -- --testNamePattern="should parse"`

## UI package map

Use this as a quick orientation when placing new code:

- `src/api/`: API interface, REST/VS Code implementations, business types
- `src/components/`: React components (table/modal/graph/mapper/admin tools/notifications)
- `src/hooks/`: custom hooks, including `useTableInfiniteScroll.ts` for a table that loads the next page when a
  sentinel row scrolls into view. Reuse it rather than writing a new observer; `src/pages/Sessions.tsx` still carries
  an inline copy from before the hook existed, so a search turns up two implementations.
- `src/hooks/testing/`: the hooks the testing screens share — `useTestingEntityList` for a list, `useTestingBulkActions`
  for its toolbar, `useTestingEntityEditor` for an editor page.
- `src/misc/`: utility functions
- `src/theme/`: Ant Design tokens and semantic color system
- `src/styles/`: global CSS and overrides
- `src/pages/`: route-level components
- `src/permissions/`: access control functions/components

## Testing style

- Prefer unit test descriptions in the style `should ... when ...` where applicable.

## jsdom prerequisites

jsdom is missing browser APIs that production code and react-router rely on. Two of them are already solved; reuse the
solutions rather than stubbing again.

- **Data routers.** A suite that renders `createMemoryRouter` must call `installDataRouterGlobals()` from
  `tests/helpers/dataRouterGlobals.ts` first. jsdom ships no fetch API, and a data router builds a `Request` per
  navigation, so without it a `useBlocker` suite fails on an error that names none of this.
- **`crypto.randomUUID`.** jsdom's `crypto` has no `randomUUID`, which production code uses for client-side row and
  modal keys. `tests/setup/crypto-random-uuid.ts` fills it in and runs globally from `jest.config.ts`, so no suite
  needs to import it.
- **`LightweightTable` mock.** `tests/__mocks__/LightweightTable.tsx` renders the custom entries of
  `rowSelection.selections`, which is what makes "select all that match the filters" reachable from a test. Drive
  sorting through the columns the component actually supplied rather than typing a column key into the test.
