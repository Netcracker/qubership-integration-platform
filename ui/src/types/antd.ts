import type { MenuProps, SelectProps } from "antd";

// Antd v6 stopped re-exporting these helper types from the package root and
// moved its form engine to `@rc-component/form`. Source them here — derived
// from antd's public props where possible — so the app avoids deep imports
// into antd internals across the codebase.

/** Click info passed to `Menu` / `Dropdown` item `onClick`. */
export type MenuInfo = Parameters<NonNullable<MenuProps["onClick"]>>[0];

// One field entry reported by `Form` `onFieldsChange`. Re-exported from antd's
// form engine, which v6 no longer surfaces through the package root.
export type { FieldData } from "@rc-component/form/lib/interface";

/** A `Select` option object. */
export type DefaultOptionType = NonNullable<SelectProps["options"]>[number];

/** Preset status colors shared by `Tag` `color` and `Badge` `status`. */
export type PresetStatusColor =
  | "success"
  | "processing"
  | "error"
  | "default"
  | "warning";

/** Argument passed to `Select` `labelRender`. */
export type LabelRenderProps = Parameters<
  NonNullable<SelectProps["labelRender"]>
>[0];

/** Argument passed to `Select` `optionRender`. */
export type OptionRenderProps = Parameters<
  NonNullable<SelectProps["optionRender"]>
>[0];
