import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Table, Button, Modal } from "antd";
import type {
  FilterDropdownProps,
  TableRowSelection,
} from "antd/es/table/interface";
import { treeExpandIcon } from "../table/TreeExpandIcon";
import { formatTimestamp } from "../../misc/format-utils";
import { UsageStatusTag } from "./utils";
import { SourceFlagTag } from "./ui/SourceFlagTag";
import { EntityLabels } from "../labels/EntityLabels";
import {
  EntityLabel,
  IntegrationSystem,
  OperationInfo,
  User,
  IntegrationSystemType,
} from "../../api/apiTypes.ts";
import { useNavigate, useParams } from "react-router-dom";
import {
  getColumnsOrderKey,
  getColumnsVisibleKey,
} from "../table/ColumnsFilter";
import { OperationInfoModal } from "./modals/OperationInfoModal";
import { api } from "../../api/api";
import {
  TextColumnFilterDropdown,
  getTextColumnFilterFn,
} from "../table/TextColumnFilterDropdown.tsx";
import type {
  SpecificationGroup,
  Specification,
  SystemOperation,
  ContextSystem,
} from "../../api/apiTypes";
import { InlineEdit } from "../InlineEdit";
import { LabelsEdit } from "../table/LabelsEdit";
import { ChainColumn } from "./ui/ChainColumn";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { HttpMethod } from "./ui/HttpMethod.tsx";
import { ProtectedDropdown } from "../../permissions/ProtectedDropdown.tsx";
import { ColumnSettingsButton } from "../table/ColumnSettingsButton.tsx";
import type { ActionConfig } from "./serviceRowActions";
import type { ColumnsType } from "antd/lib/table";
import type { SumScrollXExtras } from "../table/useTableColumnResize.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../table/useTableColumnResize.tsx";
import { DEFAULT_ACTIONS_COLUMN_WIDTH } from "../table/actionsColumn.ts";

/** rc-table expand icon column; not in `columns` but affects horizontal layout. */
const SERVICES_TREE_EXPAND_COLUMN_WIDTH = 48;
const SERVICES_TREE_SELECTION_COLUMN_WIDTH = 48;

export type ServiceEntity =
  | IntegrationSystem
  | SpecificationGroup
  | Specification
  | SystemOperation
  | ContextSystem;

export function isIntegrationSystem(
  record: ServiceEntity,
): record is IntegrationSystem {
  return "type" in record && record["type"] !== IntegrationSystemType.CONTEXT;
}

export function isSpecificationGroup(
  record: ServiceEntity,
): record is SpecificationGroup {
  return "systemId" in record && "synchronization" in record;
}

export function isSpecification(
  record: ServiceEntity,
): record is Specification {
  return (
    "specificationGroupId" in record &&
    "version" in record &&
    "source" in record
  );
}

export function isSystemOperation(
  record: ServiceEntity,
): record is SystemOperation {
  return "method" in record && "path" in record && "modelId" in record;
}

export function isContextSystem(
  record: ServiceEntity,
): record is ContextSystem {
  return "type" in record && record["type"] === IntegrationSystemType.CONTEXT;
}

export interface ServicesTableColumn<T extends ServiceEntity = ServiceEntity> {
  key: string;
  title: string;
  dataIndex?: string;
  render?: (value: unknown, record: T, index: number) => React.ReactNode;
  width?: number;
  minWidth?: number;
  align?: "left" | "center" | "right";
  filterDropdown?: (props: FilterDropdownProps) => React.ReactNode;
  onFilter?: (value: React.Key | boolean, record: T) => boolean;
}

export interface ServicesTreeTableProps<
  T extends ServiceEntity = ServiceEntity,
> {
  dataSource: T[];
  columns: string[];
  rowKey: Extract<keyof T, string>;
  loading?: boolean;
  expandable?: {
    expandedRowKeys?: React.Key[];
    onExpand?: (expanded: boolean, record: T) => void;
    rowExpandable?: (record: T) => boolean;
    childrenColumnName?: string;
  };
  size?: "small" | "middle" | "large";
  pagination?: false | object;
  style?: React.CSSProperties;
  scroll?: { y?: string | number };
  className?: string;
  actionsColumn?: ServicesTableColumn;
  enableSelection?: boolean;
  isRootEntity?: (record: T) => boolean;
  onExportSelected?: (selected: T[]) => void;
  allColumns?: string[];
  storageKey?: string;
  defaultVisibleKeys?: string[];
  selectedRowKeys?: React.Key[];
  onSelectedRowKeysChange?: (keys: React.Key[]) => void;
  rowClassName?: (record: T) => string;
  onUpdateLabels?: (record: T, labels: string[]) => Promise<void>;
  onRowClick?: (record: T, event: React.MouseEvent<HTMLElement>) => void;
  /** When set, replaces the title of the `url` column (e.g. URL vs Topic vs Channel). */
  urlColumnTitle?: string;
}

