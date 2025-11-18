import { ActionLog, EntityType, LogOperation } from "../../api/apiTypes.ts";
import {
  Badge,
  Button,
  Descriptions,
  Drawer,
  Dropdown,
  Flex,
  FloatButton,
  MenuProps,
  Table,
  Typography,
} from "antd";
import { TableProps } from "antd/lib/table";
import React, { UIEvent, useRef, useState } from "react";
import { useActionLog } from "../../hooks/useActionLog.tsx";
import {capitalize, formatSnakeCased, formatTimestamp} from "../../misc/format-utils.ts";
import DateRangePicker from "../modal/DateRangePicker.tsx";
import { exportActionsLogAsExcel } from "../../misc/log-export-utils.ts";
import type { FilterDropdownProps } from "antd/lib/table/interface";
import {
  getTextColumnFilterFn,
  TextColumnFilterDropdown,
} from "../table/TextColumnFilterDropdown.tsx";
import {
  getTimestampColumnFilterFn,
  TimestampColumnFilterDropdown,
} from "../table/TimestampColumnFilterDropdown.tsx";
import { makeEnumColumnFilterDropdown } from "../EnumColumnFilterDropdown.tsx";
import { useResizeHeight } from "../../hooks/useResizeHeigth.tsx";
import { ResizableTitle } from "../ResizableTitle.tsx";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { Icon } from "../../IconProvider.tsx";
import commonStyles from "./CommonStyle.module.css";

export enum OperationType {
  READ = "read",
  DELETE = "delete",
  UPDATE = "update",
  CREATE = "create",
}

export const OperationTypeColour: { [key: string]: string } = {
  [OperationType.READ]: "#49cc90",
  [OperationType.CREATE]: "#61affe",
  [OperationType.UPDATE]: "#0d5aa7",
  [OperationType.DELETE]: "#f93e3e",
};

export const EntityTypeIconsMap: { [key: string]: React.ReactNode } = {
  [EntityType.FOLDER]: <Icon name="folder" />,
  [EntityType.CHAIN]: <Icon name="apartment" />,
  [EntityType.CHAINS]: <Icon name="apartment" />,
  [EntityType.SNAPSHOT]: <Icon name="save" />,
  [EntityType.SNAPSHOT_CLEANUP]: <Icon name="save" />,
  [EntityType.DEPLOYMENT]: <Icon name="send" />,
  [EntityType.ELEMENT]: <Icon name="settings" />,
  [EntityType.MASKED_FIELD]: <Icon name="eyeInvisible" />,
  [EntityType.CHAIN_RUNTIME_PROPERTIES]: <Icon name="settings" />,

  [EntityType.SERVICE_DISCOVERY]: <Icon name="radarChart" />,
  [EntityType.EXTERNAL_SERVICE]: <Icon name="global" />,
  [EntityType.SERVICES]: <Icon name="global" />,
  [EntityType.INNER_CLOUD_SERVICE]: <Icon name="cloud" />,
  [EntityType.IMPLEMENTED_SERVICE]: <Icon name="cluster" />,
  [EntityType.ENVIRONMENT]: <Icon name="api" />,
  [EntityType.SPECIFICATION]: <Icon name="fileDone" />,
  [EntityType.SPECIFICATION_GROUP]: <Icon name="group" />,

  [EntityType.SECRET]: <Icon name="fileUnknown" />,
  [EntityType.SECURED_VARIABLE]: <Icon name="unorderedList" />,
  [EntityType.COMMON_VARIABLE]: <Icon name="unorderedList" />,
  [EntityType.MAAS_KAFKA]: <Icon name="deploymentUnit" />,
  [EntityType.MAAS_RABBITMQ]: <Icon name="deploymentUnit" />,
  [EntityType.DETAILED_DESIGN_TEMPLATE]: <Icon name="fileText" />,
  [EntityType.IMPORT_INSTRUCTION]: <Icon name="solution" />,
  [EntityType.IMPORT_INSTRUCTIONS]: <Icon name="solution" />,
};

