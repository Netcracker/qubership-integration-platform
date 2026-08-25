---
name: ui-core-development
description: Core development standards for the Qubership Integration Platform UI. Use when implementing, refactoring, or testing code in ui/.
---

# UI Core Development

Everyday development in `ui/` — a React + TypeScript SPA for visual integration chain management.

The application targets two runtimes, browser and VS Code webview, and four theme modes. Every UI
decision has to hold in all of them; see the `ui-theme-and-vscode-webview` skill.

## Stack

Check `package.json` for versions; what matters is which library owns which concern:

- **Components:** Ant Design v6. Prefer it over raw HTML wherever an equivalent exists, and give
  its generic APIs explicit record types (`Table<Chain>`, not `Table<any>`).
- **Graph:** `@xyflow/react` (ReactFlow v12) with ELK.js autolayout.
- **Forms:** Ant Design `Form` for regular forms, `@rjsf/antd` for chain element parameters.
- **Data fetching:** `@tanstack/react-query`. Library data is loaded once with `staleTime: Infinity`.
- **HTTP:** Axios wrapped in `axios-rate-limit`, in `src/api/rest/restApi.ts`.
- **Editor:** `@monaco-editor/react`. **Routing:** `react-router-dom` v7. **Build:** Vite v6.

## Commands

```bash
npm run lint            # eslint .
npm run format:check    # prettier --check
npm run check-types     # tsc --noEmit — a CI gate; lint alone does not catch type errors
npm run test            # jest
npm run build           # fetch-docs + vite build (the app bundle)
```

Run targeted tests while iterating:

```bash
npm test -- --testPathPattern=tests/api/restApi
npm test -- --testNamePattern="should parse"
```

`eslint`, `prettier`, and `jest` resolve config from the working directory — run them from `ui/`,
not from the repository root, where `eslint` fails with a migration-guide error instead of linting.

Keep your own changes clean of lint and format issues. Pre-existing violations in files outside your
change stay as they are unless the user asks for them.

## Two build outputs

`npm run build` produces the standalone web app. `npm run build:lib:all` produces the library bundle
in `dist-lib/` that the VS Code extension loads (external + bundled + types + `fix-preload.mjs`).
A change that only passes the app build can still break the extension — build both when touching
exports, entry points, or Vite config.

## The fetch-docs trap

`npm run fetch-docs` wipes and rewrites `public/doc`. Vite resolves `public/` against an index taken
at startup, so a dev server that was already running keeps serving the SPA fallback for `/doc/*.json`
and the documentation page reports "Document Not Found". Restart the dev server after re-fetching.

## Where code goes

```text
src/
├── api/          — ApiClient interface, REST and VS Code implementations, all business types
├── components/   — React components (see below)
├── hooks/        — data-fetching, theme, graph, and filter hooks
├── pages/        — route-level components
├── mapper/       — data-mapping model and logic
├── misc/         — pure utility functions
├── permissions/  — access-control components and functions
├── theme/        — Ant Design tokens, semantic colors, theme lifecycle
├── styles/       — global CSS: theme variables, antd overrides, reactflow theme
├── icons/        — IconProvider and icon registry
├── diagrams/     — sequence and DDS diagram generation
├── ai/           — AI panel
└── config/       — runtime app configuration
```

Under `src/components/`: `table/`, `modal/`, `graph/`, `mapper/`, `services/`, `sessions/`,
`admin_tools/`, `dev_tools/`, `elements_library/`, `notifications/`, `labels/`, `logging/`,
`chains/`, `documentation/`, `testing/`, `deployment_runtime_states/`, `ai/`.

Reuse existing components and helpers before adding new ones — the table, modal, and permission
layers all have shared primitives, and duplicating them is the most common review comment.

## Testing style

Name unit tests `should ... when ...` where it fits:

- should show full URI when no path component exists
- should export participants with names
