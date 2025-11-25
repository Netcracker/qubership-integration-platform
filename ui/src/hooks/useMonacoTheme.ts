import { useEffect, useMemo, useState } from 'react';
import type { Monaco } from '@monaco-editor/react';
import { useVSCodeTheme } from './useVSCodeTheme';

interface ThemeData {
  colors?: Record<string, string>;
  isDark?: boolean;
}

const registeredMonacoInstances = new Set<Monaco>();

function rememberMonacoInstance(monaco: Monaco): void {
  registeredMonacoInstances.add(monaco);
}

function getThemeAttribute(): string {
  if (typeof document === 'undefined') {
    return 'light';
  }
  return document.documentElement.getAttribute('data-theme') ?? 'light';
}

function normalizeHexColor(value: string): string | null {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  const shortHex = /^#([0-9a-f]{3})$/i;
  const fullHex = /^#([0-9a-f]{6})$/i;
  const hexWithAlpha = /^#([0-9a-f]{8})$/i;
  if (fullHex.test(trimmed)) {
    return trimmed;
  }
  if (shortHex.test(trimmed)) {
    const [, digits] = trimmed.match(shortHex) ?? [];
    if (!digits) {
      return null;
    }
    return `#${digits[0]}${digits[0]}${digits[1]}${digits[1]}${digits[2]}${digits[2]}`;
  }
  if (hexWithAlpha.test(trimmed)) {
    const [, digits] = trimmed.match(hexWithAlpha) ?? [];
    if (!digits) {
      return null;
    }
    return `#${digits.substring(0, 6)}`;
  }
  const rgba = trimmed.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*[\d.]+)?\)/);
  if (rgba) {
    const r = Number.parseInt(rgba[1], 10).toString(16).padStart(2, '0');
    const g = Number.parseInt(rgba[2], 10).toString(16).padStart(2, '0');
    const b = Number.parseInt(rgba[3], 10).toString(16).padStart(2, '0');
    return `#${r}${g}${b}`;
  }
  return null;
}

function readCssColor(style: CSSStyleDeclaration, varName: string, fallback: string): string {
  if (!style) {
    return fallback;
  }
  const rawValue = style.getPropertyValue(varName);
  const normalized = normalizeHexColor(rawValue);
  return normalized ?? fallback;
}

let currentThemeData: ThemeData | null = null;

function reapplyVSCodeThemeToAllRegisteredMonaco(): void {
  registeredMonacoInstances.forEach((instance) => {
    applyVSCodeThemeToMonaco(instance, currentThemeData);
  });
}

/**
 * Hook to get the appropriate Monaco Editor theme based on current application theme
 * Automatically applies VS Code theme colors to Monaco Editor
 * @returns theme name to use ('vs-dark' or 'vs')
 */
export function useMonacoTheme(): 'vs' | 'vs-dark' {
  const { isDark, themeData } = useVSCodeTheme();
  const [theme, setTheme] = useState<'vs' | 'vs-dark'>(isDark ? 'vs-dark' : 'vs');
  const [domTheme, setDomTheme] = useState(() => getThemeAttribute());

  useEffect(() => {
    if (typeof document === 'undefined' || typeof MutationObserver === 'undefined') {
      return;
    }
    const observer = new MutationObserver(() => {
      setDomTheme(getThemeAttribute());
    });
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
    return () => observer.disconnect();
  }, []);

  const themeSignature = useMemo(() => {
    if (themeData?.debug?.timestamp) {
      return `${themeData.debug.timestamp}-${themeData.themeName ?? 'unknown'}`;
    }
    return `${domTheme}-${isDark ? 'dark' : 'light'}`;
  }, [domTheme, isDark, themeData?.debug?.timestamp, themeData?.themeName]);

  useEffect(() => {
    const next = isDark ? 'vs-dark' : 'vs';
    if (theme !== next) {
      setTheme(next);
    }
  }, [isDark, theme]);

  useEffect(() => {
    currentThemeData = { colors: themeData?.colors, isDark: themeData?.isDark };
    requestAnimationFrame(() => {
      setTimeout(() => {
        reapplyVSCodeThemeToAllRegisteredMonaco();
      }, 0);
    });
  }, [themeSignature, themeData]);

  return theme;
}

