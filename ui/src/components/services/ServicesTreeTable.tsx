import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Table, Dropdown, Button, Modal } from 'antd';
import type { FilterDropdownProps, TableRowSelection } from 'antd/es/table/interface';
import { formatTimestamp } from '../../misc/format-utils';
import { UsageStatusTag } from './utils';
import { SourceFlagTag } from './SourceFlagTag';
import { EntityLabels } from '../labels/EntityLabels';
import {
  EntityLabel,
  IntegrationSystem,
  OperationInfo,
  User,
  IntegrationSystemType,
} from "../../api/apiTypes.ts";
import { useNavigate, useParams } from 'react-router-dom';
import { ColumnsFilter } from '../table/ColumnsFilter';
import { OperationInfoModal } from './OperationInfoModal';
import { api } from '../../api/api';
import { TextColumnFilterDropdown, getTextColumnFilterFn } from '../table/TextColumnFilterDropdown.tsx';
import type { SpecificationGroup, Specification, SystemOperation } from '../../api/apiTypes';
import { InlineEdit } from '../InlineEdit';
import { LabelsEdit } from '../table/LabelsEdit';
import { ChainColumn } from './ChainColumn';
import { Icon } from "../../IconProvider.tsx";
import { HttpMethod } from './HttpMethod.tsx';

export type ServiceEntity = IntegrationSystem | SpecificationGroup | Specification | SystemOperation;

export function isIntegrationSystem(record: ServiceEntity): record is IntegrationSystem {
  return 'type' in record;
}

export function isSpecificationGroup(record: ServiceEntity): record is SpecificationGroup {
  return 'systemId' in record && 'synchronization' in record;
}

export function isSpecification(record: ServiceEntity): record is Specification {
  return 'specificationGroupId' in record && 'version' in record && 'source' in record;
}

export function isSystemOperation(record: ServiceEntity): record is SystemOperation {
  return 'method' in record && 'path' in record && 'modelId' in record;
}

export interface ServicesTableColumn<T extends ServiceEntity = ServiceEntity> {
  key: string;
  title: string;
  dataIndex?: string;
  render?: (value: unknown, record: T, index: number) => React.ReactNode;
  width?: number;
  align?: 'left' | 'center' | 'right';
  filterDropdown?: (props: FilterDropdownProps) => React.ReactNode;
  onFilter?: (value: React.Key | boolean, record: T) => boolean;
}

export interface ServicesTreeTableProps<T extends ServiceEntity = ServiceEntity> {
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
  size?: 'small' | 'middle' | 'large';
  pagination?: false | object;
  style?: React.CSSProperties;
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
}

const clickableStyle: React.CSSProperties = {
  fontWeight: 500,
  color: '#1677ff',
  cursor: 'pointer',
};

const iconStyle: React.CSSProperties = {
  fontSize: 22,
  color: '#b0b8c4',
  marginRight: 8,
  verticalAlign: 'middle',
};

function getIcon(record: ServiceEntity): React.JSX.Element | null {
  if (isIntegrationSystem(record)) {
    switch (record.type) {
      case IntegrationSystemType.EXTERNAL:
        return <Icon name="global" style={iconStyle} />;
      case IntegrationSystemType.INTERNAL:
        return <Icon name="cloud" style={iconStyle} />;
      case IntegrationSystemType.IMPLEMENTED:
        return <Icon name="cluster" style={iconStyle} />;
      default:
        return <Icon name="global" style={iconStyle} />;
    }
  }
  if (isSpecificationGroup(record)) {
    return <Icon name="inbox" style={iconStyle}/>
  }
  if (isSpecification(record)) {
    return <Icon name="fileText" style={iconStyle} />;
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
  const [operationInfo, setOperationInfo] = React.useState<OperationInfo | undefined>(undefined);
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
      <span
        style={clickableStyle}
        onClick={() => void handleClick()}
      >
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
  ) : React.ReactNode => (
    <NameCell record={record} />
  );
  renderNameColumn.displayName = 'RenderNameColumn';
  return renderNameColumn;
};

function renderLabelsCell(
  labels: EntityLabel[] = [],
  record: ServiceEntity,
  onUpdateLabels?: (record: ServiceEntity, labels: string[]) => Promise<void>
): React.ReactNode {
  const filtered = labels.filter(l => !l.technical);

  if (onUpdateLabels) {
    return (
      <div className="inline-edit-labels">
        <InlineEdit
          values={{ labels: filtered.map(l => l.name) }}
          editor={<LabelsEdit name="labels" />}
          viewer={<EntityLabels labels={labels} />}
          onSubmit={async ({ labels: newLabels }) => {
            await onUpdateLabels(record, newLabels);
          }}
        />
      </div>
    );
  }

  return labels.length > 0 ? labels.map(l => l.name).join(', ') : '';
}

