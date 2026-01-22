import { useMemo, CSSProperties } from "react";
import { useVSCodeTheme } from "./useVSCodeTheme";

export function useSyntaxHighlighterTheme() {
  const { isDark } = useVSCodeTheme();

  const customTheme = useMemo(() => {
    const getBackgroundBrightness = (bgColor: string): number => {
      let r = 255,
        g = 255,
        b = 255;

      if (bgColor.startsWith("#")) {
        const hex = bgColor.substring(1);
        r = parseInt(hex.substring(0, 2), 16);
        g = parseInt(hex.substring(2, 4), 16);
        b = parseInt(hex.substring(4, 6), 16);
      } else if (bgColor.startsWith("rgb")) {
        const match = bgColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        if (match) {
          r = parseInt(match[1]);
          g = parseInt(match[2]);
          b = parseInt(match[3]);
        }
      }

      return (r * 299 + g * 587 + b * 114) / 1000;
    };

    let darkTheme = isDark;

    if (typeof document !== "undefined") {
      const dataTheme = document.documentElement.getAttribute("data-theme");
      if (dataTheme) {
        darkTheme = dataTheme === "dark" || dataTheme === "high-contrast";
      } else {
        const bgColor = getComputedStyle(document.documentElement)
          .getPropertyValue("--vscode-editor-background")
          .trim();
        if (bgColor) {
          const brightness = getBackgroundBrightness(bgColor);
          darkTheme = brightness < 128;
        }
      }
    }

    const cssVar = (name: string): string => {
      if (typeof document === "undefined") return "";
      return getComputedStyle(document.documentElement)
        .getPropertyValue(name)
        .trim();
    };

    const rgbaToHex = (color: string): string => {
      if (!color) return "";
      if (color.startsWith("#")) return color;
      const match = color.match(
        /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*[\d.]+)?\)/,
      );
      if (match) {
        const r = parseInt(match[1]).toString(16).padStart(2, "0");
        const g = parseInt(match[2]).toString(16).padStart(2, "0");
        const b = parseInt(match[3]).toString(16).padStart(2, "0");
        return `#${r}${g}${b}`;
      }
      return "";
    };

    const background =
      cssVar("--vscode-editor-background") ||
      (darkTheme ? "#1f1f1f" : "#ffffff");
    const foreground =
      cssVar("--vscode-editor-foreground") ||
      (darkTheme ? "rgba(255, 255, 255, 0.85)" : "rgba(0, 0, 0, 0.88)");

    const getMonacoColor = (className: string): string => {
      if (typeof document === "undefined") return "";
      const elements = document.querySelectorAll(`.${className}`);
      if (elements.length > 0) {
        const color = getComputedStyle(elements[0]).color;
        return rgbaToHex(color);
      }
      return "";
    };

    const cssVarOrFallback = (
      varName: string,
      monacoClass: string,
      fallbackDark: string,
      fallbackLight: string,
    ): string => {
      const cssColor = rgbaToHex(cssVar(varName));
      if (cssColor && cssColor !== "#cccccc") return cssColor;

      const monacoColor = getMonacoColor(monacoClass);
      if (monacoColor && monacoColor !== "#cccccc") return monacoColor;

      return darkTheme ? fallbackDark : fallbackLight;
    };

    const keywordColor = cssVarOrFallback(
      "--vscode-symbolIcon-keywordForeground",
      "mtk3",
      "#569CD6",
      "#0000FF",
    );
    const stringColor = cssVarOrFallback(
      "--vscode-symbolIcon-stringForeground",
      "mtk9",
      "#CE9178",
      "#A31515",
    );
    const numberColor = cssVarOrFallback(
      "--vscode-symbolIcon-numberForeground",
      "mtk10",
      "#B5CEA8",
      "#098658",
    );
    const variableColor = cssVarOrFallback(
      "--vscode-symbolIcon-variableForeground",
      "mtk5",
      "#9CDCFE",
      "#001080",
    );
    const functionColor = cssVarOrFallback(
      "--vscode-symbolIcon-functionForeground",
      "mtk12",
      "#DCDCAA",
      "#795E26",
    );
    const typeColor = cssVarOrFallback(
      "--vscode-symbolIcon-classForeground",
      "mtk8",
      "#4EC9B0",
      "#267F99",
    );
    const commentColor = cssVarOrFallback(
      "--vscode-editorIndentGuide-activeBackground",
      "mtk4",
      "#6A9955",
      "#008000",
    );

    console.log("[useSyntaxHighlighterTheme] Theme:", {
      isDark,
      dataTheme:
        typeof document !== "undefined"
          ? document.documentElement.getAttribute("data-theme")
          : "N/A",
      darkTheme,
      background,
      foreground,
    });
    console.log("[useSyntaxHighlighterTheme] Token colors:", {
      keywordColor,
      stringColor,
      numberColor,
      variableColor,
      typeColor,
      commentColor,
    });

    return {
      'code[class*="language-"]': {
        color: foreground,
        background: background,
      } as CSSProperties,
      'pre[class*="language-"]': {
        color: foreground,
        background: background,
        margin: 0,
        padding: 0,
        overflow: "auto",
      } as CSSProperties,
      comment: { color: commentColor, fontStyle: "italic" } as CSSProperties,
      prolog: { color: commentColor } as CSSProperties,
      doctype: { color: commentColor } as CSSProperties,
      cdata: { color: commentColor } as CSSProperties,
      punctuation: { color: foreground } as CSSProperties,
      property: { color: variableColor } as CSSProperties,
      tag: { color: typeColor } as CSSProperties,
      boolean: { color: keywordColor } as CSSProperties,
      null: { color: keywordColor } as CSSProperties,
      number: { color: numberColor } as CSSProperties,
      constant: { color: keywordColor } as CSSProperties,
      symbol: { color: numberColor } as CSSProperties,
      deleted: { color: stringColor } as CSSProperties,
      selector: { color: stringColor } as CSSProperties,
      "attr-name": { color: variableColor } as CSSProperties,
      string: { color: stringColor } as CSSProperties,
      char: { color: stringColor } as CSSProperties,
      builtin: { color: keywordColor } as CSSProperties,
      inserted: { color: stringColor } as CSSProperties,
      operator: { color: foreground } as CSSProperties,
      entity: { color: foreground, cursor: "help" } as CSSProperties,
      url: { color: stringColor } as CSSProperties,
      variable: { color: variableColor } as CSSProperties,
      atrule: { color: keywordColor } as CSSProperties,
      "attr-value": { color: stringColor } as CSSProperties,
      function: { color: functionColor } as CSSProperties,
      "class-name": { color: typeColor } as CSSProperties,
      keyword: { color: keywordColor } as CSSProperties,
      regex: { color: stringColor } as CSSProperties,
      important: { color: keywordColor, fontWeight: "bold" } as CSSProperties,
      bold: { fontWeight: "bold" } as CSSProperties,
      italic: { fontStyle: "italic" } as CSSProperties,
      ".token.string": { color: stringColor } as CSSProperties,
      ".token.property": { color: variableColor } as CSSProperties,
      ".token.number": { color: numberColor } as CSSProperties,
      ".token.boolean": { color: keywordColor } as CSSProperties,
      ".token.null": { color: keywordColor } as CSSProperties,
    };
  }, [isDark]);

  return customTheme;
}
