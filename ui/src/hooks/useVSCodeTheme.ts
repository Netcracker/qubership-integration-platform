import { useState, useEffect, useCallback } from "react";
import VSCodeApiSingleton from "../utils/vscodeApi";

interface ThemeData {
  kind: number;
  isDark: boolean;
  isLight: boolean;
  isHighContrast: boolean;
  themeName: string;
  colors: Record<string, string>;
  fonts: {
    fontFamily: string;
    fontSize: number;
    fontWeight: string;
    lineHeight: number;
  };
  ui: {
    tabSize: number;
    wordWrap: string;
    minimap: {
      enabled: boolean;
      maxColumn: number;
    };
    scrollbar: {
      vertical: string;
      horizontal: string;
      verticalScrollbarSize: number;
      horizontalScrollbarSize: number;
    };
  };
  accessibility: {
    highContrast: boolean;
    reducedMotion: string;
  };
  debug: {
    timestamp: string;
    extensionVersion: string;
    vscodeVersion: string;
    themeKindValues: Record<string, number>;
  };
}

type VSCodePalette = {
  panelBg: string;
  border: string;
  foreground: string;
  buttonBg: string;
  buttonFg: string;
  buttonHover: string;
  listHover: string;
};

const FALLBACK_PALETTE: Record<"light" | "dark", VSCodePalette> = {
  light: {
    panelBg: "#fafafa",
    border: "#d9d9d9",
    foreground: "rgba(0, 0, 0, 0.88)",
    buttonBg: "#1677ff",
    buttonFg: "#ffffff",
    buttonHover: "#0958d9",
    listHover: "#f5f5f5",
  },
  dark: {
    panelBg: "#1f1f1f",
    border: "#303030",
    foreground: "rgba(255, 255, 255, 0.85)",
    buttonBg: "#0e639c",
    buttonFg: "#ffffff",
    buttonHover: "#1177bb",
    listHover: "#262626",
  },
};

const readCssVariable = (name: string, fallback: string): string => {
  if (typeof window === "undefined" || typeof document === "undefined") {
    return fallback;
  }

  const value = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();

  return value || fallback;
};

const buildPalette = (isDark: boolean): VSCodePalette => {
  const defaults = isDark ? FALLBACK_PALETTE.dark : FALLBACK_PALETTE.light;

  return {
    panelBg: readCssVariable("--vscode-panel-background", defaults.panelBg),
    border: readCssVariable("--vscode-border", defaults.border),
    foreground: readCssVariable("--vscode-foreground", defaults.foreground),
    buttonBg: readCssVariable("--vscode-button-background", defaults.buttonBg),
    buttonFg: readCssVariable("--vscode-button-foreground", defaults.buttonFg),
    buttonHover: readCssVariable(
      "--vscode-button-hoverBackground",
      defaults.buttonHover,
    ),
    listHover: readCssVariable(
      "--vscode-list-hoverBackground",
      defaults.listHover,
    ),
  };
};

const detectVSCodeEnvironment = (): boolean => {
  if (typeof window === "undefined") {
    return false;
  }

  const globalWindow = window as unknown as {
    vscode?: unknown;
    vscodeApi?: unknown;
    acquireVsCodeApi?: unknown;
  };

  if (globalWindow.vscode || globalWindow.vscodeApi) {
    return true;
  }

  return typeof globalWindow.acquireVsCodeApi === "function";
};

function detectInitialIsDark(): boolean {
  if (typeof window !== "undefined" && typeof localStorage !== "undefined") {
    const saved = localStorage.getItem("vscode-theme-isdark");
    if (saved === "true" || saved === "false") {
      return saved === "true";
    }
  }
  if (typeof document !== "undefined") {
    const themeAttr = document.documentElement.getAttribute("data-theme");
    if (themeAttr === "dark" || themeAttr === "high-contrast") {
      return true;
    }
    if (themeAttr === "light") {
      return false;
    }
  }
  return false;
}

