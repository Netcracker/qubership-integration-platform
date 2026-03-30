import {
  makeSearchHaystack,
  matchesByFields,
  normalizeSearchTerm,
} from "../../../src/components/table/tableSearch";

describe("tableSearch", () => {
  describe("normalizeSearchTerm", () => {
    it("trims and lowercases", () => {
      expect(normalizeSearchTerm("  Foo BAR  ")).toBe("foo bar");
    });
  });

  describe("makeSearchHaystack", () => {
    it("joins non-empty fields as lowercase string", () => {
      expect(makeSearchHaystack(["A", null, "", 42, true])).toBe("a 42 true");
    });

    it("returns empty string when all fields empty", () => {
      expect(makeSearchHaystack([null, "", undefined])).toBe("");
    });
  });

  describe("matchesByFields", () => {
    it("returns true for empty search term", () => {
      expect(matchesByFields("", ["anything"])).toBe(true);
      expect(matchesByFields("   ", ["x"])).toBe(true);
    });

    it("returns true when haystack contains term", () => {
      expect(matchesByFields("foo", ["Hello", "FooBar"])).toBe(true);
    });

    it("is case-insensitive", () => {
      expect(matchesByFields("FOO", ["x", "food"])).toBe(true);
    });

    it("returns false when no match", () => {
      expect(matchesByFields("zzz", ["aaa", "bbb"])).toBe(false);
    });
  });
});