const clickableStyle: React.CSSProperties = {
  fontWeight: 500,
  color: "var(--vscode-textLink-foreground, #1677ff)",
  cursor: "pointer",
};

const iconStyle: React.CSSProperties = {
  fontSize: 22,
  color: "var(--vscode-descriptionForeground, rgba(0, 0, 0, 0.45))",
  marginRight: 8,
  verticalAlign: "middle",
};

function getIcon(record: ServiceEntity): React.JSX.Element | null {
  if (isIntegrationSystem(record)) {
    switch (record.type) {
      case IntegrationSystemType.EXTERNAL:
        return <OverridableIcon name="global" style={iconStyle} />;
      case IntegrationSystemType.INTERNAL:
        return <OverridableIcon name="cloud" style={iconStyle} />;
      case IntegrationSystemType.IMPLEMENTED:
        return <OverridableIcon name="cluster" style={iconStyle} />;
      case IntegrationSystemType.CONTEXT:
        return <OverridableIcon name="database" style={iconStyle} />;
      default:
        return <OverridableIcon name="global" style={iconStyle} />;
    }
  }
  if (isSpecificationGroup(record)) {
    return <OverridableIcon name="inbox" style={iconStyle} />;
  }
  if (isSpecification(record)) {
    return <OverridableIcon name="fileText" style={iconStyle} />;
  }
  return null;
}

function getNavigationUrl(record: ServiceEntity): string | null {
  if (isIntegrationSystem(record)) {
    return `/services/systems/${record.id}/specificationGroups`;
  }

  if (isSpecificationGroup(record)) {
    return `/services/systems/${record.systemId}/specificationGroups/${record.id}/specifications`;
  }

  if (isSpecification(record)) {
    return `/services/systems/${record.systemId}/specificationGroups/${record.specificationGroupId}/specifications/${record.id}/operations`;
  }

  return null;
}

const NameCell: React.FC<{ record: ServiceEntity }> = ({ record }) => {
  const navigate = useNavigate();
  const [modalOpen, setModalOpen] = React.useState(false);
  const [operationInfo, setOperationInfo] = React.useState<
    OperationInfo | undefined
  >(undefined);
  const [loading, setLoading] = React.useState(false);

  const { operationId } = useParams<{
    operationId?: string;
  }>();

  const handleClick = useCallback(() => {
    if (isSystemOperation(record)) {
      const fetchOperationInfo = async () => {
        setLoading(true);
        setModalOpen(true);
        try {
          const info = await api.getOperationInfo(record.id);
          setOperationInfo(info);
        } catch {
          setOperationInfo(undefined);
        } finally {
          setLoading(false);
        }
      };
      void fetchOperationInfo();
    } else {
      const url = getNavigationUrl(record);
      if (url) {
        void navigate(url);
      }
    }
  }, [record, navigate]);

  useEffect(() => {
    if (operationId && operationId === record.id) {
      handleClick();
    }
  }, [operationId, record.id, handleClick]);

  return (
    <>
      <span style={clickableStyle} onClick={() => void handleClick()}>
        {getIcon(record)}
        {record.name}
      </span>
      {modalOpen && isSystemOperation(record) && (
        <OperationInfoModal
          visible={modalOpen}
          onClose={() => setModalOpen(false)}
          operationInfo={operationInfo}
          loading={loading}
        />
      )}
    </>
  );
};

export const getNameColumnRender = () => {
  const renderNameColumn = (
    _value: unknown,
    record: ServiceEntity,
  ): React.ReactNode => <NameCell record={record} />;
  renderNameColumn.displayName = "RenderNameColumn";
  return renderNameColumn;
};

