import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Dropdown,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Table,
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
import { InlineEdit } from "../InlineEdit.tsx";
import { InlineEditWithButtons } from "../InlineEditWithButtons.tsx";
import { SelectEdit } from "../table/SelectEdit.tsx";
import { TextValueEdit } from "../table/TextValueEdit.tsx";
import inlineEditStyles from "../InlineEdit.module.css";
import { CompactSearch } from "../table/CompactSearch.tsx";
import {
  formatSnakeCased,
  formatTimestampShort,
  PLACEHOLDER,
} from "../../misc/format-utils.ts";
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
import { downloadFile } from "../../misc/download-utils.ts";
import type { FilterDropdownProps } from "antd/lib/table/interface";
import { EntityLabels } from "../labels/EntityLabels.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { usePermissions } from "../../permissions/usePermissions.tsx";
import { hasPermissions } from "../../permissions/funcs.ts";

const { Title } = Typography;

// Ant Design requires a stable component reference for filterDropdown.
// Passing an inline function causes the filter popover to reset on every re-render.
const TextFilterDropdown: React.FC<FilterDropdownProps> = (props) => (
  <TextColumnFilterDropdown {...props} />
);
const TimestampFilterDropdown: React.FC<FilterDropdownProps> = (props) => (
  <TimestampColumnFilterDropdown {...props} />
);

type InstructionEntityType = "Chain" | "Service" | "Common Variable";

