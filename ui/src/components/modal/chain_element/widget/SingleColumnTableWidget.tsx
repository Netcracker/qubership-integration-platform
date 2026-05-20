import React, { useCallback, useState } from "react";
import { WidgetProps } from "@rjsf/utils";
import { Input, Button } from "antd";
import styles from "./SingleColumnTableWidget.module.css";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";

const SingleColumnTableWidget: React.FC<WidgetProps> = ({
  value,
  onChange,
  schema,
  uiSchema,
  disabled,
  readonly,
  label,
}) => {
  const items: string[] = value as [];
  const [collapsed, setCollapsed] = useState(!(items.length > 0));

  const handleAdd = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      const newItems = [...items, ""];
      onChange(newItems);
    },
    [items, onChange],
  );

  const handleChange = useCallback(
    (index: number, newValue: string) => {
      const newItems = [...items];
      newItems[index] = newValue;
      onChange(newItems);
    },
    [items, onChange],
  );

  const handleDelete = useCallback(
    (index: number) => {
      const newItems = items.filter((_, i) => i !== index);
      onChange(newItems);
    },
    [items, onChange],
  );

  const title =
    (uiSchema?.["ui:title"] as string) || label || schema?.title || "Items";

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div className={styles.leftHeader}>
          <span
            className={styles.iconWrapper}
            onClick={() => setCollapsed((s) => !s)}
          >
            {collapsed ? (
              <OverridableIcon name="right" />
            ) : (
              <OverridableIcon name="down" />
            )}
          </span>
          <span onClick={() => setCollapsed((s) => !s)}>{title}</span>
          <span
            className={styles.badge}
            onClick={() => setCollapsed((s) => !s)}
          >
            {items.length}
          </span>
          <Button
            size="small"
            type="text"
            icon={<OverridableIcon name="plus" />}
            onClick={handleAdd}
            disabled={disabled || readonly}
          />
        </div>
      </div>
      {!collapsed &&
        (items.length === 0 ? (
          <div className={styles.noEntries}>
            No entries. Click <b>+</b> to add.
          </div>
        ) : (
          <table className={styles.table}>
            <thead>
              <tr>
                <th className={styles.th}>Value</th>
                <th className={styles.thAction}></th>
              </tr>
            </thead>
            <tbody>
              {items.map((item, idx) => (
                <tr key={idx}>
                  <td className={styles.td}>
                    <Input
                      value={item}
                      onChange={(e) => handleChange(idx, e.target.value)}
                      disabled={disabled || readonly}
                      placeholder="Enter value"
                    />
                  </td>
                  <td className={styles.tdAction}>
                    <Button
                      size="small"
                      type="text"
                      icon={<OverridableIcon name="delete" />}
                      onClick={() => handleDelete(idx)}
                      disabled={disabled || readonly}
                      className={styles.deleteBtn}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
    </div>
  );
};

export default SingleColumnTableWidget;
