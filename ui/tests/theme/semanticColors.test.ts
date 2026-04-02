import { describe, it, expect } from "@jest/globals";
import { foregroundForBackground } from "../../src/theme/semanticColors";

describe("foregroundForBackground", () => {
  it("uses white text on dark saturated palette colors", () => {
    expect(foregroundForBackground("#1677ff")).toBe("#ffffff");
    expect(foregroundForBackground("#52c41a")).toBe("#ffffff");
    expect(foregroundForBackground("#9012fe")).toBe("#ffffff");
  });

  it("uses dark text on light backgrounds", () => {
    expect(foregroundForBackground("#4FC0F8")).toBe("rgba(0, 0, 0, 0.88)");
    expect(foregroundForBackground("#bfbfbf")).toBe("rgba(0, 0, 0, 0.88)");
  });

  it("falls back for invalid hex", () => {
    expect(foregroundForBackground("not-a-color")).toBe("rgba(0, 0, 0, 0.88)");
  });
});
