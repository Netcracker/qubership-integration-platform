/**
 * @jest-environment jsdom
 */
import { renderHook, act } from "@testing-library/react";
import { useRef } from "react";
import { useDocumentOutline } from "../../src/hooks/useDocumentOutline";

function makeHeading(tag: string, id: string, text: string): HTMLElement {
  const el = document.createElement(tag);
  el.id = id;
  el.textContent = text;
  return el;
}

function makeContainer(...children: HTMLElement[]): HTMLDivElement {
  const div = document.createElement("div");
  children.forEach((c) => div.appendChild(c));
  document.body.appendChild(div);
  return div;
}

afterEach(() => {
  document.body.innerHTML = "";
  jest.restoreAllMocks();
});

async function flushRafs(): Promise<void> {
  await act(async () => {
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
  });
}

describe("useDocumentOutline — heading extraction", () => {
  test("extracts headings with id and text", async () => {
    const container = makeContainer(
      makeHeading("h1", "intro", "Introduction"),
      makeHeading("h2", "setup", "Setup"),
      makeHeading("h3", "install", "Installation"),
    );

    const { result } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "v1");
    });

    await flushRafs();

    expect(result.current.headings).toEqual([
      { id: "intro", text: "Introduction", level: 1 },
      { id: "setup", text: "Setup", level: 2 },
      { id: "install", text: "Installation", level: 3 },
    ]);
  });

  test("skips headings without id", async () => {
    const withId = makeHeading("h1", "with-id", "Has ID");
    const withoutId = makeHeading("h2", "", "No ID");
    const container = makeContainer(withId, withoutId);

    const { result } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "v1");
    });

    await flushRafs();

    expect(result.current.headings).toHaveLength(1);
    expect(result.current.headings[0].id).toBe("with-id");
  });

  test("strips a.anchor children from heading text", async () => {
    const h = document.createElement("h2");
    h.id = "section";
    h.textContent = "Section Title";

    const anchor = document.createElement("a");
    anchor.className = "anchor";
    anchor.href = "#section";
    const span = document.createElement("span");
    span.setAttribute("aria-hidden", "true");
    anchor.appendChild(span);
    h.appendChild(anchor);

    const container = makeContainer(h);

    const { result } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "v1");
    });

    await flushRafs();

    expect(result.current.headings[0].text).toBe("Section Title");
  });

  test("re-extracts when content prop changes", async () => {
    const container = makeContainer(makeHeading("h1", "a", "Alpha"));

    const { result, rerender } = renderHook(
      ({ content }: { content: string }) => {
        const ref = useRef<HTMLElement | null>(container);
        return useDocumentOutline(ref, content);
      },
      { initialProps: { content: "v1" } },
    );

    await flushRafs();
    expect(result.current.headings).toHaveLength(1);

    container.appendChild(makeHeading("h2", "b", "Beta"));
    rerender({ content: "v2" });
    await flushRafs();

    expect(result.current.headings).toHaveLength(2);
  });
});

describe("useDocumentOutline — scroll spy", () => {
  test("activeId is null when all headings are below threshold", async () => {
    const container = makeContainer(
      makeHeading("h1", "top", "Top"),
      makeHeading("h2", "bottom", "Bottom"),
    );

    // scrollParent (documentElement) returns top:0 → threshold = 80.
    // Heading elements return top:500 → 500 > 80, so none are active.
    jest
      .spyOn(Element.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: Element) {
        const top = this === document.documentElement ? 0 : 500;
        return {
          top,
          bottom: top + 20,
          left: 0,
          right: 100,
          width: 100,
          height: 20,
          x: 0,
          y: top,
          toJSON: () => ({}),
        };
      });

    const { result } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "c1");
    });

    await flushRafs();

    expect(result.current.activeId).toBeNull();
  });

  test("activeId matches last heading scrolled past threshold", async () => {
    const h1 = makeHeading("h1", "first", "First");
    const h2 = makeHeading("h2", "second", "Second");
    const container = makeContainer(h1, h2);

    jest
      .spyOn(Element.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: Element) {
        const topMap: Record<string, number> = { first: -10, second: 300 };
        const top = topMap[this.id] ?? 0;
        return {
          top,
          bottom: top + 20,
          left: 0,
          right: 100,
          width: 100,
          height: 20,
          x: 0,
          y: top,
          toJSON: () => ({}),
        };
      });

    const { result } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "c1");
    });

    await flushRafs();

    expect(result.current.activeId).toBe("first");
  });

  test("updates activeId on scroll event", async () => {
    const h1 = makeHeading("h1", "sec1", "Section 1");
    const h2 = makeHeading("h2", "sec2", "Section 2");
    const container = makeContainer(h1, h2);

    const tops: Record<string, number> = { sec1: 200, sec2: 400 };

    jest
      .spyOn(Element.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: Element) {
        const top = tops[this.id] ?? 0;
        return {
          top,
          bottom: top + 20,
          left: 0,
          right: 100,
          width: 100,
          height: 20,
          x: 0,
          y: top,
          toJSON: () => ({}),
        };
      });

    const { result } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "c1");
    });

    await flushRafs();
    expect(result.current.activeId).toBeNull();

    tops.sec1 = -100;
    tops.sec2 = -20;

    act(() => {
      document.documentElement.dispatchEvent(new Event("scroll"));
    });
    await flushRafs();

    expect(result.current.activeId).toBe("sec2");
  });

  test("cleans up scroll listener on unmount", async () => {
    const container = makeContainer(makeHeading("h1", "x", "X"));
    const removeSpy = jest.spyOn(
      document.documentElement,
      "removeEventListener",
    );

    const { result, unmount } = renderHook(() => {
      const ref = useRef<HTMLElement | null>(container);
      return useDocumentOutline(ref, "c1");
    });

    await flushRafs();
    expect(result.current.headings).toHaveLength(1);

    unmount();

    expect(removeSpy).toHaveBeenCalledWith("scroll", expect.any(Function));
  });
});
