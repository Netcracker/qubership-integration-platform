import type { KeyboardEvent, MouseEvent } from "react";

export function handleMiddlePanelConnectionSvgKeyDown(
  event: Pick<
    KeyboardEvent<SVGElement>,
    "key" | "preventDefault" | "stopPropagation"
  >,
  actions: {
    deleteSelectedConnections: () => void;
    clearSelection: () => void;
  },
): void {
  if (event.key === "Delete" || event.key === "Backspace") {
    event.preventDefault();
    event.stopPropagation();
    actions.deleteSelectedConnections();
    actions.clearSelection();
  }
}

export function focusSvgEventTarget(event: MouseEvent<SVGElement>): void {
  event.currentTarget.focus();
}
