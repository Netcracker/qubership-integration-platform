import { useEffect, useState } from 'react';
import type { Monaco } from '@monaco-editor/react';
import { useVSCodeTheme } from './useVSCodeTheme';

/**
 * Hook to get the appropriate Monaco Editor theme based on current application theme
 * Automatically applies VS Code theme colors to Monaco Editor
 * @returns theme name to use ('vs-dark' or 'vs')
 */
export function useMonacoTheme(): 'vs' | 'vs-dark' {
  const { isDark } = useVSCodeTheme();
  const theme = isDark ? 'vs-dark' : 'vs';
  console.log('[useMonacoTheme] Theme:', theme, 'isDark:', isDark);
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
export function applyVSCodeThemeToMonaco(monaco: Monaco): void {
  if (typeof document === 'undefined' || typeof window === 'undefined') {
    return;
  }

  const style = getComputedStyle(document.documentElement);
  const dataTheme = document.documentElement.getAttribute('data-theme');
  const isDark = dataTheme === 'dark' || dataTheme === 'high-contrast';
  const isHighContrast = dataTheme === 'high-contrast';

  // Get VS Code theme colors from CSS variables
  const editorBg = style.getPropertyValue('--vscode-editor-background').trim();
  const editorFg = style.getPropertyValue('--vscode-editor-foreground').trim();
  const lineHighlight = style.getPropertyValue('--vscode-editor-lineHighlightBackground').trim();
  const selection = style.getPropertyValue('--vscode-editor-selectionBackground').trim();
  const inactiveSelection = style.getPropertyValue('--vscode-editor-inactiveSelectionBackground').trim();
  const cursor = style.getPropertyValue('--vscode-editorCursor-foreground').trim();

  // Helper: read CSS var and normalize to hex
  const cssVar = (name: string): string => style.getPropertyValue(name).trim();
  const cssVarHex = (name: string): string => rgbaToHex(cssVar(name));

  // Helper function to convert rgba/rgb colors to hex
  const rgbaToHex = (color: string): string => {
    if (!color) return '';
    // Already hex
    if (color.startsWith('#')) {
      return color.length === 7 ? color : '';
    }
    // Parse rgba(r, g, b, a) or rgb(r, g, b)
    const match = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*[\d.]+)?\)/);
    if (match) {
      const r = parseInt(match[1]).toString(16).padStart(2, '0');
      const g = parseInt(match[2]).toString(16).padStart(2, '0');
      const b = parseInt(match[3]).toString(16).padStart(2, '0');
      return `#${r}${g}${b}`;
    }
    return '';
  };

  // Prefer VS Code theme CSS variables for symbol/token colors (works across themes)
  // Fallback to calibrated palette if not provided by the host
  const keywordColor = cssVarHex('--vscode-symbolIcon-keywordForeground') || (isDark ? '569CD6' : '0000FF');
  const stringColor = cssVarHex('--vscode-symbolIcon-stringForeground') || (isDark ? 'CE9178' : 'A31515');
  const numberColor = cssVarHex('--vscode-symbolIcon-numberForeground') || (isDark ? 'B5CEA8' : '098658');
  const operatorColor = cssVarHex('--vscode-symbolIcon-operatorForeground') || (isDark ? 'D4D4D4' : '000000');
  const variableColor = cssVarHex('--vscode-symbolIcon-variableForeground') || (isDark ? '9CDCFE' : '001080');
  const functionColor = cssVarHex('--vscode-symbolIcon-functionForeground') || (isDark ? 'DCDCAA' : '795E26');
  const typeColor = cssVarHex('--vscode-symbolIcon-classForeground') || (isDark ? '4EC9B0' : '267F99');
  const constantColor = cssVarHex('--vscode-symbolIcon-constantForeground') || (isDark ? '4FC1FF' : '0000FF');
  const commentColor = cssVarHex('--vscode-editorIndentGuide-activeBackground') // often light/desaturated
    || (isDark ? '6A9955' : '008000');

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
    if (lineHighlight) return lineHighlight;
    // Use semi-transparent overlays that don't obscure text
    if (isHighContrast) {
      return isDark ? '#ffffff0a' : '#0000000a';
    }
    return isDark ? '#ffffff08' : '#00000008';
  };

  // Define custom theme based on VS Code colors
  const customTheme = {
    base: isDark ? 'vs-dark' : 'vs',
    inherit: true,
    rules: tokenRules,
    colors: {
      'editor.background': rgbaToHex(editorBg) || (isDark ? '#141414' : '#ffffff'),
      'editor.foreground': rgbaToHex(editorFg) || (isDark ? '#d4d4d4' : '#3b3b3b'),
      'editor.lineHighlightBackground': rgbaToHex(lineHighlight) || getLineHighlight(),
      'editor.selectionBackground': rgbaToHex(selection) || (isDark ? '#264f78' : '#add6ff'),
      'editor.inactiveSelectionBackground': rgbaToHex(inactiveSelection) || (isDark ? '#3a3d41' : '#e5ebf1'),
      'editorCursor.foreground': rgbaToHex(cursor) || (isDark ? '#ffffff' : '#000000'),
      'editor.selectionHighlightBackground': isDark ? '#ffffff15' : '#00000015',
      'editor.wordHighlightBackground': isDark ? '#ffffff20' : '#00000020',
      'editor.findMatchBackground': isDark ? '#515c6a' : '#a8ac94',
      'editor.findMatchHighlightBackground': isDark ? '#ea5c0055' : '#ea5c0033',
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
}
