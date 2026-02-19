import {
  Alert,
  Badge,
  BadgeProps,
  Button,
  Dropdown,
  Flex,
  MenuProps,
  Table,
  Tag,
  Tooltip,
} from "antd";
import Search from "antd/lib/input/Search";
import { DiagnosticValidation, ValidationSeverity, ValidationState } from "../../api/apiTypes";
import { TableProps } from "antd/lib/table";
import { useCallback, useEffect, useRef, useState } from "react";
import { TableRowSelection } from "antd/lib/table/interface";
import { api } from "../../api/api";
import { useNotificationService } from "../../hooks/useNotificationService";
import { formatTimestamp } from "../../misc/format-utils";
import { IconName, OverridableIcon } from "../../icons/IconProvider";
import { useModalsContext } from "../../Modals";
import { DiagnosticValidationModal } from "./DiagnosticValidationModal";
import { useDiagnosticValidationFilters } from "./useDiagnosticValidationFilters";

export const VALIDATION_STATE_TO_LABEL: { [key: string]: string } = {
  [ValidationState.OK]: "Finished",
  [ValidationState.NOT_STARTED]: "Not Started",
  [ValidationState.IN_PROGRESS]: "In Progress",
  [ValidationState.FAILED]: "Failed",
};

export const VALIDATION_STATE_TO_COLOR: {
  [key: string]: BadgeProps["status"];
} = {
  [ValidationState.OK]: "success",
  [ValidationState.NOT_STARTED]: "default",
  [ValidationState.IN_PROGRESS]: "processing",
  [ValidationState.FAILED]: "error",
};

type ValidationRowType = "validation" | "chain" | "chain_element";

type BaseTableItem = {
  itemId: string; //introduced since id is not always unique (ex. the same chain can be under different validations)
  id: string;
  title: string;
  type: ValidationRowType;
  parentId?: string;
  children?: BaseTableItem[];
  icon?: IconName;
  alertsCount?: number;
  severity?: ValidationSeverity;
};

type DiagnosticValidationTableItem = DiagnosticValidation & BaseTableItem;

const isDiagnosticValidation = (
  item: DiagnosticValidationTableItem,
): boolean => {
  return item.type === "validation";
};

const isChainEntity = (item: DiagnosticValidationTableItem): boolean => {
  return item.type === "chain";
};

