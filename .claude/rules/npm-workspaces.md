---
paths:
  - "{schemas,ui,vscode-extension}/**"
---

### npm workspaces (root `package.json`)

```bash
npm install                                    # Installs all workspaces (schemas, ui, vscode-extension) + creates symlinks between them
npm run build                                  # schemas, the ui app, then the vscode-extension build; CI runs it
npm -w @netcracker/qip-ui run dev              # Vite dev server (port 4200)
npm -w @netcracker/qip-ui run build            # Vite app build (the working one)
npm test --workspaces --if-present             # Run tests in all workspaces that define them
```

Node `>=22.5.0` is required (`engines` in the root `package.json`), and CI runs `22.x`. Treat a test or build result
from another Node major as unconfirmed, and do not add a polyfill that only the local mismatch needs.

Browse the UI through the nginx proxy at `http://localhost:8080`, never the bare Vite port 4200: API routing and the
library bootstrap work only through nginx, so 4200 shows an empty chain list and a deep link to a chain throws.

### Build order between workspaces

`@netcracker/qip-ui` imports `@netcracker/qip-schemas` as a workspace dep, and `@netcracker/qip-vscode-extension` embeds the qip-ui library bundle. So the build order matters:

1. `npm -w @netcracker/qip-schemas run build` — writes `schemas/dist/index.mjs` (the runtime `schemasByType` map). Without this, qip-ui's vite build can't resolve `@netcracker/qip-schemas`.
2. `npm -w @netcracker/qip-ui run build:lib:bundled` — writes `ui/dist-lib/index.bundled.es.js` (React embedded) and `cip-ui.css`; `build:lib:types` adds the declarations.
3. `npm -w @netcracker/qip-vscode-extension run compile-web` — webpack + copy-webpack-plugin pulls `ui/dist-lib` into `vscode-extension/dist/web/qip-ui/`.

`npm -w @netcracker/qip-vscode-extension run build` runs all three: its `prepare-deps` script covers steps 1 and 2, then
`compile-web` runs.

`npm -w @netcracker/qip-ui run build` (the **app** build, not `build:lib`) builds the standalone web UI bundle, a
separate output.

A change touching schemas + UI + vscode-extension usually only needs `npm install` once at the root — workspace symlinks propagate. No publish step required for local testing.
