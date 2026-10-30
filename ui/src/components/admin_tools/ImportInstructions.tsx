import { Table } from "antd";
import React, {
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import { Button, Flex, Form, Input, Modal, Select, UploadFile } from "antd";
import Dragger from "antd/es/upload/Dragger";
import type { TableProps } from "antd";
import { confirmAndRun } from "../../misc/confirm-utils.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { treeExpandIcon } from "../table/TreeExpandIcon.tsx";
import commonStyles from "./CommonStyle.module.css";
import { api } from "../../api/api.ts";
import {
  GeneralImportInstructions,
  ImportInstructionAction,
  ImportInstructionResult,
  ImportEntityType,
  ImportInstructionRequest,
} from "../../api/apiTypes.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { StatusTag } from "../labels/StatusTag.tsx";
import { InlineEdit } from "../InlineEdit.tsx";
import { SelectEdit } from "../table/SelectEdit.tsx";
import { TextValueEdit } from "../table/TextValueEdit.tsx";
import inlineEditStyles from "../InlineEdit.module.css";
import { TableToolbar } from "../table/TableToolbar.tsx";
import { AdminToolsHeader } from "./AdminToolsHeader.tsx";
import {
  formatSnakeCased,
  formatTimestampShort,
  PLACEHOLDER,
} from "../../misc/format-utils.ts";
import { downloadFile } from "../../misc/download-utils.ts";
import { EntityLabels } from "../labels/EntityLabels.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { usePermissions } from "../../permissions/usePermissions.tsx";
import { hasPermissions } from "../../permissions/funcs.ts";
import {
  uploadFileHasDeleteInstructions,
  uploadImportInstructionsFile,
} from "./importInstructionsHandlers.ts";
import { useModalsContext } from "../../Modals.tsx";
import { UploadImportInstructionsWarningModal } from "../modal/UploadImportInstructionsWarningModal.tsx";
import {
  EXPAND_COLUMN_WIDTH,
  SELECTION_COLUMN_WIDTH,
  useTableConfiguration,
} from "../table/useTableConfiguration.tsx";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../table/useColumnSettingsButton.tsx";
import { useImportInstructionsFilter } from "../../hooks/filter/useImportInstructionsFilter.ts";
import { useModalContext } from "../../ModalContextProvider.tsx";

const columnSettingsWithTestId = (button: React.ReactNode) => (
  <span data-testid="import-instructions-column-settings">{button}</span>
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

type UploadInstructionResultRow = {
  key: string;
  id: string;
  name: string;
  entityType: ImportEntityType;
  status?: ImportInstructionResult["status"];
  errorMessage?: ImportInstructionResult["errorMessage"];
  isGroup: boolean;
  children?: UploadInstructionResultRow[];
};

const ENTITY_DISPLAY: Record<InstructionEntityType, string> = {
  Chain: "Chains",
  Service: "Services",
  "Common Variable": "Common Variables",
};

const UPLOAD_RESULT_ENTITY_DISPLAY: Record<ImportEntityType, string> = {
  [ImportEntityType.CHAIN]: "Chains",
  [ImportEntityType.SERVICE]: "Services",
  [ImportEntityType.SPECIFICATION_GROUP]: "Specification Groups",
  [ImportEntityType.SPECIFICATION]: "Specifications",
  [ImportEntityType.COMMON_VARIABLE]: "Common Variables",
};

const UPLOAD_RESULT_ENTITY_ICON: Record<ImportEntityType, string> = {
  [ImportEntityType.CHAIN]: "link",
  [ImportEntityType.SERVICE]: "cluster",
  [ImportEntityType.SPECIFICATION_GROUP]: "group",
  [ImportEntityType.SPECIFICATION]: "fileText",
  [ImportEntityType.COMMON_VARIABLE]: "code",
};

const UPLOAD_RESULT_ENTITY_ORDER = Object.values(ImportEntityType);

const ENTITY_ICON: Record<InstructionEntityType, string> = {
  Chain: "chain",
  Service: "cluster",
  "Common Variable": "code",
};

function getEntityHref(row: InstructionRow): string | undefined {
  if (!row.name) return undefined;
  if (row.entityType === "Chain") return `/chains/${row.id}`;
  if (row.entityType === "Service")
    return `/services/systems/${row.id}/parameters`;
  return undefined;
}

function getOverriddenByHref(row: InstructionRow): string | undefined {
  if (!row.overriddenByName) return undefined;
  if (row.entityType === "Chain") return `/chains/${row.overriddenById}`;
  return undefined;
}

function stopInlineEditClick(event: React.MouseEvent<HTMLAnchorElement>): void {
  event.stopPropagation();
}

function stopInlineEditActivation(
  event: React.KeyboardEvent<HTMLAnchorElement>,
): void {
  if (event.key === "Enter" || event.key === " ") {
    event.stopPropagation();
  }
}

function renderOverriddenByViewer(
  row: InstructionRow,
  showEditIcon = false,
): React.ReactNode {
  const value = row.overriddenByName ?? row.overriddenById ?? PLACEHOLDER;
  const href = getOverriddenByHref(row);

  return (
    <span>
      {href ? (
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          onClick={stopInlineEditClick}
          onKeyDown={stopInlineEditActivation}
        >
          {value}
        </a>
      ) : (
        value
      )}
      {showEditIcon && (
        <OverridableIcon name="edit" className={inlineEditStyles.inlineIcon} />
      )}
    </span>
  );
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
    name: i.name,
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

  const groups: InstructionRow[] = [
    {
      key: "Chain",
      id: "Chain",
      entityType: "Chain",
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: chainChildren,
    },
    {
      key: "Service",
      id: "Service",
      entityType: "Service",
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: serviceChildren,
    },
    {
      key: "Common Variable",
      id: "Common Variable",
      entityType: "Common Variable",
      action: ImportInstructionAction.IGNORE,
      isGroup: true,
      children: varChildren,
    },
  ];

  return groups.filter((row) => row.children?.length);
}

export function buildUploadResultTableData(
  results: ImportInstructionResult[],
): UploadInstructionResultRow[] {
  const rowsByEntityType = results.reduce((groups, result, index) => {
    const groupRows = groups.get(result.entityType) ?? [];
    groupRows.push({
      ...result,
      key: `${result.entityType}-${result.id}-${index}`,
      isGroup: false,
    });
    groups.set(result.entityType, groupRows);
    return groups;
  }, new Map<ImportEntityType, UploadInstructionResultRow[]>());

  return UPLOAD_RESULT_ENTITY_ORDER.filter((entityType) =>
    rowsByEntityType.has(entityType),
  ).map((entityType) => ({
    key: `group-${entityType}`,
    id: entityType,
    name: UPLOAD_RESULT_ENTITY_DISPLAY[entityType],
    entityType,
    isGroup: true,
    children: rowsByEntityType.get(entityType),
  }));
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
  return rows
    .map((r) => ({
      ...r,
      children: r.children?.length ? filterChildren(r.children) : [],
    }))
    .filter((r) => r.children.length > 0);
}

export const ImportInstructions: React.FC = () => {
  const notificationService = useNotificationService();
  const [instructions, setInstructions] = useState<
    GeneralImportInstructions | undefined
  >(undefined);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState("");
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [exportLoading, setExportLoading] = useState(false);
  const [pendingOverrideRowKey, setPendingOverrideRowKey] = useState<
    string | null
  >(null);
  const permissions = usePermissions();
  const [enableEdit, setEnableEdit] = useState<boolean>(false);
  const { showModal } = useModalsContext();

  useEffect(() => {
    setEnableEdit(
      hasPermissions(permissions, { importInstructions: ["update"] }),
    );
  }, [permissions]);

  const { filters, filterButton } = useImportInstructionsFilter();

  const fetchInstructions = useCallback(async () => {
    setLoading(true);
    try {
      const data =
        filters.length > 0
          ? await api.filterImportInstructions(filters)
          : await api.getImportInstructions();
      setInstructions(data);
    } catch (err: unknown) {
      notificationService.requestFailed(
        "Failed to load import instructions",
        err,
      );
    } finally {
      setLoading(false);
    }
  }, [notificationService, filters]);

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

  const addInstruction = useCallback(
    async (values: ImportInstructionRequest) => {
      const isOverride =
        values.entityType === ImportEntityType.CHAIN &&
        values.action === ImportInstructionAction.OVERRIDE;
      try {
        await api.addImportInstruction({
          ...values,
          id: values.id.trim(),
          // The hidden Overridden By field keeps its value after the action changes.
          overriddenBy: isOverride ? values.overriddenBy?.trim() : undefined,
        });
      } catch (err: unknown) {
        notificationService.requestFailed(
          "Failed to add import instruction",
          err,
        );
        throw err;
      }
      await fetchInstructions();
    },
    [fetchInstructions, notificationService],
  );

  const columns: ColumnsTypeWithSettings<InstructionRow> = useMemo(() => {
    return [
      {
        title: "Id",
        dataIndex: "id",
        key: "id",
        settings: {
          visibilityLocked: true,
          orderLocked: true,
        },
        width: 280,
        sorter: (a, b) =>
          (a.name ?? a.id).localeCompare(b.name ?? b.id, undefined, {
            sensitivity: "base",
          }),
        render: (_, row) => {
          if (row.isGroup)
            return (
              <strong>
                <OverridableIcon
                  name={ENTITY_ICON[row.entityType]}
                  className={commonStyles.iconInline}
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
                    className={inlineEditStyles.inlineIcon}
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
        render: (_, row) => {
          if (row.isGroup) return "";
          const showOverriddenBy =
            row.action === ImportInstructionAction.OVERRIDE ||
            pendingOverrideRowKey === row.key;
          if (!showOverriddenBy) return "";
          if (!enableEdit) {
            return renderOverriddenByViewer(row);
          }

          return (
            <InlineEdit<{ overriddenBy: string }>
              values={{
                overriddenBy: row.overriddenById ?? row.overriddenByName ?? "",
              }}
              initialActive={pendingOverrideRowKey === row.key}
              editor={<TextValueEdit name="overriddenBy" rules={[]} />}
              viewer={renderOverriddenByViewer(row, true)}
              onSubmit={async ({ overriddenBy }) => {
                setPendingOverrideRowKey(null);
                const target = overriddenBy?.trim();
                // An override with no target turns the instruction into Ignore.
                await handleUpdateAction(
                  row,
                  target
                    ? ImportInstructionAction.OVERRIDE
                    : ImportInstructionAction.IGNORE,
                  target || null,
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
        title: "Modified At",
        key: "modifiedWhen",
        width: 160,
        render: (_: unknown, row: InstructionRow) => {
          if (row.isGroup) return "";
          const ts = row.modifiedWhen;
          if (ts == null) return PLACEHOLDER;
          return formatTimestampShort(ts);
        },
      },
    ];
  }, [handleUpdateAction, pendingOverrideRowKey, enableEdit]);

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<InstructionRow>(
      "importInstructionsTable",
      columns,
    );

  const {
    columnsWithResize: visibleColumnsWithResize,
    components: importInstructionsComponents,
    scrollX: importInstructionsScrollX,
    handleTableChange: handleConfiguredTableChange,
  } = useTableConfiguration(
    orderedColumns,
    {
      id: 280,
      action: 140,
      overriddenBy: 200,
      labels: 200,
      modifiedWhen: 160,
    },
    {
      expandColumnWidth: EXPAND_COLUMN_WIDTH,
      selectionColumnWidth: SELECTION_COLUMN_WIDTH,
    },
    "importInstructionsTable",
  );

  const rowSelection = useMemo<TableProps<InstructionRow>["rowSelection"]>(
    () => ({
      selectedRowKeys,
      onChange: (keys) => setSelectedRowKeys(keys ?? []),
      getCheckboxProps: (record) => ({ disabled: record.isGroup }),
      columnWidth: SELECTION_COLUMN_WIDTH,
    }),
    [selectedRowKeys],
  );

  const importInstructionsToolbar = (
    <TableToolbar
      variant="admin"
      search={{
        value: searchTerm,
        onChange: setSearchTerm,
        placeholder: "Search import instructions...",
      }}
      filterButton={filterButton}
      columnSettingsButton={columnSettingsWithTestId(columnSettingsButton)}
      actions={
        <>
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
              onClick: () => {
                showModal({
                  component: (
                    <UploadInstructionsModal
                      onSubmit={() => {
                        void fetchInstructions();
                      }}
                    />
                  ),
                });
              },
            }}
          />
          <ProtectedButton
            require={{ importInstructions: ["delete"] }}
            tooltipProps={{ title: "Delete selected" }}
            buttonProps={{
              "data-testid": "import-instructions-delete",
              iconName: "delete",
              disabled: selectedRowKeys.length === 0,
              onClick: () => {
                if (selectedRowKeys.length > 0) {
                  confirmAndRun({
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
            require={{ importInstructions: ["create"] }}
            tooltipProps={{}}
            buttonProps={{
              type: "primary",
              onClick: () => {
                showModal({
                  component: <AddInstructionModal onSubmit={addInstruction} />,
                });
              },
              children: "Add Instruction",
            }}
          />
        </>
      }
    />
  );

  return (
    <Flex vertical className={commonStyles.container}>
      <AdminToolsHeader
        title="Import Instructions"
        toolbar={importInstructionsToolbar}
      />

      <div className={commonStyles["table-wrapper"]}>
        <Table<InstructionRow>
          className="flex-table"
          size="small"
          rowKey="key"
          columns={visibleColumnsWithResize}
          dataSource={tableData}
          rowSelection={rowSelection}
          pagination={false}
          loading={loading}
          scroll={{
            x: importInstructionsScrollX,
            y: "100%",
          }}
          components={importInstructionsComponents}
          onChange={handleConfiguredTableChange}
          expandable={{
            expandIcon: treeExpandIcon(),
            columnWidth: EXPAND_COLUMN_WIDTH,
            defaultExpandAllRows: true,
            defaultExpandedRowKeys: ["Chain", "Service", "Common Variable"],
          }}
        />
      </div>
    </Flex>
  );
};

type AddInstructionModalProps = {
  onSubmit: (request: ImportInstructionRequest) => Promise<void>;
};

export const AddInstructionModal: React.FC<AddInstructionModalProps> = ({
  onSubmit,
}): ReactNode => {
  const [form] = Form.useForm<ImportInstructionRequest>();
  const { closeContainingModal } = useModalContext();
  const [loading, setLoading] = useState(false);
  const entityType = Form.useWatch("entityType", form);
  const action = Form.useWatch("action", form);
  const overrideEnabled = useMemo(() => {
    return (
      entityType === ImportEntityType.CHAIN &&
      action === ImportInstructionAction.OVERRIDE
    );
  }, [action, entityType]);

  useEffect(() => {
    if (
      entityType &&
      entityType !== ImportEntityType.CHAIN &&
      action === ImportInstructionAction.OVERRIDE
    ) {
      form.setFieldValue("action", ImportInstructionAction.IGNORE);
    }
  }, [entityType, action, form]);

  const handleSubmit = useCallback(
    async (request: ImportInstructionRequest) => {
      setLoading(true);
      try {
        await onSubmit(request);
        closeContainingModal();
      } catch {
        // onSubmit reports the error; the modal stays open so the user can retry.
      } finally {
        setLoading(false);
      }
    },
    [closeContainingModal, onSubmit],
  );

  const formId = "add-instruction-form";

  return (
    <Modal
      title="Add Instruction"
      open
      destroyOnHidden
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" size="middle" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          htmlType={"submit"}
          size="middle"
          form={formId}
          loading={loading}
        >
          Save
        </Button>,
      ]}
    >
      <Form<ImportInstructionRequest>
        id={formId}
        form={form}
        layout="vertical"
        initialValues={{
          entityType: ImportEntityType.CHAIN,
          action: ImportInstructionAction.IGNORE,
        }}
        onFinish={(values) => void handleSubmit(values)}
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
              { label: "Chain", value: ImportEntityType.CHAIN },
              { label: "Service", value: ImportEntityType.SERVICE },
              {
                label: "Common Variable",
                value: ImportEntityType.COMMON_VARIABLE,
              },
            ]}
          />
        </Form.Item>
        <Form.Item name="action" label="Action" rules={[{ required: true }]}>
          <Select
            options={
              entityType === ImportEntityType.CHAIN
                ? ACTION_OPTIONS_CHAIN
                : ACTION_OPTIONS_SERVICE_OR_VAR
            }
          />
        </Form.Item>
        <Form.Item
          name="overriddenBy"
          label="Overridden By"
          hidden={!overrideEnabled}
          rules={[
            {
              required: overrideEnabled,
              message: "Overridden By is required when Action is Override",
            },
          ]}
        >
          <Input placeholder="Enter overridden by id" />
        </Form.Item>
      </Form>
    </Modal>
  );
};

type UploadInstructionsModalProps = {
  onSubmit: () => void;
};

export const UploadInstructionsModal: React.FC<
  UploadInstructionsModalProps
> = ({ onSubmit }): ReactNode => {
  const { closeContainingModal } = useModalContext();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ImportInstructionResult[]>([]);
  const [uploaded, setUploaded] = useState(false);
  const notificationService = useNotificationService();
  const { showModal } = useModalsContext();

  const uploadResultTableData = useMemo(
    () => buildUploadResultTableData(result),
    [result],
  );

  const uploadResultColumns = useMemo<
    TableProps<UploadInstructionResultRow>["columns"]
  >(
    () => [
      {
        title: "Id",
        dataIndex: "id",
        key: "id",
        render: (_name: unknown, row: UploadInstructionResultRow) =>
          row.isGroup ? (
            <strong>
              <OverridableIcon
                name={UPLOAD_RESULT_ENTITY_ICON[row.entityType]}
                className={commonStyles.iconInline}
              />
              {row.name ?? row.id}
            </strong>
          ) : (
            (row.name ?? row.id)
          ),
      },
      {
        title: "Status",
        dataIndex: "status",
        key: "status",
        render: (_status: unknown, row: UploadInstructionResultRow) =>
          row.isGroup ? null : (
            <StatusTag status={row.status} message={row.errorMessage} />
          ),
      },
    ],
    [],
  );

  const {
    columnsWithResize: uploadColumnsWithResize,
    components: uploadInstructionsComponents,
    scrollX: uploadInstructionsScrollX,
  } = useTableConfiguration(
    uploadResultColumns,
    {
      id: 280,
      status: 200,
    },
    {
      expandColumnWidth: EXPAND_COLUMN_WIDTH,
    },
  );

  const uploadSelectedFile = useCallback(async () => {
    setUploading(true);
    try {
      await uploadImportInstructionsFile(
        fileList,
        api,
        notificationService,
        (results) => {
          setResult(results);
          setUploaded(true);
        },
      );
    } finally {
      setUploading(false);
    }
  }, [fileList, notificationService]);

  const handleUpload = async () => {
    const requiresConfirmation =
      await uploadFileHasDeleteInstructions(fileList);

    if (requiresConfirmation) {
      showModal({
        component: (
          <UploadImportInstructionsWarningModal
            onUpload={() => void uploadSelectedFile()}
          />
        ),
      });
      return;
    }

    await uploadSelectedFile();
  };

  const handleClose = useCallback(() => {
    if (uploaded) {
      onSubmit();
    }
    closeContainingModal();
  }, [closeContainingModal, onSubmit, uploaded]);

  return (
    <Modal
      title="Upload Instructions (yaml, yml)"
      open
      destroyOnHidden
      onCancel={handleClose}
      width={600}
      footer={[
        <Button key="close" onClick={handleClose}>
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
        <Table<UploadInstructionResultRow>
          size="small"
          rowKey="key"
          columns={uploadColumnsWithResize}
          dataSource={uploadResultTableData}
          pagination={false}
          scroll={{ x: uploadInstructionsScrollX }}
          components={uploadInstructionsComponents}
          expandable={{
            expandIcon: treeExpandIcon<UploadInstructionResultRow>(),
            columnWidth: EXPAND_COLUMN_WIDTH,
            defaultExpandAllRows: true,
          }}
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