function renderLabelsCell(
  labels: EntityLabel[] = [],
  record: ServiceEntity,
  onUpdateLabels?: (record: ServiceEntity, labels: string[]) => Promise<void>,
): React.ReactNode {
  const filtered = labels.filter((l) => !l.technical);

  if (onUpdateLabels) {
    return (
      <div
        className="inline-edit-labels"
        style={{ overflow: "hidden", maxWidth: "100%" }}
      >
        <InlineEdit
          values={{ labels: filtered.map((l) => l.name) }}
          editor={<LabelsEdit name="labels" />}
          viewer={<EntityLabels labels={labels} />}
          onSubmit={async ({ labels: newLabels }) => {
            await onUpdateLabels(record, newLabels);
          }}
        />
      </div>
    );
  }

  return labels.length > 0 ? labels.map((l) => l.name).join(", ") : "";
}

function getLabelsColumn(
  onUpdateLabels?: (record: ServiceEntity, labels: string[]) => Promise<void>,
): ServicesTableColumn {
  const base = allServicesTreeTableColumns.find((c) => c.key === "labels");
  return {
    ...base,
    title: "Labels",
    dataIndex: "labels",
    key: "labels",
    render: (value, record) => {
      const labels = Array.isArray(value) ? (value as EntityLabel[]) : [];
      return renderLabelsCell(labels, record, onUpdateLabels);
    },
  };
}

export const allServicesTreeTableColumns: ServicesTableColumn<ServiceEntity>[] =
  [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      width: 200,
      minWidth: 120,
      filterDropdown: (props: FilterDropdownProps) => (
        <TextColumnFilterDropdown {...props} />
      ),
      onFilter: getTextColumnFilterFn((record) => record.name),
      render: getNameColumnRender(),
    },
    {
      title: "Protocol",
      dataIndex: "protocol",
      key: "protocol",
      width: 120,
      render: (text) => (
        <SourceFlagTag
          source={typeof text === "string" ? text : ""}
          toUpperCase={true}
        />
      ),
    },
    {
      title: "Extended Protocol",
      dataIndex: "extendedProtocol",
      key: "extendedProtocol",
      width: 150,
      render: (value: unknown) => (typeof value === "string" ? value : ""),
    },
    {
      title: "Specification",
      dataIndex: "specification",
      key: "specification",
      width: 150,
      render: (value: unknown) => (typeof value === "string" ? value : ""),
    },
    {
      title: "Internal Service Name",
      dataIndex: "internalServiceName",
      key: "internalServiceName",
      width: 180,
      render: (value: unknown) => (typeof value === "string" ? value : ""),
    },
    {
      title: "Status",
      dataIndex: "status",
      key: "status",
      width: 100,
      render: (_value, record) => <UsageStatusTag element={record} />,
    },
    {
      title: "Source",
      dataIndex: "source",
      key: "source",
      width: 100,
      render: (value) =>
        typeof value === "string" ? <SourceFlagTag source={value} /> : "",
    },
    {
      title: "Labels",
      dataIndex: "labels",
      key: "labels",
      width: 200,
      render: undefined,
    },
    {
      title: "Used by",
      dataIndex: "usedBy",
      key: "usedBy",
      width: 120,
      render: (_: unknown, record: ServiceEntity) => {
        if (isIntegrationSystem(record))
          return (
            <div
              style={{
                display: "flex",
                justifyContent: "center",
                alignItems: "center",
                height: "100%",
              }}
            ></div>
          );
        const chains =
          "chains" in record && Array.isArray(record.chains)
            ? record.chains
            : [];
        return (
          <div
            style={{
              display: "flex",
              justifyContent: "center",
              alignItems: "center",
              height: "100%",
            }}
          >
            <ChainColumn chains={chains} />
          </div>
        );
      },
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      width: 160,
      render: (createdWhen) => <>{formatTimestamp(createdWhen as number)}</>,
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      width: 130,
      render: (value: unknown) => {
        const createdBy = value as User | undefined;
        return createdBy?.username || "";
      },
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      width: 160,
      render: (modifiedWhen) => <>{formatTimestamp(modifiedWhen as number)}</>,
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      width: 130,
      render: (value: unknown) => {
        const modifiedBy = value as User | undefined;
        return modifiedBy?.username || "";
      },
    },
    {
      title: "Method",
      dataIndex: "method",
      key: "method",
      width: 100,
      render: (value: unknown) => <HttpMethod value={value} />,
    },
    {
      title: "URL",
      dataIndex: "url",
      key: "url",
      width: 200,
      render: (_value: unknown, record: ServiceEntity) => {
        if (!isSystemOperation(record)) return "";
        if (record.topic) return record.topic;
        if (record.channel) return record.channel;
        return record.path ?? "";
      },
    },
  ];

