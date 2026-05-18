import { describe, it, expect, jest } from "@jest/globals";

jest.mock("monaco-editor", () => ({
  editor: {},
  languages: {
    CompletionItemKind: {
      Method: 0,
      Function: 1,
      Constant: 2,
      Variable: 4,
      Keyword: 17,
      Snippet: 27,
    },
    CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
  },
}));

import {
  getGroovyCompletionItems,
  GROOVY_EXPRESSION_KEYWORDS,
  GROOVY_KEYWORDS,
  groovySuggestionsFor,
  GROOVY_STATEMENT_KEYWORDS,
  isGroovyStatementStart,
  resolveGroovyContextSuggestions,
} from "../../../src/misc/monaco/groovy-suggestions";

const RANGE = {
  startLineNumber: 1,
  endLineNumber: 1,
  startColumn: 1,
  endColumn: 1,
};

describe("groovy-suggestions", () => {
  describe("GROOVY_KEYWORDS", () => {
    it("contains all standard Groovy keywords", () => {
      expect(GROOVY_KEYWORDS).toContain("def");
      expect(GROOVY_KEYWORDS).toContain("if");
      expect(GROOVY_KEYWORDS).toContain("class");
      expect(GROOVY_KEYWORDS).toContain("import");
      expect(GROOVY_KEYWORDS.length).toBeGreaterThanOrEqual(50);
    });
  });

  describe("groovySuggestionsFor", () => {
    it("'keywords' returns one item per keyword with Keyword kind", () => {
      const items = groovySuggestionsFor("keywords", RANGE);
      expect(items).toHaveLength(GROOVY_KEYWORDS.length);
      for (const item of items) {
        expect(item.kind).toBe(17);
        expect(item.range).toEqual(RANGE);
      }
    });

    it("'snippets' includes core control-flow snippets with InsertAsSnippet rule", () => {
      const items = groovySuggestionsFor("snippets", RANGE);
      const labels = items.map((i) => i.label);
      expect(labels).toEqual(
        expect.arrayContaining(["if", "ifelse", "for", "try", "closure"]),
      );
      for (const item of items) {
        expect(item.insertTextRules).toBe(4);
        expect(item.kind).toBe(27);
      }
    });

    it("'exchangeApi' includes 'exchange' with Variable kind and detail", () => {
      const items = groovySuggestionsFor("exchangeApi", RANGE);
      const exchange = items.find((i) => i.label === "exchange");
      expect(exchange).toBeTruthy();
      expect(exchange?.kind).toBe(4);
      expect(exchange?.detail).toBe("org.apache.camel.Exchange");
    });

    it("'exchangeMembers' includes core Exchange methods with Method kind", () => {
      const items = groovySuggestionsFor("exchangeMembers", RANGE);
      const labels = items.map((i) => i.label);
      expect(labels).toEqual(
        expect.arrayContaining([
          "getIn",
          "getMessage",
          "getProperty",
          "setProperty",
          "removeProperty",
          "getException",
          "getContext",
        ]),
      );
      for (const item of items) {
        expect(item.kind).toBe(0);
      }
    });

    it("'exchangeMembers' marks parameterized methods as snippets", () => {
      const items = groovySuggestionsFor("exchangeMembers", RANGE);
      const setProp = items.find((i) => i.label === "setProperty");
      expect(setProp?.insertTextRules).toBe(4);
      const getMessage = items.find((i) => i.label === "getMessage");
      expect(getMessage?.insertTextRules).toBeUndefined();
    });

    it("'messageMembers' includes core Message methods", () => {
      const labels = groovySuggestionsFor("messageMembers", RANGE).map(
        (i) => i.label,
      );
      expect(labels).toEqual(
        expect.arrayContaining([
          "getBody",
          "getBodyAs",
          "setBody",
          "getHeader",
          "setHeader",
          "removeHeader",
          "getMessageId",
        ]),
      );
    });
  });

  describe("resolveGroovyContextSuggestions", () => {
    it("returns null for plain text without dot chain", () => {
      expect(resolveGroovyContextSuggestions("", RANGE)).toBeNull();
      expect(resolveGroovyContextSuggestions("hello", RANGE)).toBeNull();
    });

    it("returns Exchange members after 'exchange.'", () => {
      const result = resolveGroovyContextSuggestions("exchange.", RANGE);
      expect(result).not.toBeNull();
      expect(result?.map((i) => i.label)).toEqual(
        expect.arrayContaining(["getIn", "getMessage", "getProperty"]),
      );
    });

    it("returns Message members after 'getMessage().'", () => {
      const result = resolveGroovyContextSuggestions(
        "exchange.getMessage().",
        RANGE,
      );
      expect(result).not.toBeNull();
      expect(result?.map((i) => i.label)).toEqual(
        expect.arrayContaining(["getBody", "getHeader"]),
      );
    });

    it("returns Message members after 'getIn().'", () => {
      const result = resolveGroovyContextSuggestions("getIn().", RANGE);
      expect(result?.map((i) => i.label)).toContain("getBody");
    });

    it("returns empty list for unknown last segment", () => {
      const result = resolveGroovyContextSuggestions("foo.", RANGE);
      expect(result).toEqual([]);
    });

    it("returns empty list after a chain that ends on an unknown method", () => {
      const result = resolveGroovyContextSuggestions(
        "exchange.getProperty('x').",
        RANGE,
      );
      expect(result).toEqual([]);
    });
  });

  describe("GROOVY_STATEMENT_KEYWORDS / GROOVY_EXPRESSION_KEYWORDS", () => {
    it("statement set contains statement-only keywords", () => {
      expect(GROOVY_STATEMENT_KEYWORDS).toEqual(
        expect.arrayContaining([
          "if",
          "for",
          "while",
          "try",
          "return",
          "abstract",
          "def",
          "class",
        ]),
      );
    });

    it("expression set contains value/operator keywords", () => {
      expect(GROOVY_EXPRESSION_KEYWORDS).toEqual(
        expect.arrayContaining([
          "null",
          "true",
          "false",
          "this",
          "super",
          "new",
          "instanceof",
          "as",
          "in",
        ]),
      );
    });

    it("statement and expression sets are disjoint", () => {
      const expr = new Set(GROOVY_EXPRESSION_KEYWORDS);
      for (const k of GROOVY_STATEMENT_KEYWORDS) {
        expect(expr.has(k)).toBe(false);
      }
    });

    it("full GROOVY_KEYWORDS is union of both", () => {
      const total =
        GROOVY_STATEMENT_KEYWORDS.length + GROOVY_EXPRESSION_KEYWORDS.length;
      expect(GROOVY_KEYWORDS.length).toBe(total);
    });
  });

  describe("isGroovyStatementStart", () => {
    it("treats empty text as statement start", () => {
      expect(isGroovyStatementStart("")).toBe(true);
      expect(isGroovyStatementStart("   \n  ")).toBe(true);
    });

    it("returns true after ';', '{', '}'", () => {
      expect(isGroovyStatementStart("def x = 5;")).toBe(true);
      expect(isGroovyStatementStart("def x = 5; ")).toBe(true);
      expect(isGroovyStatementStart("if (x) {")).toBe(true);
      expect(isGroovyStatementStart("if (x) { ")).toBe(true);
      expect(isGroovyStatementStart("foo()\n}")).toBe(true);
    });

    it("returns false inside parens", () => {
      expect(isGroovyStatementStart("foo(")).toBe(false);
      expect(isGroovyStatementStart("foo(bar, ")).toBe(false);
      expect(isGroovyStatementStart("exchange.setProperty('a', ")).toBe(false);
    });

    it("returns false after assignment / operators on same line", () => {
      expect(isGroovyStatementStart("def x = ")).toBe(false);
      expect(isGroovyStatementStart("def x = 5 + ")).toBe(false);
      expect(isGroovyStatementStart("a, ")).toBe(false);
    });

    it("returns true on a fresh line after a complete statement", () => {
      expect(isGroovyStatementStart("def x = 5\n")).toBe(true);
      expect(isGroovyStatementStart("foo()\n  ")).toBe(true);
    });

    it("returns false on a continued line after an unfinished expression", () => {
      expect(isGroovyStatementStart("def x = 5 +\n  ")).toBe(false);
      expect(isGroovyStatementStart("foo(\n  ")).toBe(false);
    });

    it("ignores separators inside strings and comments", () => {
      expect(isGroovyStatementStart("def x = 'a;b'")).toBe(false);
      expect(isGroovyStatementStart('def x = "a;b"')).toBe(false);
      expect(isGroovyStatementStart("def x = 5 /* ; */")).toBe(false);
      expect(isGroovyStatementStart("def x = 5 // ;")).toBe(false);
    });
  });

  describe("getGroovyCompletionItems", () => {
    it("returns Exchange members after 'exchange.'", () => {
      const labels = getGroovyCompletionItems("exchange.", RANGE).map(
        (i) => i.label,
      );
      expect(labels).toEqual(expect.arrayContaining(["getMessage", "getIn"]));
      expect(labels).not.toContain("if");
    });

    it("returns empty list after a dot on an unknown receiver", () => {
      expect(
        getGroovyCompletionItems("exchange.getException().", RANGE),
      ).toEqual([]);
      expect(getGroovyCompletionItems("foo.", RANGE)).toEqual([]);
    });

    it("returns keywords + snippets + Exchange API at statement start", () => {
      const labels = getGroovyCompletionItems("", RANGE).map((i) => i.label);
      expect(labels).toContain("if");
      expect(labels).toContain("for");
      expect(labels).toContain("def");
      expect(labels).toContain("abstract");
      expect(labels).toContain("ifelse");
      expect(labels).toContain("forin");
      expect(labels).toContain("closure");
      expect(labels).toContain("exchange");
      expect(labels).toContain("log");
    });

    it("inside parens returns only expression keywords + Exchange API (no statement keywords/snippets)", () => {
      const labels = getGroovyCompletionItems(
        "exchange.setProperty('aas', ",
        RANGE,
      ).map((i) => i.label);
      // expression-safe values
      expect(labels).toEqual(
        expect.arrayContaining(["null", "true", "false", "this", "new"]),
      );
      // Exchange API still available
      expect(labels).toContain("exchange");
      // No statement keywords or snippets
      expect(labels).not.toContain("if");
      expect(labels).not.toContain("for");
      expect(labels).not.toContain("abstract");
      expect(labels).not.toContain("def");
      expect(labels).not.toContain("ifelse");
      expect(labels).not.toContain("forin");
    });

    it("after assignment returns expression context", () => {
      const labels = getGroovyCompletionItems("def x = ", RANGE).map(
        (i) => i.label,
      );
      expect(labels).not.toContain("if");
      expect(labels).not.toContain("ifelse");
      expect(labels).toContain("null");
      expect(labels).toContain("exchange");
    });

    it("returns statement-context list after ';'", () => {
      const labels = getGroovyCompletionItems("def x = 5; ", RANGE).map(
        (i) => i.label,
      );
      expect(labels).toContain("if");
      expect(labels).toContain("ifelse");
    });
  });
});
