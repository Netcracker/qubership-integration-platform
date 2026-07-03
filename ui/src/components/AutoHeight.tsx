import React, {
  PropsWithChildren,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

type AutoHeightProps = PropsWithChildren & React.HTMLAttributes<HTMLDivElement>;

const MIN_HEIGHT = 300;
// Seed the editor tall so the form's content always clears the form body's
// min-height during the first measurement. Otherwise the body stretches to its
// min-height and inflates the "content below the editor" reading, which would
// collapse the editor. The real height is computed before paint, so the seed
// is never shown.
const SEED_HEIGHT = 600;
// Small breathing room left below the editor's form content.
const BOTTOM_GAP = 12;

// Returns the [innermost, outermost] scroll containers between the editor and
// the modal body. The outermost is the box that actually clips the form (so its
// visible height bounds the editor); the innermost holds the form fields and so
// its rect gives the true bottom of the content that follows the editor. The
// editor's RJSF field nesting — and thus the margin below it — varies per tab,
// so this is measured rather than assumed.
function findScrollAncestors(
  el: HTMLElement,
): [HTMLElement | null, HTMLElement | null] {
  let nearest: HTMLElement | null = null;
  let outermost: HTMLElement | null = null;
  let node = el.parentElement;
  while (node) {
    const overflowY = getComputedStyle(node).overflowY;
    if (overflowY === "auto" || overflowY === "scroll") {
      nearest = nearest ?? node;
      outermost = node;
    }
    // Stop at the modal body so we never reach the page scroll; if no dedicated
    // scroll area was found inside it, the modal body is the fallback viewport.
    if (node.classList.contains("ant-modal-body")) {
      outermost = outermost ?? node;
      break;
    }
    node = node.parentElement;
  }
  outermost = outermost ?? el.parentElement;
  return [nearest ?? outermost, outermost];
}

export const AutoHeight: React.FC<AutoHeightProps> = ({
  children,
  className,
  ...props
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(SEED_HEIGHT);

  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const calcHeight = () => {
      const child = el.children[0];
      if (!child) return;
      const [content, viewport] = findScrollAncestors(el);
      if (!content || !viewport) return;

      // Visible bottom of the clipping viewport (clientHeight excludes a
      // scrollbar). The editor's top is fixed by the fields above it, so the
      // result converges in a single pass.
      const viewportBottom =
        viewport.getBoundingClientRect().top + viewport.clientHeight;
      // Form content that follows the editor — the field's trailing margins.
      const belowEditor =
        content.getBoundingClientRect().bottom -
        el.getBoundingClientRect().bottom;
      const available =
        viewportBottom -
        child.getBoundingClientRect().top -
        belowEditor -
        BOTTOM_GAP;
      setHeight(Math.max(available, MIN_HEIGHT));
    };

    // Run on mount so a tab becoming active sizes its editor right away, then
    // keep it in sync with the modal open animation and window resizes.
    calcHeight();

    const modalWrap = el.closest(".ant-modal-wrap");
    modalWrap?.addEventListener("transitionend", calcHeight);
    window.addEventListener("resize", calcHeight);

    return () => {
      modalWrap?.removeEventListener("transitionend", calcHeight);
      window.removeEventListener("resize", calcHeight);
    };
  }, []);

  return (
    <div
      ref={containerRef}
      {...props}
      // Marker for the form CSS to strip the dead trailing margins of the
      // editor's nested RJSF wrappers (see ChainElementModification.module.css).
      className={className ? `qip-auto-height ${className}` : "qip-auto-height"}
      // Clip the editor to its measured height. The mapper's flex-filled column
      // tables round up by ~1px, which would otherwise leak out as a stray
      // scrollbar on the form. Monaco's own popups render in a body-level node,
      // so nothing real is clipped.
      style={{ ...props.style, height, overflow: "hidden" }}
    >
      {children}
    </div>
  );
};
