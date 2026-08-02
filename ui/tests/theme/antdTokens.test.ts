/**
 * @jest-environment jsdom
 */

// `--vscode-border` is a name this project invented: VS Code registers no
// `border` color and never injects it, so inside the webview it reads empty and
// the border token used to land on a hard-coded default. `.ant-input` overrides
// its border in CSS and looked right; Select, which has no override, did not.

import { describe, it, expect, afterEach } from "@jest/globals";
import { getThemeTokens } from "../../src/theme/antdTokens";

const root = () => document.documentElement;

afterEach(() => {
  root().style.removeProperty("--vscode-editorGroup-border");
  root().style.removeProperty("--vscode-border");
});

describe("getThemeTokens", () => {
  it("should take the border from --vscode-editorGroup-border when the host injects it", () => {
    root().style.setProperty("--vscode-editorGroup-border", "#3c3c3c");
    root().style.setProperty("--vscode-border", "#d9d9d9");

    const tokens = getThemeTokens(true);

    expect(tokens.colorBorder).toBe("#3c3c3c");
    expect(tokens.colorBorderSecondary).toBe("#3c3c3c");
  });

  it("should fall back to --vscode-border when the host variable is absent", () => {
    root().style.setProperty("--vscode-border", "#303030");

    expect(getThemeTokens(true).colorBorder).toBe("#303030");
  });

  it("should fall back to the theme default when neither variable is set", () => {
    expect(getThemeTokens(true).colorBorder).toBe("#303030");
    expect(getThemeTokens(false).colorBorder).toBe("#d9d9d9");
  });
});
