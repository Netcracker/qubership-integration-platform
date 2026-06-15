/**
 * @jest-environment jsdom
 */
import {
  isDarkThemeActive,
  applyLightThemeFlip,
} from "../../../src/hooks/graph/exportTheme";

describe("exportTheme", () => {
  beforeEach(() => {
    document.documentElement.className = "";
    document.body.className = "";
    document.documentElement.removeAttribute("data-theme");
    document.documentElement.removeAttribute("style");
  });

  describe("isDarkThemeActive", () => {
    test("should return true when the --vscode-is-dark flag is 1", () => {
      document.documentElement.style.setProperty("--vscode-is-dark", "1");
      expect(isDarkThemeActive()).toBe(true);
    });

    test("should return false when the --vscode-is-dark flag is 0", () => {
      document.documentElement.style.setProperty("--vscode-is-dark", "0");
      expect(isDarkThemeActive()).toBe(false);
    });

    test("should prefer the --vscode-is-dark flag over data-theme", () => {
      document.documentElement.setAttribute("data-theme", "dark");
      document.documentElement.style.setProperty("--vscode-is-dark", "0");
      expect(isDarkThemeActive()).toBe(false);
    });

    test("should fall back to data-theme=dark when the flag is absent", () => {
      document.documentElement.setAttribute("data-theme", "dark");
      expect(isDarkThemeActive()).toBe(true);
    });

    test("should treat data-theme=high-contrast as dark", () => {
      document.documentElement.setAttribute("data-theme", "high-contrast");
      expect(isDarkThemeActive()).toBe(true);
    });

    test("should return false for data-theme=light with no flag", () => {
      document.documentElement.setAttribute("data-theme", "light");
      expect(isDarkThemeActive()).toBe(false);
    });
  });

  describe("applyLightThemeFlip", () => {
    const makeDarkWebview = () => {
      const root = document.documentElement;
      root.classList.add("vscode-webview");
      document.body.classList.add("vscode-webview");
      root.setAttribute("data-theme", "dark");
      root.style.setProperty("--vscode-editor-background", "#1f1f1f");
    };

    test("should switch a dark webview document to the light theme", () => {
      makeDarkWebview();
      const root = document.documentElement;

      applyLightThemeFlip();

      expect(root.classList.contains("vscode-webview")).toBe(false);
      expect(document.body.classList.contains("vscode-webview")).toBe(false);
      expect(root.getAttribute("data-theme")).toBe("light");
      expect(root.classList.contains("theme-switching")).toBe(true);
      expect(root.style.getPropertyValue("--vscode-editor-background")).toBe(
        "",
      );
    });

    test("should restore the original theme exactly on cleanup", () => {
      makeDarkWebview();
      const root = document.documentElement;
      const styleBefore = root.getAttribute("style");

      const restore = applyLightThemeFlip();
      restore();

      expect(root.classList.contains("vscode-webview")).toBe(true);
      expect(document.body.classList.contains("vscode-webview")).toBe(true);
      expect(root.getAttribute("data-theme")).toBe("dark");
      expect(root.getAttribute("style")).toBe(styleBefore);
      expect(root.classList.contains("theme-switching")).toBe(false);
    });

    test("should remove data-theme on cleanup when none was set", () => {
      const restore = applyLightThemeFlip();
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");
      restore();
      expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    });

    test("should remove the style attribute on cleanup when none was set", () => {
      const restore = applyLightThemeFlip();
      restore();
      expect(document.documentElement.hasAttribute("style")).toBe(false);
    });

    test("should preserve unrelated inline styles across the flip", () => {
      const root = document.documentElement;
      root.style.setProperty("--vscode-is-dark", "1");
      root.style.setProperty("color", "red");

      const restore = applyLightThemeFlip();
      expect(root.style.getPropertyValue("color")).toBe("");

      restore();
      expect(root.style.getPropertyValue("color")).toBe("red");
      expect(root.style.getPropertyValue("--vscode-is-dark")).toBe("1");
    });

    test("should not re-add vscode-webview on cleanup when it was absent", () => {
      document.documentElement.setAttribute("data-theme", "dark");

      const restore = applyLightThemeFlip();
      restore();

      expect(
        document.documentElement.classList.contains("vscode-webview"),
      ).toBe(false);
      expect(document.body.classList.contains("vscode-webview")).toBe(false);
    });
  });
});
