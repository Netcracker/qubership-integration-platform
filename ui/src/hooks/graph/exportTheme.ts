// DOM-only theme helpers for the chain-graph image export, kept free of the
// modern-screenshot dependency so they stay unit-testable in jsdom.

/**
 * Whether the active theme is dark (incl. high-contrast dark). useVSCodeTheme sets
 * `--vscode-is-dark` as an inline custom property on documentElement; we read that
 * inline value (reliable, no cascade) and fall back to the `data-theme` attribute.
 */
export const isDarkThemeActive = (): boolean => {
  if (typeof document === "undefined") return false;
  const root = document.documentElement;
  const flag = root.style.getPropertyValue("--vscode-is-dark").trim();
  if (flag === "1") return true;
  if (flag === "0") return false;
  const dataTheme = root.getAttribute("data-theme");
  return dataTheme === "dark" || dataTheme === "high-contrast";
};

/**
 * Switches the whole document to the app's own light theme (the light defaults in
 * styles/theme-variables.css) so the snapshot is one-to-one with the light theme —
 * including hard-coded rules like edge stroke that only branch on data-theme. Returns
 * a restore cleanup. Container fill opacity is theme state, not CSS — flipped via
 * ThemeContext during export (see ChainGraphView). Transitions are suppressed so the
 * new colors apply instantly rather than animating into the frame.
 */
export const applyLightThemeFlip = (): (() => void) => {
  const root = document.documentElement;
  const body = document.body;
  const hadRootWebview = root.classList.contains("vscode-webview");
  const hadBodyWebview = body.classList.contains("vscode-webview");
  const savedDataTheme = root.getAttribute("data-theme");
  const savedStyle = root.getAttribute("style");

  root.classList.add("theme-switching");
  // Drop the IDE theme: remove the webview marker + host-injected --vscode-* inline
  // overrides and select the light data-theme, so the light defaults win.
  root.classList.remove("vscode-webview");
  body.classList.remove("vscode-webview");
  root.setAttribute("data-theme", "light");
  root.removeAttribute("style");

  return () => {
    if (savedStyle !== null) root.setAttribute("style", savedStyle);
    else root.removeAttribute("style");
    if (savedDataTheme !== null)
      root.setAttribute("data-theme", savedDataTheme);
    else root.removeAttribute("data-theme");
    if (hadRootWebview) root.classList.add("vscode-webview");
    if (hadBodyWebview) body.classList.add("vscode-webview");
    root.classList.remove("theme-switching");
  };
};

/** Current editor background color (matches the possibly light-flipped document). */
export const readBackgroundColor = (): string => {
  if (typeof window === "undefined") return "#ffffff";
  const root =
    document.querySelector(".vscode-webview") ?? document.documentElement;
  const value = getComputedStyle(root)
    .getPropertyValue("--vscode-editor-background")
    .trim();
  return value || "#ffffff";
};
