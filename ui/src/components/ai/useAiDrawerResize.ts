import React, { useCallback, useEffect, useState } from "react";

export const DRAWER_WIDTH_STORAGE_KEY = "ai-assistant-drawer-width";
export const DEFAULT_DRAWER_WIDTH = 500;
export const MIN_DRAWER_WIDTH = 300;
export const MAX_DRAWER_WIDTH = 1200;

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function safeStorageGet(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeStorageSet(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    return;
  }
}

export function useAiDrawerResize(open: boolean): {
  drawerWidth: number;
  isResizing: boolean;
  onResizeMouseDown: (e: React.MouseEvent) => void;
} {
  const [drawerWidth, setDrawerWidth] = useState(() => {
    const savedWidth = safeStorageGet(DRAWER_WIDTH_STORAGE_KEY);
    const parsed = savedWidth ? parseInt(savedWidth, 10) : DEFAULT_DRAWER_WIDTH;
    const maxAllowed =
      typeof window !== "undefined"
        ? Math.min(MAX_DRAWER_WIDTH, window.innerWidth)
        : MAX_DRAWER_WIDTH;
    return clamp(
      Number.isFinite(parsed) ? parsed : DEFAULT_DRAWER_WIDTH,
      MIN_DRAWER_WIDTH,
      maxAllowed,
    );
  });

  const [isResizing, setIsResizing] = useState(false);
  const drawerWidthRef = React.useRef<number>(drawerWidth);
  const drawerWrapperRef = React.useRef<HTMLElement | null>(null);
  const rafRef = React.useRef<number | null>(null);

  useEffect(() => {
    drawerWidthRef.current = drawerWidth;
  }, [drawerWidth]);

  useEffect(() => {
    if (!open) return;
    const maxAllowed = Math.min(MAX_DRAWER_WIDTH, window.innerWidth);
    if (drawerWidth > maxAllowed) setDrawerWidth(maxAllowed);
  }, [drawerWidth, open]);

  const onResizeMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    drawerWrapperRef.current = null;
    setIsResizing(true);
  }, []);

  useEffect(() => {
    if (!isResizing) return;

    const handleMouseMove = (e: MouseEvent) => {
      if (rafRef.current !== null) return;
      rafRef.current = window.requestAnimationFrame(() => {
        rafRef.current = null;
        const element =
          drawerWrapperRef.current ||
          document.querySelector(
            ".ai-assistant-drawer .ant-drawer-content-wrapper",
          );
        const wrapper = element instanceof HTMLElement ? element : null;
        if (!wrapper) return;
        drawerWrapperRef.current = wrapper;
        const newWidth = Math.round(
          wrapper.getBoundingClientRect().right - e.clientX,
        );
        if (newWidth < MIN_DRAWER_WIDTH || newWidth > MAX_DRAWER_WIDTH) return;
        drawerWidthRef.current = newWidth;
        setDrawerWidth(newWidth);
        wrapper.style.width = `${newWidth}px`;
      });
    };

    const handleMouseUp = () => {
      setIsResizing(false);
      safeStorageSet(
        DRAWER_WIDTH_STORAGE_KEY,
        drawerWidthRef.current.toString(),
      );
      if (rafRef.current !== null) {
        window.cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };

    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      if (rafRef.current !== null) {
        window.cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [isResizing]);

  return { drawerWidth, isResizing, onResizeMouseDown };
}