type InstructionRow = {
  key: string;
  id: string;
  name?: string;
  entityType: InstructionEntityType;
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

const { filterDropdown: ACTION_FILTER_DROPDOWN } = makeEnumColumnFilterDropdown<
  "action",
  ImportInstructionAction
>(ACTION_FILTER_OPTIONS, "action", true);

type ApiItem = {
  id: string;
  name?: string;
  overriddenById?: string;
  overriddenByName?: string;
  labels?: string[];
  modifiedWhen?: number;
};

function toInstructionRow(
  i: ApiItem,
  entityType: InstructionEntityType,
  action: ImportInstructionAction,
): InstructionRow {
  return {
    key: `${entityType}-${action}-${i.id}`,
    id: i.id,
    name: i.name ?? i.id,
    entityType,
    action,
    overriddenById: i.overriddenById,
    overriddenByName: i.overriddenByName,
    labels: i.labels,
    modifiedWhen: i.modifiedWhen,
    isGroup: false,
  };
}

export function buildTableData(
  instructions: GeneralImportInstructions | undefined,
): InstructionRow[] {
  if (!instructions) return [];

  const sortById = (a: InstructionRow, b: InstructionRow) =>
    (a.name ?? a.id).localeCompare(b.name ?? b.id);

  const chainChildren: InstructionRow[] = [
    ...(instructions.chains?.ignore ?? [])
      .map((item) =>
        toInstructionRow(item, "Chain", ImportInstructionAction.IGNORE),
      )
      .sort(sortById),
    ...(instructions.chains?.override ?? [])
      .map((item) =>
        toInstructionRow(item, "Chain", ImportInstructionAction.OVERRIDE),
      )
      .sort(sortById),
    ...(instructions.chains?.delete ?? [])
      .map((item) =>
        toInstructionRow(item, "Chain", ImportInstructionAction.DELETE),
      )
      .sort(sortById),
  ];

  const serviceChildren: InstructionRow[] = [
    ...(instructions.services?.ignore ?? [])
      .map((item) =>
        toInstructionRow(item, "Service", ImportInstructionAction.IGNORE),
      )
      .sort(sortById),
    ...(instructions.services?.delete ?? [])
      .map((item) =>
        toInstructionRow(item, "Service", ImportInstructionAction.DELETE),
      )
      .sort(sortById),
  ];

  const varChildren: InstructionRow[] = [
    ...(instructions.commonVariables?.ignore ?? [])
      .map((item) =>
        toInstructionRow(
          item,
          "Common Variable",
          ImportInstructionAction.IGNORE,
        ),
      )
      .sort(sortById),
    ...(instructions.commonVariables?.delete ?? [])
      .map((item) =>
        toInstructionRow(
          item,
          "Common Variable",
          ImportInstructionAction.DELETE,
        ),
      )
      .sort(sortById),
  ];

  return [
    {
      key: "Chain",
      id: "Chain",
      entityType: "Chain",
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: chainChildren.length > 0 ? chainChildren : undefined,
    },
    {
      key: "Service",
      id: "Service",
      entityType: "Service",
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: serviceChildren.length > 0 ? serviceChildren : undefined,
    },
    {
      key: "Common Variable",
      id: "Common Variable",
      entityType: "Common Variable",
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: varChildren.length > 0 ? varChildren : undefined,
    },
  ];
}

type CollectedIds = {
  chains: string[];
  services: string[];
  commonVariables: string[];
};

function collectSelectedIds(
  tableData: InstructionRow[],
  selectedRowKeys: React.Key[],
): CollectedIds {
  const chains: string[] = [];
  const services: string[] = [];
  const commonVariables: string[] = [];
  // Table data is intentionally two levels deep: top-level groups contain leaf rows.
  for (const group of tableData) {
    for (const child of group.children ?? []) {
      if (!selectedRowKeys.includes(child.key)) continue;
      if (child.entityType === "Chain") chains.push(child.id);
      else if (child.entityType === "Service") services.push(child.id);
      else commonVariables.push(child.id);
    }
  }
  return { chains, services, commonVariables };
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
  const [pendingOverrideRowKey, setPendingOverrideRowKey] = useState<
    string | null
  >(null);
  const [visibleColumns, setVisibleColumns] = useState<string[]>([
    "id",
    "action",
    "overriddenBy",
    "labels",
    "modifiedWhen",
  ]);
  const permissions = usePermissions();
  const [enableEdit, setEnableEdit] = useState<boolean>(false);

  useEffect(() => {
    setEnableEdit(
      hasPermissions(permissions, { importInstructions: ["update"] }),
    );
  }, [permissions]);

  const fetchInstructions = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.getImportInstructions();
      setInstructions(data);
    } catch (err: unknown) {
      notificationService.requestFailed(
        "Failed to load import instructions",
        err,
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
          entityType: ENTITY_TO_API[row.entityType],
          action: newAction,
          overriddenBy: overriddenBy ?? undefined,
        });
        await fetchInstructions();
      } catch (err: unknown) {
        notificationService.requestFailed(
          "Failed to update import instruction",
          err,
        );
      }
    },
    [fetchInstructions, notificationService],
  );

  const handleExport = useCallback(async () => {
    setExportLoading(true);
    try {
      const file = await api.exportImportInstructions();
      downloadFile(file, "import-instructions.yaml");
    } catch (err: unknown) {
      notificationService.requestFailed(
        "Failed to export import instructions",
        err,
      );
    } finally {
      setExportLoading(false);
    }
  }, [notificationService]);

  const handleDelete = useCallback(async () => {
    const { chains, services, commonVariables } = collectSelectedIds(
      tableData,
      selectedRowKeys,
    );
    if (!chains.length && !services.length && !commonVariables.length) return;
    try {
      await api.deleteImportInstructions({
        chains: chains.length ? chains : undefined,
        services: services.length ? services : undefined,
        commonVariables: commonVariables.length ? commonVariables : undefined,
      });
      setSelectedRowKeys([]);
      await fetchInstructions();
    } catch (err: unknown) {
      notificationService.requestFailed(
        "Failed to delete import instructions",
        err,
      );
    }
  }, [selectedRowKeys, tableData, fetchInstructions, notificationService]);

  const columns: TableProps<InstructionRow>["columns"] = useMemo(() => {
    return [
      {
        title: "Id",
        dataIndex: "id",
        key: "id",
        width: 280,
        sorter: (a, b) =>
          (a.name ?? a.id).localeCompare(b.name ?? b.id, undefined, {
            sensitivity: "base",
          }),
        filterDropdown: TextFilterDropdown,
        onFilter: (value: React.Key | boolean, record: InstructionRow) => {
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
                  className={commonStyles.iconInline as string}
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
        width: 140,
        sorter: (a, b) =>
          String(a.action).localeCompare(String(b.action), undefined, {
            sensitivity: "base",
          }),
        filterDropdown: ACTION_FILTER_DROPDOWN,
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
          const displayAction =
            pendingOverrideRowKey === row.key
              ? ImportInstructionAction.OVERRIDE
              : row.action;
          if (!enableEdit) {
            return <span>{formatSnakeCased(displayAction)}</span>;
          }
          return (
            <InlineEdit<{ action: ImportInstructionAction }>
              values={{ action: displayAction }}
              editor={
                <SelectEdit
                  name="action"
                  options={options}
                  onChangeSideEffect={(value) => {
                    if (
                      value === ImportInstructionAction.OVERRIDE &&
                      row.entityType === "Chain"
                    ) {
                      setPendingOverrideRowKey(row.key);
                    }
                  }}
                  shouldSubmitOnChange={(value) =>
                    !(
                      value === ImportInstructionAction.OVERRIDE &&
                      row.entityType === "Chain"
                    )
                  }
                />
              }
              viewer={
                <span>
                  {formatSnakeCased(
                    pendingOverrideRowKey === row.key
                      ? ImportInstructionAction.OVERRIDE
                      : row.action,
                  )}
                  <OverridableIcon
                    name="edit"
                    className={inlineEditStyles.inlineIcon as string}
                  />
                </span>
              }
              onSubmit={async ({ action }) => {
                await handleUpdateAction(row, action);
              }}
            />
          );
        },
      },
      {
        title: "Overridden By",
        dataIndex: "overriddenByName",
        key: "overriddenBy",
        width: 200,
        sorter: (a, b) =>
          (a.overriddenByName ?? a.overriddenById ?? "").localeCompare(
            b.overriddenByName ?? b.overriddenById ?? "",
            undefined,
            { sensitivity: "base" },
          ),
        filterDropdown: TextFilterDropdown,
        onFilter: (value: React.Key | boolean, record: InstructionRow) => {
          if (record.isGroup) return true;
          return getTextColumnFilterFn<InstructionRow>(
            (r) => r.overriddenById ?? r.overriddenByName ?? "",
          )(value, record);
        },
        render: (_, row) => {
          if (row.isGroup) return "";
          const showOverriddenBy =
            row.action === ImportInstructionAction.OVERRIDE ||
            pendingOverrideRowKey === row.key;
          if (!showOverriddenBy) return "";
          if (!enableEdit) {
            return (
              <span>
                {row.overriddenByName ?? row.overriddenById ?? PLACEHOLDER}
              </span>
            );
          }

          return (
            <InlineEditWithButtons<{ overriddenBy: string }>
              values={{
                overriddenBy: row.overriddenById ?? row.overriddenByName ?? "",
              }}
              initialActive={pendingOverrideRowKey === row.key}
              editor={<TextValueEdit name="overriddenBy" rules={[]} />}
              viewer={
                <span>
                  {row.overriddenByName ?? row.overriddenById ?? PLACEHOLDER}
                  <OverridableIcon
                    name="edit"
                    className={inlineEditStyles.inlineIcon as string}
                  />
                </span>
              }
              onSubmit={async ({ overriddenBy }) => {
                setPendingOverrideRowKey(null);
                await handleUpdateAction(
                  row,
                  ImportInstructionAction.OVERRIDE,
                  overriddenBy || null,
                );
              }}
              onCancel={() => {
                setPendingOverrideRowKey(null);
              }}
            />
          );
        },
      },
      {
        title: "Labels",
        dataIndex: "labels",
        key: "labels",
        width: 180,
        sorter: (a, b) =>
          (a.labels ?? [])
            .join(",")
            .localeCompare((b.labels ?? []).join(","), undefined, {
              sensitivity: "base",
            }),
        filterDropdown: TextFilterDropdown,
        onFilter: (value: React.Key | boolean, record: InstructionRow) => {
          if (record.isGroup) return true;
          return getTextListColumnFilterFn<InstructionRow>(
            (r) => r.labels ?? [],
          )(value, record);
        },
        render: (_, row) => {
          if (row.isGroup) return "";
          return (
            <EntityLabels
              labels={(row.labels ?? []).map((name) => ({
                name,
                technical: false,
              }))}
            />
          );
        },
      },
      {
        title: "Modified When",
        key: "modifiedWhen",
        width: 160,
        filterDropdown: TimestampFilterDropdown,
        onFilter: (value: React.Key | boolean, record: InstructionRow) => {
          if (record.isGroup) return true;
          return getTimestampColumnFilterFn<InstructionRow>(
            (r) => r.modifiedWhen ?? 0,
          )(value, record);
        },
        render: (_: unknown, row: InstructionRow) => {
          if (row.isGroup) return "";
          const ts = row.modifiedWhen;
          if (ts == null) return PLACEHOLDER;
          return formatTimestampShort(ts);
        },
      },
    ];
  }, [handleUpdateAction, pendingOverrideRowKey, enableEdit]);

  const visibleColumnsFiltered = useMemo(
    () =>
      columns.filter((col) =>
        visibleColumns.includes((col.key ?? "") as string),
      ),
    [columns, visibleColumns],
  );

  const rowSelection = useMemo<TableProps<InstructionRow>["rowSelection"]>(
    () => ({
      selectedRowKeys,
      onChange: (keys) => setSelectedRowKeys(keys ?? []),
      getCheckboxProps: (record) => ({ disabled: record.isGroup }),
    }),
    [selectedRowKeys],
  );

  return (
    <Flex vertical className={commonStyles.container}>
      <Flex className={commonStyles.header}>
        <Title level={4} className={commonStyles.title}>
          <OverridableIcon
            name="importInstructions"
            className={commonStyles.icon}
          />
          Import Instructions
        </Title>
        <Flex
          vertical={false}
          gap={8}
          className={commonStyles.actions}
          align="center"
        >
          <CompactSearch
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder="Search..."
            allowClear
            className={commonStyles.searchField as string}
          />
          <Dropdown
            menu={{
              items: COLUMN_VISIBILITY_MENU_ITEMS,
              selectable: true,
              multiple: true,
              selectedKeys: visibleColumns,
              onSelect: ({ selectedKeys }) => {
                setVisibleColumns((selectedKeys ?? []).map(String));
              },
              onDeselect: ({ selectedKeys }) => {
                setVisibleColumns((selectedKeys ?? []).map(String));
              },
            }}
          >
            <Tooltip title="Column settings">
              <Button
                data-testid="import-instructions-column-settings"
                icon={<OverridableIcon name="settings" />}
              />
            </Tooltip>
          </Dropdown>
          <ProtectedButton
            require={{ importInstructions: ["delete"] }}
            tooltipProps={{ title: "Delete selected" }}
            buttonProps={{
              "data-testid": "import-instructions-delete",
              iconName: "delete",
              disabled: selectedRowKeys.length === 0,
              onClick: () => {
                if (selectedRowKeys.length > 0) {
                  Modal.confirm({
                    title: "Delete instructions",
                    content: `Are you sure you want to delete ${selectedRowKeys.length} instruction(s)?`,
                    onOk: handleDelete,
                    okText: "Delete",
                  });
                }
              },
            }}
          />
          <ProtectedButton
            require={{ importInstructions: ["export"] }}
            tooltipProps={{ title: "Export import instructions" }}
            buttonProps={{
              "data-testid": "import-instructions-export",
              iconName: "cloudDownload",
              loading: exportLoading,
              onClick: () => void handleExport(),
            }}
          />
          <ProtectedButton
            require={{ importInstructions: ["import"] }}
            tooltipProps={{ title: "Upload import instructions file" }}
            buttonProps={{
              "data-testid": "import-instructions-upload",
              iconName: "cloudUpload",
              onClick: () => setUploadModalVisible(true),
            }}
          />
          <ProtectedButton
            require={{ importInstructions: ["create"] }}
            tooltipProps={{}}
            buttonProps={{
              type: "primary",
              iconName: "plus",
              onClick: () => setAddModalVisible(true),
              children: "Add",
            }}
          />
        </Flex>
      </Flex>

      <div className={commonStyles["table-wrapper"]}>
        <Table<InstructionRow>
          className="flex-table"
          size="small"
          rowKey="key"
          columns={visibleColumnsFiltered}
          dataSource={tableData}
          rowSelection={rowSelection}
          pagination={false}
          loading={loading}
          scroll={{ y: "100%" }}
          expandable={{
            defaultExpandAllRows: true,
            defaultExpandedRowKeys: ["Chain", "Service", "Common Variable"],
          }}
        />
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
    } catch (err: unknown) {
      if (err && typeof err === "object" && "errorFields" in err) return;
      notificationService.requestFailed(
        "Failed to add import instruction",
        err,
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Add Instruction"
      open
      destroyOnHidden
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
  const [result, setResult] = useState<ImportInstructionResult[]>([]);
  const [uploaded, setUploaded] = useState(false);
  const notificationService = useNotificationService();

  const handleUpload = async () => {
    const file = fileList[0];
    const rawFile =
      file && "originFileObj" in file ? file.originFileObj : undefined;
    if (!rawFile) return;
    setUploading(true);
    try {
      const results = await api.uploadImportInstructions(rawFile);
      setResult(results);
      setUploaded(true);
    } catch (err: unknown) {
      notificationService.requestFailed(
        "Failed to upload import instructions",
        err,
      );
    } finally {
      setUploading(false);
    }
  };

  return (
    <Modal
      title="Upload Instructions (yaml, yml)"
      open
      destroyOnHidden
      onCancel={onClose}
      width={600}
      footer={[
        <Button key="close" onClick={onClose}>
          Close
        </Button>,
        uploaded ? null : (
          <Button
            key="upload"
            type="primary"
            loading={uploading}
            disabled={!fileList.length}
            onClick={() => void handleUpload()}
          >
            Upload
          </Button>
        ),
      ]}
    >
      {uploaded ? (
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
      ) : (
        <Dragger
          multiple={false}
          accept=".yaml,.yml"
          fileList={fileList}
          beforeUpload={() => false}
          onChange={(info) => setFileList(info.fileList.slice(-1))}
        >
          <p className="ant-upload-drag-icon">
            <OverridableIcon name="inbox" />
          </p>
          <p className="ant-upload-text">Click or drag file to upload</p>
          <p className="ant-upload-hint">
            Supports: YAML files with import instructions
          </p>
        </Dragger>
      )}
    </Modal>
  );
};
