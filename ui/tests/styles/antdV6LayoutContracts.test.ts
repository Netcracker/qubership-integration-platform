import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const sourceRoot = resolve(__dirname, "../../src");

function readStyle(relativePath: string): string {
  return readFileSync(resolve(sourceRoot, relativePath), "utf8");
}

function declarationsFor(css: string, selector: string): string {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = css.match(new RegExp(`${escapedSelector}\\s*\\{([^{}]*)\\}`));
  return match?.[1].replace(/\\s+/g, " ").trim() ?? "";
}

describe("Ant Design 6 global style contracts", () => {
  it("does not replace component transitions outside a theme switch", () => {
    const css = readStyle("styles/theme-variables.css");

    expect(css).not.toMatch(/:root:not\(\.theme-switching\)\s*\*/);
    expect(declarationsFor(css, ".theme-switching *")).toContain(
      "transition: none !important",
    );
  });
});
