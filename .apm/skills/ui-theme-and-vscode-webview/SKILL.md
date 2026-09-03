---
name: ui-theme-and-vscode-webview
description: Theme and environment behavior for QIP UI. Use when working on styles, color logic, rendering, or mode-specific behavior.
---

# Theme and VS Code Webview

Any UI behavior that depends on color or on the host environment.

## Four modes, two runtimes

| Mode | Activated by | Colors come from |
|---|---|---|
| Light | default, no attribute | `--vscode-*` fallbacks in `styles/theme-variables.css` |
| Dark | `[data-theme="dark"]` on `:root` | dark overrides in the same file |
| High contrast | `[data-theme="high-contrast"]` on `:root` | high-contrast overrides in the same file |
| VS Code webview | `.vscode-webview` class on `:root` | real `--vscode-*` variables injected by the extension host |

The fallbacks in `theme-variables.css` exist for standalone browser use. In the webview the
extension host injects the IDE's actual colors and overrides them, so a value that looks right in
the browser says nothing about how it renders in VS Code. Never hardcode a color that a
`--vscode-*` variable already covers.

## Reading the theme in a component

```typescript
const { isDark, isVSCodeWebview, palette } = useVSCodeTheme();
```

- `isDark` — conditional visuals: Monaco theme, chart colors, icon variants.
- `isVSCodeWebview` — behavior that differs inside the extension host.
- `palette` — resolved VS Code colors, with the browser fallbacks applied when a variable is absent.

## Where color lives

- `styles/theme-variables.css` — the CSS variables and their per-mode overrides.
- `styles/antd-overrides.css`, `styles/reactflow-theme.css` — library surfaces that need their own
  themed rules.
- `theme/antdTokens.ts` — the Ant Design token set.
- `theme/semanticColors.ts` — meaning-carrying palettes shared across components: `METHOD_COLORS`,
  `PROTOCOL_COLORS`, `SOURCE_COLORS`, `COLOR_PALETTE`, `SOLID_TAG_TONES`. Reuse these instead of
  picking a hex per component, or the same concept ends up a different color on two pages.
- `theme/themeInit.ts` — theme lifecycle and the initial mode detection.

## The rule

Any new visual behavior has to hold in all four modes and in both runtimes. Check dark and
high contrast before handing the change back — high contrast is the one that usually breaks, because
a fallback that merely looks dim in dark mode becomes unreadable there.
