import { Connection, Element } from "../../../../../src/api/apiTypes";
import {
  compareArraysLexicographically,
  extractValues,
  intersection,
  sortBy,
  sortElementsTopologically,
} from "../../../../../src/components/chains/diff/compare/utils";

type TestObj = { a: number; b: number; c?: number };

function makeElement(overrides: Partial<Element> = {}): Element {
  return {
    id: "e1",
    name: "Element",
    description: "",
    type: "script",
    chainId: "chain-1",
    properties: {} as never,
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

function makeConnection(id: string, from: string, to: string): Connection {
  return { id, from, to };
}

describe("compareArraysLexicographically", () => {
  test("should return 0 when both arrays are empty", () => {
    expect(compareArraysLexicographically([], [])).toBe(0);
  });

  test("should return 0 when arrays are equal", () => {
    expect(compareArraysLexicographically([1, 2, 3], [1, 2, 3])).toBe(0);
  });

  test("should return negative when first differing element of a is less than b", () => {
    expect(compareArraysLexicographically([1], [2])).toBeLessThan(0);
  });

  test("should return positive when first differing element of a is greater than b", () => {
    expect(compareArraysLexicographically([2], [1])).toBeGreaterThan(0);
  });

  test("should return negative when a is a proper prefix of b", () => {
    expect(compareArraysLexicographically([1], [1, 2])).toBeLessThan(0);
  });

  test("should return positive when b is a proper prefix of a", () => {
    expect(compareArraysLexicographically([1, 2], [1])).toBeGreaterThan(0);
  });

  test("should compare at second position when first elements are equal", () => {
    expect(compareArraysLexicographically([1, 2], [1, 3])).toBeLessThan(0);
  });
});

describe("extractValues", () => {
  test("should return property values when keys are property names", () => {
    const extract = extractValues<TestObj>(["a", "b"]);
    expect(extract({ a: 1, b: 2 })).toEqual([1, 2]);
  });

  test("should return computed values when keys are extractor functions", () => {
    const extract = extractValues<TestObj>([(v) => v.a * 2]);
    expect(extract({ a: 3, b: 0 })).toEqual([6]);
  });

  test("should return mixed values when keys combine property names and functions", () => {
    const extract = extractValues<TestObj>(["a", (v) => v.b + 1]);
    expect(extract({ a: 1, b: 2 })).toEqual([1, 3]);
  });

  test("should return empty array when keys list is empty", () => {
    const extract = extractValues<TestObj>([]);
    expect(extract({ a: 1, b: 2 })).toEqual([]);
  });

  test("should return undefined when a key is absent from the object", () => {
    const extract = extractValues<TestObj>(["c"]);
    expect(extract({ a: 1, b: 2 })).toEqual([undefined]);
  });
});

describe("sortBy", () => {
  const numericComparator = (a: number, b: number): number => a - b;

  test("should sort ascending when comparator orders numerically", () => {
    expect(sortBy([3, 1, 2], (v) => v, numericComparator)).toEqual([1, 2, 3]);
  });

  test("should sort descending when comparator is reversed", () => {
    expect(
      sortBy(
        [3, 1, 2],
        (v) => v,
        (a, b) => b - a,
      ),
    ).toEqual([3, 2, 1]);
  });

  test("should return empty array when input is empty", () => {
    expect(sortBy<number, number>([], (v) => v, numericComparator)).toEqual([]);
  });

  test("should not mutate the original array when sorting", () => {
    const original = [3, 1, 2];
    sortBy(original, (v) => v, numericComparator);
    expect(original).toEqual([3, 1, 2]);
  });

  test("should return the same single element when list has one item", () => {
    expect(sortBy([42], (v) => v, numericComparator)).toEqual([42]);
  });

  test("should apply criteria before comparing when sorting by derived value", () => {
    const items = [{ name: "bb" }, { name: "a" }, { name: "ccc" }];
    const result = sortBy(items, (v) => v.name.length, numericComparator);
    expect(result.map((v) => v.name)).toEqual(["a", "bb", "ccc"]);
  });
});

describe("intersection", () => {
  test("should return empty set when both sets are empty", () => {
    expect(intersection(new Set<number>(), new Set<number>())).toEqual(
      new Set<number>(),
    );
  });

  test("should return empty set when sets are disjoint", () => {
    expect(intersection(new Set([1, 2]), new Set([3, 4]))).toEqual(new Set());
  });

  test("should return only common elements when sets partially overlap", () => {
    expect(intersection(new Set([1, 2, 3]), new Set([2, 3, 4]))).toEqual(
      new Set([2, 3]),
    );
  });

  test("should return s1 elements when s1 is a subset of s2", () => {
    expect(intersection(new Set([2]), new Set([1, 2, 3]))).toEqual(
      new Set([2]),
    );
  });

  test("should return all elements when sets are identical", () => {
    expect(intersection(new Set([1, 2]), new Set([1, 2]))).toEqual(
      new Set([1, 2]),
    );
  });
});

describe("sortElementsTopologically", () => {
  test("should return empty array when inputs are empty", () => {
    expect(sortElementsTopologically([], [])).toEqual([]);
  });

  test("should return the element when there is only one element and no connections", () => {
    const element = makeElement({ id: "a" });
    expect(sortElementsTopologically([element], [])).toEqual([element]);
  });

  test("should return all elements when there are no connections", () => {
    const elements = [
      makeElement({ id: "a" }),
      makeElement({ id: "b" }),
      makeElement({ id: "c" }),
    ];
    const result = sortElementsTopologically(elements, []);
    expect(result).toHaveLength(3);
    expect(result.map((e) => e.id)).toEqual(
      expect.arrayContaining(["a", "b", "c"]),
    );
  });

  test("should place source before target when a linear chain of connections is given", () => {
    const a = makeElement({ id: "a" });
    const b = makeElement({ id: "b" });
    const c = makeElement({ id: "c" });
    const result = sortElementsTopologically(
      [c, b, a],
      [makeConnection("1", "a", "b"), makeConnection("2", "b", "c")],
    );
    const ids = result.map((e) => e.id);
    expect(ids.indexOf("a")).toBeLessThan(ids.indexOf("b"));
    expect(ids.indexOf("b")).toBeLessThan(ids.indexOf("c"));
  });

  test("should place parent before child when parentElementId is set", () => {
    const parent = makeElement({ id: "parent" });
    const child = makeElement({ id: "child", parentElementId: "parent" });
    const result = sortElementsTopologically([child, parent], []);
    const ids = result.map((e) => e.id);
    expect(ids.indexOf("parent")).toBeLessThan(ids.indexOf("child"));
  });

  test("should not duplicate elements when connections are present", () => {
    const elements = [makeElement({ id: "a" }), makeElement({ id: "b" })];
    const result = sortElementsTopologically(elements, [
      makeConnection("1", "a", "b"),
    ]);
    expect(result).toHaveLength(2);
    expect(new Set(result.map((e) => e.id)).size).toBe(2);
  });
});
