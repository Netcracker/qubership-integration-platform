export type ThemeMode = 'light' | 'dark' | 'high-contrast';
export type ThemeModeWithSystem = ThemeMode | 'system';

const THEME_STORAGE_KEY = 'qip-ui-theme-mode';

export function getSystemTheme(): ThemeMode {
  if (typeof window === 'undefined') {
    return 'light';
  }

  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const prefersHighContrast = window.matchMedia('(prefers-contrast: more)').matches;

  if (prefersHighContrast) {
    return 'high-contrast';
  }

  return prefersDark ? 'dark' : 'light';
}

export function getSavedTheme(): ThemeMode | null {
  if (typeof window === 'undefined' || typeof localStorage === 'undefined') {
    return null;
  }

  const saved = localStorage.getItem(THEME_STORAGE_KEY);
  if (saved === 'light' || saved === 'dark' || saved === 'high-contrast') {
    return saved;
  }

  return null;
}

export function saveTheme(theme: ThemeMode): void {
  if (typeof window === 'undefined' || typeof localStorage === 'undefined') {
    return;
  }

  localStorage.setItem(THEME_STORAGE_KEY, theme);
}

export function clearSavedTheme(): void {
  if (typeof window === 'undefined' || typeof localStorage === 'undefined') {
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
  if (typeof document === 'undefined') {
    return;
  }

  const root = document.documentElement;
  const hasWindow = typeof window !== 'undefined';
  const importantVariables = [
    '--vscode-editor-background',
    '--vscode-panel-background',
    '--vscode-editorGroupHeader-tabsBackground',
    '--vscode-foreground',
    '--vscode-border',
    '--vscode-editorGroup-border',
    '--vscode-list-hoverBackground',
    '--vscode-dropdown-background',
    '--vscode-dropdown-border',
    '--vscode-button-background',
    '--vscode-button-foreground',
    '--vscode-input-background',
    '--vscode-input-foreground',
    '--vscode-input-border',
    '--vscode-input-placeholderForeground',
    '--vscode-textLink-foreground',
    '--vscode-editorLineNumber-foreground',
    '--vscode-editorLineNumber-activeForeground',
    '--vscode-editor-selectionBackground',
    '--vscode-editorCursor-foreground'
  ];

  importantVariables.forEach((cssVariable) => {
    root.style.removeProperty(cssVariable);
  });

  root.setAttribute('data-theme', theme);

  document.body.classList.remove('theme-light', 'theme-dark', 'theme-high-contrast');
  document.body.classList.add(`theme-${theme}`);

  const applyInlineVariables = () => {
    const computedStyle = getComputedStyle(root);
    importantVariables.forEach((cssVariable) => {
      const value = computedStyle.getPropertyValue(cssVariable).trim();
      if (value) {
        root.style.setProperty(cssVariable, value);
      } else {
        root.style.removeProperty(cssVariable);
      }
    });
  };

  if (hasWindow) {
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(applyInlineVariables);
    } else {
      setTimeout(applyInlineVariables, 0);
    }
  }

  root.classList.add('theme-switching');
  setTimeout(() => {
    root.classList.remove('theme-switching');
  }, 250);
}

export function initializeBrowserTheme(): ThemeMode {
  // Always start with system theme, ignore saved theme on first load
  const theme = getSystemTheme();
  
  applyThemeToDOM(theme);
  
  return theme;
}

export function setupThemeListener(callback: (theme: ThemeMode) => void): () => void {
  if (typeof window === 'undefined') {
    return () => {};
  }

  const darkModeQuery = window.matchMedia('(prefers-color-scheme: dark)');
  const highContrastQuery = window.matchMedia('(prefers-contrast: more)');

  const handleChange = () => {
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

  // Use modern addEventListener API
  darkModeQuery.addEventListener('change', handleChange);
  highContrastQuery.addEventListener('change', handleChange);

  return () => {
    darkModeQuery.removeEventListener('change', handleChange);
    highContrastQuery.removeEventListener('change', handleChange);
  };
}