export const OperationTypeMap: { [key: string]: OperationType } = {
  [LogOperation.CREATE]: OperationType.CREATE,
  [LogOperation.UPDATE]: OperationType.UPDATE,
  [LogOperation.CREATE_OR_UPDATE]: OperationType.CREATE,
  [LogOperation.DELETE]: OperationType.DELETE,
  [LogOperation.COPY]: OperationType.CREATE,
  [LogOperation.MOVE]: OperationType.UPDATE,
  [LogOperation.REVERT]: OperationType.UPDATE,
  [LogOperation.GROUP]: OperationType.UPDATE,
  [LogOperation.UNGROUP]: OperationType.UPDATE,
  [LogOperation.EXPORT]: OperationType.READ,
  [LogOperation.IMPORT]: OperationType.UPDATE,
  [LogOperation.SCALE]: OperationType.UPDATE,
  [LogOperation.EXECUTE]: OperationType.UPDATE,
  [LogOperation.ACTIVATE]: OperationType.UPDATE,
  [LogOperation.DEPRECATE]: OperationType.UPDATE,
};

const entityLinkMap: Partial<
  Record<
    EntityType,
    (entityId?: string, parentId?: string, entityName?: string) => string
  >
> = {
  [EntityType.FOLDER]: (entityId) => `/chains/folders/${entityId}`,
  [EntityType.CHAIN]: (entityId) => `/chains/${entityId}`,
  [EntityType.CHAINS]: (entityId) => `/chains/${entityId}`,
  [EntityType.SNAPSHOT]: (_entityId, parentId) =>
    `/chains/${parentId}/snapshots`,
  [EntityType.DEPLOYMENT]: (_entityId, parentId) =>
    `/chains/${parentId}/deployments`,
  [EntityType.ELEMENT]: (entityId, parentId) =>
    `/chains/${parentId}/graph/${entityId}`,
  [EntityType.MASKED_FIELD]: (_entityId, parentId) =>
    `/chains/${parentId}/logging-settings`,
  //TODO update after pages been implemented
  [EntityType.EXTERNAL_SERVICE]: (/*entityId*/) => `/not-implemented`,
  [EntityType.INNER_CLOUD_SERVICE]: (/*entityId*/) => `/not-implemented`,
  [EntityType.IMPLEMENTED_SERVICE]: (/*entityId*/) => `/not-implemented`,
  [EntityType.ENVIRONMENT]: (/*parentId*/) => `/not-implemented`,
  [EntityType.SPECIFICATION_GROUP]: (/*entityId, parentId*/) =>
    `/not-implemented`,
  [EntityType.SPECIFICATION]: () => "/not-implemented",
  //TODO end
  [EntityType.SECRET]: (_entityId, _parentId, entityName) =>
    `/admintools/variables/secured/${entityName}`,
  [EntityType.SECURED_VARIABLE]: (_entityId, parentId) =>
    `/admintools/variables/secured/${parentId || ""}`,
  [EntityType.COMMON_VARIABLE]: () => `/admintools/variables/common`,
};

const externalEntityType: EntityType[] = [
  EntityType.SPECIFICATION,
  EntityType.IMPORT_INSTRUCTION,
  EntityType.IMPORT_INSTRUCTIONS,
  EntityType.DETAILED_DESIGN_TEMPLATE,
];

const columnVisibilityMenuItems: MenuProps["items"] = [
  { label: "Action Time", key: "actionTime" },
  { label: "Initiator", key: "username" },
  { label: "Operation", key: "operation" },
  { label: "Entity Type", key: "entityType" },
  { label: "Entity Name", key: "entityName" },
  { label: "ParentName", key: "parentName" },
];

const { Title } = Typography;

