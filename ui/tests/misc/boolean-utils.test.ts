import { describe, it, expect } from "@jest/globals";
import { parseBooleanFlag } from "../../src/misc/boolean-utils.ts";

describe("parseBooleanFlag", () => {
  it.each([
    [true, true],
    [false, false],
    ["true", true],
    ["false", false],
    ["TRUE", true],
    ["False", false],
  ])("should read %p as %p", (value, expected) => {
    expect(parseBooleanFlag(value, !expected)).toBe(expected);
  });

  it("should return the default for a missing flag", () => {
    expect(parseBooleanFlag(undefined, true)).toBe(true);
    expect(parseBooleanFlag(null, true)).toBe(true);
    expect(parseBooleanFlag(undefined)).toBe(false);
  });
});
