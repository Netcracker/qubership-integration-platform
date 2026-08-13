import { escapeHtml, unescapeHtml } from "../../src/misc/html-utils";

describe("html-utils", () => {
  it("escapeHtml encodes dangerous characters", () => {
    expect(escapeHtml('<img src=x onerror=alert(1)>')).toBe(
      "&lt;img src=x onerror=alert(1)&gt;",
    );
  });

  it("unescapeHtml decodes entities without DOM parsing", () => {
    expect(unescapeHtml("&lt;b&gt;test&lt;/b&gt;")).toBe("<b>test</b>");
  });

  it("unescapeHtml leaves already-unescaped markup unchanged", () => {
    const payload = "<img src=x onerror=alert(1)>";
    expect(unescapeHtml(payload)).toBe(payload);
  });

  it("unescapeHtml returns empty string for falsy input", () => {
    expect(unescapeHtml("")).toBe("");
  });

  it("escapeHtml encodes quotes and ampersands", () => {
    expect(escapeHtml(`Tom & Jerry's "x"`)).toBe(
      "Tom &amp; Jerry&#39;s &quot;x&quot;",
    );
  });
});