const EXTERNAL_ENTITY_PATTERN = /^[^\\/:*?"<>|]+\.(zip|ya?ml|xml|wsdl)$/i;

export const ActionsLog: React.FC = () => {
  const {
    logsData,
    fetchNextPage,
    hasNextPage,
    isFetching,
    isLoading,
    refresh,
  } = useActionLog();
  const [currentActionLog, setCurrentActionLog] = useState<ActionLog | null>(
    null,
  );

  const onScroll = async (event: UIEvent<HTMLDivElement>) => {
    const target = event.target as HTMLDivElement;
    const isScrolledToTheEnd =
      target.scrollTop + target.clientHeight + 1 >= target.scrollHeight;
    if (hasNextPage && isScrolledToTheEnd) {
      await fetchNextPage();
    }
  };

  const [containerRef, containerHeight] = useResizeHeight<HTMLElement>();

  const [selectedKeys, setSelectedKeys] = useState<string[]>([
    "actionTime",
    "username",
    "operation",
    "entityType",
    "entityName",
    "parentName",
  ]);

  const [columnsWidth, setColumnsWidth] = useState<{ [key: string]: number }>({
    actionTime: 200,
    username: 200,
    operation: 200,
    entityType: 300,
    entityName: 500,
    parentName: 400,
  });

  const totalColumnsWidth = Object.values(columnsWidth).reduce(
    (acc, width) => acc + width,
    0,
  );

  const operationOptions = Object.values(LogOperation).map((value) => ({
    label: formatSnakeCased(capitalize(value)),
    value,
  }));

  const entityTypeOptions = Object.values(EntityType).map((value) => ({
    label: formatSnakeCased(capitalize(value)),
    value,
  }));

  const { filterDropdown: operationFilter, onFilter: operationOnFilter } =
    makeEnumColumnFilterDropdown(operationOptions, "operation", true);

  const { filterDropdown: entityTypeFilter, onFilter: entityTypeOnFilter } =
    makeEnumColumnFilterDropdown(entityTypeOptions, "entityType", true);

  const [openSidebar, setOpenSidebar] = useState(false);

  const observerRef = useRef<HTMLDivElement | null>(null);

  const getTrimmedValue = (value: string, columnWidth: number): string => {
    const maxVisibleChars = Math.floor(columnWidth / 10);
    const firstLine = value.split("\n")[0];

    return firstLine.length > maxVisibleChars
      ? firstLine.slice(0, maxVisibleChars) + "..."
      : firstLine;
  };

  const columns: TableProps<ActionLog>["columns"] = [
    {
      title: "Action Time",
      dataIndex: "actionTime",
      hidden: !selectedKeys.includes("actionTime"),
      width: columnsWidth.actionTime,
      sorter: (a: ActionLog, b: ActionLog) => b.actionTime - a.actionTime,
      filterDropdown: (props: FilterDropdownProps) => (
        <TimestampColumnFilterDropdown {...props} />
      ),
      onFilter: getTimestampColumnFilterFn((log) => log.actionTime),
      onHeaderCell: () => ({
        width: columnsWidth.actionTime,
        onResize: handleResize("actionTime"),
      }),
      render: (_, actionLog) => <>{formatTimestamp(actionLog.actionTime)}</>,
    },
    {
      title: "Initiator",
      dataIndex: "username",
      hidden: !selectedKeys.includes("username"),
      width: columnsWidth.username,
      filterDropdown: (props: FilterDropdownProps) => (
        <TextColumnFilterDropdown {...props} />
      ),
      onFilter: getTextColumnFilterFn((log) =>
        log?.username ? log?.username : "",
      ),
      onHeaderCell: () => ({
        width: columnsWidth.username,
        onResize: handleResize("username"),
      }),
    },
    {
      title: "Operation",
      dataIndex: "operation",
      hidden: !selectedKeys.includes("operation"),
      width: columnsWidth.operation,
      filterDropdown: operationFilter,
      onFilter: operationOnFilter,
      onHeaderCell: () => ({
        width: columnsWidth.operation,
        onResize: handleResize("operation"),
      }),
      render: (_, actionLog) => (
        <>
          <Badge
            color={OperationTypeColour[OperationTypeMap[actionLog.operation]]}
            style={{ marginRight: 10 }}
          />
          {formatSnakeCased(capitalize(actionLog.operation))}
        </>
      ),
    },
    {
      title: "Entity Type",
      dataIndex: "entityType",
      width: columnsWidth.entityType,
      hidden: !selectedKeys.includes("entityType"),
      filterDropdown: entityTypeFilter,
      onFilter: entityTypeOnFilter,
      onHeaderCell: () => ({
        width: columnsWidth.entityType,
        onResize: handleResize("entityType"),
      }),
      render: (_, actionLog) => (
        <>
          {getIconByEntityType(actionLog.entityType)}
          {formatSnakeCased(capitalize(actionLog.entityType))}
        </>
      ),
    },
    {
      title: "Entity Name",
      dataIndex: "entityName",
      width: columnsWidth.entityName,
      filterDropdown: (props: FilterDropdownProps) => (
        <TextColumnFilterDropdown {...props} />
      ),
      onFilter: getTextColumnFilterFn((log) =>
        log?.entityName ? log.entityName : "",
      ),
      onHeaderCell: () => ({
        width: columnsWidth.entityName,
        onResize: handleResize("entityName"),
      }),
      render: (_, actionLog) =>
        renderEntityLink(actionLog, columnsWidth.entityName),
    },
    {
      title: "Parent Name",
      dataIndex: "parentName",
      width: columnsWidth.parentName,
      hidden: !selectedKeys.includes("parentName"),
      filterDropdown: (props: FilterDropdownProps) => (
        <TextColumnFilterDropdown {...props} />
      ),
      onFilter: getTextColumnFilterFn((log) =>
        log?.parentName ? log.parentName : "",
      ),
      onHeaderCell: () => ({
        width: columnsWidth.parentName,
        onResize: handleResize("parentName"),
      }),
      render: (_, actionLog) =>
        renderParentLink(actionLog, columnsWidth.parentName),
    },
    {
      title: "ID",
      dataIndex: "id",
      hidden: true,
    },
    {
      title: "Entity Id",
      dataIndex: "entityId",
      hidden: true,
    },
    {
      title: "Parent Id",
      dataIndex: "parentId",
      hidden: true,
    },
    {
      title: "Request Id",
      dataIndex: "requestId",
      hidden: true,
    },
  ];

  const handleResize =
    (dataIndex: string) =>
    (
      _: React.SyntheticEvent<Element>,
      { size }: { size: { width: number } },
    ) => {
      requestAnimationFrame(() => {
        setColumnsWidth((prev) => ({
          ...prev,
          [dataIndex]: size.width,
        }));
      });
    };

  const showDrawer = (actionLog: ActionLog) => {
    setCurrentActionLog(actionLog);
    setOpenSidebar(true);
  };

  const onClose = () => {
    setOpenSidebar(false);
    setCurrentActionLog(null);
  };

  const getEntityLink = (
    entityType?: EntityType,
    entityId?: string,
    parentId?: string,
    entityName?: string,
  ): string => {
    const resolver = entityType ? entityLinkMap[entityType] : undefined;
    return resolver ? resolver(entityId, parentId, entityName) : "/chains";
  };

  const renderEntityLink = (actionLog: ActionLog, columnWith?: number) => {
    return renderLink(
      actionLog.actionTime,
      actionLog.entityType,
      actionLog.entityId,
      actionLog.parentId,
      actionLog.entityName,
      columnWith,
    );
  };

  const renderParentLink = (actionLog: ActionLog, columnWith?: number) => {
    return renderLink(
      actionLog.actionTime,
      actionLog.parentType,
      actionLog.parentId,
      undefined,
      actionLog.parentName,
      columnWith,
    );
  };

  const renderLink = (
    actionTime: number,
    type?: EntityType,
    id?: string,
    parentId?: string,
    name?: string,
    columnWith?: number,
  ) => {
    const displayedName = columnWith
      ? getTrimmedValue(name ?? "", columnWith)
      : name;

    const alreadyDeleted = logsData.find(
      (actionLog) =>
        (actionLog.entityId == id ||
          (parentId && actionLog.entityId == parentId)) &&
        actionLog.actionTime >= actionTime &&
        actionLog.operation === LogOperation.DELETE,
    );

    const isExternalEntity =
      EXTERNAL_ENTITY_PATTERN.test(name ?? "") ||
      (type ? externalEntityType.includes(type) : true);

    if (alreadyDeleted || isExternalEntity) {
      return displayedName ? displayedName : "—";
    }

    const link = (
      <Flex vertical={true} gap={4}>
        <a
          onClick={() => {
            window.open(getEntityLink(type, id, parentId, name), "_blank");
          }}
        >
          {displayedName}
        </a>
      </Flex>
    );

    return displayedName ? link : "—";
  };

  const getIconByEntityType = (type: EntityType): React.ReactNode => {
    const icon = EntityTypeIconsMap[type] ?? <Icon name="question" />;
    return React.cloneElement(icon as React.ReactElement, {
      style: { marginRight: 10 },
    });
  };

  const exportActionLogs = async (from: Date, to: Date) => {
    await exportActionsLogAsExcel(from, to);
  };

  return (
    <Flex vertical className={commonStyles["container"]}>
      <Flex className={commonStyles["header"]}>
        <Title level={4} className={commonStyles["title"]}>
          <Icon name="audit" className={commonStyles["icon"]} />
          Audit
        </Title>
        <Flex vertical={false} gap={8} className={commonStyles["actions"]}>
          <Dropdown
            menu={{
              items: columnVisibilityMenuItems,
              selectable: true,
              multiple: true,
              selectedKeys,
              onSelect: ({ selectedKeys }) => setSelectedKeys(selectedKeys),
              onDeselect: ({ selectedKeys }) => setSelectedKeys(selectedKeys),
            }}
          >
            <Button icon={<Icon name="settings" />} />
          </Dropdown>
        </Flex>
      </Flex>
      <Flex
        style={{
          flex: "1 1 auto",
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          borderRadius: "8px",
          overflowY: "auto",
        }}
      >
        {currentActionLog && (
          <Drawer
            title="Action Details"
            placement="right"
            open={openSidebar}
            closable={false}
            onClose={onClose}
          >
            <Descriptions column={1} size="small" layout="vertical">
              <Descriptions.Item label="Action Time">
                {formatTimestamp(currentActionLog.actionTime)}
              </Descriptions.Item>
              <Descriptions.Item label="Initiator">
                {currentActionLog.username}
              </Descriptions.Item>
              <Descriptions.Item label="Operation">
                {capitalize(OperationTypeMap[currentActionLog.operation])}
              </Descriptions.Item>
              <Descriptions.Item label="Entity Id">
                {currentActionLog.entityId}
              </Descriptions.Item>
              <Descriptions.Item label="Entity Type">
                {capitalize(currentActionLog.entityType)}
              </Descriptions.Item>
              <Descriptions.Item label="Entity Name">
                {renderEntityLink(currentActionLog)}
              </Descriptions.Item>
              <Descriptions.Item label="Parent Id">
                {currentActionLog.parentId}
              </Descriptions.Item>
              <Descriptions.Item label="Parent Name">
                {renderParentLink(currentActionLog)}
              </Descriptions.Item>
              <Descriptions.Item label="Request Id">
                {currentActionLog.requestId}
              </Descriptions.Item>
            </Descriptions>
          </Drawer>
        )}
        <Flex
          style={{
            width: "100%",
            maxWidth: "100%",
            flex: 1,
            display: "flex",
            flexDirection: "column",
            minHeight: 0,
            overflow: "hidden",
            height: "100%",
          }}
        >
          <div
            ref={containerRef as unknown as React.Ref<HTMLDivElement>}
            style={{
              height: "100%",
              display: "flex",
              flexDirection: "column",
            }}
          >
            <Table<ActionLog>
              className="flex-table"
              size="small"
              columns={columns}
              dataSource={logsData}
              scroll={{ x: totalColumnsWidth, y: containerHeight - 59 || 400 }}
              pagination={false}
              rowKey="id"
              loading={isFetching}
              onScroll={(event) => void onScroll(event)}
              components={{
                header: {
                  cell: ResizableTitle,
                },
              }}
              onRow={(row) => {
                return {
                  onClick: () => {
                    showDrawer(row);
                  },
                };
              }}
            />
            <FloatButtonGroup trigger="hover" icon={<Icon name="more" />}>
              <FloatButton
                tooltip={{ title: "Refresh", placement: "left" }}
                icon={<Icon name="redo" />}
                onClick={() => void refresh()}
              />
              {isLoading || isFetching ? (
                <></>
              ) : (
                <DateRangePicker
                  trigger={
                    <FloatButton
                      tooltip={{
                        title: "Export action logs",
                        placement: "left",
                      }}
                      icon={<Icon name="cloudDownload" />}
                    />
                  }
                  onRangeApply={(from, to) => {
                    void exportActionLogs(from, to);
                  }}
                />
              )}
            </FloatButtonGroup>
            <div ref={observerRef} style={{ height: 1 }} />
          </div>
        </Flex>
      </Flex>
    </Flex>
  );
};
