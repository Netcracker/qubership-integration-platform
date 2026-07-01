import type { ForwardedRef, MutableRefObject } from "react";

// Assigns a node to several refs at once. Use to keep an internal ref while
// also forwarding the node to a caller (e.g. a Tooltip/Popover anchor).
export function mergeRefs<T>(
  ...refs: (ForwardedRef<T> | MutableRefObject<T | null> | undefined)[]
): (node: T | null) => void {
  return (node) => {
    for (const ref of refs) {
      if (typeof ref === "function") {
        ref(node);
      } else if (ref) {
        ref.current = node;
      }
    }
  };
}
