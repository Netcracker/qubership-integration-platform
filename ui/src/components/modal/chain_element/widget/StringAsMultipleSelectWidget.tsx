import React from "react";
import { Select, SelectProps, Tag } from "antd";
import { WidgetProps } from "@rjsf/utils";
import { methodValues } from "../../../../hooks/useChainFilter.ts";
import { methodTagStyle } from "../../../services/ui/HttpMethod.tsx";

// Color each selected method tag the same way operations render methods. The
// qip-method-tag class keeps a white label in every theme and centers the close
// icon (see antd-overrides.css).
const renderMethodTag: SelectProps["tagRender"] = ({
  value,
  closable,
  onClose,
}) => {
  const method = String(value).toUpperCase();
  return (
    <Tag
      className="qip-method-tag"
      closable={closable}
      onClose={onClose}
      onMouseDown={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
      style={{ ...methodTagStyle(method), marginInlineEnd: 4 }}
    >
      {method}
    </Tag>
  );
};

// Show each dropdown row as the same colored badge, aligned in a column, so the
// list reads like the OpenAPI method palette instead of plain text.
const renderMethodOption: SelectProps["optionRender"] = (option) => {
  const method = String(option.value).toUpperCase();
  return (
    <span
      style={{
        ...methodTagStyle(method),
        display: "inline-block",
        minWidth: 72,
      }}
    >
      {method}
    </span>
  );
};

const StringAsMultipleSelectWidget: React.FC<WidgetProps> = ({
  value,
  name,
  onChange,
}) => {
  const isMethod = name === "httpMethodRestrict";
  const options = isMethod ? methodValues : [];

  const handleChange = (selected: string[]) => {
    onChange(selected.join(","));
  };

  return (
    <Select
      mode="multiple"
      allowClear
      style={{ width: "100%" }}
      placeholder="Please select"
      options={options}
      tagRender={isMethod ? renderMethodTag : undefined}
      optionRender={isMethod ? renderMethodOption : undefined}
      onChange={handleChange}
      value={typeof value === "string" ? value.split(",").filter(Boolean) : []}
    />
  );
};

export default StringAsMultipleSelectWidget;
