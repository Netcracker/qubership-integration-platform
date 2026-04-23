import React, { useEffect, useLayoutEffect, useRef } from "react";
import type { ResizeCallbackData, ResizableProps } from "react-resizable";

export type ResizableTitleProps =
  React.ThHTMLAttributes<HTMLTableCellElement> & {
    onResize?: ResizableProps["onResize"];
    onResizeStop?: ResizableProps["onResizeStop"];
    width: number | undefined;
    minResizeWidth?: number;
    maxResizeWidth?: number;
    /** Higher index = above; reduces accidental grabs on a neighbor's east handle. */
    resizeHandleZIndex?: number;
  };

const DEFAULT_MIN = 80;

/**
 * East-edge resize driven by pointer deltas:
 * nextWidth = lastEmittedWidth + (clientX - lastClientX).
 */
export const ResizableTitle = React.forwardRef<
  HTMLTableCellElement,
  ResizableTitleProps
>((props, ref) => {
  const {
    onResize,
    onResizeStop,
    width,
    minResizeWidth = DEFAULT_MIN,
    maxResizeWidth,
    resizeHandleZIndex = 1,
    children,
    ...restProps
  } = props;

  const dragRef = useRef<{
    pointerId: number;
    lastClientX: number;
    thEl: HTMLTableCellElement;
    handleEl: HTMLButtonElement;
  } | null>(null);
  const lastEmittedRef = useRef<number>(width ?? 0);

  useLayoutEffect(() => {
    if (width != null && width > 0) {
      lastEmittedRef.current = width;
    }
  }, [width]);

  /** A column without callbacks doesn't resize (f.e. fixed 'actions' column). */
  const hasResizeHandlers =
    typeof onResize === "function" && typeof onResizeStop === "function";
  const isResizable = hasResizeHandlers && width != null && width > 0;

  const toCallbackData = (
    node: HTMLElement,
    w: number,
  ): ResizeCallbackData => ({
    node,
    size: { width: w, height: 0 },
    handle: "e",
  });

  const clampWidth = (w: number): number => {
    let v = w;
    v = Math.max(minResizeWidth, v);
    if (typeof maxResizeWidth === "number") {
      v = Math.min(maxResizeWidth, v);
    }
    return v;
  };

  const emitResize = (
    nativeEvent: PointerEvent | React.PointerEvent<HTMLButtonElement>,
    node: HTMLElement,
    nextWidth: number,
  ) => {
    const clamped = clampWidth(nextWidth);
    const committedBase = width ?? lastEmittedRef.current;
    if (clamped === committedBase && dragRef.current != null) {
      return;
    }
    // Keep drag base tied to committed width to prevent runaway deltas
    // when compensation saturates and effective column width stops changing.
    lastEmittedRef.current = committedBase;
    onResize?.(
      nativeEvent as unknown as React.PointerEvent<HTMLButtonElement>,
      toCallbackData(node, clamped),
    );
  };

  const endSession = (
    e: PointerEvent | React.PointerEvent<HTMLButtonElement>,
  ) => {
    const session = dragRef.current;
    if (session == null) {
      return;
    }
    dragRef.current = null;
    try {
      if (session.handleEl.hasPointerCapture(session.pointerId)) {
        session.handleEl.releasePointerCapture(session.pointerId);
      }
    } catch {
      /* releasePointerCapture may throw if already released */
    }
    onResizeStop?.(
      e as unknown as React.PointerEvent<HTMLButtonElement>,
      toCallbackData(session.handleEl, lastEmittedRef.current),
    );
  };

  const onWindowPointerMove = (e: PointerEvent) => {
    const session = dragRef.current;
    if (session?.pointerId !== e.pointerId) {
      return;
    }
    const cx = e.clientX;
    const delta = cx - session.lastClientX;
    session.lastClientX = cx;
    const measuredWidth = session.thEl.getBoundingClientRect().width;
    const localScale =
      measuredWidth > 0
        ? Math.max(0.05, lastEmittedRef.current / measuredWidth)
        : 1;
    const requestedByDelta = lastEmittedRef.current + delta * localScale;
    emitResize(e, session.handleEl, requestedByDelta);
  };

  const onWindowPointerDone = (e: PointerEvent) => {
    if (dragRef.current?.pointerId !== e.pointerId) {
      return;
    }
    endSession(e);
  };

  useEffect(() => {
    globalThis.addEventListener("pointermove", onWindowPointerMove);
    globalThis.addEventListener("pointerup", onWindowPointerDone);
    globalThis.addEventListener("pointercancel", onWindowPointerDone);
    return () => {
      globalThis.removeEventListener("pointermove", onWindowPointerMove);
      globalThis.removeEventListener("pointerup", onWindowPointerDone);
      globalThis.removeEventListener("pointercancel", onWindowPointerDone);
    };
  });

  const onResizeHandlePointerDown = (
    e: React.PointerEvent<HTMLButtonElement>,
  ) => {
    if (e.button !== 0) {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    const th = e.currentTarget.closest("th");
    if (!(th instanceof HTMLTableCellElement)) {
      return;
    }
    dragRef.current = {
      pointerId: e.pointerId,
      lastClientX: e.clientX,
      thEl: th,
      handleEl: e.currentTarget,
    };
    lastEmittedRef.current = width ?? 0;
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const thStyle = isResizable
    ? { position: "relative", ...restProps.style, width }
    : restProps.style;

  return (
    <th {...restProps} ref={ref} style={thStyle}>
      {children}
      {isResizable ? (
        <button
          type="button"
          aria-label="Resize column"
          style={{
            position: "absolute",
            top: 0,
            right: -5,
            bottom: 0,
            width: 10,
            height: "100%",
            marginTop: 0,
            padding: 0,
            border: 0,
            background: "transparent",
            backgroundImage: "none",
            transform: "none",
            cursor: "col-resize",
            touchAction: "none",
            zIndex: resizeHandleZIndex,
          }}
          tabIndex={-1}
          onPointerDown={onResizeHandlePointerDown}
          onLostPointerCapture={(e) => endSession(e)}
          onClick={(ev) => ev.stopPropagation()}
        />
      ) : null}
    </th>
  );
});

ResizableTitle.displayName = "ResizableTitle";
