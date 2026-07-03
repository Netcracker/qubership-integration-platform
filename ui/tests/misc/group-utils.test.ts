import {
  GROUP_SEGMENT_REGEX,
  parseGroupSegments,
} from "../../src/misc/group-utils";

// The characters forbidden in a group segment, per the schema `metaInfo.group` pattern.
const FORBIDDEN = ["/", ":", "*", "?", '"', "<", ">", "|", ",", ";", "\\"];

describe("GROUP_SEGMENT_REGEX", () => {
  it("should accept a plain segment name", () => {
    expect(GROUP_SEGMENT_REGEX.test("folder")).toBe(true);
    expect(GROUP_SEGMENT_REGEX.test("My Folder 1")).toBe(true);
  });

  it("should reject an empty segment", () => {
    expect(GROUP_SEGMENT_REGEX.test("")).toBe(false);
  });

  it.each(FORBIDDEN)("should reject a segment containing %j", (ch) => {
    expect(GROUP_SEGMENT_REGEX.test(`a${ch}b`)).toBe(false);
  });
});

describe("parseGroupSegments", () => {
  it("should split a path into segments", () => {
    expect(parseGroupSegments("a/b/c")).toEqual(["a", "b", "c"]);
  });

  it("should drop leading, trailing, and repeated slashes", () => {
    expect(parseGroupSegments("/a//b/")).toEqual(["a", "b"]);
  });

  it("should trim whitespace around segments", () => {
    expect(parseGroupSegments(" a / b ")).toEqual(["a", "b"]);
  });

  it("should return an empty array for an empty or slash-only path", () => {
    expect(parseGroupSegments("")).toEqual([]);
    expect(parseGroupSegments("///")).toEqual([]);
    expect(parseGroupSegments("   ")).toEqual([]);
  });
});