export const useVSCodeTheme = () => {
  const [themeData, setThemeData] = useState<ThemeData | null>(null);
  const [isDark, setIsDark] = useState(detectInitialIsDark);

  // Use VS Code API singleton
  const vscodeApiSingleton = VSCodeApiSingleton.getInstance();
  const [isVSCodeWebview, setIsVSCodeWebview] = useState<boolean>(() =>
    detectVSCodeEnvironment(),
  );

  useEffect(() => {
    if (!isVSCodeWebview) {
      const api = vscodeApiSingleton.getApi?.();
      if (api && typeof api.postMessage === "function") {
        setIsVSCodeWebview(true);
      }
    }
  }, [isVSCodeWebview, vscodeApiSingleton]);

  const applyThemeToDocument = useCallback(
    (theme: ThemeData) => {
      const root = document.documentElement;

      if (isVSCodeWebview) {
        root.classList.add("vscode-webview");
        document.body.classList.add("vscode-webview");
      } else {
        root.classList.remove("vscode-webview");
        document.body.classList.remove("vscode-webview");
      }

      // Determine theme type
      const isDarkTheme =
        theme.isDark ?? (theme.kind === 2 || theme.kind === 3);
      const isHighContrast = theme.isHighContrast ?? theme.kind === 3;
      const themeClass = isHighContrast
        ? "high-contrast"
        : isDarkTheme
          ? "dark"
          : "light";

      // Apply theme attribute to root for CSS selectors
      root.setAttribute("data-theme", themeClass);

      // Apply theme class to body
      document.body.className = document.body.className.replace(
        /theme-\w+/g,
        "",
      );
      document.body.classList.add(`theme-${themeClass}`);

      // Set meta theme variables
      root.style.setProperty("--vscode-theme-kind", theme.kind.toString());
      root.style.setProperty("--vscode-is-dark", isDarkTheme ? "1" : "0");
      root.style.setProperty("--vscode-is-light", !isDarkTheme ? "1" : "0");
      root.style.setProperty(
        "--vscode-is-high-contrast",
        isHighContrast ? "1" : "0",
      );

      // Set font variables only for browser fallback
      if (!isVSCodeWebview && theme.fonts) {
        root.style.setProperty("--vscode-font-family", theme.fonts.fontFamily);
        root.style.setProperty(
          "--vscode-font-size",
          `${theme.fonts.fontSize}px`,
        );
        root.style.setProperty("--vscode-font-weight", theme.fonts.fontWeight);
        root.style.setProperty(
          "--vscode-line-height",
          theme.fonts.lineHeight.toString(),
        );
      }

      // Set UI variables
      if (theme.ui) {
        root.style.setProperty(
          "--vscode-tab-size",
          theme.ui.tabSize.toString(),
        );
        root.style.setProperty("--vscode-word-wrap", theme.ui.wordWrap);
      }

      // Set accessibility variables
      if (theme.accessibility) {
        root.style.setProperty(
          "--vscode-high-contrast",
          theme.accessibility.highContrast ? "1" : "0",
        );
        root.style.setProperty(
          "--vscode-reduced-motion",
          theme.accessibility.reducedMotion,
        );
      }

      // Apply VSCode colors as CSS variables
      if (!isVSCodeWebview) {
        if (theme.colors && Object.keys(theme.colors).length > 0) {
          console.log(
            "QIP UI: Applying fallback colors for browser mode:",
            Object.keys(theme.colors).length,
          );
          Object.entries(theme.colors).forEach(([key, value]) => {
            if (typeof value === "string") {
              const cssVarName = `--vscode-${key.replace(/\./g, "-")}`;
              root.style.setProperty(cssVarName, value);
            }
          });
        } else {
          console.log(
            "QIP UI: No VSCode colors received for browser mode, using stylesheet fallbacks",
          );
        }
      }

      // Smooth theme transition
      root.classList.add("theme-switching");
      setTimeout(() => {
        root.classList.remove("theme-switching");

        if (typeof window !== "undefined") {
          window.dispatchEvent(
            new CustomEvent("theme-variables-updated", {
              detail: { theme: themeClass },
            }),
          );
        }
      }, 250);
    },
    [isVSCodeWebview],
  );

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data as {
        type?: string;
        payload?: ThemeData;
        command?: string;
        theme?: ThemeData;
      };

      // Debug: log theme messages
      if (
        message.type === "theme-update" ||
        message.command === "themeUpdate"
      ) {
        console.log("[useVSCodeTheme] Theme message received:", message);
      }

      // Handle new format theme updates from VSCode extension
      if (message.type === "theme-update" && message.payload) {
        const themeData = message.payload;
        setThemeData(themeData);
        // Unified logic - same as applyThemeToDocument
        const isDarkTheme =
          themeData.isDark ?? (themeData.kind === 2 || themeData.kind === 3);
        console.log(
          "[useVSCodeTheme] Setting isDark:",
          isDarkTheme,
          "from theme:",
          themeData.themeName,
        );
        setIsDark(isDarkTheme);
        // Save to localStorage for next reload
        if (typeof localStorage !== "undefined") {
          localStorage.setItem("vscode-theme-isdark", String(isDarkTheme));
        }

        // Apply theme to document
        applyThemeToDocument(themeData);
      }
      // Handle legacy format for backwards compatibility
      else if (message.command === "themeUpdate" && message.theme) {
        const themeData = message.theme;
        setThemeData(themeData);
        // Unified logic - same as applyThemeToDocument
        const isDarkTheme =
          themeData.isDark ?? (themeData.kind === 2 || themeData.kind === 3);
        setIsDark(isDarkTheme);
        // Save to localStorage for next reload
        if (typeof localStorage !== "undefined") {
          localStorage.setItem("vscode-theme-isdark", String(isDarkTheme));
        }

        // Apply theme to document
        applyThemeToDocument(themeData);
      }
    };

    window.addEventListener("message", handleMessage);

    // Request initial theme if API is available
    if (vscodeApiSingleton.isAvailable()) {
      vscodeApiSingleton.sendMessage({ command: "requestTheme" });
    }

    return () => {
      window.removeEventListener("message", handleMessage);
    };
  }, [vscodeApiSingleton, applyThemeToDocument]);

  const palette = buildPalette(isDark);

  return {
    themeData,
    isDark,
    isLight: !isDark,
    isHighContrast: themeData?.isHighContrast || false,
    themeName: themeData?.themeName || "Unknown",
    isVSCodeWebview,
    colors: themeData?.colors || {},
    fonts: themeData?.fonts || {
      fontFamily: 'Consolas, "Courier New", monospace',
      fontSize: 14,
      fontWeight: "normal",
      lineHeight: 1.5,
    },
    palette,
  };
};
