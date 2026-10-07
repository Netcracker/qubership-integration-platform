const manifest = require("../../package.json");

describe("package.json editor associations", () => {
  const patterns: string[] = manifest.contributes.customEditors.flatMap(
    (editor: { selector: { filenamePattern: string }[] }) =>
      editor.selector.map((selector) => selector.filenamePattern),
  );

  it("opens only *.cip.yaml files in the custom editors", () => {
    expect(patterns).toEqual([
      "*.chain.cip.yaml",
      "*.service.cip.yaml",
      "*.context-service.cip.yaml",
      "*.mcp-service.cip.yaml",
    ]);
  });

  it("associates no *.qip.yaml pattern with an editor", () => {
    const diffPatterns = Object.keys(
      manifest.contributes.configurationDefaults[
        "workbench.diffEditorAssociations"
      ],
    );

    expect(
      [...patterns, ...diffPatterns].filter((p) => p.includes(".qip.")),
    ).toEqual([]);
  });
});
