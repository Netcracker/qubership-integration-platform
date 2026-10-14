import React, { useCallback } from "react";
import { FieldProps } from "@rjsf/utils";
import { Input, Button } from "antd";
import styles from "./PatternPropertiesField.module.css";
import { CollapsibleSection } from "../../../CollapsibleSection.tsx";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import { DescriptionTooltipIcon } from "../DescriptionTooltipFieldTemplate";

const PatternPropertiesField: React.FC<FieldProps<Record<string, string>>> = ({
  formData = {},
  onChange,
  schema,
  uiSchema,
  disabled,
  readonly,
  fieldPathId,
}) => {
  const changeValue = useCallback(
    (value: Record<string, string>) => {
      onChange(value, fieldPathId?.path);
    },
    [fieldPathId?.path, onChange],
  );
  const rowCount = Object.entries(formData).length;

  const handleAdd = () => {
    const newKey = "";
    const newData = { ...formData, [newKey]: "" };
    changeValue(newData);
  };

  const handleKeyChange = (oldKey: string, newKey: string) => {
    if (oldKey === newKey) return;
    const updated = { ...formData };
    const value = updated[oldKey];
    delete updated[oldKey];
    updated[newKey] = value;
    changeValue(updated);
  };

  const handleValueChange = (key: string, value: string) => {
    changeValue({ ...formData, [key]: value });
  };

  const handleDelete = (key: string) => {
    const updated = { ...formData };
    delete updated[key];
    changeValue(updated);
  };

  return (
    <CollapsibleSection
      title={schema?.title || uiSchema?.["ui:title"] || "Items"}
      count={rowCount}
      onAdd={handleAdd}
      addDisabled={disabled || readonly}
      titleExtra={
        schema?.description ? (
          <DescriptionTooltipIcon description={schema.description} />
        ) : undefined
      }
    >
      <table className={styles.table}>
        <thead>
          <tr>
            <th className={styles.th}>Name</th>
            <th className={styles.th}>Value</th>
            <th className={`${styles.th} ${styles.actionsCol}`}></th>
          </tr>
        </thead>
        <tbody>
          {Object.entries(formData).map(([key, value], idx) => (
            <tr key={idx}>
              <td className={styles.td}>
                <Input
                  value={key}
                  onChange={(e) => handleKeyChange(key, e.target.value)}
                  disabled={disabled || readonly}
                  placeholder="Name"
                />
              </td>
              <td className={styles.td}>
                <Input
                  value={value}
                  onChange={(e) => handleValueChange(key, e.target.value)}
                  disabled={disabled || readonly}
                  placeholder="Value"
                />
              </td>
              <td className={styles.tdAction}>
                <Button
                  size="small"
                  type="text"
                  icon={<OverridableIcon name="delete" />}
                  onClick={() => handleDelete(key)}
                  disabled={disabled || readonly}
                  className={styles.deleteBtn}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </CollapsibleSection>
  );
};

export default PatternPropertiesField;
