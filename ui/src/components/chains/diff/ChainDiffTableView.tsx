import React, { useEffect, useMemo, useState } from "react";
import { Chain } from "../../../api/apiTypes.ts";
import { Change } from "./compare/types.ts";
import { Table, TableProps } from "antd";
import { TableRowSelection } from "antd/lib/table/interface";
import { ChangedEntityView, LinkToChain } from "./ChangedEntityView.tsx";

export type ChainDiffTableViewProps = {
  chain1?: Chain;
  chain2?: Chain;
  changes: Change[];
  selectedChangeId?: string;
  onSelectChange: (id: string) => void;
};

export const ChainDiffTableView: React.FC<ChainDiffTableViewProps> = ({
  chain1,
  chain2,
  changes,
  selectedChangeId,
  onSelectChange,
}): React.ReactNode => {
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  const rowSelection: TableRowSelection<Change> = {
    type: "radio",
    selectedRowKeys,
    onChange: (selected) => {
      onSelectChange(selected.length > 0 ? selected[0].toString() : "");
    },
  };

  const columns: TableProps<Change>["columns"] = useMemo(() => {
    return [
      {
        key: "Type",
        title: "Type",
        render: (_: unknown, change) => {
          switch (change.kind) {
            case "element":
              return "element";
            case "chain-property":
              return "chain property";
            case "element-property":
              return "element property";
            case "connection":
              return "connection";
            default:
              return <></>;
          }
        },
      },
      {
        key: "one",
        title: <LinkToChain chain={chain1} />,
        render: (_, change) => {
          return chain1 ? (
            <ChangedEntityView change={change} side={"one"} chain={chain1} />
          ) : (
            <></>
          );
        },
      },
      {
        key: "another",
        title: <LinkToChain chain={chain2} />,
        render: (_, change) => {
          return chain2 ? (
            <ChangedEntityView
              change={change}
              side={"another"}
              chain={chain2}
            />
          ) : (
            <></>
          );
        },
      },
    ];
  }, [chain1, chain2]);

  useEffect(() => {
    setSelectedRowKeys(selectedChangeId ? [selectedChangeId] : []);
  }, [selectedChangeId]);

  return (
    <Table<Change>
      className="flex-table"
      size="small"
      dataSource={changes}
      columns={columns}
      rowSelection={rowSelection}
      pagination={false}
      scroll={{ y: "" }}
      rowKey="id"
    />
  );
};
