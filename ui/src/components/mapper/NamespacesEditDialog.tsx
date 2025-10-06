import React, { useCallback, useEffect, useState } from "react";
import { useModalContext } from "../../ModalContextProvider";
import { XmlNamespace } from "../../mapper/model/metadata";
import { Button, Flex, message, Modal, Table, TableProps } from "antd";
import { InlineEdit } from "../InlineEdit";
import { TextValueEdit } from "../table/TextValueEdit";
import { Icon } from "../../IconProvider.tsx";

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
              icon={<Icon name="plusCircle" />}
              onClick={() => addRecord()}
            >
              Add rule
            </Button>
            <Button
              size="small"
              icon={<Icon name="delete" />}
              onClick={() => clearRecords()}
            >
              Clear rules
            </Button>
          </Flex>
          <Table
            className="flex-table"
            size="small"
            scroll={{ y: "" }}
            columns={[
              {
                key: "alias",
                title: "Prefix",
                dataIndex: "alias",
                sorter: (a, b, sortOrder) => {
                  if (sortOrder === "ascend") {
                    return a.alias.localeCompare(b.alias);
                  } else if (sortOrder === "descend") {
                    return b.alias.localeCompare(a.alias);
                  }
                  return 0;
                },
                render: (
                  value: string,
                  _record: XmlNamespace,
                  index: number,
                ) => {
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
                sorter: (a, b, sortOrder) => {
                  if (sortOrder === "ascend") {
                    return a.uri.localeCompare(b.uri);
                  } else if (sortOrder === "descend") {
                    return b.uri.localeCompare(a.uri);
                  }
                  return 0;
                },
                render: (
                  value: string,
                  _record: XmlNamespace,
                  index: number,
                ) => {
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
                align: "right",
                render: (_, _record, index: number) => {
                  return (
                    <Button
                      type="text"
                      icon={<Icon name="delete" />}
                      onClick={() => deleteRecord(index)}
                    />
                  );
                },
              },
            ]}
            dataSource={tableData}
            pagination={false}
            rowKey={(record) => record.alias}
          />
        </Flex>
      </>
    </Modal>
  );
};
