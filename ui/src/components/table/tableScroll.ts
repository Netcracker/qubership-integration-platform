/**
 * Horizontal-scroll config for a flex-bounded table.
 *
 * antd v6 (`@rc-component/table`) treats `y: ""` as a *defined* value
 * (`validateValue("") === true`), so it splits the header from a fixed-height
 * body — the sticky-header-while-rows-scroll behaviour we want. When the table
 * is empty we drop `y` entirely (`validateValue(undefined) === false`) so the
 * empty-state placeholder isn't trapped inside that split body.
 *
 * Centralised here so every table shares one definition of the rule instead of
 * copy-pasting the `rowCount > 0 ? { x, y: "" } : { x }` ternary.
 */
export function tableScroll(
  scrollX: number,
  rowCount: number,
): { x: number; y?: string } {
  return rowCount > 0 ? { x: scrollX, y: "" } : { x: scrollX };
}