const serviceTreeResizeWidths: Record<string, number> = {
  name: 200,
  protocol: 120,
  extendedProtocol: 150,
  specification: 150,
  internalServiceName: 180,
  status: 100,
  source: 100,
  labels: 200,
  usedBy: 120,
  createdWhen: 160,
  createdBy: 130,
  modifiedWhen: 160,
  modifiedBy: 130,
  method: 100,
  url: 200,
};

function ActionMenu<T>({
  record,
  actions,
}: {
  record: T;
  actions: ActionConfig<T>[];
}) {
  const items = actions
    .filter((a) => a.visible === undefined || a.visible(record))
    .map((action) => ({
      key: action.key,
      icon: action.icon,
      label: action.label,
      require: action.require,
      onClick: () => {
        if (action.confirm) {
          Modal.confirm({
            title: action.confirm.title,
            okText: action.confirm.okText || "OK",
            cancelText: action.confirm.cancelText || "Cancel",
            onOk: () => action.onClick(record),
          });
        } else {
          action.onClick(record);
        }
      },
    }));

  return (
    <ProtectedDropdown
      menu={{
        items,
      }}
      trigger={["click"]}
      placement="bottomRight"
    >
      <Button type="text" icon={<OverridableIcon name="more" />} />
    </ProtectedDropdown>
  );
}

export function getActionsColumn<T extends ServiceEntity = ServiceEntity>(
  getActionsForRecord: (record: T) => ActionConfig<T>[],
): ServicesTableColumn<T> {
  return {
    key: "actions",
    title: "",
    width: DEFAULT_ACTIONS_COLUMN_WIDTH,
    align: "center",
    render: (_value: unknown, record: T) => {
      const actions = getActionsForRecord(record);
      if (!actions || actions.length === 0) return null;
      return <ActionMenu record={record} actions={actions} />;
    },
  };
}

export type { ActionConfig } from "./serviceRowActions";
export { getServiceActions } from "./serviceRowActions";

