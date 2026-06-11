---
name: ui-theme-and-vscode-webview
description: Theme and environment behavior for QIP UI. Use when working on styles, color logic, rendering, or mode-specific behavior.
---

# Theme and VS Code Webview

Use this skill when any UI behavior depends on theme or runtime environment.

## Supported modes

The UI must work in all four modes:

- Light (default)
- Dark (`[data-theme="dark"]`)
- High contrast (`[data-theme="high-contrast"]`)
- VS Code webview (`:root.vscode-webview`)

## Source of theme values

- Browser mode uses fallback `--vscode-*` values from `theme-variables.css`.
- VS Code webview mode receives actual IDE theme variables injected by extension host.
- Do not assume browser fallback values are authoritative in webview mode.

## Runtime detection

Use `useVSCodeTheme()` in components:

```typescript
const { isDark, isVSCodeWebview, palette } = useVSCodeTheme();
```

- Use `isDark` for conditional visuals (for example chart/Monaco/icon variants).
- Use `isVSCodeWebview` for behavior differences specific to extension runtime.

## Theming rule

- Any new visual behavior must be validated for browser and webview runtimes, across all supported theme modes.
