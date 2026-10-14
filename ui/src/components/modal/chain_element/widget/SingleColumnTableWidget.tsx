import React, { useCallback } from "react";
import { WidgetProps } from "@rjsf/utils";
import { Input, Button } from "antd";
import styles from "./SingleColumnTableWidget.module.css";
import { CollapsibleSection } from "../../../CollapsibleSection.tsx";
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

  const handleAdd = useCallback(() => {
    onChange([...items, ""]);
  }, [items, onChange]);

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
      <CollapsibleSection
        title={title}
        count={items.length}
        onAdd={handleAdd}
        addDisabled={disabled || readonly}
      >
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
      </CollapsibleSection>
    </div>
  );
};

export default SingleColumnTableWidget;
