# Documentation Integration Guide

This guide explains how to integrate the built-in documentation system into host applications that use `@netcracker/qip-ui` as an npm dependency (e.g., Visual Studio Code extensions or other web applications).

## Overview

The documentation system works by:

1. **Fetching** Markdown files from a source (Git repository, npm package, or local directory)
2. **Indexing** them to generate search indices, table of contents, and navigation metadata
3. **Serving** the files as static assets that the UI loads at runtime

The fetch and index scripts are included in the `@netcracker/qip-ui` npm package, so host applications can reuse them.

## Step 1: Create a documentation config

Create a `.documentation-config.json` file in your project root:

### Git source (recommended)

```json
{
  "documentation": {
    "source": "git",
    "repository": "https://github.com/Netcracker/qubership-integration-help.git",
    "branch": "main",
    "path": "docs",
    "destination": "public/doc"
  }
}
```

### npm package source

```json
{
  "documentation": {
    "source": "npm",
    "package": "@netcracker/qip-help",
    "version": "^1.0.0",
    "path": "docs",
    "destination": "public/doc"
  }
}
```

### Local directory source

```json
{
  "documentation": {
    "source": "local",
    "path": "../my-docs/docs",
    "destination": "public/doc"
  }
}
```

### Config fields

| Field | Required | Description |
|-------|----------|-------------|
| `source` | Yes | `"git"`, `"npm"`, or `"local"` |
| `repository` | For Git | Git repository URL |
| `branch` | For Git | Branch name (default: `"master"`) |
| `package` | For npm | npm package name |
| `version` | For npm | npm version range |
| `path` | Yes | Path to docs directory within the source |
| `destination` | No | Output directory (default: `"public/doc"`) |

## Step 2: Add build scripts

Add the following scripts to your `package.json`:

```json
{
  "scripts": {
    "fetch-docs": "node node_modules/@netcracker/qip-ui/scripts/fetch-documentation.mjs",
    "prebuild": "npm run fetch-docs"
  }
}
```

This will:
- Clone/download documentation from the configured source
- Generate `paths.json`, `names.json`, `toc.json`, and `search-index.json` indices
- Place everything in the `destination` directory (default: `public/doc`)

## Step 3: Configure the documentation base URL (if needed)

By default, the UI expects documentation assets at `/doc`. If your host application serves them at a different path, configure it at runtime:

```typescript
import { configure } from "@netcracker/qip-ui";

configure({
  documentationBaseUrl: "/my-custom-docs-path",
});
```

## Replacing documentation with custom content

To replace the default documentation with your own, simply point the `.documentation-config.json` to a different source:

```json
{
  "documentation": {
    "source": "git",
    "repository": "https://gitlab.company.com/team/custom-docs.git",
    "branch": "main",
    "path": "docs",
    "destination": "public/doc"
  }
}
```

The documentation system will use your custom content instead.

## Documentation directory structure

The documentation source must follow a specific directory naming convention for proper navigation and search:

```text
docs/
  00__Overview/
    overview.md
  01__Chains/
    chains.md
    1__Graph/
      1__QIP_Elements_Library/
        1__Routing/
          1__HTTP_Trigger/
            http_trigger.md
          2__Condition/
            condition.md
```

### Naming rules

- **Directories** use the format `N__Title_Name` where `N` is the sort order and `Title_Name` becomes the display title (underscores become spaces)
- **Files** use snake_case names: `http_trigger.md` maps to element type `http-trigger`
- Nesting depth is unlimited

### Generated output

After running the fetch script, the destination directory will contain Markdown files alongside generated indices:

```text
public/doc/
  00__Overview/
    overview.md
  01__Chains/
    chains.md
    ...
  paths.json
  names.json
  toc.json
  search-index.json
```

| File | Description |
|------|-------------|
| `00__Overview/`, `01__Chains/`, ... | Markdown files and images |
| `paths.json` | Ordered list of all document paths |
| `names.json` | Hierarchical titles for each document |
| `toc.json` | Table of contents tree structure |
| `search-index.json` | Full-text search index (elasticlunr) |

## Application configuration example

When using `@netcracker/qip-ui` as a library, you can configure the application at runtime via `configure()`. Here is a full example:

```json
{
  "apiGateway": "http://localhost:8080",
  "appName": "my-app",
  "icons": {
    "someIcon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2L2 7v10c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V7l-10-5z\"/></svg>"
  },
  "cssVariables": {
    "--vscode-editor-background": "#ffffff",
    "--vscode-primary-color": "#1890ff",
    "--vscode-button-background": "#1890ff",
    "--vscode-button-foreground": "#ffffff"
  },
  "additionalCss": ["./custom-theme.css"],
  "documentationBaseUrl": "/doc"
}
```

```typescript
import { configure } from "@netcracker/qip-ui";

// Load config from a JSON file or define inline
const config = await fetch("./config.json").then((r) => r.json());
configure(config);
```
