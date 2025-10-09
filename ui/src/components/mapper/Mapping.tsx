import React, { useCallback, useEffect, useState } from "react";
import { MappingDescription } from "../../mapper/model/model.ts";
import { MappingTableView } from "./MappingTableView.tsx";
import { Tabs } from "antd";
import { MappingActionsTextView } from "./MappingActionsTextView.tsx";
import { MappingUtil } from "../../mapper/util/mapping.ts";
import { MappingGraphView } from "./MappingGraphView.tsx";

export type MappingProps = React.HTMLAttributes<HTMLElement> & {
  elementId: string;
  mapping?: MappingDescription;
  readonlySource?: boolean;
  readonlyTarget?: boolean;
  onChange?: (mapping: MappingDescription) => void;
};

export const Mapping: React.FC<MappingProps> = ({
  elementId,
  mapping,
  readonlySource,
  readonlyTarget,
  onChange,
  ...props
}) => {
  const [value, setValue] = useState<MappingDescription>();

  useEffect(() => {
    setValue(mapping ?? MappingUtil.emptyMapping());
  }, [mapping]);

  const onValueChange = useCallback(
    (newValue: MappingDescription) => {
      setValue(newValue);
      onChange?.(newValue);
    },
    [onChange],
  );

  return (
    <Tabs
      style={{ height: "100%" }}
      className={"flex-tabs"}
      items={[
        {
          key: "graph",
          label: "Graph",
          children: (
            <MappingGraphView
              elementId={elementId}
              mapping={value}
              readonlySource={readonlySource}
              readonlyTarget={readonlyTarget}
              onChange={onValueChange}
            />
          ),
        },
        {
          key: "table",
          label: "Table",
          children: (
            <MappingTableView
              elementId={elementId}
              mapping={value}
              readonlySource={readonlySource}
              readonlyTarget={readonlyTarget}
              onChange={onValueChange}
            />
          ),
        },
        {
          key: "text",
          label: "Text",
          children: (
            <MappingActionsTextView mapping={value} onChange={onValueChange} />
          ),
        },
      ]}
      {...props}
    />
  );
};