/**
 * Hook to get Monaco Editor font settings from VS Code CSS variables
 * @returns editor font configuration from VS Code theme
 */
export function useMonacoEditorOptions() {
  const { isDark } = useVSCodeTheme();
  
  const [options, setOptions] = useState<{
    fontSize: number;
    lineHeight: number;
    fontFamily: string;
    fontWeight: string;
  }>({
    fontSize: 13,
    lineHeight: 20,
    fontFamily: "'Consolas', 'Courier New', monospace",
    fontWeight: 'normal',
  });

  useEffect(() => {
    if (typeof document === 'undefined') return;

    const style = getComputedStyle(document.documentElement);
    
    const fontFamily = style.getPropertyValue('--vscode-editor-font-family').trim() 
      || "'Consolas', 'Courier New', monospace";
    const fontSize = parseInt(style.getPropertyValue('--vscode-editor-font-size').trim()) || 13;
    const lineHeight = parseInt(style.getPropertyValue('--vscode-editor-line-height').trim()) || 20;
    const fontWeight = style.getPropertyValue('--vscode-editor-font-weight').trim() || 'normal';

    setOptions({
      fontSize,
      lineHeight,
      fontFamily,
      fontWeight,
    });
  }, [isDark]);

  return options;
}

/**
 * Apply VS Code theme colors to Monaco Editor
 * Call this in the Editor's onMount callback
 */
