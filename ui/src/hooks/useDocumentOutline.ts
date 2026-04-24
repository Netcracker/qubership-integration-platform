import { RefObject, useEffect, useRef, useState } from "react";

const HEADING_TAGS = ["h1", "h2", "h3", "h4"] as const;
const HEADING_SELECTOR = HEADING_TAGS.join(", ");
const ACTIVE_OFFSET_PX = 80;

export interface HeadingEntry {
  id: string;
  text: string;
  level: number;
}

function getScrollParent(el: HTMLElement): HTMLElement {
  let cur = el.parentElement;
  while (cur && cur !== document.documentElement) {
    const { overflow, overflowY } = getComputedStyle(cur);
    if (/auto|scroll/.test(overflow + overflowY)) {
      return cur;
    }
    cur = cur.parentElement;
  }
  return document.documentElement;
}

function extractHeadingText(el: Element): string {
  const clone = el.cloneNode(true) as Element;
  clone.querySelectorAll("a.anchor").forEach((a) => a.remove());
  return clone.textContent?.trim() ?? "";
}

export function useDocumentOutline(
  containerRef: RefObject<HTMLElement | null>,
  content: string,
): { headings: HeadingEntry[]; activeId: string | null } {
  const [headings, setHeadings] = useState<HeadingEntry[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const headingElementsRef = useRef<Element[]>([]);

  // Two rAF frames wait for react-markdown to commit before reading the DOM.
  useEffect(() => {
    let raf2 = -1;
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        const container = containerRef.current;
        if (!container) return;

        const els = Array.from(container.querySelectorAll(HEADING_SELECTOR));
        const extracted: HeadingEntry[] = [];
        const elementRefs: Element[] = [];
        for (const el of els) {
          const text = extractHeadingText(el);
          if (!el.id || !text) continue;
          extracted.push({
            id: el.id,
            text,
            level: Number.parseInt(el.tagName[1], 10),
          });
          elementRefs.push(el);
        }

        headingElementsRef.current = elementRefs;
        setHeadings(extracted);
        setActiveId(null);
      });
    });

    return () => {
      cancelAnimationFrame(raf1);
      cancelAnimationFrame(raf2);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [content]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || headings.length === 0) return;

    const scrollParent = getScrollParent(container);
    let rafHandle = -1;

    const update = () => {
      rafHandle = -1;
      const threshold =
        scrollParent.getBoundingClientRect().top + ACTIVE_OFFSET_PX;

      let next: string | null = null;
      const els = headingElementsRef.current;
      for (let i = 0; i < els.length; i++) {
        if (els[i].getBoundingClientRect().top <= threshold) {
          next = headings[i].id;
        }
      }
      setActiveId((prev) => (prev === next ? prev : next));
    };

    // Coalesce bursts of scroll events into one rAF tick.
    const onScroll = () => {
      if (rafHandle !== -1) return;
      rafHandle = requestAnimationFrame(update);
    };

    scrollParent.addEventListener("scroll", onScroll, { passive: true });
    update();

    return () => {
      scrollParent.removeEventListener("scroll", onScroll);
      if (rafHandle !== -1) cancelAnimationFrame(rafHandle);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [headings]);

  return { headings, activeId };
}