function getLabelsColumn(onUpdateLabels?: (record: ServiceEntity, labels: string[]) => Promise<void>): ServicesTableColumn {
  return {
    title: 'Labels',
    dataIndex: 'labels',
    key: 'labels',
    render: (value, record) => {
      const labels = Array.isArray(value) ? value as EntityLabel[] : [];
      return renderLabelsCell(labels, record, onUpdateLabels);
    }
  };
}

export const allServicesTreeTableColumns: ServicesTableColumn<ServiceEntity>[] = [
  {
    title: "Name",
    dataIndex: "name",
    key: "name",
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
    render: (text) => <SourceFlagTag source={typeof text === "string" ? text : ""} toUpperCase={true} />,
  },
  {
    title: "Extended Protocol",
    dataIndex: "extendedProtocol",
    key: "extendedProtocol",
    render: (value: unknown) => (typeof value === "string" ? value : ""),
  },
  {
    title: "Specification",
    dataIndex: "specification",
    key: "specification",
    render: (value: unknown) => (typeof value === "string" ? value : ""),
  },
  {
    title: "Internal Service Name",
    dataIndex: "internalServiceName",
    key: "internalServiceName",
    render: (value: unknown) => (typeof value === "string" ? value : ""),
  },
  {
    title: "Status",
    dataIndex: "status",
    key: "status",
    render: (_value, record) => <UsageStatusTag element={record} />,
  },
  {
    title: "Source",
    dataIndex: "source",
    key: "source",
    render: (value) =>
      typeof value === "string" ? <SourceFlagTag source={value} /> : "",
  },
  {
    title: "Labels",
    dataIndex: "labels",
    key: "labels",
    render: undefined,
  },
  {
    title: "Used by",
    dataIndex: "usedBy",
    key: "usedBy",
    render: (_: unknown, record: ServiceEntity) => {
      if (isIntegrationSystem(record)) return <div style={{display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%'}}></div>;
      const chains =
        "chains" in record && Array.isArray(record.chains) ? record.chains : [];
      return <div style={{display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%'}}><ChainColumn chains={chains} /></div>;
    },
  },
  {
    title: "Created When",
    dataIndex: "createdWhen",
    key: "createdWhen",
    render: (createdWhen) => <>{formatTimestamp(createdWhen as number)}</>,
  },
  {
    title: "Created By",
    dataIndex: "createdBy",
    key: "createdBy",
    render: (value: unknown) => {
      const createdBy = value as User | undefined;
      return createdBy?.username || "";
    },
  },
  {
    title: "Modified When",
    dataIndex: "modifiedWhen",
    key: "modifiedWhen",
    render: (modifiedWhen) => <>{formatTimestamp(modifiedWhen as number)}</>,
  },
  {
    title: "Modified By",
    dataIndex: "modifiedBy",
    key: "modifiedBy",
    render: (value: unknown) => {
      const modifiedBy = value as User | undefined;
      return modifiedBy?.username || "";
    },
  },
  {
    title: "Method",
    dataIndex: "method",
    key: "method",
    render: (value: unknown) => <HttpMethod value={value} />,
  },
  {
    title: "URL",
    dataIndex: "url",
    key: "url",
    render: (_value: unknown, record: ServiceEntity) => {
      return "path" in record ? record.path : "";
    },
  },
];

export interface ActionConfig<T = never> {
  key: string;
  label: string;
  icon?: React.ReactNode;
  onClick: (record: T) => void;
  confirm?: {
    title: string;
    okText?: string;
    cancelText?: string;
  };
  visible?: (record: T) => boolean;
}

function ActionMenu<T>({ record, actions }: { record: T; actions: ActionConfig<T>[] }) {
  const items = actions
    .filter(a => a.visible === undefined || a.visible(record))
    .map(action => ({
      key: action.key,
      icon: action.icon,
      label: action.label,
      onClick: () => {
        if (action.confirm) {
          Modal.confirm({
            title: action.confirm.title,
            okText: action.confirm.okText || 'OK',
            cancelText: action.confirm.cancelText || 'Cancel',
            onOk: () => action.onClick(record),
          });
        } else {
          action.onClick(record);
        }
      }
    }));

  return (
    <Dropdown
      menu={{
        items
      }}
      trigger={["click"]}
      placement="bottomRight"
    >
      <Button type="text" icon={<Icon name="more" />} />
    </Dropdown>
  );
}

export function getActionsColumn<T extends ServiceEntity = ServiceEntity>(getActionsForRecord: (record: T) => ActionConfig<T>[]): ServicesTableColumn<T> {
  return {
    key: "actions",
    title: "",
    width: 40,
    align: "center",
    render: (_value: unknown, record: T) => {
      const actions = getActionsForRecord(record);
      if (!actions || actions.length === 0) return null;
      return (
        <ActionMenu record={record} actions={actions} />
      );
    },
  };
}

export function getServiceActions({
  onEdit,
  onDelete,
  onExpandAll,
  onCollapseAll,
  isRootEntity,
  onExportSelected,
}: {
  onEdit: (record: ServiceEntity) => void;
  onDelete: (record: ServiceEntity) => void;
  onExpandAll: (record: ServiceEntity) => void;
  onCollapseAll: (record: ServiceEntity) => void;
  isRootEntity: (record: ServiceEntity) => boolean;
  onExportSelected?: (selected: ServiceEntity[]) => void;
}) {
  return (record: ServiceEntity): ActionConfig<ServiceEntity>[] => {
    if (!isRootEntity(record)) return [];
    const actions: ActionConfig<ServiceEntity>[] = [
      {
        key: 'edit',
        label: 'Edit',
        icon: <Icon name="edit" />,
        onClick: onEdit,
      },
      {
        key: 'delete',
        label: 'Delete',
        icon: <Icon name="delete" />,
        onClick: onDelete,
        confirm: {
          title: 'Are you sure you want to delete this service?',
          okText: 'Delete',
          cancelText: 'Cancel',
        },
      },
      {
        key: 'expandAll',
        label: 'Expand All',
        icon: <Icon name="columnHeight" />,
        onClick: onExpandAll,
      },
      {
        key: 'collapseAll',
        label: 'Collapse All',
        icon: <Icon name="verticalAlignMiddle" />,
        onClick: onCollapseAll,
      },
    ];
    if (onExportSelected) {
      actions.push({
        key: 'export',
        label: 'Export',
        icon: <Icon name="cloudDownload" />,
        onClick: (rec) => onExportSelected([rec]),
      });
    }
    return actions;
  };
}

export function useServicesTreeTable<T extends ServiceEntity = ServiceEntity>({
  dataSource,
  rowKey,
  allColumns = [],
  storageKey = '',
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
}: ServicesTreeTableProps<T>) {
  const allColumnKeys = useMemo(() => {
    return allColumns && allColumns.length > 0
      ? allColumns
      : allServicesTreeTableColumns.map(col => col.key);
  }, [allColumns]);

  const initialKeys = useMemo(() => {
    return defaultVisibleKeys && defaultVisibleKeys.length > 0
      ? defaultVisibleKeys
      : allColumnKeys;
  }, [defaultVisibleKeys, allColumnKeys]);

  const [columnsOrder, setColumnsOrder] = useState<string[]>(() => {
    const storedOrder = localStorage.getItem(`${storageKey}_columnsOrder`);
    return storedOrder ? (JSON.parse(storedOrder) as string[]) : allColumnKeys;
  });
  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    const storedVisible = localStorage.getItem(`${storageKey}_columnsVisible`);
    return storedVisible ? (JSON.parse(storedVisible) as string[]) : initialKeys;
  });
  const [internalSelectedRowKeys, setInternalSelectedRowKeys] = useState<React.Key[]>([]);
  const selectedRowKeys = externalSelectedRowKeys ?? internalSelectedRowKeys;
  const setSelectedRowKeys = onSelectedRowKeysChange ?? setInternalSelectedRowKeys;

  const handleColumnsChange = (order: string[], visible: string[]) => {
    setColumnsOrder(order);
    setVisibleColumns(visible);
  };

  const FilterButton = () => (
    <Dropdown
      popupRender={() => (
        <ColumnsFilter
          allColumns={allColumnKeys}
          defaultColumns={initialKeys}
          storageKey={storageKey}
          labelsByKey={Object.fromEntries(allServicesTreeTableColumns.map(c => [c.key, c.title]))}
          onChange={handleColumnsChange}
        />
      )}
      trigger={['click']}
    >
      <Button icon={<Icon name="settings" />}/>
    </Dropdown>
  );

  const finalColumns = useMemo(() => {
    const cols = columnsOrder
      .filter(key => visibleColumns.includes(key))
      .map(key => {
        if (key === 'labels') {
          return getLabelsColumn(onUpdateLabels as ((record: ServiceEntity, labels: string[]) => Promise<void>) | undefined);
        }
        return allServicesTreeTableColumns.find(col => col.key === key);
      })
      .filter(Boolean) as ServicesTableColumn<T>[];

    return actionsColumn ? [...cols, actionsColumn] : cols;
  }, [columnsOrder, visibleColumns, actionsColumn, onUpdateLabels]);

  const TableComponent = () => {
    const rowSelection: TableRowSelection<T> | undefined = enableSelection ? {
        selectedRowKeys,
        onChange: (keys: React.Key[]) => {
          setSelectedRowKeys(keys);
        },
        getCheckboxProps: (record: T) => ({
          disabled: !((isRootEntity ?? (() => true))(record)),
        }),
      }
      : undefined;
    return (
      <Table
        dataSource={dataSource}
        columns={finalColumns}
        rowKey={rowKey}
        loading={loading}
        expandable={expandable}
        size={"small"}
        pagination={pagination}
        style={{ background: "#fff", borderRadius: 12, width: '100%' }}
        rowClassName={rowClassName}
        onRow={onRowClick ? (record) => ({
          onClick: (event: React.MouseEvent<HTMLElement>) => onRowClick(record, event),
        }) : undefined}
        rowSelection={rowSelection}
      />
    );
  };

  return { Table: TableComponent, FilterButton };
}
