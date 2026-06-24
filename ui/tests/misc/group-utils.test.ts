import {
  GROUP_PATH_REGEX,
  GROUP_SEGMENT_REGEX,
  parseGroupSegments,
  sanitizeGroupSegment,
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

describe("GROUP_PATH_REGEX", () => {
  it("should accept a single segment", () => {
    expect(GROUP_PATH_REGEX.test("a")).toBe(true);
  });

  it("should accept a multi-segment path", () => {
    expect(GROUP_PATH_REGEX.test("a/b/c")).toBe(true);
  });

  it("should reject a leading slash", () => {
    expect(GROUP_PATH_REGEX.test("/a/b")).toBe(false);
  });

  it("should reject a trailing slash", () => {
    expect(GROUP_PATH_REGEX.test("a/b/")).toBe(false);
  });

  it("should reject an empty string", () => {
    expect(GROUP_PATH_REGEX.test("")).toBe(false);
  });

  it("should reject a forbidden character inside a segment", () => {
    expect(GROUP_PATH_REGEX.test("a/b:c")).toBe(false);
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

describe("sanitizeGroupSegment", () => {
  it("should leave a clean segment unchanged", () => {
    expect(sanitizeGroupSegment("folder")).toBe("folder");
  });

  it.each(FORBIDDEN)("should replace %j with '-'", (ch) => {
    expect(sanitizeGroupSegment(`a${ch}b`)).toBe("a-b");
  });

  it("should replace every forbidden character in a segment", () => {
    const segment = "a" + FORBIDDEN.join("") + "b";
    expect(sanitizeGroupSegment(segment)).toBe(
      "a" + "-".repeat(FORBIDDEN.length) + "b",
    );
  });
});
