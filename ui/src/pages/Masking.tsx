import { Table } from "antd";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import React, { useCallback, useEffect, useMemo, useState } from "react";
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
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { filterOutByIds, toStringIds } from "../misc/selection-utils.ts";
import { Require } from "../permissions/Require.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../components/table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../components/table/useTableColumnResize.tsx";
import { disableResizeBeforeActions } from "../components/table/actionsColumn.ts";
import { matchesByFields } from "../components/table/tableSearch.ts";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { TableToolbar } from "../components/table/TableToolbar.tsx";

const MASKING_SELECTION_COLUMN_WIDTH = 48;

function maskedFieldMatchesSearch(field: MaskedField, term: string): boolean {
  const createdStr = field.createdWhen
    ? formatTimestamp(field.createdWhen)
    : "";
  const modifiedStr = field.modifiedWhen
    ? formatTimestamp(field.modifiedWhen)
    : "";
  return matchesByFields(term, [
    field.name,
    field.createdBy?.username,
    field.modifiedBy?.username,
    createdStr,
    modifiedStr,
  ]);
}

export const Masking: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [maskedFields, setMaskedFields] = useState<MaskedField[]>([]);
  const [isMaskedFieldsLoading, setIsMaskedFieldsLoading] = useState(false);
  const [searchTerm, setSearchTerm] = useState("");
  const notificationService = useNotificationService();

  const filteredMaskedFields = useMemo(
    () =>
      maskedFields.filter((row) => maskedFieldMatchesSearch(row, searchTerm)),
    [maskedFields, searchTerm],
  );

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

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<MaskedField>("maskingTable", columns);

  const maskingColumnResize = useTableColumnResize({
    name: 220,
    createdBy: 120,
    createdWhen: 168,
    modifiedBy: 120,
    modifiedWhen: 168,
  });

  const columnsWithResize = useMemo(() => {
    const resized = attachResizeToColumns(
      orderedColumns,
      maskingColumnResize.columnWidths,
      maskingColumnResize.createResizeHandlers,
      { minWidth: 80 },
    );
    return disableResizeBeforeActions(resized);
  }, [
    orderedColumns,
    maskingColumnResize.columnWidths,
    maskingColumnResize.createResizeHandlers,
  ]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        maskingColumnResize.columnWidths,
        { selectionColumnWidth: MASKING_SELECTION_COLUMN_WIDTH },
      ),
    [columnsWithResize, maskingColumnResize.columnWidths],
  );

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

  const chainTabToolbar = useMemo(
    () => (
      <TableToolbar
        variant="chain-tab"
        search={{
          value: searchTerm,
          onChange: setSearchTerm,
          placeholder: "Search masked fields...",
          allowClear: true,
          style: { minWidth: 160, maxWidth: 360, flex: "0 1 auto" },
        }}
        columnSettingsButton={columnSettingsButton}
        actions={
          <>
            <ProtectedButton
              require={{ maskedField: ["delete"] }}
              tooltipProps={{ title: "Delete selected masked fields" }}
              buttonProps={{
                iconName: "delete",
                onClick: () => void onDeleteBtnClick(),
                disabled: selectedRowKeys.length === 0,
              }}
            />
            <ProtectedButton
              require={{ maskedField: ["create"] }}
              tooltipProps={{ title: "Add new masked field" }}
              buttonProps={{
                type: "primary",
                iconName: "plus",
                onClick: () => void onCreateBtnClick(),
              }}
            />
          </>
        }
      />
    ),
    [searchTerm, columnSettingsButton, selectedRowKeys],
  );

  useRegisterChainHeaderActions(chainTabToolbar, [
    searchTerm,
    selectedRowKeys,
    // columnSettingsButton is new JSX every render — omit (see Deployments).
  ]);

  return (
    <TablePageLayout>
      <Table
        size="small"
        columns={columnsWithResize}
        rowSelection={rowSelection}
        dataSource={filteredMaskedFields}
        pagination={false}
        loading={isMaskedFieldsLoading}
        rowKey="id"
        className="flex-table"
        style={{ flex: 1, minHeight: 0 }}
        scroll={
          filteredMaskedFields.length > 0
            ? { x: scrollX, y: "" }
            : { x: scrollX }
        }
        components={maskingColumnResize.resizableHeaderComponents}
      />
    </TablePageLayout>
  );
};
