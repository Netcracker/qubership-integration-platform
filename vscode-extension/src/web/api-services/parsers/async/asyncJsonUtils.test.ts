import { deepClone, isObject } from "./asyncJsonUtils";

describe("asyncJsonUtils", () => {
  describe("isObject", () => {
    it.each([
      [{ a: 1 }, true],
      [[], false],
      [null, false],
      ["x", false],
      [1, false],
      [undefined, false],
    ])("isObject(%p) === %p", (value, expected) => {
      expect(isObject(value)).toBe(expected);
    });
  });

  describe("deepClone", () => {
    it("returns a structurally-equal but non-identical copy", () => {
      const src = { a: { b: [1, 2] } };
      const copy = deepClone(src);
      expect(copy).toEqual(src);
      expect(copy).not.toBe(src);
      expect(copy.a).not.toBe(src.a);
    });

    it("returns null/undefined as-is", () => {
      expect(deepClone(null)).toBeNull();
      expect(deepClone(undefined)).toBeUndefined();
    });
  });
});
