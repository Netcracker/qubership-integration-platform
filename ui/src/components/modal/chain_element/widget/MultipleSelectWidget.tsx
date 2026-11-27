import React from "react";
import { Select } from "antd";
import { WidgetProps } from "@rjsf/utils";

const MultipleSelectWidget: React.FC<WidgetProps> = ({
  id,
  label,
  value,
  name,
  onChange,
  options,
  schema,
}) => {
  const items =
    schema.type === "array" &&
    typeof schema.items === "object" &&
    !Array.isArray(schema.items)
      ? schema.items
      : undefined;
  const configuredSuggestions: unknown = options.suggestions;
  const suggestions = Array.isArray(configuredSuggestions)
    ? configuredSuggestions.filter(
        (value: unknown): value is string => typeof value === "string",
      )
    : [];
  const optionValues = Array.isArray(items?.enum)
    ? (items.enum as string[])
    : suggestions;
  const selectOptions = [...new Set(optionValues)].map((value) => ({ value }));
  const suggestedValues = new Set(suggestions);

  const modeMap: Record<string, "multiple" | "tags"> = {
    synchronousPullRetryableCodes: "multiple",
  };

  const mode = modeMap[name] ?? "tags";

  return (
    <Select<string[], { value: string }>
      id={id}
      aria-label={label}
      mode={mode}
      allowClear
      style={{ width: "100%" }}
      placeholder="Please input"
      onChange={(selected) => onChange(selected)}
      value={value as string[]}
      options={selectOptions}
      showSearch={
        suggestedValues.size > 0
          ? {
              filterSort: (a, b) =>
                Number(suggestedValues.has(b.value)) -
                Number(suggestedValues.has(a.value)),
            }
          : undefined
      }
    />
  );
};

export default MultipleSelectWidget;
