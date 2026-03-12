import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Dropdown,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  UploadFile,
} from "antd";
import type { MenuProps, TableProps } from "antd";
import Dragger from "antd/es/upload/Dragger";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import commonStyles from "./CommonStyle.module.css";
import { api } from "../../api/api.ts";
import {
  GeneralImportInstructions,
  ImportInstructionAction,
  ImportInstructionResult,
  ImportEntityType,
} from "../../api/apiTypes.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { ImportStatus } from "../labels/ImportStatus.tsx";
import {
  EditableCellTrigger,
  InlineEditWithButtons,
  editableCellStyles,
} from "../table/EditableCell.tsx";
import { CompactSearch } from "../table/CompactSearch.tsx";
import { capitalize } from "../../misc/format-utils.ts";
import {
  TextColumnFilterDropdown,
  getTextColumnFilterFn,
  getTextListColumnFilterFn,
} from "../table/TextColumnFilterDropdown.tsx";
import { makeEnumColumnFilterDropdown } from "../EnumColumnFilterDropdown.tsx";
import {
  TimestampColumnFilterDropdown,
  getTimestampColumnFilterFn,
} from "../table/TimestampColumnFilterDropdown.tsx";
import type { FilterDropdownProps } from "antd/lib/table/interface";
import dayjs from "dayjs";

const { Title } = Typography;

const IdColumnFilterDropdown: React.FC<FilterDropdownProps> = (props) => (
  <TextColumnFilterDropdown {...props} />
);

const OverriddenByColumnFilterDropdown: React.FC<FilterDropdownProps> = (
  props,
) => <TextColumnFilterDropdown {...props} />;

const LabelsColumnFilterDropdown: React.FC<FilterDropdownProps> = (props) => (
  <TextColumnFilterDropdown {...props} />
);

const ModifiedWhenColumnFilterDropdown: React.FC<FilterDropdownProps> = (
  props,
) => <TimestampColumnFilterDropdown {...props} />;

type InstructionEntityType = "Chain" | "Service" | "Common Variable";

type InstructionRow = {
  key: string;
  id: string;
  name?: string;
  entityType: InstructionEntityType;
  entityTypeForApi: ImportEntityType;
  action: ImportInstructionAction;
  overriddenById?: string;
  overriddenByName?: string;
  labels?: string[];
  modifiedWhen?: number;
  isGroup: boolean;
  children?: InstructionRow[];
};

const ENTITY_DISPLAY: Record<InstructionEntityType, string> = {
  Chain: "Chains",
  Service: "Services",
  "Common Variable": "Common Variables",
};

const ENTITY_ICON: Record<InstructionEntityType, string> = {
  Chain: "link",
  Service: "cluster",
  "Common Variable": "code",
};

function getEntityHref(row: InstructionRow): string | undefined {
  if (row.entityType === "Chain") return `/chains/${row.id}`;
  if (row.entityType === "Service") return `/services/systems/${row.id}`;
  return undefined;
}

const ENTITY_TO_API: Record<InstructionEntityType, ImportEntityType> = {
  Chain: ImportEntityType.CHAIN,
  Service: ImportEntityType.SERVICE,
  "Common Variable": ImportEntityType.COMMON_VARIABLE,
};

const ACTION_OPTIONS_CHAIN = [
  { label: "Ignore", value: ImportInstructionAction.IGNORE },
  { label: "Override", value: ImportInstructionAction.OVERRIDE },
];

const ACTION_OPTIONS_SERVICE_OR_VAR = [
  { label: "Ignore", value: ImportInstructionAction.IGNORE },
];

const ACTION_FILTER_OPTIONS = [
  { label: "Ignore", value: ImportInstructionAction.IGNORE },
  { label: "Override", value: ImportInstructionAction.OVERRIDE },
  { label: "Delete", value: ImportInstructionAction.DELETE },
];

