import React, { useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Input, Button } from "antd";
import {
  DeleteOutlined,
  PlusOutlined,
  RightOutlined,
  DownOutlined,
} from "@ant-design/icons";

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

  const headerStyle: React.CSSProperties = {
    display: "flex",
    alignItems: "center",
    marginBottom: 8,
  };
  const tableStyle: React.CSSProperties = {
    width: "100%",
    borderCollapse: "collapse",
  };
  const thStyle: React.CSSProperties = {
    textAlign: "left",
    borderBottom: "1px solid #ddd",
    padding: "8px 4px",
    fontWeight: 500,
  };
  const tdStyle: React.CSSProperties = {
    borderBottom: "1px solid #eee",
    padding: "4px",
  };
  const badgeStyle: React.CSSProperties = {
    minWidth: 22,
    height: 22,
    borderRadius: 11,
    background: "#e6eef8",
    color: "#0b66ff",
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: 12,
  };

  const leftHeaderStyle: React.CSSProperties = {
    display: "flex",
    alignItems: "center",
    gap: 8,
  };

  return (
    <div>
      <div style={headerStyle}>
        <div style={leftHeaderStyle} onClick={() => setCollapsed((s) => !s)}>
          <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
            {collapsed ? <RightOutlined /> : <DownOutlined />}
          </span>
          <span>{schema?.title || uiSchema?.["ui:title"] || "Items"}</span>
          <span style={badgeStyle}>{rowCount}</span>
        </div>

        <div>
          <Button
            size="small"
            type="text"
            icon={<PlusOutlined />}
            onClick={handleAdd}
            disabled={disabled || readonly}
            style={{ marginLeft: 8 }}
          />
        </div>
      </div>
      {!collapsed &&
        (rowCount === 0 ? (
          <div style={{ fontWeight: 600 }}>
            No entries. Click <b>+</b> to add.
          </div>
        ) : (
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Name</th>
                <th style={thStyle}>Value</th>
                <th style={thStyle}></th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(formData).map(([key, value], idx) => (
                <tr key={idx}>
                  <td style={tdStyle}>
                    <Input
                      value={key}
                      onChange={(e) => handleKeyChange(key, e.target.value)}
                      disabled={disabled || readonly}
                      placeholder="Name"
                    />
                  </td>
                  <td style={tdStyle}>
                    <Input
                      value={value}
                      onChange={(e) => handleValueChange(key, e.target.value)}
                      disabled={disabled || readonly}
                      placeholder="Value"
                    />
                  </td>
                  <td style={tdStyle}>
                    <Button
                      size="small"
                      type="text"
                      icon={<DeleteOutlined />}
                      onClick={() => handleDelete(key)}
                      disabled={disabled || readonly}
                      style={{ fontSize: 16, height: "32px", width: "32px" }}
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