export function useServicesTreeTable<T extends ServiceEntity = ServiceEntity>({
  dataSource,
  rowKey,
  allColumns = [],
  storageKey = "",
  defaultVisibleKeys = [],
  loading,
  expandable,
  pagination = false,
  actionsColumn,
  enableSelection,
  isRootEntity,
  selectedRowKeys: externalSelectedRowKeys,
  onSelectedRowKeysChange,
  rowClassName,
  onUpdateLabels,
  onRowClick,
  scroll,
  className,
  style,
  urlColumnTitle,
}: ServicesTreeTableProps<T>) {
  const allColumnKeys = useMemo(() => {
    return allColumns && allColumns.length > 0
      ? allColumns
      : allServicesTreeTableColumns.map((col) => col.key);
  }, [allColumns]);

  const initialKeys = useMemo(() => {
    return defaultVisibleKeys && defaultVisibleKeys.length > 0
      ? defaultVisibleKeys
      : allColumnKeys;
  }, [defaultVisibleKeys, allColumnKeys]);

  const [columnsOrder, setColumnsOrder] = useState<string[]>(() => {
    const storedOrder = localStorage.getItem(getColumnsOrderKey(storageKey));
    return storedOrder ? (JSON.parse(storedOrder) as string[]) : allColumnKeys;
  });
  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    const storedVisible = localStorage.getItem(
      getColumnsVisibleKey(storageKey),
    );
    return storedVisible
      ? (JSON.parse(storedVisible) as string[])
      : initialKeys;
  });
  const [internalSelectedRowKeys, setInternalSelectedRowKeys] = useState<
    React.Key[]
  >([]);
  const selectedRowKeys = externalSelectedRowKeys ?? internalSelectedRowKeys;
  const setSelectedRowKeys =
    onSelectedRowKeysChange ?? setInternalSelectedRowKeys;

  const handleColumnsChange = (order: string[], visible: string[]) => {
    setColumnsOrder(order);
    setVisibleColumns(visible);
  };

  const FilterButton = () => (
    <ColumnSettingsButton
      allColumns={allColumnKeys}
      defaultColumns={initialKeys}
      storageKey={storageKey}
      labelsByKey={Object.fromEntries(
        allServicesTreeTableColumns.map((c) => [c.key, c.title]),
      )}
      onChange={handleColumnsChange}
    />
  );

  const finalColumns = useMemo(() => {
    const cols = columnsOrder
      .filter((key) => visibleColumns.includes(key))
      .map((key) => {
        if (key === "labels") {
          return getLabelsColumn(
            onUpdateLabels as
              | ((record: ServiceEntity, labels: string[]) => Promise<void>)
              | undefined,
          );
        }
        return allServicesTreeTableColumns.find((col) => col.key === key);
      })
      .filter(Boolean) as ServicesTableColumn<T>[];

    return actionsColumn ? [...cols, actionsColumn] : cols;
  }, [columnsOrder, visibleColumns, actionsColumn, onUpdateLabels]);

  const finalColumnsWithUrlTitle = useMemo(() => {
    if (!urlColumnTitle) return finalColumns;
    return finalColumns.map((col) =>
      col.key === "url" ? { ...col, title: urlColumnTitle } : col,
    );
  }, [finalColumns, urlColumnTitle]);

  const { columnWidths, createResizeHandlers, resizableHeaderComponents } =
    useTableColumnResize(serviceTreeResizeWidths);

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        finalColumnsWithUrlTitle as ColumnsType<T>,
        columnWidths,
        createResizeHandlers,
        { minWidth: 80 },
      ),
    [finalColumnsWithUrlTitle, columnWidths, createResizeHandlers],
  );

  const mergedScroll = useMemo(() => {
    const extras: SumScrollXExtras = {};
    if (expandable) {
      extras.expandColumnWidth = SERVICES_TREE_EXPAND_COLUMN_WIDTH;
    }
    if (enableSelection) {
      extras.selectionColumnWidth = SERVICES_TREE_SELECTION_COLUMN_WIDTH;
    }
    const scrollX = sumScrollXForColumns(
      columnsWithResize,
      columnWidths,
      Object.keys(extras).length > 0 ? extras : undefined,
    );
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- omit y from rest (empty table: no scroll.y)
    const { y, ...scrollWithoutY } = scroll ?? {};
    if (dataSource.length === 0) {
      return { ...scrollWithoutY, x: scrollX };
    }
    return {
      ...scrollWithoutY,
      x: scrollX,
      y: scroll?.y ?? "",
    };
  }, [
    scroll,
    columnsWithResize,
    columnWidths,
    expandable,
    enableSelection,
    dataSource.length,
  ]);

  const mergedExpandable = useMemo(
    () => ({
      expandIcon: treeExpandIcon(),
      ...expandable,
      indentSize: 24,
    }),
    [expandable],
  );

  const rowSelection = useMemo((): TableRowSelection<T> | undefined => {
    if (!enableSelection) {
      return undefined;
    }
    return {
      selectedRowKeys,
      onChange: (keys: React.Key[]) => {
        setSelectedRowKeys(keys);
      },
      getCheckboxProps: (record: T) => ({
        disabled: !(isRootEntity ?? (() => true))(record),
      }),
    };
  }, [enableSelection, selectedRowKeys, setSelectedRowKeys, isRootEntity]);

  const tableStyle = useMemo(() => {
    const base: React.CSSProperties = {
      background: "var(--vscode-editor-background)",
      borderRadius: 12,
      width: "100%",
    };
    return style ? { ...base, ...style } : base;
  }, [style]);

  const onRowHandler = useMemo(() => {
    if (!onRowClick) {
      return undefined;
    }
    return (record: T) => ({
      onClick: (event: React.MouseEvent<HTMLElement>) =>
        onRowClick(record, event),
    });
  }, [onRowClick]);

  const tableElement = useMemo(
    () => (
      <Table<T>
        dataSource={dataSource}
        columns={columnsWithResize}
        rowKey={rowKey}
        loading={loading}
        expandable={mergedExpandable}
        size="small"
        pagination={pagination}
        tableLayout="fixed"
        scroll={mergedScroll}
        components={resizableHeaderComponents}
        className={className}
        style={tableStyle}
        rowClassName={rowClassName}
        onRow={onRowHandler}
        rowSelection={rowSelection}
      />
    ),
    [
      dataSource,
      columnsWithResize,
      rowKey,
      loading,
      mergedExpandable,
      pagination,
      mergedScroll,
      resizableHeaderComponents,
      className,
      tableStyle,
      rowClassName,
      onRowHandler,
      rowSelection,
    ],
  );

  return { tableElement, FilterButton };
}
