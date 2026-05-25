import { describe, it, expect, jest } from "@jest/globals";

jest.mock("monaco-editor", () => ({
  editor: {},
  languages: {},
}));

import {
  getDotChainPrefix,
  getRangeForWord,
  getTextBeforePosition,
  markdown,
  withRange,
} from "../../../src/misc/monaco/completion-helpers";

describe("completion-helpers", () => {
  describe("getRangeForWord", () => {
    it("converts position + word into IRange", () => {
      const range = getRangeForWord(
        { lineNumber: 5, column: 10 } as never,
        { startColumn: 6, endColumn: 10, word: "test" },
      );
      expect(range).toEqual({
        startLineNumber: 5,
        endLineNumber: 5,
        startColumn: 6,
        endColumn: 10,
      });
    });
  });

  describe("getTextBeforePosition", () => {
    it("returns full document text up to the cursor offset", () => {
      const fullText = "line1\nline2\nexchange.getMessage()";
      const model = {
        getValue: jest.fn(() => fullText),
        getOffsetAt: jest.fn(() => 21), // points just past "line1\nline2\nexchange."
      };
      const text = getTextBeforePosition(
        model as never,
        { lineNumber: 3, column: 10 } as never,
      );
      expect(text).toBe("line1\nline2\nexchange.");
      expect(model.getValue).toHaveBeenCalled();
      expect(model.getOffsetAt).toHaveBeenCalledWith({
        lineNumber: 3,
        column: 10,
      });
    });
  });

  describe("getDotChainPrefix", () => {
    it("returns null if no dot chain present", () => {
      expect(getDotChainPrefix("")).toBeNull();
      expect(getDotChainPrefix("hello world")).toBeNull();
      expect(getDotChainPrefix("foo")).toBeNull();
    });

    it("splits a simple chain into segments", () => {
      expect(getDotChainPrefix("exchange.")).toEqual(["exchange"]);
      expect(getDotChainPrefix("exchange.get")).toEqual(["exchange"]);
    });

    it("includes parentheses in segments for method calls", () => {
      expect(getDotChainPrefix("exchange.getMessage().")).toEqual([
        "exchange",
        "getMessage()",
      ]);
      // The trailing identifier is the in-progress word being typed,
      // so the chain stops at the previous segment.
      expect(getDotChainPrefix("exchange.getMessage().getBody")).toEqual([
        "exchange",
        "getMessage()",
      ]);
      // After a trailing dot, the previous identifier becomes part of the chain.
      expect(getDotChainPrefix("exchange.getMessage().getBody.")).toEqual([
        "exchange",
        "getMessage()",
        "getBody",
      ]);
    });

    it("anchors to the end of input", () => {
      expect(getDotChainPrefix("if (true) exchange.")).toEqual(["exchange"]);
    });
  });

  describe("withRange", () => {
    it("attaches the same range to every item", () => {
      const range = {
        startLineNumber: 1,
        endLineNumber: 1,
        startColumn: 1,
        endColumn: 4,
      };
      const items = [
        { label: "a", kind: 1, insertText: "a" },
        { label: "b", kind: 1, insertText: "b" },
      ];
      const result = withRange(items, range);
      expect(result).toHaveLength(2);
      expect(result[0]).toEqual({
        label: "a",
        kind: 1,
        insertText: "a",
        range,
      });
      expect(result[1].range).toBe(range);
    });
  });

  describe("markdown", () => {
    it("wraps the value as untrusted markdown", () => {
      expect(markdown("**hi**")).toEqual({
        value: "**hi**",
        isTrusted: false,
      });
    });
  });
});
