import { Button, Flex, message, Table, TableProps } from "antd";
import type { ColumnsType } from "antd/es/table";
import React, {
  ReactNode,
  Ref,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useState,
} from "react";
import {
  KeyValuePair,
  removeDuplicateKeys,
} from "../../../misc/key-value-text-util.ts";
import { InlineEdit } from "../../InlineEdit.tsx";
import { TextValueEdit } from "../TextValueEdit.tsx";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { useTableConfiguration } from "../useTableConfiguration.tsx";
import { tableScroll } from "../tableScroll.ts";

export type KeyValuePairsEditorProps = {
  value?: KeyValuePair[];
  onChange?: (value: KeyValuePair[]) => void;
};

export type KeyValuePairsTableRef = {
  add: () => void;
  clear: () => void;
};

export type KeyValuePairsTableProps = KeyValuePairsEditorProps & {
  actionRef?: Ref<KeyValuePairsTableRef>;
  columnNames?: KeyValuePair;
  header?: ReactNode;
};

export const KeyValuePairsTable: React.FC<KeyValuePairsTableProps> = ({
  onChange,
  value,
  columnNames,
  actionRef,
  header,
}) => {
  const [messageApi, contextHolder] = message.useMessage();
  const [tableData, setTableData] = useState<
    TableProps<KeyValuePair>["dataSource"]
  >([]);

  useEffect(() => {
    try {
      setTableData(removeDuplicateKeys(value ?? []));
    } catch (error) {
      void messageApi.error(`Invalid dictionary: ${String(error)}`);
      setTableData([]);
    }
  }, [messageApi, value]);

  const updateRecord = useCallback(
    (index: number, changes: Partial<KeyValuePair>) => {
      setTableData((data) => {
        const result =
          data?.map((r, idx) => (idx === index ? { ...r, ...changes } : r)) ??
          [];
        onChange?.(result);
        return result;
      });
    },
    [onChange],
  );

  const addRecord = useCallback(() => {
    setTableData((data) => {
      if (data?.some((r) => r.key === "")) {
        return data;
      }
      const result = [{ key: "", value: "" }, ...(data ?? [])];
      onChange?.(result);
      return result;
    });
  }, [onChange]);

  const deleteRecord = useCallback(
    (index: number) => {
      setTableData((data) => {
        const result =
          data?.slice(0, index)?.concat(data?.slice(index + 1)) ?? [];
        onChange?.(result);
        return result;
      });
    },
    [onChange],
  );

  const clearRecords = useCallback(() => {
    setTableData([]);
    onChange?.([]);
  }, [onChange]);

  const tableColumns = useMemo<ColumnsType<KeyValuePair>>(
    () => [
      {
        key: "key",
        title: columnNames?.key ?? "Key",
        dataIndex: "key",
        sorter: (a, b, sortOrder) => {
          if (sortOrder === "ascend") {
            return a.key.localeCompare(b.key);
          } else if (sortOrder === "descend") {
            return b.key.localeCompare(a.key);
          }
          return 0;
        },
        render: (value: string, _record: KeyValuePair, index: number) => {
          return (
            <InlineEdit<{ value: string }>
              values={{ value }}
              editor={<TextValueEdit name="value" />}
              viewer={value}
              initialActive={value === ""}
              onSubmit={({ value }) => {
                if (tableData?.some((r) => r.key === value)) {
                  void messageApi.error(`Already exists: ${value}`);
                } else {
                  updateRecord(index, { key: value });
                }
              }}
            />
          );
        },
      },
      {
        key: "value",
        title: columnNames?.value ?? "Value",
        dataIndex: "value",
        sorter: (a, b, sortOrder) => {
          if (sortOrder === "ascend") {
            return a.value.localeCompare(b.value);
          } else if (sortOrder === "descend") {
            return b.value.localeCompare(a.value);
          }
          return 0;
        },
        render: (value: string, _record: KeyValuePair, index: number) => {
          return (
            <InlineEdit<{ value: string }>
              values={{ value }}
              editor={<TextValueEdit name="value" rules={[]} />}
              viewer={value}
              onSubmit={({ value }) => {
                updateRecord(index, { value });
              }}
            />
          );
        },
      },
      {
        key: "actions",
        className: "actions-column",
        width: 40,
        align: "right" as const,
        render: (_: unknown, _record: KeyValuePair, index: number) => {
          return (
            <Button
              type="text"
              icon={<OverridableIcon name="delete" />}
              onClick={() => deleteRecord(index)}
            />
          );
        },
      },
    ],
    [
      columnNames?.key,
      columnNames?.value,
      tableData,
      messageApi,
      updateRecord,
      deleteRecord,
    ],
  );

  const { columnsWithResize, scrollX, components } = useTableConfiguration(
    tableColumns,
    {
      key: 160,
      value: 220,
      actions: 40,
    },
    { applyDisableResizeBeforeActions: true },
  );

  useImperativeHandle(actionRef, () => {
    return {
      add() {
        addRecord();
      },
      clear() {
        clearRecords();
      },
    };
  }, [addRecord, clearRecords]);

  return (
    <>
      {contextHolder}
      <Flex style={{ height: "100%" }} vertical gap={8}>
        {header}
        <Table
          className="flex-table"
          size="small"
          scroll={tableScroll(scrollX, tableData?.length ?? 0)}
          columns={columnsWithResize}
          dataSource={tableData}
          pagination={false}
          rowKey={(record) => record.key}
          components={components}
        />
      </Flex>
    </>
  );
};
