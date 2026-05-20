const mockGetConfig = jest.fn(() => ({}));
jest.mock("../../../src/appConfig", () => ({
  getConfig: () => mockGetConfig(),
}));

import {
  getDocumentationAssetsBaseUrl,
  DOCUMENTATION_ROUTE_BASE,
  isSafeHref,
  joinUrl,
  resolveDocLink,
  toDocMarkdownAssetPath,
} from "../../../src/services/documentation/documentationUrlUtils";

describe("documentationUrlUtils", () => {
  beforeEach(() => {
    mockGetConfig.mockReturnValue({});
  });

  test("DOCUMENTATION_ROUTE_BASE is /doc", () => {
    expect(DOCUMENTATION_ROUTE_BASE).toBe("/doc");
  });

  test("getDocumentationAssetsBaseUrl defaults to /doc", () => {
    expect(getDocumentationAssetsBaseUrl()).toBe("/doc");
  });

  test("getDocumentationAssetsBaseUrl returns custom value from config", () => {
    mockGetConfig.mockReturnValue({
      documentationBaseUrl: "https://example.com/custom-docs",
    });
    expect(getDocumentationAssetsBaseUrl()).toBe(
      "https://example.com/custom-docs",
    );
  });

  test("joinUrl joins base and path safely", () => {
    expect(joinUrl("/doc/", "/paths.json")).toBe("/doc/paths.json");
    expect(joinUrl("https://example.com/docs/", "toc.json")).toBe(
      "https://example.com/docs/toc.json",
    );
  });

  test("toDocMarkdownAssetPath builds path and extension", () => {
    expect(toDocMarkdownAssetPath("01__Chains/chains")).toBe(
      "01__Chains/chains.md",
    );
    expect(toDocMarkdownAssetPath("01__Chains/chains.md")).toBe(
      "01__Chains/chains.md",
    );
    expect(toDocMarkdownAssetPath("")).toBe("");
  });

  test("isSafeHref blocks javascript:", () => {
    expect(isSafeHref("javascript:alert(1)")).toBe(false);
  });

  test("isSafeHref allows relative and common protocols", () => {
    expect(isSafeHref("../a/b")).toBe(true);
    expect(isSafeHref("/doc/abc")).toBe(true);
    expect(isSafeHref("#anchor")).toBe(true);
    expect(isSafeHref("mailto:test@example.com")).toBe(true);
    expect(isSafeHref("tel:+123")).toBe(true);
    expect(isSafeHref("https://example.com")).toBe(true);
  });

  describe("resolveDocLink", () => {
    test("fixes 6__Triggers/1__Routing/6__Chain_Call when ../../1__Routing resolved from Chain Trigger", () => {
      const result = resolveDocLink(
        "../../1__Routing/6__Chain_Call/chain_call.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger",
      );
      expect(result).toBe(
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call",
      );
    });

    test("fixes 6__Triggers/6__Chain_Call when short link from Triggers section", () => {
      const result = resolveDocLink(
        "6__Chain_Call/chain_call.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/6__RabbitMQ_Trigger/rabbitmq_trigger",
      );
      expect(result).toBe(
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call",
      );
    });

    test("fixes leaf doc wrongly treated as directory on Services page", () => {
      const result = resolveDocLink(
        "1__External/external.md",
        "02__Services/services",
      );
      expect(result).toBe("02__Services/1__External/external");
    });

    test("fixes leaf doc on graph page (graph/1__QIP_Elements_Library -> 1__QIP_Elements_Library)", () => {
      const result = resolveDocLink(
        "1__QIP_Elements_Library/1__Routing/8__Loop/loop.md",
        "01__Chains/1__Graph/graph",
      );
      expect(result).toBe(
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/8__Loop/loop",
      );
    });
  });
});