export function applyVSCodeThemeToMonaco(monaco: Monaco, themeData?: ThemeData | null): void {
  if (typeof document === 'undefined' || typeof window === 'undefined') {
    return;
  }

  rememberMonacoInstance(monaco);

  const applyTheme = () => {
    const style = getComputedStyle(document.documentElement);
    const dataTheme = document.documentElement.getAttribute('data-theme');
    const isDark = themeData?.isDark ?? (dataTheme === 'dark' || dataTheme === 'high-contrast');
    const isHighContrast = dataTheme === 'high-contrast';

    const getColorFromThemeOrCss = (themeKey: string, cssVar: string, fallback: string): string => {
      if (themeData?.colors?.[themeKey]) {
        const normalized = normalizeHexColor(themeData.colors[themeKey]);
        if (normalized) {
          return normalized;
        }
      }
      return readCssColor(style, cssVar, fallback);
    };

  const editorBg = getColorFromThemeOrCss('editor.background', '--vscode-editor-background', isDark ? '#141414' : '#ffffff');
  const editorFg = getColorFromThemeOrCss('editor.foreground', '--vscode-editor-foreground', isDark ? '#d4d4d4' : '#3b3b3b');
  const lineHighlight = themeData?.colors?.['editor.lineHighlightBackground'] || style.getPropertyValue('--vscode-editor-lineHighlightBackground').trim();
  const selection = getColorFromThemeOrCss('editor.selectionBackground', '--vscode-editor-selectionBackground', isDark ? '#264f78' : '#add6ff');
  const inactiveSelection = getColorFromThemeOrCss(
    'editor.inactiveSelectionBackground',
    '--vscode-editor-inactiveSelectionBackground',
    isDark ? '#3a3d41' : '#e5ebf1',
  );
  const cursor = getColorFromThemeOrCss('editorCursor.foreground', '--vscode-editorCursor-foreground', isDark ? '#ffffff' : '#000000');

  const keywordColor =
    getColorFromThemeOrCss('symbolIcon.keywordForeground', '--vscode-symbolIcon-keywordForeground', isDark ? '#569cd6' : '#0000ff');
  const stringColor =
    getColorFromThemeOrCss('symbolIcon.stringForeground', '--vscode-symbolIcon-stringForeground', isDark ? '#ce9178' : '#a31515');
  const numberColor =
    getColorFromThemeOrCss('symbolIcon.numberForeground', '--vscode-symbolIcon-numberForeground', isDark ? '#b5cea8' : '#098658');
  const operatorColor =
    getColorFromThemeOrCss('symbolIcon.operatorForeground', '--vscode-symbolIcon-operatorForeground', isDark ? '#d4d4d4' : '#000000');
  const variableColor =
    getColorFromThemeOrCss('symbolIcon.variableForeground', '--vscode-symbolIcon-variableForeground', isDark ? '#9cdcfe' : '#001080');
  const functionColor =
    getColorFromThemeOrCss('symbolIcon.functionForeground', '--vscode-symbolIcon-functionForeground', isDark ? '#dcdCAA' : '#795e26');
  const typeColor =
    getColorFromThemeOrCss('symbolIcon.classForeground', '--vscode-symbolIcon-classForeground', isDark ? '#4ec9b0' : '#267f99');
  const constantColor =
    getColorFromThemeOrCss('symbolIcon.constantForeground', '--vscode-symbolIcon-constantForeground', isDark ? '#4fc1ff' : '#0000ff');
  const commentColor =
    getColorFromThemeOrCss('editorIndentGuide.activeBackground', '--vscode-editorIndentGuide-activeBackground', isDark ? '#6a9955' : '#008000');

  const tokenRules = [
    { token: 'comment', foreground: commentColor, fontStyle: 'italic' },
    { token: 'comment.line', foreground: commentColor, fontStyle: 'italic' },
    { token: 'comment.block', foreground: commentColor, fontStyle: 'italic' },
    { token: 'keyword', foreground: keywordColor },
    { token: 'keyword.control', foreground: keywordColor },
    { token: 'string', foreground: stringColor },
    { token: 'string.escape', foreground: stringColor },
    { token: 'number', foreground: numberColor },
    { token: 'number.hex', foreground: numberColor },
    { token: 'number.octal', foreground: numberColor },
    { token: 'number.binary', foreground: numberColor },
    { token: 'regexp', foreground: stringColor },
    { token: 'operator', foreground: operatorColor },
    { token: 'delimiter', foreground: operatorColor },
    { token: 'type', foreground: typeColor },
    { token: 'type.identifier', foreground: typeColor },
    { token: 'identifier', foreground: variableColor },
    { token: 'identifier.function', foreground: functionColor },
    { token: 'identifier.method', foreground: functionColor },
    { token: 'variable', foreground: variableColor },
    { token: 'constant', foreground: constantColor },
    { token: 'predefined', foreground: typeColor },
  ];

  // Calculate line highlight with proper opacity for better readability
  const getLineHighlight = (): string => {
    if (normalizeHexColor(lineHighlight)) {
      return normalizeHexColor(lineHighlight) as string;
    }
    if (isHighContrast) {
      return isDark ? '#ffffff0a' : '#0000000a';
    }
    return isDark ? '#ffffff08' : '#00000008';
  };

  const scrollbarBackground = getColorFromThemeOrCss(
    'scrollbarSlider.background',
    '--vscode-scrollbarSlider-background',
    isDark ? '#424242' : '#c4c4c4',
  );
  const scrollbarHoverBackground = getColorFromThemeOrCss(
    'scrollbarSlider.hoverBackground',
    '--vscode-scrollbarSlider-hoverBackground',
    isDark ? '#4f4f4f' : '#b8b8b8',
  );
  const scrollbarActiveBackground = getColorFromThemeOrCss(
    'scrollbarSlider.activeBackground',
    '--vscode-scrollbarSlider-activeBackground',
    isDark ? '#5f5f5f' : '#a0a0a0',
  );

  const lineNumberFg = getColorFromThemeOrCss(
    'editorLineNumber.foreground',
    '--vscode-editorLineNumber-foreground',
    isDark ? '#858585' : '#237893',
  );
  const activeLineNumberFg = getColorFromThemeOrCss(
    'editorLineNumber.activeForeground',
    '--vscode-editorLineNumber-activeForeground',
    isDark ? '#c6c6c6' : '#0b216f',
  );
  const gutterBg = getColorFromThemeOrCss(
    'editorGutter.background',
    '--vscode-editorGutter-background',
    isDark ? '#141414' : '#ffffff',
  );
  const selectionHighlight = getColorFromThemeOrCss(
    'editor.selectionHighlightBackground',
    '--vscode-editor-selectionHighlightBackground',
    isDark ? '#ffffff15' : '#00000015',
  );
  const wordHighlight = getColorFromThemeOrCss(
    'editor.wordHighlightBackground',
    '--vscode-editor-wordHighlightBackground',
    isDark ? '#ffffff20' : '#00000020',
  );
  const findMatch = getColorFromThemeOrCss(
    'editor.findMatchBackground',
    '--vscode-editor-findMatchBackground',
    isDark ? '#515c6a' : '#a8ac94',
  );
  const findMatchHighlight = getColorFromThemeOrCss(
    'editor.findMatchHighlightBackground',
    '--vscode-editor-findMatchHighlightBackground',
    isDark ? '#ea5c0055' : '#ea5c0033',
  );
  const widgetBg = getColorFromThemeOrCss(
    'editorWidget.background',
    '--vscode-editorWidget-background',
    isDark ? '#1f1f1f' : '#f3f3f3',
  );
  const suggestWidgetBg = getColorFromThemeOrCss(
    'editorSuggestWidget.background',
    '--vscode-editorSuggestWidget-background',
    isDark ? '#252526' : '#f3f3f3',
  );
  const suggestWidgetBorder = getColorFromThemeOrCss(
    'editorSuggestWidget.border',
    '--vscode-editorSuggestWidget-border',
    isDark ? '#454545' : '#c8c8c8',
  );

  // Define custom theme based on VS Code colors
  const customTheme = {
    base: isDark ? 'vs-dark' : 'vs',
    inherit: true,
    rules: tokenRules,
    colors: {
      'editor.background': editorBg,
      'editor.foreground': editorFg,
      'editor.lineHighlightBackground': getLineHighlight(),
      'editor.selectionBackground': selection,
      'editor.inactiveSelectionBackground': inactiveSelection,
      'editorCursor.foreground': cursor,
      'editor.selectionHighlightBackground': selectionHighlight,
      'editor.wordHighlightBackground': wordHighlight,
      'editor.findMatchBackground': findMatch,
      'editor.findMatchHighlightBackground': findMatchHighlight,
      'editorLineNumber.foreground': lineNumberFg,
      'editorLineNumber.activeForeground': activeLineNumberFg,
      'editorGutter.background': gutterBg,
      'scrollbarSlider.background': scrollbarBackground,
      'scrollbarSlider.hoverBackground': scrollbarHoverBackground,
      'scrollbarSlider.activeBackground': scrollbarActiveBackground,
      'editorWidget.background': widgetBg,
      'editorSuggestWidget.background': suggestWidgetBg,
      'editorSuggestWidget.border': suggestWidgetBorder,
    },
  };

    // Register and apply the custom theme
    const themeName = isDark ? 'vscode-dark-custom' : 'vscode-light-custom';
    
    try {
      // @ts-expect-error Monaco types expect literal 'vs' | 'vs-dark' but runtime accepts the string
      monaco.editor.defineTheme(themeName, customTheme);
      monaco.editor.setTheme(themeName);
    } catch (error) {
      console.warn('Failed to apply VS Code theme to Monaco:', error);
    }
  };

  requestAnimationFrame(() => {
    applyTheme();
  });
}

