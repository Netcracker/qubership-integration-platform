import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useModalContext } from "../../ModalContextProvider";
import { XmlNamespace } from "../../mapper/model/metadata";
import { Button, Flex, message, Modal, Table, TableProps } from "antd";
import { InlineEdit } from "../InlineEdit";
import { TextValueEdit } from "../table/TextValueEdit";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../table/useTableColumnResize.tsx";

export type NamespacesEditDialogProps = {
  namespaces: XmlNamespace[];
  onSubmit: (namespaces: XmlNamespace[]) => void;
};

export const NamespacesEditDialog: React.FC<NamespacesEditDialogProps> = ({
  namespaces,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [messageApi, contextHolder] = message.useMessage();
  const [tableData, setTableData] = useState<
    TableProps<XmlNamespace>["dataSource"]
  >([]);

  useEffect(() => {
    setTableData(namespaces);
  }, [namespaces]);

  const updateRecord = useCallback(
    (index: number, changes: Partial<XmlNamespace>) => {
      setTableData(
        (data) =>
          data?.map((r, idx) => (idx === index ? { ...r, ...changes } : r)) ??
          [],
      );
    },
    [],
  );

  const addRecord = useCallback(() => {
    setTableData((data) => {
      if (data?.some((r) => r.alias === "")) {
        return data;
      }
      return [...(data ?? []), { alias: "", uri: "" }];
    });
  }, []);

  const deleteRecord = useCallback((index: number) => {
    setTableData(
      (data) => data?.slice(0, index)?.concat(data?.slice(index + 1)) ?? [],
    );
  }, []);

  const clearRecords = useCallback(() => {
    setTableData([]);
  }, []);

  const namespaceColumns = useMemo(
    () => [
      {
        key: "alias",
        title: "Prefix",
        dataIndex: "alias",
        sorter: (
          a: XmlNamespace,
          b: XmlNamespace,
          sortOrder: string | undefined,
        ) => {
          if (sortOrder === "ascend") {
            return a.alias.localeCompare(b.alias);
          } else if (sortOrder === "descend") {
            return b.alias.localeCompare(a.alias);
          }
          return 0;
        },
        render: (value: string, _record: XmlNamespace, index: number) => {
          return (
            <InlineEdit<{ value: string }>
              values={{ value }}
              editor={<TextValueEdit name="value" />}
              viewer={value}
              initialActive={value === ""}
              onSubmit={({ value }) => {
                if (tableData?.some((r) => r.alias === value)) {
                  messageApi.error(`Already exists: ${value}`);
                } else {
                  updateRecord(index, { alias: value });
                }
              }}
            />
          );
        },
      },
      {
        key: "uri",
        title: "URI",
        dataIndex: "uri",
        sorter: (
          a: XmlNamespace,
          b: XmlNamespace,
          sortOrder: string | undefined,
        ) => {
          if (sortOrder === "ascend") {
            return a.uri.localeCompare(b.uri);
          } else if (sortOrder === "descend") {
            return b.uri.localeCompare(a.uri);
          }
          return 0;
        },
        render: (value: string, _record: XmlNamespace, index: number) => {
          return (
            <InlineEdit<{ value: string }>
              values={{ value }}
              editor={<TextValueEdit name="value" rules={[]} />}
              viewer={value}
              onSubmit={({ value }) => {
                updateRecord(index, { uri: value });
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
        render: (_: unknown, _record: XmlNamespace, index: number) => {
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
    [tableData, messageApi, updateRecord, deleteRecord],
  );

  const namespaceColumnResize = useTableColumnResize({
    alias: 160,
    uri: 360,
  });

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        namespaceColumns,
        namespaceColumnResize.columnWidths,
        namespaceColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      namespaceColumns,
      namespaceColumnResize.columnWidths,
      namespaceColumnResize.createResizeHandlers,
    ],
  );

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        namespaceColumnResize.columnWidths,
      ),
    [columnsWithResize, namespaceColumnResize.columnWidths],
  );

  return (
    <Modal
      title="Edit namespaces"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          onClick={() => {
            onSubmit([...(tableData ?? [])]);
            closeContainingModal();
          }}
        >
          Save
        </Button>,
      ]}
    >
      <>
        {contextHolder}
        <Flex style={{ height: "60vh" }} vertical gap={8}>
          <Flex wrap="wrap" vertical={false} gap={8}>
            <Button
              size="small"
              icon={<OverridableIcon name="plusCircle" />}
              onClick={() => addRecord()}
            >
              Add rule
            </Button>
            <Button
              size="small"
              icon={<OverridableIcon name="delete" />}
              onClick={() => clearRecords()}
            >
              Clear rules
            </Button>
          </Flex>
          <Table
            className="flex-table"
            size="small"
            scroll={{ x: scrollX, y: "" }}
            columns={columnsWithResize}
            dataSource={tableData}
            pagination={false}
            rowKey={(record) => record.alias}
            components={namespaceColumnResize.resizableHeaderComponents}
          />
        </Flex>
      </>
    </Modal>
  );
};
