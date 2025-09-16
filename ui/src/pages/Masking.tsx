import { FloatButton, Table } from "antd";

import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { useCallback, useEffect, useState } from "react";
import { MaskedField } from "../api/apiTypes";
import { useParams } from "react-router";
import { api } from "../api/api.ts";
import { DeleteOutlined, MoreOutlined, PlusOutlined } from "@ant-design/icons";
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
      const ids = selectedRowKeys.map((key) => key.toString());
      await api.deleteMaskedFields(chainId, ids);
      setMaskedFields(
        maskedFields?.filter((field) => !ids.some((id) => field.id === id)) ??
          [],
      );
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
        <InlineEdit<{ name: string }>
          values={{ name: field.name }}
          editor={<TextValueEdit name={"name"} />}
          viewer={field.name}
          onSubmit={async ({ name }) => {
            await updateMaskedField(field.id, { name });
          }}
        />
      ),
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      render: (_, field) => <>{field.createdBy.username}</>,
      sorter: (a, b) =>
        a.createdBy.username.localeCompare(b.createdBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.createdBy.username,
      ),
      hidden: isVsCode,
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, field) => <>{formatTimestamp(field.createdWhen)}</>,
      sorter: (a, b) => a.createdWhen - b.createdWhen,
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn((snapshot) => snapshot.createdWhen),
      hidden: isVsCode,
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      render: (_, field) => <>{field.modifiedBy.username}</>,
      sorter: (a, b) =>
        a.modifiedBy.username.localeCompare(b.modifiedBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.modifiedBy.username,
      ),
      hidden: isVsCode,
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      render: (_, field) => <>{formatTimestamp(field.modifiedWhen)}</>,
      sorter: (a, b) => a.modifiedWhen - b.modifiedWhen,
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn((snapshot) => snapshot.modifiedWhen),
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

  return (
    <>
      <Table
        size="small"
        columns={columns}
        rowSelection={rowSelection}
        dataSource={maskedFields}
        pagination={false}
        loading={isMaskedFieldsLoading}
        rowKey="id"
        className="flex-table"
        style={{ height: "100%" }}
        scroll={{ y: "" }}
      />
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton
          tooltip={{
            title: "Delete selected masked fields",
            placement: "left",
          }}
          icon={<DeleteOutlined />}
          onClick={() => void onDeleteBtnClick()}
        />
        <FloatButton
          tooltip={{ title: "Add new masked field", placement: "left" }}
          icon={<PlusOutlined />}
          onClick={() => void onCreateBtnClick()}
        />
      </FloatButtonGroup>
    </>
  );
};
