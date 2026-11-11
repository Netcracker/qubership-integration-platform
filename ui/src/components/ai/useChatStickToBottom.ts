import { useCallback, useEffect, useRef, useState } from "react";

/** Follow until the reader is this far from the bottom, in pixels. */
export const CHAT_NEAR_BOTTOM_PX = 80;

function isNearBottom(el: HTMLElement, thresholdPx: number): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight < thresholdPx;
}

/**
 * Pins a chat transcript to the latest row while the reader is following.
 *
 * Tool cards wrap and change height after the DOM mutation that inserted them.
 * Observe the content box size so a pin runs after that layout, not only after
 * the child-list change. Ignore scroll events caused by the pin itself so a
 * mid-layout gap does not turn follow off.
 */
export function useChatStickToBottom(): {
  scrollContainerRef: (node: HTMLDivElement | null) => void;
  contentRef: (node: HTMLDivElement | null) => void;
  onScroll: () => void;
  pinToBottom: () => void;
  resumeFollowing: () => void;
} {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const followingRef = useRef(true);
  const programmaticRef = useRef(false);
  const [container, setContainer] = useState<HTMLDivElement | null>(null);
  const [content, setContent] = useState<HTMLDivElement | null>(null);

  const scrollContainerRef = useCallback((node: HTMLDivElement | null) => {
    containerRef.current = node;
    setContainer(node);
  }, []);

  const contentRef = useCallback((node: HTMLDivElement | null) => {
    setContent(node);
  }, []);

  const pinToBottom = useCallback(() => {
    const el = containerRef.current;
    if (!el || !followingRef.current) {
      return;
    }
    programmaticRef.current = true;
    el.scrollTop = el.scrollHeight;
    requestAnimationFrame(() => {
      programmaticRef.current = false;
    });
  }, []);

  const onScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el || programmaticRef.current) {
      return;
    }
    followingRef.current = isNearBottom(el, CHAT_NEAR_BOTTOM_PX);
  }, []);

  const resumeFollowing = useCallback(() => {
    followingRef.current = true;
    pinToBottom();
  }, [pinToBottom]);

  useEffect(() => {
    if (!container || !content) {
      return;
    }

    const mutationObserver = new MutationObserver(pinToBottom);
    mutationObserver.observe(content, {
      childList: true,
      subtree: true,
      characterData: true,
    });

    let resizeObserver: ResizeObserver | undefined;
    if (typeof ResizeObserver !== "undefined") {
      resizeObserver = new ResizeObserver(pinToBottom);
      resizeObserver.observe(content);
    }

    pinToBottom();

    return () => {
      mutationObserver.disconnect();
      resizeObserver?.disconnect();
    };
  }, [container, content, pinToBottom]);

  return {
    scrollContainerRef,
    contentRef,
    onScroll,
    pinToBottom,
    resumeFollowing,
  };
}
