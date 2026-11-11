import { describe, it, expect, jest } from "@jest/globals";
import { buildPrefix } from "../../../../../src/components/modal/chain_element/field/BasePathField";

jest.mock("../../../../../src/api/api", () => ({ api: {} }));

describe("buildPrefix", () => {
  it("should return /cip-routes/ when the route is external", () => {
    expect(buildPrefix(true)).toBe("/cip-routes/");
  });

  it("should return /routes/ when the route is internal", () => {
    expect(buildPrefix(false)).toBe("/routes/");
  });

  it("should return /routes/ when the route type is undefined", () => {
    expect(buildPrefix(undefined)).toBe("/routes/");
  });
});
