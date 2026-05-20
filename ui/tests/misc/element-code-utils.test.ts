import type { editor } from "monaco-editor";
import {
  buildElementsSignature,
  findElementBlockRanges,
} from "../../src/misc/element-code-utils";
import type { Element } from "../../src/api/apiTypes";

/**
 * Minimal `ITextModel` stub implementing just the methods used by
 * `findElementBlockRanges`. Backed by the provided YAML text.
 */
const makeModel = (text: string): editor.ITextModel => {
  const lines = text.split("\n");
  const findMatches = (
    pattern: string,
    _searchOnlyEditableRange: boolean,
    _isRegex: boolean,
    _matchCase: boolean,
    _wordSeparators: string | null,
    captureMatches: boolean,
  ): editor.FindMatch[] => {
    const regex = new RegExp(pattern);
    const results: editor.FindMatch[] = [];
    for (let i = 0; i < lines.length; i++) {
      const match = lines[i].match(regex);
      if (!match) continue;
      results.push({
        range: {
          startLineNumber: i + 1,
          startColumn: 1,
          endLineNumber: i + 1,
          endColumn: lines[i].length + 1,
        },
        matches: captureMatches ? Array.from(match) : undefined,
      } as editor.FindMatch);
    }
    return results;
  };
  return {
    findMatches,
    getLineCount: () => lines.length,
  } as unknown as editor.ITextModel;
};

describe("buildElementsSignature", () => {
  const mk = (id: string, modifiedWhen?: number): Element =>
    ({
      id,
      name: id,
      description: "",
      chainId: "c",
      type: "script",
      properties: undefined as never,
      mandatoryChecksPassed: true,
      modifiedWhen,
    }) as Element;

  test("returns empty string for empty list", () => {
    expect(buildElementsSignature([])).toBe("");
  });

  test("combines id and modifiedWhen pipe-separated", () => {
    expect(buildElementsSignature([mk("a", 1), mk("b", 2)])).toBe("a:1|b:2");
  });

  test("changes when modifiedWhen changes", () => {
    const before = buildElementsSignature([mk("a", 1)]);
    const after = buildElementsSignature([mk("a", 2)]);
    expect(before).not.toEqual(after);
  });

  test("changes when id set changes", () => {
    const before = buildElementsSignature([mk("a", 1)]);
    const after = buildElementsSignature([mk("a", 1), mk("b", 1)]);
    expect(before).not.toEqual(after);
  });

  test("is stable for same input", () => {
    const elements = [mk("a", 1), mk("b", 2)];
    expect(buildElementsSignature(elements)).toBe(
      buildElementsSignature(elements),
    );
  });

  test("handles undefined modifiedWhen", () => {
    expect(buildElementsSignature([mk("a"), mk("b")])).toBe("a:|b:");
  });
});

describe("findElementBlockRanges", () => {
  const sample = [
    "---",
    '- id: "uuid-1"',
    '  name: "Mapper"',
    "  properties:",
    "    x: 1",
    '- id: "uuid-2"',
    '  name: "HTTP Trigger"',
    "  properties:",
    "    after:",
    '    - id: "nested"',
    '      code: "2xx"',
    '- id: "uuid-3"',
    '  name: "Script"',
  ].join("\n");

  test("returns empty map for empty id list", () => {
    const model = makeModel(sample);
    expect(findElementBlockRanges(model, []).size).toBe(0);
  });

  test("locates a single top-level element block", () => {
    const model = makeModel(sample);
    const result = findElementBlockRanges(model, ["uuid-1"]);
    expect(result.get("uuid-1")).toEqual({ startLine: 2, endLine: 5 });
  });

  test("locates multiple elements in one pass", () => {
    const model = makeModel(sample);
    const result = findElementBlockRanges(model, ["uuid-1", "uuid-3"]);
    expect(result.get("uuid-1")).toEqual({ startLine: 2, endLine: 5 });
    expect(result.get("uuid-3")).toEqual({ startLine: 12, endLine: 13 });
  });

  test("last element block ends at the last line", () => {
    const model = makeModel(sample);
    const result = findElementBlockRanges(model, ["uuid-3"]);
    expect(result.get("uuid-3")?.endLine).toBe(13);
  });

  test("ignores nested `- id:` occurrences (indented)", () => {
    const model = makeModel(sample);
    const result = findElementBlockRanges(model, ["nested"]);
    expect(result.size).toBe(0);
  });

  test("returns empty map when id is not present", () => {
    const model = makeModel(sample);
    const result = findElementBlockRanges(model, ["missing"]);
    expect(result.size).toBe(0);
  });

  test("returns empty map when the YAML has no top-level id markers", () => {
    const model = makeModel("---\n# only a comment\nfoo: bar");
    const result = findElementBlockRanges(model, ["anything"]);
    expect(result.size).toBe(0);
  });

  test("handles single-quoted ids", () => {
    const yaml = ["- id: 'abc'", "  name: foo", "- id: 'def'"].join("\n");
    const model = makeModel(yaml);
    const result = findElementBlockRanges(model, ["abc", "def"]);
    expect(result.get("abc")).toEqual({ startLine: 1, endLine: 2 });
    expect(result.get("def")).toEqual({ startLine: 3, endLine: 3 });
  });

  test("handles unquoted ids", () => {
    const yaml = ["- id: abc", "  name: foo", "- id: def"].join("\n");
    const model = makeModel(yaml);
    const result = findElementBlockRanges(model, ["abc"]);
    expect(result.get("abc")).toEqual({ startLine: 1, endLine: 2 });
  });

  test("skips matches whose id is not in the target set", () => {
    const model = makeModel(sample);
    const result = findElementBlockRanges(model, ["uuid-2"]);
    expect(result.has("uuid-1")).toBe(false);
    expect(result.has("uuid-3")).toBe(false);
    expect(result.get("uuid-2")).toEqual({ startLine: 6, endLine: 11 });
  });
});
