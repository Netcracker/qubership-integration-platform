import React, { useCallback, useEffect, useState } from "react";
import { MappingDescription } from "../../mapper/model/model.ts";
import { MappingTableView } from "./MappingTableView.tsx";
import { ConfigProvider, Tabs } from "antd";
import type { ThemeConfig } from "antd";
import { MappingActionsTextView } from "./MappingActionsTextView.tsx";
import { MappingUtil } from "../../mapper/util/mapping.ts";
import { MappingGraphView } from "./MappingGraphView.tsx";
import { AutoHeight } from "../AutoHeight.tsx";

// Every mapping row is a single line of text, and this tab bar sits under the
// element modal's own one, so both defaults only cost visible rows.
const MAPPING_THEME: ThemeConfig = {
  components: {
    Table: { cellPaddingBlockSM: 4 },
    Tabs: { horizontalMargin: "0 0 8px 0" },
  },
};

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
    <AutoHeight>
      <ConfigProvider theme={MAPPING_THEME}>
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
                <MappingActionsTextView
                  mapping={value}
                  onChange={onValueChange}
                />
              ),
            },
          ]}
          {...props}
        />
      </ConfigProvider>
    </AutoHeight>
  );
};
