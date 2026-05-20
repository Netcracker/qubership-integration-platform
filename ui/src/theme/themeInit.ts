export type ThemeMode = "light" | "dark" | "high-contrast";
export type ThemeModeWithSystem = ThemeMode | "system";

const THEME_STORAGE_KEY = "qip-ui-theme-mode";

export function getSystemTheme(): ThemeMode {
  if (globalThis.window === undefined) {
    return "light";
  }

  const prefersDark = globalThis.matchMedia(
    "(prefers-color-scheme: dark)",
  ).matches;
  const prefersHighContrast = globalThis.matchMedia(
    "(prefers-contrast: more)",
  ).matches;

  if (prefersHighContrast) {
    return "high-contrast";
  }

  return prefersDark ? "dark" : "light";
}

export function getSavedTheme(): ThemeMode | null {
  if (globalThis.window === undefined || typeof localStorage === "undefined") {
    return null;
  }

  const saved = localStorage.getItem(THEME_STORAGE_KEY);
  if (saved === "light" || saved === "dark" || saved === "high-contrast") {
    return saved;
  }

  return null;
}

export function saveTheme(theme: ThemeMode): void {
  if (globalThis.window === undefined || typeof localStorage === "undefined") {
    return;
  }

  localStorage.setItem(THEME_STORAGE_KEY, theme);
}

export function clearSavedTheme(): void {
  if (globalThis.window === undefined || typeof localStorage === "undefined") {
    return;
  }

  localStorage.removeItem(THEME_STORAGE_KEY);
}

export function resetToSystemTheme(): ThemeMode {
  clearSavedTheme();
  const systemTheme = getSystemTheme();
  applyThemeToDOM(systemTheme);
  return systemTheme;
}

export function enableAutoThemeSwitching(): void {
  // Clear any saved theme to enable automatic switching
  clearSavedTheme();

  // Apply current system theme
  const systemTheme = getSystemTheme();
  applyThemeToDOM(systemTheme);
}

export function isAutoThemeEnabled(): boolean {
  return getSavedTheme() === null;
}

export function applyThemeToDOM(theme: ThemeMode): void {
  if (typeof document === "undefined") {
    return;
  }

  const root = document.documentElement;
  const hasWindow = globalThis.window !== undefined;
  const importantVariables = [
    "--vscode-editor-background",
    "--vscode-panel-background",
    "--vscode-editorGroupHeader-tabsBackground",
    "--vscode-foreground",
    "--vscode-border",
    "--vscode-editorGroup-border",
    "--vscode-list-hoverBackground",
    "--vscode-dropdown-background",
    "--vscode-dropdown-border",
    "--vscode-button-background",
    "--vscode-button-foreground",
    "--vscode-input-background",
    "--vscode-input-foreground",
    "--vscode-input-border",
    "--vscode-input-placeholderForeground",
    "--vscode-textLink-foreground",
    "--vscode-editorLineNumber-foreground",
    "--vscode-editorLineNumber-activeForeground",
    "--vscode-editor-selectionBackground",
    "--vscode-editorCursor-foreground",
  ];

  // Disable transitions BEFORE changing any CSS variables so that all
  // elements snap to their new values instantly instead of transitioning.
  root.classList.add("theme-switching");

  importantVariables.forEach((cssVariable) => {
    root.style.removeProperty(cssVariable);
  });

  root.setAttribute("data-theme", theme);

  document.body.classList.remove(
    "theme-light",
    "theme-dark",
    "theme-high-contrast",
  );
  document.body.classList.add(`theme-${theme}`);

  const applyInlineVariables = () => {
    const computedStyle = getComputedStyle(root);
    let hasChanges = false;

    importantVariables.forEach((cssVariable) => {
      const value = computedStyle.getPropertyValue(cssVariable).trim();
      if (value) {
        root.style.setProperty(cssVariable, value);
        hasChanges = true;
      } else {
        root.style.removeProperty(cssVariable);
      }
    });

    // Re-enable transitions after the new values have been painted.
    setTimeout(() => {
      root.classList.remove("theme-switching");

      if (hasWindow && hasChanges) {
        globalThis.dispatchEvent(
          new CustomEvent("theme-variables-updated", { detail: { theme } }),
        );
      }
    }, 50);
  };

  if (hasWindow) {
    if (typeof globalThis.requestAnimationFrame === "function") {
      globalThis.requestAnimationFrame(() => {
        globalThis.requestAnimationFrame(applyInlineVariables);
      });
    } else {
      setTimeout(() => {
        setTimeout(applyInlineVariables, 0);
      }, 0);
    }
  } else {
    applyInlineVariables();
  }
}

export function initializeBrowserTheme(): ThemeMode {
  const theme = getSavedTheme() ?? getSystemTheme();
  applyThemeToDOM(theme);

  return theme;
}

export function setupThemeListener(
  callback: (theme: ThemeMode) => void,
): () => void {
  if (globalThis.window === undefined) {
    return () => {};
  }

  const darkModeQuery = globalThis.matchMedia("(prefers-color-scheme: dark)");
  const highContrastQuery = globalThis.matchMedia("(prefers-contrast: more)");

  const handleSystemChange = () => {
    const savedTheme = getSavedTheme();

    // If no theme is saved (System theme is active), follow system changes
    if (!savedTheme) {
      const newTheme = getSystemTheme();
      applyThemeToDOM(newTheme);
      callback(newTheme);
    }
    // If a specific theme is saved, don't change automatically
    // User can manually switch to System theme to enable auto-switching
  };

  const handleStorageChange = (event: StorageEvent) => {
    if (event.key === THEME_STORAGE_KEY && event.newValue) {
      const newTheme = event.newValue as ThemeMode;
      if (
        newTheme === "light" ||
        newTheme === "dark" ||
        newTheme === "high-contrast"
      ) {
        applyThemeToDOM(newTheme);
        callback(newTheme);
      }
    }
  };

  darkModeQuery.addEventListener("change", handleSystemChange);
  highContrastQuery.addEventListener("change", handleSystemChange);
  globalThis.addEventListener("storage", handleStorageChange);

  return () => {
    darkModeQuery.removeEventListener("change", handleSystemChange);
    highContrastQuery.removeEventListener("change", handleSystemChange);
    globalThis.removeEventListener("storage", handleStorageChange);
  };
}
