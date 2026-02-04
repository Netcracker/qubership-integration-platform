import {
  escapeHtml,
  formatFragmentSegments,
  highlightSegments,
  segmentsToSafeHtml,
} from "../../../src/services/documentation/documentationHighlightUtils";

describe("documentationHighlightUtils", () => {
  const stemmer = (w: string) => w.toLowerCase();

  test("highlightSegments returns highlighted segments", () => {
    const segments = highlightSegments("Hello world", "world", stemmer);
    expect(segments).toEqual([
      { text: "Hello ", isHit: false },
      { text: "world", isHit: true },
    ]);
  });

  test("segmentsToSafeHtml escapes HTML and wraps hits", () => {
    const html = segmentsToSafeHtml([
      { text: "<img src=x onerror=alert(1)> ", isHit: false },
      { text: "test", isHit: true },
    ]);

    expect(html).toContain("&lt;img");
    expect(html).toContain("<b>test</b>");
    expect(html).not.toContain("<img");
  });

  test("escapeHtml escapes critical characters", () => {
    expect(escapeHtml(`&<>"'`)).toBe("&amp;&lt;&gt;&quot;&#39;");
  });

  test("formatFragmentSegments truncates by last space", () => {
    const text = "aaa bbb ccc ddd";
    const segments = formatFragmentSegments(text, "bbb", stemmer, 8);
    // "aaa bbb" length is 7, then it should add "..."
    expect(segments.map((s) => s.text).join("")).toBe("aaa bbb...");
  });
});
