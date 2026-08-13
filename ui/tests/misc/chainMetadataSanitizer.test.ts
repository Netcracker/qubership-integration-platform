import {
  decodeStoredText,
  normalizeStoredText,
  sanitizeChainCreationRequest,
  sanitizeChainUpdate,
  sanitizeServiceRequest,
  sanitizeServiceUpdate,
} from "../../src/misc/chainMetadataSanitizer";
import { IntegrationSystemType } from "../../src/api/apiTypes";

describe("chainMetadataSanitizer", () => {
  it("decodeStoredText reverses stored escaping for edit forms", () => {
    expect(decodeStoredText("&lt;tag&gt;")).toBe("<tag>");
  });

  it("decodeStoredText treats nullish values as empty", () => {
    expect(decodeStoredText(null)).toBe("");
    expect(decodeStoredText(undefined)).toBe("");
  });

  it("normalizeStoredText returns undefined for nullish values", () => {
    expect(normalizeStoredText(null)).toBeUndefined();
    expect(normalizeStoredText(undefined)).toBeUndefined();
  });

  it("normalizeStoredText escapes XSS payloads for persistence", () => {
    expect(normalizeStoredText('<img src=x onerror=alert(1)>')).toBe(
      "&lt;img src=x onerror=alert(1)&gt;",
    );
  });

  it("normalizeStoredText is idempotent on already escaped values", () => {
    const once = normalizeStoredText("<b>x</b>");
    expect(normalizeStoredText(once)).toBe(once);
  });

  it("sanitizeChainCreationRequest escapes description and label names", () => {
    const result = sanitizeChainCreationRequest({
      name: "chain",
      description: '<img src=x onerror=alert(1)>',
      labels: [{ name: "<script>", technical: false }],
    });
    expect(result.description).toBe("&lt;img src=x onerror=alert(1)&gt;");
    expect(result.labels?.[0]?.name).toBe("&lt;script&gt;");
  });

  it("sanitizeChainUpdate escapes only provided metadata fields", () => {
    const result = sanitizeChainUpdate({
      description: "<x>",
      labels: [{ name: "<y>", technical: true }],
    });
    expect(result.description).toBe("&lt;x&gt;");
    expect(result.labels?.[0]?.name).toBe("&lt;y&gt;");
  });

  it("sanitizeServiceUpdate escapes description and labels (CIP-2238)", () => {
    const result = sanitizeServiceUpdate({
      description: "<h1>test</h1>",
      labels: [{ name: "<h1>label</h1>", technical: false }],
    });
    expect(result.description).toBe("&lt;h1&gt;test&lt;/h1&gt;");
    expect(result.labels?.[0]?.name).toBe("&lt;h1&gt;label&lt;/h1&gt;");
  });

  it("sanitizeServiceRequest escapes description and labels", () => {
    const result = sanitizeServiceRequest({
      name: "svc",
      type: IntegrationSystemType.EXTERNAL,
      description: "<h1>test</h1>",
      labels: [{ name: "<h1>label</h1>", technical: false }],
    });
    expect(result.description).toBe("&lt;h1&gt;test&lt;/h1&gt;");
    expect(result.labels?.[0]?.name).toBe("&lt;h1&gt;label&lt;/h1&gt;");
  });
});
