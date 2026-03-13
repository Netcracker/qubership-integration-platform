import { Table } from "antd";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import React, { useCallback, useEffect, useState } from "react";
import { MaskedField } from "../api/apiTypes";
import { useParams } from "react-router";
import { api } from "../api/api.ts";
import {
  getTextColumnFilterFn,
  TextColumnFilterDropdown,
} from "../components/table/TextColumnFilterDropdown.tsx";
import { TableRowSelection } from "antd/lib/table/interface";
import { TextValueEdit } from "../components/table/TextValueEdit.tsx";
import { InlineEdit } from "../components/InlineEdit.tsx";
import { TableProps } from "antd/lib/table";
import { formatTimestamp } from "../misc/format-utils.ts";
import {
  getTimestampColumnFilterFn,
  TimestampColumnFilterDropdown,
} from "../components/table/TimestampColumnFilterDropdown.tsx";
import { isVsCode } from "../api/rest/vscodeExtensionApi.ts";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { ChainHeaderToolbar } from "../components/ChainHeaderToolbar.tsx";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { filterOutByIds, toStringIds } from "../misc/selection-utils.ts";
import { Require } from "../permissions/Require.tsx";

export const Masking: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [maskedFields, setMaskedFields] = useState<MaskedField[]>([]);
  const [isMaskedFieldsLoading, setIsMaskedFieldsLoading] = useState(false);
  const notificationService = useNotificationService();

  const getMaskedFields = useCallback(async (): Promise<MaskedField[]> => {
    setIsMaskedFieldsLoading(true);
    if (!chainId) {
      return [];
    }
    try {
      return api.getMaskedFields(chainId);
    } catch (error) {
      notificationService.requestFailed("Failed to get masked fields", error);
      return [];
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  }, [chainId, notificationService]);

  useEffect(() => {
    void getMaskedFields().then(setMaskedFields);
  }, [chainId, getMaskedFields]);

  const updateMaskedField = async (
    fieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<void> => {
    if (!chainId) {
      return;
    }
    setIsMaskedFieldsLoading(true);
    try {
      const field = await api.updateMaskedField(chainId, fieldId, changes);
      setMaskedFields(maskedFields.map((f) => (f.id === fieldId ? field : f)));
    } catch (error) {
      notificationService.requestFailed("Failed to update masked field", error);
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const createMaskedField = async () => {
    if (!chainId) {
      return;
    }
    setIsMaskedFieldsLoading(true);
    try {
      const field = await api.createMaskedField(chainId, { name: "" });
      setMaskedFields([field, ...maskedFields]);
    } catch (error) {
      notificationService.requestFailed("Failed to create masked field", error);
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const deleteSelectedMaskedFields = async () => {
    if (!chainId) {
      return;
    }
    setIsMaskedFieldsLoading(true);
    try {
      const ids = toStringIds(selectedRowKeys);
      await api.deleteMaskedFields(chainId, ids);
      setMaskedFields(filterOutByIds(maskedFields, ids));
    } catch (error) {
      notificationService.requestFailed(
        "Failed to delete masked fields",
        error,
      );
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const columns: TableProps<MaskedField>["columns"] = [
    {
      title: "Field",
      dataIndex: "name",
      key: "name",
      sorter: (a, b) => a.name.localeCompare(b.name),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn((snapshot) => snapshot.name),
      render: (_, field) => (
        <Require
          permissions={{ maskedField: ["update"] }}
          fallback={field.name}
        >
          <InlineEdit<{ name: string }>
            values={{ name: field.name }}
            editor={<TextValueEdit name={"name"} />}
            viewer={field.name}
            onSubmit={async ({ name }) => {
              await updateMaskedField(field.id, { name });
            }}
          />
        </Require>
      ),
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      render: (_, field) => <>{field.createdBy?.username ?? "-"}</>,
      sorter: (a, b) =>
        (a.createdBy?.username ?? "").localeCompare(
          b.createdBy?.username ?? "",
        ),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.createdBy?.username ?? "",
      ),
      hidden: isVsCode,
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, field) => (
        <>{field.createdWhen ? formatTimestamp(field.createdWhen) : "-"}</>
      ),
      sorter: (a, b) => (a.createdWhen ?? 0) - (b.createdWhen ?? 0),
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn(
        (snapshot) => snapshot.createdWhen ?? 0,
      ),
      hidden: isVsCode,
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      render: (_, field) => <>{field.modifiedBy?.username ?? "-"}</>,
      sorter: (a, b) =>
        (a.modifiedBy?.username ?? "").localeCompare(
          b.modifiedBy?.username ?? "",
        ),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.modifiedBy?.username ?? "",
      ),
      hidden: isVsCode,
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      render: (_, field) => (
        <>{field.modifiedWhen ? formatTimestamp(field.modifiedWhen) : "-"}</>
      ),
      sorter: (a, b) => (a.modifiedWhen ?? 0) - (b.modifiedWhen ?? 0),
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn(
        (snapshot) => snapshot.modifiedWhen ?? 0,
      ),
      hidden: isVsCode,
    },
  ];

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const onCreateBtnClick = async () => {
    await createMaskedField();
  };

  const onDeleteBtnClick = async () => {
    if (selectedRowKeys.length === 0) return;
    await deleteSelectedMaskedFields();
  };

  const rowSelection: TableRowSelection<MaskedField> = {
    type: "checkbox",
    selectedRowKeys,
    onChange: onSelectChange,
  };

  useRegisterChainHeaderActions(
    <ChainHeaderToolbar
      buttons={[
        {
          require: { maskedField: ["delete"] },
          tooltipProps: {
            title: "Delete selected masked fields",
          },
          buttonProps: {
            iconName: "delete",
            onClick: () => void onDeleteBtnClick(),
            disabled: selectedRowKeys.length === 0,
          },
        },
        {
          require: { maskedField: ["create"] },
          tooltipProps: {
            title: "Add new masked field",
          },
          buttonProps: {
            type: "primary",
            iconName: "plus",
            onClick: () => void onCreateBtnClick(),
          },
        },
      ]}
    />,
    [selectedRowKeys.length],
  );

  return (
    <TablePageLayout>
      <Table
        size="small"
        columns={columns}
        rowSelection={rowSelection}
        dataSource={maskedFields}
        pagination={false}
        loading={isMaskedFieldsLoading}
        rowKey="id"
        className="flex-table"
        style={{ flex: 1, minHeight: 0 }}
        scroll={{ y: "" }}
      />
    </TablePageLayout>
  );
};
