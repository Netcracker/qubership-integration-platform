import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const sourceRoot = resolve(__dirname, "../../src");

function readStyle(relativePath: string): string {
  return readFileSync(resolve(sourceRoot, relativePath), "utf8");
}

function declarationsFor(css: string, selector: string): string {
  const rulePattern = /([^{}]+)\{([^{}]*)\}/g;
  const stylesheet = css.replace(/\/\*[\s\S]*?\*\//g, "");

  for (const match of stylesheet.matchAll(rulePattern)) {
    if (match[1].split(",").some((item) => item.trim() === selector)) {
      return match[2].replace(/\s+/g, " ").trim();
    }
  }

  return "";
}

describe("Ant Design 6 global style contracts", () => {
  it("does not replace component transitions outside a theme switch", () => {
    const css = readStyle("styles/theme-variables.css");

    expect(css).not.toMatch(/:root:not\(\.theme-switching\)\s*\*/);
    expect(declarationsFor(css, ".theme-switching *")).toContain(
      "transition: none !important",
    );
  });

  it("targets the Ant Design 6 tab body structure", () => {
    const css = readStyle("index.css");

    expect(declarationsFor(css, ".flex-tabs .ant-tabs-body-holder")).toContain(
      "display: flex",
    );
    expect(declarationsFor(css, ".flex-tabs .ant-tabs-body")).toContain(
      "flex: 1 1 auto",
    );
    expect(
      declarationsFor(css, ".flex-tabs .ant-tabs-content-active"),
    ).toContain("display: flex");
  });

  it("keeps inactive Ant Design tabs out of the layout", () => {
    const css = readStyle("index.css");

    expect(
      declarationsFor(css, ".flex-tabs .ant-tabs-content-hidden"),
    ).toContain("display: none");
  });

  it("allows Monaco wrappers to shrink inside flex layouts", () => {
    const css = readStyle("index.css");
    const declarations = declarationsFor(css, ".qip-editor");

    expect(declarations).toContain("min-height: 0");
    expect(declarations).toContain("min-width: 0");
  });
});