export const Diagnostic: React.FC = () => {
  const updateStatusDelay = 2500;
  const notificationService = useNotificationService();
  const { showModal } = useModalsContext();
  const [isLoading, setIsLoading] = useState(false);
  const [diagnosticInProgress, setDiagnosticInProgress] = useState<boolean>();
  const diagnosticInProgressRef = useRef(diagnosticInProgress);
  diagnosticInProgressRef.current = diagnosticInProgress;
  const pollingTimerRef = useRef<ReturnType<typeof setTimeout>>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);
  const [loadedValidations, setLoadedValidations] = useState<Set<string>>(
    new Set(),
  );
  const [tableData, setTableData] = useState<DiagnosticValidationTableItem[]>(
    [],
  );
  const [searchString, setSearchString] = useState<string>("");
  const [selectedKeys, setSelectedKeys] = useState<string[]>([
    "title",
    "status",
    "alertsCount",
    "hint",
    "startTime",
  ]);
  const { filters, filterButton } = useDiagnosticValidationFilters();

  const columnVisibilityMenuItems: MenuProps["items"] = [
    { label: "Name", key: "title", disabled: true },
    { label: "Status", key: "status" },
    { label: "Alerts", key: "alertsCount" },
    { label: "Hint", key: "hint" },
    { label: "Start Time", key: "startTime" },
  ];

  const columns: TableProps<DiagnosticValidationTableItem>["columns"] = [
    {
      title: "Table Id",
      dataIndex: "itemId",
      key: "itemId",
      hidden: true,
    },
    {
      title: "Name",
      dataIndex: "title",
      key: "title",
      hidden: !selectedKeys.includes("title"),
      sorter: (a, b) => a.title.localeCompare(b.title),
      defaultSortOrder: "ascend",
      render: (_, validation) => {
        if (isDiagnosticValidation(validation)) {
          const diagnosticValidation = validation as DiagnosticValidation;
          return (
            <Tooltip placement="right" title={validation.description}>
              <a
                onClick={() =>
                  showModal({
                    component: (
                      <DiagnosticValidationModal {...diagnosticValidation} />
                    ),
                  })
                }
              >
                {diagnosticValidation.title}
              </a>
            </Tooltip>
          );
        } else {
          return (
            <span>
              {validation.icon && (
                <OverridableIcon
                  style={{ marginRight: 8 }}
                  name={validation.icon}
                />
              )}
              <a
                rel="noopener noreferrer"
                target="_blank"
                href={
                  isChainEntity(validation)
                    ? `/chains/${validation.id}`
                    : `/chains/${validation.parentId}/graph/${validation.id}`
                }
              >
                {validation.title}
              </a>
            </span>
          );
        }
      },
    },
    {
      title: "Status",
      dataIndex: "status",
      key: "status",
      width: 130,
      hidden: !selectedKeys.includes("status"),
      render: (_, validation) =>
        isDiagnosticValidation(validation) && (
          <Badge
            status={
              VALIDATION_STATE_TO_COLOR[
                (validation as DiagnosticValidation).status.state
              ]
            }
            text={
              VALIDATION_STATE_TO_LABEL[
                (validation as DiagnosticValidation).status.state
              ]
            }
          />
        ),
    },
    {
      title: "Alerts",
      dataIndex: "alertsCount",
      key: "alertsCount",
      width: 90,
      hidden: !selectedKeys.includes("alertsCount"),
      sorter: (a, b) => (a.alertsCount ?? 0) - (b.alertsCount ?? 0),
      render: (_, record) => {
        const count = record.alertsCount;
        if (count == null) return null;
        if (count === 0) {
          return isDiagnosticValidation(record) ? <Tag>{count}</Tag> : null;
        }
        const color =
          record.severity === ValidationSeverity.ERROR ? "red" : "gold";
        const bordered = isDiagnosticValidation(record);
        return (
          <Tag color={color} bordered={bordered}>
            {count}
          </Tag>
        );
      },
    },
    {
      title: "Hint",
      dataIndex: "hint",
      key: "hint",
      width: 70,
      hidden: !selectedKeys.includes("hint"),
      render: (_, validation) =>
        isDiagnosticValidation(validation) && (
          <Tooltip placement="left" title={validation.suggestion}>
            <OverridableIcon name="bulb" />
          </Tooltip>
        ),
    },
    {
      title: "Start Time",
      dataIndex: "startedWhen",
      key: "startTime",
      width: 180,
      hidden: !selectedKeys.includes("startTime"),
      render: (_, validation) =>
        isDiagnosticValidation(validation) &&
        validation?.status?.startedWhen &&
        formatTimestamp(validation.status.startedWhen),
    },
  ];

  const rowSelection: TableRowSelection<DiagnosticValidationTableItem> = {
    type: "checkbox",
    selectedRowKeys,
    checkStrictly: false,
    onChange: setSelectedRowKeys,
    getCheckboxProps: (record) => ({
      disabled: !isDiagnosticValidation(record),
      style: !isDiagnosticValidation(record)
        ? { visibility: "hidden" as const }
        : undefined,
    }),
  };

  useEffect(() => {
    if (diagnosticInProgress) {
      notificationService.info("Diagnostic started", "Diagnostic started");
    } else if (diagnosticInProgress === false) {
      notificationService.info("Diagnostic finished", "Diagnostic finished");
    }
  }, [notificationService, diagnosticInProgress]);

  const updateLoadedValidations = (validationIds: string[]) => {
    setLoadedValidations((prevState) => {
      if (validationIds.length === 0) {
        return new Set();
      }
      const newState = new Set(prevState);
      validationIds.forEach((id) => newState.add(id));
      return newState;
    });
  };

  const loadValidations = useCallback(async () => {
    setIsLoading(true);
    try {
      const validations: DiagnosticValidation[] = await api.getValidations(
        filters,
        searchString,
      );
      setSelectedRowKeys([]);
      setExpandedRowKeys([]);

      const validationsWithChains: string[] = [];
      const validationItems: DiagnosticValidationTableItem[] = validations.map(
        (validation) => {
          if (validation.chainEntities?.length > 0) {
            validationsWithChains.push(validation.id);
            return buildValidationWithChildren(validation);
          }
          return {
            ...validation,
            itemId: validation.id,
            children: validation.alertsCount > 0 ? [] : undefined,
            type: "validation",
          };
        },
      );
      setTableData(validationItems);
      updateLoadedValidations(validationsWithChains);

      // check in progress
      let stillInProgress = false;
      for (const validation of validations) {
        if (validation.status.state === ValidationState.IN_PROGRESS) {
          stillInProgress = true;
          break;
        }
      }
      if (diagnosticInProgressRef.current) {
        if (stillInProgress) {
          pollingTimerRef.current = setTimeout(
            () => void loadValidations(),
            updateStatusDelay,
          );
        } else {
          setDiagnosticInProgress(false);
        }
      }
    } catch (error) {
      notificationService.requestFailed("Failed to load validations", error);
    } finally {
      setIsLoading(false);
    }
  }, [notificationService, searchString, filters]);

  useEffect(() => {
    void loadValidations();
    return () => clearTimeout(pollingTimerRef.current);
  }, [loadValidations]);

  const buildValidationWithChildren = (
    validation: DiagnosticValidation,
  ): DiagnosticValidationTableItem => {
    const chainsMap: Map<string, BaseTableItem> = new Map();
    validation.chainEntities.forEach((element) => {
      if (!element.elementId) {
        if (!chainsMap.has(element.chainId)) {
          chainsMap.set(element.chainId, {
            itemId: `${validation.id}_${element.chainId}`,
            id: element.chainId,
            title: element.chainName,
            type: "chain",
            icon: "unorderedList",
            severity: validation.severity,
          });
        }
        return;
      }
      let chain = chainsMap.get(element.chainId);
      if (!chain) {
        chain = {
          itemId: `${validation.id}_${element.chainId}`,
          id: element.chainId,
          title: element.chainName,
          type: "chain",
          children: [],
          icon: "unorderedList",
          severity: validation.severity,
        };
        chainsMap.set(chain.id, chain);
      }
      chain.children?.push({
        itemId: `${validation.id}_${element.elementId}`,
        id: element.elementId,
        title: element.elementName,
        type: "chain_element",
        parentId: chain.id,
        icon: element.elementType as IconName,
      });
    });
    for (const value of chainsMap.values()) {
      value.alertsCount = value.children?.length;
    }

    return {
      ...validation,
      children: [...chainsMap.values()],
      type: "validation",
      itemId: validation.id,
    };
  };

  const openValidation = async (validationId: string) => {
    setIsLoading(true);
    try {
      const validation = await api.getValidation(validationId);
      if (!validation) {
        return;
      }

      setTableData((prevState) =>
        prevState.map((tableItem) =>
          tableItem.id === validationId
            ? buildValidationWithChildren(validation)
            : tableItem,
        ),
      );

      updateLoadedValidations([validationId]);
    } catch (error) {
      notificationService.requestFailed(
        "Error while loading diagnostic validation details",
        error,
      );
    } finally {
      setIsLoading(false);
    }
  };

  const handleRunValidations = async () => {
    setDiagnosticInProgress(true);
    try {
      const keys = selectedRowKeys.map((key) => String(key));
      await api.runValidations(keys);
      setTimeout(() => void loadValidations(), 500);
    } catch (error) {
      notificationService.requestFailed("Validations run failed", error);
      setDiagnosticInProgress(undefined);
    }
  };

  return (
    <Flex vertical gap={16} style={{ height: "100%" }}>
      <Alert
        type="info"
        showIcon
        message="This menu is only available for testing environment and won't be accessible on production. Data, created via this tab won't be exported with the chains."
      />
      <Flex vertical={false} gap={8}>
        <Search
          placeholder="Full text search"
          allowClear
          onSearch={(value) => setSearchString(value)}
        />
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
          <Button icon={<OverridableIcon name="settings" />} />
        </Dropdown>
        {filterButton}
        <Button
          type="primary"
          icon={<OverridableIcon name="caretRightFilled" />}
          title="Run Diagnostic"
          disabled={diagnosticInProgress || isLoading}
          onClick={() => void handleRunValidations()}
        >
          Run Diagnostic
        </Button>
      </Flex>

      <Table<DiagnosticValidationTableItem>
        size="small"
        className="flex-table"
        columns={columns}
        rowSelection={rowSelection}
        dataSource={tableData}
        pagination={false}
        loading={isLoading}
        rowKey="itemId"
        sticky
        scroll={{ y: "" }}
        expandable={{
          expandIcon: ({ expanded, onExpand, record, expandable }) =>
            expandable ? (
              <OverridableIcon
                name={expanded ? "down" : "right"}
                style={{ cursor: "pointer", marginRight: 8, fontSize: 12 }}
                onClick={(e) => onExpand(record, e)}
              />
            ) : (
              <span style={{ display: "inline-block", width: 20 }} />
            ),
          expandedRowKeys,
          onExpandedRowsChange: (rowKeys) => {
            setExpandedRowKeys([...rowKeys]);
          },
          onExpand: (expanded, item) => {
            if (isDiagnosticValidation(item) && expanded) {
              const validationItem = item as DiagnosticValidation;
              if (!loadedValidations.has(validationItem.id)) {
                void openValidation(validationItem.id);
              }
            }
          },
        }}
      />
    </Flex>
  );
};