const COLUMN_VISIBILITY_MENU_ITEMS: MenuProps["items"] = [
  { label: "Id", key: "id" },
  { label: "Action", key: "action" },
  { label: "Overridden By", key: "overriddenBy" },
  { label: "Labels", key: "labels" },
  { label: "Modified When", key: "modifiedWhen" },
];

export function buildTableData(
  instructions: GeneralImportInstructions | undefined,
): InstructionRow[] {
  if (!instructions) return [];

  const chainIgnores = instructions.chains?.ignore ?? [];
  const chainOverrides = instructions.chains?.override ?? [];
  const chainDeletes = instructions.chains?.delete ?? [];
  const serviceIgnores = instructions.services?.ignore ?? [];
  const serviceDeletes = instructions.services?.delete ?? [];
  const varIgnores = instructions.commonVariables?.ignore ?? [];
  const varDeletes = instructions.commonVariables?.delete ?? [];

  const chainChildren: InstructionRow[] = [
    ...chainIgnores.map((i) => ({
      key: `Chain-IGNORE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Chain" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.CHAIN,
      action: ImportInstructionAction.IGNORE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
    ...(chainOverrides ?? []).map((i) => ({
      key: `Chain-OVERRIDE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Chain" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.CHAIN,
      action: ImportInstructionAction.OVERRIDE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
    ...(chainDeletes ?? []).map((i) => ({
      key: `Chain-DELETE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Chain" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.CHAIN,
      action: ImportInstructionAction.DELETE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
  ];

  const serviceChildren: InstructionRow[] = [
    ...(serviceIgnores ?? []).map((i) => ({
      key: `Service-IGNORE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Service" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.SERVICE,
      action: ImportInstructionAction.IGNORE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
    ...(serviceDeletes ?? []).map((i) => ({
      key: `Service-DELETE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Service" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.SERVICE,
      action: ImportInstructionAction.DELETE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
  ];

  const varChildren: InstructionRow[] = [
    ...(varIgnores ?? []).map((i) => ({
      key: `Common Variable-IGNORE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Common Variable" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.COMMON_VARIABLE,
      action: ImportInstructionAction.IGNORE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
    ...(varDeletes ?? []).map((i) => ({
      key: `Common Variable-DELETE-${i.id}`,
      id: i.id,
      name: i.name ?? i.id,
      entityType: "Common Variable" as InstructionEntityType,
      entityTypeForApi: ImportEntityType.COMMON_VARIABLE,
      action: ImportInstructionAction.DELETE,
      overriddenById: i.overriddenById,
      overriddenByName: i.overriddenByName,
      labels: i.labels,
      modifiedWhen: i.modifiedWhen,
      isGroup: false,
    })),
  ];

  return [
    {
      key: "Chain",
      id: "Chain",
      entityType: "Chain",
      entityTypeForApi: ImportEntityType.CHAIN,
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: chainChildren.length > 0 ? chainChildren : undefined,
    },
    {
      key: "Service",
      id: "Service",
      entityType: "Service",
      entityTypeForApi: ImportEntityType.SERVICE,
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: serviceChildren.length > 0 ? serviceChildren : undefined,
    },
    {
      key: "Common Variable",
      id: "Common Variable",
      entityType: "Common Variable",
      entityTypeForApi: ImportEntityType.COMMON_VARIABLE,
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: varChildren.length > 0 ? varChildren : undefined,
    },
  ];
}

function filterRowsBySearchTerm(
  rows: InstructionRow[],
  term: string,
): InstructionRow[] {
  if (!term.trim()) return rows;
  const t = term.toLowerCase().trim();
  const filterChildren = (children: InstructionRow[] | undefined) =>
    children?.filter(
      (c) =>
        c.id.toLowerCase().includes(t) ||
        (c.name?.toLowerCase().includes(t) ?? false) ||
        c.labels?.some((l) => l.toLowerCase().includes(t)),
    ) ?? [];
  return rows.map((r) => ({
    ...r,
    children: r.children?.length ? filterChildren(r.children) : undefined,
  }));
}

export const ImportInstructions: React.FC = () => {
  const notificationService = useNotificationService();
  const [instructions, setInstructions] = useState<
    GeneralImportInstructions | undefined
  >(undefined);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState("");
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [addModalVisible, setAddModalVisible] = useState(false);
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [exportLoading, setExportLoading] = useState(false);
  const [editingRowKey, setEditingRowKey] = useState<string | null>(null);
  const [editingField, setEditingField] = useState<
    "action" | "overriddenBy" | null
  >(null);
  const [editingAction, setEditingAction] = useState<ImportInstructionAction>(
    ImportInstructionAction.IGNORE,
  );
  const [editingOverriddenBy, setEditingOverriddenBy] = useState("");
  const [visibleColumns, setVisibleColumns] = useState<string[]>([
    "id",
    "action",
    "overriddenBy",
    "labels",
    "modifiedWhen",
  ]);

  const fetchInstructions = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.getImportInstructions();
      setInstructions(data);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to load import instructions",
        error,
      );
    } finally {
      setLoading(false);
    }
  }, [notificationService]);

  useEffect(() => {
    void fetchInstructions();
  }, [fetchInstructions]);

  const tableData = useMemo(
    () => filterRowsBySearchTerm(buildTableData(instructions), searchTerm),
    [instructions, searchTerm],
  );

  const handleUpdateAction = useCallback(
    async (
      row: InstructionRow,
      newAction: ImportInstructionAction,
      overriddenBy?: string | null,
    ) => {
      try {
        await api.updateImportInstruction({
          id: row.id,
          entityType: row.entityTypeForApi,
          action: newAction,
          overriddenBy: overriddenBy ?? undefined,
        });
        await fetchInstructions();
      } catch (error) {
        notificationService.requestFailed(
          "Failed to update import instruction",
          error,
        );
      }
    },
    [fetchInstructions, notificationService],
  );

  const handleExport = useCallback(async () => {
    setExportLoading(true);
    try {
      const file = await api.exportImportInstructions();
      const link = document.createElement("a");
      link.href = URL.createObjectURL(file);
      link.download = file.name || "import-instructions.yaml";
      link.click();
      link.remove();
    } catch (error) {
      notificationService.requestFailed(
        "Failed to export import instructions",
        error,
      );
    } finally {
      setExportLoading(false);
    }
  }, [notificationService]);

  const handleDelete = useCallback(async () => {
    const chains: string[] = [];
    const services: string[] = [];
    const commonVariables: string[] = [];

    const collectFromRows = (rows: InstructionRow[]) => {
      rows.forEach((r) => {
        if (r.isGroup && r.children) collectFromRows(r.children);
        else if (!r.isGroup && selectedRowKeys.includes(r.key)) {
          if (r.entityType === "Chain") chains.push(r.id);
          else if (r.entityType === "Service") services.push(r.id);
          else if (r.entityType === "Common Variable")
            commonVariables.push(r.id);
        }
      });
    };
    tableData.forEach((r) => {
      if (r.children) collectFromRows(r.children);
    });

    if (
      chains.length === 0 &&
      services.length === 0 &&
      commonVariables.length === 0
    )
      return;

    try {
      await api.deleteImportInstructions({
        chains: chains.length > 0 ? chains : undefined,
        services: services.length > 0 ? services : undefined,
        commonVariables:
          commonVariables.length > 0 ? commonVariables : undefined,
      });
      setSelectedRowKeys([]);
      await fetchInstructions();
    } catch (error) {
      notificationService.requestFailed(
        "Failed to delete import instructions",
        error,
      );
    }
  }, [selectedRowKeys, tableData, fetchInstructions, notificationService]);

  const { filterDropdown: actionFilterDropdown } = useMemo(
    () =>
      makeEnumColumnFilterDropdown<"action", ImportInstructionAction>(
        ACTION_FILTER_OPTIONS,
        "action",
        true,
      ),
    [],
  );

  const columns: TableProps<InstructionRow>["columns"] = [
    {
      title: "Id",
      dataIndex: "id",
      key: "id",
      hidden: !visibleColumns.includes("id"),
      width: 280,
      filterDropdown: IdColumnFilterDropdown,
      onFilter: (value, record) => {
        if (record.isGroup) return true;
        return getTextColumnFilterFn<InstructionRow>((r) =>
          `${r.id} ${r.name ?? ""}`.trim(),
        )(value, record);
      },
      render: (_, row) => {
        if (row.isGroup)
          return (
            <strong>
              <OverridableIcon
                name={ENTITY_ICON[row.entityType]}
                style={{ marginRight: 8 }}
              />
              {ENTITY_DISPLAY[row.entityType]}
            </strong>
          );
        const href = getEntityHref(row);
        return href ? (
          <a href={href} target="_blank" rel="noopener noreferrer">
            {row.name ?? row.id}
          </a>
        ) : (
          (row.name ?? row.id)
        );
      },
    },
    {
      title: "Action",
      dataIndex: "action",
      key: "action",
      hidden: !visibleColumns.includes("action"),
      width: 140,
      filterDropdown: actionFilterDropdown,
      onFilter: (value, record) => {
        if (record.isGroup) return true;
        const vals = Array.isArray(value) ? value : [value];
        if (vals.length === 0) return true;
        return vals.includes(record.action);
      },
      render: (_, row) => {
        if (row.isGroup) return "";
        const options =
          row.entityType === "Chain"
            ? ACTION_OPTIONS_CHAIN
            : ACTION_OPTIONS_SERVICE_OR_VAR;
        const isEditing =
          editingRowKey === row.key && editingField === "action";

        if (isEditing) {
          const showOverriddenByInput =
            row.entityType === "Chain" &&
            editingAction === ImportInstructionAction.OVERRIDE;

          const onKeyDown = (e: React.KeyboardEvent) => {
            if (e.key === "Escape") {
              e.preventDefault();
              setEditingRowKey(null);
              setEditingField(null);
            } else if (e.key === "Enter" && !showOverriddenByInput) {
              e.preventDefault();
              void handleUpdateAction(row, editingAction);
              setEditingRowKey(null);
              setEditingField(null);
            }
          };

          return (
            <InlineEditWithButtons
              showButtons={!showOverriddenByInput}
              onKeyDown={onKeyDown}
              onApply={() => {
                void handleUpdateAction(row, editingAction);
                setEditingRowKey(null);
                setEditingField(null);
              }}
              onCancel={() => {
                setEditingRowKey(null);
                setEditingField(null);
              }}
            >
              <Select<ImportInstructionAction>
                size="small"
                autoFocus
                style={{ width: "100%", minWidth: 100 }}
                options={options}
                value={editingAction}
                onChange={setEditingAction}
                onKeyDown={(e) => e.stopPropagation()}
              />
            </InlineEditWithButtons>
          );
        }

        const startEditAction = () => {
          setEditingRowKey(row.key);
          setEditingField("action");
          setEditingAction(row.action);
          setEditingOverriddenBy(
            row.overriddenById ?? row.overriddenByName ?? "",
          );
        };
        return (
          <EditableCellTrigger
            onClick={startEditAction}
            style={{ paddingInlineEnd: 24 }}
          >
            {capitalize(row.action.replace("_", " "))}
            <OverridableIcon
              name="edit"
              className={editableCellStyles.inlineIcon}
            />
          </EditableCellTrigger>
        );
      },
    },
    {
      title: "Overridden By",
      dataIndex: "overriddenByName",
      key: "overriddenBy",
      hidden: !visibleColumns.includes("overriddenBy"),
      width: 200,
      filterDropdown: OverriddenByColumnFilterDropdown,
      onFilter: (value, record) => {
        if (record.isGroup) return true;
        return getTextColumnFilterFn<InstructionRow>(
          (r) => r.overriddenById ?? r.overriddenByName ?? "",
        )(value, record);
      },
      render: (_, row) => {
        if (row.isGroup) return "";
        const isOverrideRow =
          row.action === ImportInstructionAction.OVERRIDE ||
          (editingRowKey === row.key &&
            editingField === "action" &&
            editingAction === ImportInstructionAction.OVERRIDE &&
            row.entityType === "Chain");
        if (!isOverrideRow) return "";
        const isEditing =
          (editingRowKey === row.key && editingField === "overriddenBy") ||
          (editingRowKey === row.key &&
            editingField === "action" &&
            editingAction === ImportInstructionAction.OVERRIDE);

        if (isEditing) {
          const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
            if (e.key === "Escape") {
              e.preventDefault();
              setEditingRowKey(null);
              setEditingField(null);
            } else if (e.key === "Enter") {
              e.preventDefault();
              void handleUpdateAction(
                row,
                ImportInstructionAction.OVERRIDE,
                editingOverriddenBy || null,
              );
              setEditingRowKey(null);
              setEditingField(null);
            }
          };

          return (
            <InlineEditWithButtons
              onApply={() => {
                void handleUpdateAction(
                  row,
                  ImportInstructionAction.OVERRIDE,
                  editingOverriddenBy || null,
                );
                setEditingRowKey(null);
                setEditingField(null);
              }}
              onCancel={() => {
                setEditingRowKey(null);
                setEditingField(null);
              }}
            >
              <Input
                size="small"
                autoFocus
                value={editingOverriddenBy}
                onChange={(e) => setEditingOverriddenBy(e.target.value)}
                onKeyDown={onKeyDown}
                style={{ flex: 1, minWidth: 100 }}
              />
            </InlineEditWithButtons>
          );
        }

        const startEditOverriddenBy = () => {
          setEditingRowKey(row.key);
          setEditingField("overriddenBy");
          setEditingOverriddenBy(
            row.overriddenById ?? row.overriddenByName ?? "",
          );
        };
        return (
          <EditableCellTrigger
            onClick={startEditOverriddenBy}
            style={{ paddingInlineEnd: 24 }}
          >
            {row.overriddenByName ?? row.overriddenById ?? "—"}
            <OverridableIcon
              name="edit"
              className={editableCellStyles.inlineIcon}
            />
          </EditableCellTrigger>
        );
      },
    },
    {
      title: "Labels",
      dataIndex: "labels",
      key: "labels",
      hidden: !visibleColumns.includes("labels"),
      width: 180,
      filterDropdown: LabelsColumnFilterDropdown,
      onFilter: (value, record) => {
        if (record.isGroup) return true;
        return getTextListColumnFilterFn<InstructionRow>((r) => r.labels ?? [])(
          value,
          record,
        );
      },
      render: (_, row) => {
        if (row.isGroup) return "";
        return (
          <Space size={[0, 4]} wrap>
            {(row.labels ?? []).slice(0, 3).map((l) => (
              <Tag key={l}>{l}</Tag>
            ))}
            {(row.labels?.length ?? 0) > 3 && (
              <Tag>+{(row.labels?.length ?? 0) - 3}</Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: "Modified When",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      hidden: !visibleColumns.includes("modifiedWhen"),
      width: 160,
      filterDropdown: ModifiedWhenColumnFilterDropdown,
      onFilter: (value, record) => {
        if (record.isGroup) return true;
        return getTimestampColumnFilterFn<InstructionRow>(
          (r) => r.modifiedWhen ?? 0,
        )(value, record);
      },
      render: (_, row) => {
        if (row.isGroup) return "";
        return row.modifiedWhen
          ? dayjs(row.modifiedWhen).format("YYYY-MM-DD HH:mm")
          : "—";
      },
    },
  ];

  const rowSelection: TableProps<InstructionRow>["rowSelection"] = {
    selectedRowKeys: selectedRowKeys,
    onChange: (keys) => setSelectedRowKeys(keys ?? []),
    getCheckboxProps: (record) => ({
      disabled: record.isGroup,
    }),
  };

  return (
    <Flex vertical className={commonStyles.container}>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Title level={4} className={commonStyles.title}>
          <OverridableIcon
            name="importInstructions"
            className={commonStyles.icon}
          />
          <span style={{ display: "inline-block", verticalAlign: "middle" }}>
            Import
            <br />
            Instructions
          </span>
        </Title>
        <Flex gap={8} align="center">
          <CompactSearch
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder="Search..."
            allowClear
            style={{ width: 200 }}
          />
          <Dropdown
            menu={{
              items: COLUMN_VISIBILITY_MENU_ITEMS,
              selectable: true,
              multiple: true,
              selectedKeys: visibleColumns,
              onSelect: ({ selectedKeys }) =>
                setVisibleColumns(selectedKeys ?? []),
              onDeselect: ({ selectedKeys }) =>
                setVisibleColumns(selectedKeys ?? []),
            }}
          >
            <Tooltip title="Column settings">
              <Button
                data-testid="import-instructions-column-settings"
                icon={<OverridableIcon name="settings" />}
              />
            </Tooltip>
          </Dropdown>
          <Tooltip title="Delete selected">
            <Button
              data-testid="import-instructions-delete"
              icon={<OverridableIcon name="delete" />}
              disabled={selectedRowKeys.length === 0}
              onClick={() => {
                if (selectedRowKeys.length > 0) {
                  Modal.confirm({
                    title: "Delete instructions",
                    content: `Are you sure you want to delete ${selectedRowKeys.length} instruction(s)?`,
                    onOk: handleDelete,
                    okText: "Delete",
                  });
                }
              }}
            />
          </Tooltip>
          <Tooltip title="Export import instructions">
            <Button
              data-testid="import-instructions-export"
              icon={<OverridableIcon name="cloudDownload" />}
              loading={exportLoading}
              onClick={() => void handleExport()}
            />
          </Tooltip>
          <Tooltip title="Upload import instructions file">
            <Button
              data-testid="import-instructions-upload"
              icon={<OverridableIcon name="cloudUpload" />}
              onClick={() => setUploadModalVisible(true)}
            />
          </Tooltip>
          <Button
            type="primary"
            icon={<OverridableIcon name="plus" />}
            onClick={() => setAddModalVisible(true)}
          >
            Add
          </Button>
        </Flex>
      </Flex>

      <div className={commonStyles["table-wrapper"]}>
        {loading ? (
          <Flex justify="center" align="center" style={{ flex: 1 }}>
            <Spin size="large" />
          </Flex>
        ) : (
          <Table<InstructionRow>
            size="small"
            rowKey="key"
            columns={columns}
            dataSource={tableData}
            rowSelection={rowSelection}
            pagination={false}
            scroll={{ y: "calc(100vh - 280px)" }}
            expandable={{ defaultExpandAllRows: true }}
          />
        )}
      </div>

      {addModalVisible && (
        <AddInstructionModal
          onClose={() => setAddModalVisible(false)}
          onSuccess={() => {
            setAddModalVisible(false);
            void fetchInstructions();
          }}
        />
      )}

      {uploadModalVisible && (
        <UploadInstructionsModal
          onClose={() => {
            setUploadModalVisible(false);
            void fetchInstructions();
          }}
        />
      )}
    </Flex>
  );
};

type AddInstructionModalProps = {
  onClose: () => void;
  onSuccess: () => void;
};

const AddInstructionModal: React.FC<AddInstructionModalProps> = ({
  onClose,
  onSuccess,
}) => {
  const [form] = Form.useForm<{
    id: string;
    entityType: InstructionEntityType;
    action: ImportInstructionAction;
    overriddenBy?: string;
  }>();
  const [loading, setLoading] = useState(false);
  const notificationService = useNotificationService();
  const entityType = Form.useWatch("entityType", form);
  const action = Form.useWatch("action", form);

  useEffect(() => {
    if (
      entityType &&
      entityType !== "Chain" &&
      action === ImportInstructionAction.OVERRIDE
    ) {
      form.setFieldValue("action", ImportInstructionAction.IGNORE);
    }
  }, [entityType, action, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);
      await api.addImportInstruction({
        id: values.id.trim(),
        entityType: ENTITY_TO_API[values.entityType],
        action: values.action,
        overriddenBy:
          values.entityType === "Chain" &&
          values.action === ImportInstructionAction.OVERRIDE
            ? (values.overriddenBy?.trim() ?? null)
            : undefined,
      });
      onSuccess();
    } catch (error) {
      if (error && typeof error === "object" && "errorFields" in error) return;
      notificationService.requestFailed(
        "Failed to add import instruction",
        error,
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Add Instruction"
      open
      onCancel={onClose}
      footer={[
        <Button key="cancel" onClick={onClose}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          loading={loading}
          onClick={() => void handleSubmit()}
        >
          Add
        </Button>,
      ]}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          entityType: "Chain",
          action: ImportInstructionAction.IGNORE,
        }}
      >
        <Form.Item
          name="id"
          label="Id"
          rules={[{ required: true, message: "Id is required" }]}
        >
          <Input placeholder="Enter id" />
        </Form.Item>
        <Form.Item
          name="entityType"
          label="Entity Type"
          rules={[{ required: true }]}
        >
          <Select
            options={[
              { label: "Chain", value: "Chain" },
              { label: "Service", value: "Service" },
              { label: "Common Variable", value: "Common Variable" },
            ]}
          />
        </Form.Item>
        <Form.Item name="action" label="Action" rules={[{ required: true }]}>
          <Select
            options={
              entityType === "Chain"
                ? ACTION_OPTIONS_CHAIN
                : ACTION_OPTIONS_SERVICE_OR_VAR
            }
          />
        </Form.Item>
        {entityType === "Chain" &&
          action === ImportInstructionAction.OVERRIDE && (
            <Form.Item
              name="overriddenBy"
              label="Overridden By"
              rules={[
                {
                  required: true,
                  message: "Overridden By is required when Action is Override",
                },
              ]}
            >
              <Input placeholder="Enter overridden by id" />
            </Form.Item>
          )}
      </Form>
    </Modal>
  );
};

type UploadInstructionsModalProps = {
  onClose: () => void;
};

const UploadInstructionsModal: React.FC<UploadInstructionsModalProps> = ({
  onClose,
}) => {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ImportInstructionResult[] | null>(null);
  const notificationService = useNotificationService();

  const handleUpload = async () => {
    const file = fileList?.[0];
    const rawFile =
      file && "originFileObj" in file ? file.originFileObj : undefined;
    if (!rawFile) return;
    setUploading(true);
    try {
      const results = await api.uploadImportInstructions(rawFile);
      setResult(results);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to upload import instructions",
        error,
      );
    } finally {
      setUploading(false);
    }
  };

  return (
    <Modal
      title="Upload Instructions (yaml, yml)"
      open
      onCancel={onClose}
      width={600}
      footer={[
        <Button key="close" onClick={onClose}>
          Close
        </Button>,
        result === null ? (
          <Button
            key="upload"
            type="primary"
            loading={uploading}
            disabled={!fileList.length}
            onClick={() => void handleUpload()}
          >
            Upload
          </Button>
        ) : null,
      ]}
    >
      {result === null ? (
        <Dragger
          multiple={false}
          accept=".yaml,.yml"
          fileList={fileList ?? []}
          beforeUpload={() => false}
          onChange={(info) => setFileList(info.fileList.slice(-1))}
        >
          <p className="ant-upload-drag-icon">
            <OverridableIcon name="inbox" />
          </p>
          <p className="ant-upload-text">Click or drag file to upload</p>
        </Dragger>
      ) : (
        <Table
          size="small"
          rowKey={(r) => `${r.entityType}-${r.id}`}
          columns={[
            { title: "Id", dataIndex: "name", key: "id" },
            {
              title: "Status",
              dataIndex: "status",
              key: "status",
              render: (_status, row: ImportInstructionResult) => (
                <ImportStatus status={row.status} message={row.errorMessage} />
              ),
            },
          ]}
          dataSource={result}
          pagination={false}
        />
      )}
    </Modal>
  );
};
