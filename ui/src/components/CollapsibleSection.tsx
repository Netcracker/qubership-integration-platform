import React, { ReactNode, useState } from "react";
import { Button, Tooltip } from "antd";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import styles from "./CollapsibleSection.module.css";

export type CollapsibleSectionProps = {
  title: ReactNode;
  /** Shown in the header, so an empty section says so while still folded. */
  count: number;
  /** Omitted on a read-only section, which then offers no add button. */
  onAdd?: () => void;
  addDisabled?: boolean;
  /** Names the add button for a screen reader: "Add header", "Add parameter". */
  addLabel?: string;
  /** Sits beside the title, for a description tooltip and the like. */
  titleExtra?: ReactNode;
  emptyText?: ReactNode;
  children: ReactNode;
  "data-testid"?: string;
};

/**
 * A titled section that folds away. The rows a chain element edits and the ones
 * a test case edits are held in these, so they are one component: the count and
 * the add button live in the header beside the title.
 */
export const CollapsibleSection: React.FC<CollapsibleSectionProps> = ({
  title,
  count,
  onAdd,
  addDisabled = false,
  addLabel = "Add",
  titleExtra,
  emptyText = (
    <>
      No entries. Click <b>+</b> to add.
    </>
  ),
  children,
  "data-testid": dataTestId,
}) => {
  // Until it is folded by hand the section follows its own count: it opens as
  // soon as it holds something, which covers both the rows that arrive with the
  // screen and the rows a specification fills in afterwards. Once folded either
  // way by hand it stays where it was put.
  const [foldedByHand, setFoldedByHand] = useState<boolean | null>(null);
  const open = foldedByHand ?? count > 0;
  const body =
    count === 0 ? <div className={styles.empty}>{emptyText}</div> : children;

  return (
    <div data-testid={dataTestId}>
      <div className={styles.header}>
        <span
          className={styles.trigger}
          onClick={() => setFoldedByHand(!open)}
          role="button"
          tabIndex={0}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              setFoldedByHand(!open);
            }
          }}
        >
          <span className={styles.icon}>
            <OverridableIcon name={open ? "down" : "right"} />
          </span>
          {title}
          <span className={styles.badge}>{count}</span>
        </span>
        {titleExtra}
        {onAdd ? (
          <Tooltip title={addLabel}>
            <Button
              size="small"
              type="text"
              aria-label={addLabel}
              icon={<OverridableIcon name="plus" />}
              disabled={addDisabled}
              onClick={onAdd}
            />
          </Tooltip>
        ) : null}
      </div>
      {open ? body : null}
    </div>
  );
};
