import type { ExpandableConfig } from "antd/es/table/interface";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import styles from "./TreeExpandIcon.module.css";

type ExpandIconProps<T> = Parameters<
  NonNullable<ExpandableConfig<T>["expandIcon"]>
>[0];

/**
 * Factory function that returns a chevron-based expand icon for Ant Design Table.
 * Replaces the default "+" square with a right/down arrow.
 *
 * Usage:
 * ```tsx
 * <Table expandable={{ expandIcon: treeExpandIcon(), ...otherProps }} />
 * ```
 */
export function treeExpandIcon<T = unknown>() {
  function TreeExpandIcon({
    expanded,
    onExpand,
    record,
    expandable,
  }: ExpandIconProps<T>) {
    return expandable ? (
      <button
        type="button"
        className={styles.expandIcon}
        onClick={(e) => {
          onExpand(record, e as unknown as React.MouseEvent<HTMLElement>);
          e.stopPropagation();
        }}
      >
        <OverridableIcon
          name={expanded ? "down" : "right"}
          style={{ fontSize: 11 }}
        />
      </button>
    ) : (
      <span className={styles.expandSpacer} />
    );
  }
  return TreeExpandIcon;
}
