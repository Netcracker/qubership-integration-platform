import React, {
  ReactNode,
  Ref,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import { KeyValuePair } from "../../../misc/key-value-text-util.ts";
import { Tabs, TabsProps } from "antd";
import {
  KeyValuePairsEditorProps,
  KeyValuePairsTable,
  KeyValuePairsTableRef,
} from "./KeyValuePairsTable.tsx";
import { KeyValuePairsTextEditor } from "./KeyValuePairsTextEditor.tsx";

export type KeyValuePairsEditorTab = "table" | "text";

export type KeyValuePairsEditorRef = KeyValuePairsTableRef & {
  activateTab: (tab: KeyValuePairsEditorTab) => void;
};

export type KeyValuePairsEditorExProps = KeyValuePairsEditorProps &
  Omit<TabsProps, keyof KeyValuePairsEditorProps> & {
    tableHeader?: ReactNode;
    tableColumnNames?: KeyValuePair;
    actionRef?: Ref<KeyValuePairsEditorRef>;
  };

export const KeyValuePairsEditor: React.FC<KeyValuePairsEditorExProps> = ({
  onChange,
  value,
  tableHeader,
  tableColumnNames,
  actionRef,
  ...rest
}) => {
  const [activeKey, setActiveKey] = useState<string>("table");
  const tableActionsRef = useRef<KeyValuePairsTableRef>(null);

  useImperativeHandle(actionRef, () => {
    return {
      add() {
        tableActionsRef.current?.add();
      },
      clear() {
        tableActionsRef.current?.clear();
      },
      activateTab(tab: KeyValuePairsEditorTab) {
        setActiveKey(tab);
      },
    };
  }, [tableActionsRef]);

  return (
    <Tabs
      style={{ height: "100%", width: "100%" }}
      className={"flex-tabs"}
      tabPlacement="bottom"
      size="small"
      defaultActiveKey="table"
      {...rest}
      items={[
        {
          key: "table",
          label: "Table",
          children: (
            <KeyValuePairsTable
              value={value}
              onChange={onChange}
              header={tableHeader}
              columnNames={tableColumnNames}
              actionRef={tableActionsRef}
            />
          ),
        },
        {
          key: "text",
          label: "Text",
          children: (
            <KeyValuePairsTextEditor value={value} onChange={onChange} />
          ),
        },
      ]}
      activeKey={activeKey}
      onChange={setActiveKey}
    />
  );
};
