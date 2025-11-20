import React, { useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Input, Button } from "antd";
import styles from "./PatternPropertiesField.module.css";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";

const PatternPropertiesField: React.FC<FieldProps<Record<string, string>>> = ({
  formData = {},
  onChange,
  schema,
  uiSchema,
  disabled,
  readonly,
}) => {
  const rowCount = Object.entries(formData).length;
  const [collapsed, setCollapsed] = useState(!(rowCount > 0));

  const handleAdd = () => {
    const newKey = "";
    const newData = { ...formData, [newKey]: "" };
    onChange(newData);
  };

  const handleKeyChange = (oldKey: string, newKey: string) => {
    if (oldKey === newKey) return;
    const updated = { ...formData };
    const value = updated[oldKey];
    delete updated[oldKey];
    updated[newKey] = value;
    onChange(updated);
  };

  const handleValueChange = (key: string, value: string) => {
    onChange({ ...formData, [key]: value });
  };

  const handleDelete = (key: string) => {
    const updated = { ...formData };
    delete updated[key];
    onChange(updated);
  };

  return (
    <div>
      <div className={styles.header}>
        <div
          className={styles.leftHeader}
          onClick={() => setCollapsed((s) => !s)}
        >
          <span className={styles.iconWrapper}>
            {collapsed ? <OverridableIcon name="right" /> : <OverridableIcon name="down" />}
          </span>
          <span>{schema?.title || uiSchema?.["ui:title"] || "Items"}</span>
          <span className={styles.badge}>{rowCount}</span>
        </div>

        <div>
          <Button
            size="small"
            type="text"
            icon={<OverridableIcon name="plus" />}
            onClick={handleAdd}
            disabled={disabled || readonly}
            style={{ marginLeft: 8 }}
          />
        </div>
      </div>
      {!collapsed &&
        (rowCount === 0 ? (
          <div className={styles.noEntries}>
            No entries. Click <b>+</b> to add.
          </div>
        ) : (
          <table className={styles.table}>
            <thead>
              <tr>
                <th className={styles.th}>Name</th>
                <th className={styles.th}>Value</th>
                <th className={styles.th}></th>
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
                  <td className={styles.td}>
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
        ))}
    </div>
  );
};

export default PatternPropertiesField;
