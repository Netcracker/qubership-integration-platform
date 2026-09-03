import type React from "react";

/** The controls a row can hold that answer a click of their own. */
const ROW_CLICK_IGNORE_SELECTOR =
  "a, button, input, label, .ant-table-selection-column, .ant-table-row-expand-icon";

/**
 * Whether a row click landed on one of those controls.
 *
 * A table whose rows open a details drawer receives every click through `onRow`, including the ones
 * on a link, a button, or the table's own selection and expand cells. antd stops a selection
 * checkbox from reaching `onRow` on its own; the rest of what a row can hold is what this covers.
 *
 * Centralized here so every such table shares one selector instead of restating it per page.
 */
export function shouldIgnoreRowClick(target: EventTarget | null): boolean {
  return (
    target instanceof Element && !!target.closest(ROW_CLICK_IGNORE_SELECTOR)
  );
}

/**
 * `onRow` props for a table whose rows open a details view.
 *
 * Wraps the handler in the guard above, so every such table states the rule once rather than
 * repeating the same seven lines.
 */
export function rowClickProps<T>(
  open: (record: T) => void,
): (record: T) => React.HTMLAttributes<HTMLElement> {
  return (record) => ({
    onClick: (event) => {
      if (shouldIgnoreRowClick(event.target)) {
        return;
      }
      open(record);
    },
  });
}
