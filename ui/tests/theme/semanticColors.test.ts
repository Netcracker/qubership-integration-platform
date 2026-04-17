import { describe, it, expect } from "@jest/globals";
import type { GlobalToken } from "antd";
import { DeploymentStatus } from "../../src/api/apiTypes";
import {
  foregroundForBackground,
  getDeploymentStatusColor,
  getDeploymentStatusTone,
  isTokenDark,
  parseHex,
} from "../../src/theme/semanticColors";

function makeToken(overrides: Partial<GlobalToken> = {}): GlobalToken {
  return {
    colorSuccess: "#52c41a",
    colorSuccessBg: "#f6ffed",
    colorSuccessBorder: "#b7eb8f",
    colorSuccessBorderHover: "#95de64",
    colorSuccessText: "#389e0d",
    colorInfo: "#1677ff",
    colorInfoBg: "#e6f4ff",
    colorInfoBorder: "#91caff",
    colorInfoBorderHover: "#69b1ff",
    colorInfoText: "#0958d9",
    colorError: "#ff4d4f",
    colorErrorBg: "#fff2f0",
    colorErrorBorder: "#ffccc7",
    colorErrorBorderHover: "#ffa39e",
    colorErrorText: "#cf1322",
    colorWarning: "#faad14",
    colorWarningBg: "#fffbe6",
    colorWarningBorder: "#ffe58f",
    colorWarningBorderHover: "#ffd666",
    colorWarningText: "#d48806",
    colorTextTertiary: "rgba(0, 0, 0, 0.45)",
    colorFillQuaternary: "rgba(0, 0, 0, 0.02)",
    colorBorderSecondary: "#f0f0f0",
    colorBorder: "#d9d9d9",
    colorTextSecondary: "rgba(0, 0, 0, 0.65)",
    colorBgBase: "#ffffff",
    ...overrides,
  } as GlobalToken;
}

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

describe("parseHex", () => {
  it("parses 6-digit hex with or without leading #", () => {
    expect(parseHex("#ff4d4f")).toEqual({ r: 255, g: 77, b: 79 });
    expect(parseHex("1677FF")).toEqual({ r: 22, g: 119, b: 255 });
  });

  it("returns null for invalid input", () => {
    expect(parseHex("not-a-color")).toBeNull();
    expect(parseHex("#ff4")).toBeNull();
  });
});

describe("getDeploymentStatusColor", () => {
  it("returns Ant Design semantic color names for known statuses", () => {
    expect(getDeploymentStatusColor("DEPLOYED")).toBe("green");
    expect(getDeploymentStatusColor("PROCESSING")).toBe("blue");
    expect(getDeploymentStatusColor("FAILED")).toBe("red");
    expect(getDeploymentStatusColor("REMOVED")).toBe("orange");
  });

  it("falls back to neutral hex for unknown statuses", () => {
    expect(getDeploymentStatusColor("UNKNOWN")).toBe("#888888");
  });
});

describe("getDeploymentStatusTone", () => {
  const token = makeToken();

  it.each([
    [DeploymentStatus.DEPLOYED, "colorSuccess"],
    [DeploymentStatus.PROCESSING, "colorInfo"],
    [DeploymentStatus.FAILED, "colorError"],
    [DeploymentStatus.REMOVED, "colorWarning"],
  ])("maps %s to token.%s family", (status, family) => {
    const tone = getDeploymentStatusTone(status, token);
    expect(tone.accent).toBe(token[family as keyof GlobalToken]);
    expect(tone.bg).toBe(token[`${family}Bg` as keyof GlobalToken]);
    expect(tone.border).toBe(token[`${family}Border` as keyof GlobalToken]);
    expect(tone.borderHover).toBe(
      token[`${family}BorderHover` as keyof GlobalToken],
    );
    expect(tone.text).toBe(token[`${family}Text` as keyof GlobalToken]);
  });

  it("falls back to neutral tokens for unknown status", () => {
    const tone = getDeploymentStatusTone("UNKNOWN", token);
    expect(tone.accent).toBe(token.colorTextTertiary);
    expect(tone.bg).toBe(token.colorFillQuaternary);
    expect(tone.border).toBe(token.colorBorderSecondary);
    expect(tone.borderHover).toBe(token.colorBorder);
    expect(tone.text).toBe(token.colorTextSecondary);
  });

  it("picks up dark-theme token values automatically", () => {
    const lightTone = getDeploymentStatusTone(
      DeploymentStatus.FAILED,
      makeToken({ colorErrorBg: "#fff2f0" }),
    );
    const darkTone = getDeploymentStatusTone(
      DeploymentStatus.FAILED,
      makeToken({ colorErrorBg: "#2a1215" }),
    );
    expect(lightTone.bg).toBe("#fff2f0");
    expect(darkTone.bg).toBe("#2a1215");
  });
});

describe("isTokenDark", () => {
  it("returns true for a dark base background", () => {
    expect(isTokenDark(makeToken({ colorBgBase: "#141414" }))).toBe(true);
    expect(isTokenDark(makeToken({ colorBgBase: "#000000" }))).toBe(true);
  });

  it("returns false for a light base background", () => {
    expect(isTokenDark(makeToken({ colorBgBase: "#ffffff" }))).toBe(false);
    expect(isTokenDark(makeToken({ colorBgBase: "#fafafa" }))).toBe(false);
  });

  it("returns false for unparseable colors", () => {
    expect(isTokenDark(makeToken({ colorBgBase: "transparent" }))).toBe(false);
  });
});
