import React, { useMemo, useState, UIEvent } from "react";
import {
  Flex,
  Table,
  Typography,
  Button,
  Tag,
  Tooltip,
  Drawer,
  Descriptions,
  Space,
} from "antd";
import { useResizeHeight } from "../../../hooks/useResizeHeigth.tsx";
import commonStyles from "../CommonStyle.module.css";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { makeEnumColumnFilterDropdown } from "../../EnumColumnFilterDropdown.tsx";
import {
  AccessControlType,
  AccessControl as AccessControlData,
  AccessControlBulkDeployRequest,
  AccessControlProperty,
} from "../../../api/apiTypes.ts";
import type { FilterDropdownProps } from "antd/lib/table/interface";
import {
  getTextColumnFilterFn,
  TextColumnFilterDropdown,
} from "../../table/TextColumnFilterDropdown.tsx";
import { useAccessControl } from "../../../hooks/useAccessControl.tsx";
import { ColumnsType } from "antd/es/table";
import { useModalsContext } from "../../../Modals.tsx";
import { AbacAttributesPopUp } from "./AbacAttributesPopUp.tsx";
import { AddDeleteRolesPopUp } from "./AddDeleteRolesPopUp.tsx";
import { useNavigate } from "react-router";
import {
  DeploymentsCumulativeState,
  getCumulativeStatus,
} from "../../deployment_runtime_states/DeploymentsCumulativeState.tsx";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import { CompactSearch } from "../../table/CompactSearch.tsx";
import { matchesByFields } from "../../table/tableSearch.ts";

const { Title } = Typography;

const typeOptions = [
  { label: "External", value: "External" },
  { label: "Private", value: "Private" },
  { label: "Internal", value: "Internal" },
  { label: "External, Private", value: "External, Private" },
];

const chainStatusOptions = [
  { label: "Draft", value: "DRAFT" },
  { label: "Deployed", value: "DEPLOYED" },
  { label: "Failed", value: "FAILED" },
  { label: "Processing", value: "PROCESSING" },
];

const accessControlTypeOptions = Object.values(AccessControlType).map(
  (value) => ({
    label: value,
    value,
  }),
);

function routeTypeLabel(record: AccessControlData): string {
  const props = record.properties as unknown as
    | AccessControlProperty
    | undefined;
  const { externalRoute, privateRoute } = props ?? {};
  if (externalRoute && privateRoute) return "External, Private";
  if (externalRoute) return "External";
  if (privateRoute) return "Private";
  return "Internal";
}

function accessControlMatchesSearch(
  record: AccessControlData,
  term: string,
): boolean {
  const props = record.properties as unknown as
    | AccessControlProperty
    | undefined;
  const roles = props?.roles;
  const rolesStr = Array.isArray(roles) ? roles.join(" ") : "";
  const act = props?.accessControlType;
  let actStr = "";
  if (typeof act === "string") actStr = act;
  else if (act != null) actStr = String(act);
  return matchesByFields(term, [
    props?.contextPath ?? "",
    record.chainName,
    record.chainId,
    record.elementId,
    rolesStr,
    actStr,
    routeTypeLabel(record),
    ...(record.deploymentStatus ?? []),
  ]);
}

export const AccessControl: React.FC = () => {
  const {
    isLoading,
    accessControlData,
    setAccessControlData,
    getAccessControl,
    bulkDeployAccessControl,
    loadMore,
    allDataLoaded,
  } = useAccessControl();
  const { showModal } = useModalsContext();
  const navigate = useNavigate();
  const notificationService = useNotificationService();

  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [containerRef, containerHeight] = useResizeHeight<HTMLElement>();
  const [currentRecord, setCurrentRecord] = useState<AccessControlData | null>(
    null,
  );
  const [openSidebar, setOpenSidebar] = useState(false);
  const [deployedChainIds, setDeployedChainIds] = useState<Set<string>>(
    new Set(),
  );
  const [searchTerm, setSearchTerm] = useState("");

  const accessControlColumnResize = useTableColumnResize({
    endpoint: 200,
    type: 100,
    accessControlType: 160,
    roles: 100,
    attributes: 100,
    chain: 190,
    chainStatus: 110,
  });

  const filteredRoles = useMemo(
    () =>
      (accessControlData?.roles ?? []).filter((row) =>
        accessControlMatchesSearch(row, searchTerm),
      ),
    [accessControlData?.roles, searchTerm],
  );

  const { filterDropdown: accessControlTypeFilter } =
    makeEnumColumnFilterDropdown(
      accessControlTypeOptions,
      "accessControlType",
      true,
    );

  const { filterDropdown: typeFilter } = makeEnumColumnFilterDropdown(
    typeOptions,
    "type",
    true,
  );

  const { filterDropdown: chainStatusFilter } = makeEnumColumnFilterDropdown(
    chainStatusOptions,
    "chainStatus",
    true,
  );

  const onScroll = async (event: UIEvent<HTMLDivElement>) => {
    const target = event.target as HTMLDivElement;
    const isScrolledToTheEnd =
      target.scrollTop + target.clientHeight + 1 >= target.scrollHeight;
    if (!allDataLoaded && isScrolledToTheEnd && !isLoading) {
      await loadMore();
    }
  };

  const showDrawer = (record: AccessControlData) => {
    setCurrentRecord(record);
    setOpenSidebar(true);
  };

  const onClose = () => {
    setOpenSidebar(false);
    setCurrentRecord(null);
  };

  const handleBulkDeploy = async (records: AccessControlData[]) => {
    if (records.length === 0) {
      notificationService.info("Error", "No chains to deploy");
      return;
    }

    const recordsToDeploy = records.filter(
      (record) => record.chainId && hasUnsavedChanges(record),
    );

    if (recordsToDeploy.length === 0) {
      notificationService.info("Error", "No changes to apply");
      return;
    }

    try {
      const bulkDeployRequests: AccessControlBulkDeployRequest[] =
        recordsToDeploy.map((record) => ({
          chainId: record.chainId,
          unsavedChanges: record.unsavedChanges,
        }));

      await bulkDeployAccessControl(bulkDeployRequests);

      const chainToDeploy = new Set(recordsToDeploy.map((r) => r.chainId));
      setDeployedChainIds(new Set(chainToDeploy));

      if (accessControlData?.roles) {
        const deployedChainIds = new Set(
          recordsToDeploy.map((r) => buildRowKey(r)),
        );
        const updatedRoles = accessControlData.roles.map((role) => {
          if (deployedChainIds.has(buildRowKey(role))) {
            return { ...role, unsavedChanges: false };
          }
          return role;
        });
        setAccessControlData({ ...accessControlData, roles: updatedRoles });
      }

      notificationService.info("Success", `Selected chains are deployed.`);
      return;
    } catch (err: unknown) {
      notificationService.requestFailed(
        "Failed to deploy chains",
        err instanceof Error ? err : new Error(String(err)),
      );
    }
  };

  const selectUnsavedChains = () => {
    if (!accessControlData?.roles) {
      return;
    }

    const selected: string[] = accessControlData?.roles
      .filter((row) => hasUnsavedChanges(row))
      .map((row) => buildRowKey(row));

    setSelectedRowKeys(selected);
  };

  const columns: ColumnsType<AccessControlData> = [
    {
      title: "Endpoint",
      dataIndex: "endpoint",
      key: "endpoint",
      sorter: {
        compare: (a: AccessControlData, b: AccessControlData) => {
          const aPath =
            (a.properties as unknown as AccessControlProperty)?.contextPath ??
            "";
          const bPath =
            (b.properties as unknown as AccessControlProperty)?.contextPath ??
            "";
          return String(aPath).localeCompare(String(bPath));
        },
      },
      render: (_value: unknown, record: AccessControlData) => {
        if (record.chainId) {
          return (
            <a
              href={`/chains/${record.chainId}/graph/${record.elementId}`}
              target="_blank"
              rel="noopener noreferrer"
            >
              {(record.properties as unknown as AccessControlProperty)
                ?.contextPath || "—"}
            </a>
          );
        }
        return <>—</>;
      },
    },
    {
      title: "Type",
      key: "type",
      filterDropdown: typeFilter,
      onFilter: (value: React.Key | boolean, record: AccessControlData) => {
        const { externalRoute, privateRoute } =
          (record.properties as unknown as AccessControlProperty) ?? {};
        const recordType =
          externalRoute && privateRoute
            ? "External, Private"
            : externalRoute
              ? "External"
              : privateRoute
                ? "Private"
                : "Internal";
        return recordType === value;
      },
      render: (_value: unknown, record: AccessControlData) => {
        const props = record.properties as unknown as
          | AccessControlProperty
          | undefined;
        const { externalRoute, privateRoute } = props ?? {};
        return (
          <>
            {externalRoute && privateRoute
              ? "External, Private"
              : externalRoute
                ? "External"
                : privateRoute
                  ? "Private"
                  : "Internal"}
          </>
        );
      },
    },
    {
      title: "Access Control Type",
      key: "accessControlType",
      dataIndex: "accessControlType",
      filterDropdown: accessControlTypeFilter,
      onFilter: (value: React.Key | boolean, record: AccessControlData) => {
        const recordValue = (
          record.properties as unknown as AccessControlProperty
        )?.accessControlType;
        return recordValue === value;
      },
      render: (_value: unknown, record: AccessControlData) => {
        const props = record.properties as unknown as
          | AccessControlProperty
          | undefined;
        const val = props?.accessControlType;
        return (
          <>{typeof val === "string" ? val : val != null ? String(val) : "—"}</>
        );
      },
    },
    {
      title: "Roles",
      key: "roles",
      filterDropdown: (props: FilterDropdownProps) => (
        <TextColumnFilterDropdown {...props} />
      ),
      onFilter: getTextColumnFilterFn((record: AccessControlData) => {
        const roles = (record.properties as unknown as AccessControlProperty)
          ?.roles;
        if (roles && Array.isArray(roles) && roles.length > 0) {
          return roles.join(" ");
        }
        return "";
      }),
      render: (_value: unknown, record: AccessControlData) => {
        const props = record.properties as unknown as
          | AccessControlProperty
          | undefined;
        const roles = props?.roles;
        if (roles && Array.isArray(roles) && roles.length > 0) {
          return (
            <span>
              {roles.map((role, idx) => (
                <Tag key={`${role}-${idx}`} color="blue">
                  {role}
                </Tag>
              ))}
            </span>
          );
        }
        return <>—</>;
      },
    },
    {
      title: "Attributes",
      key: "attributes",
      render: (_value: unknown, record: AccessControlData) => {
        if (
          (record.properties as unknown as AccessControlProperty)
            ?.accessControlType === AccessControlType.ABAC
        ) {
          return (
            <span
              style={{ cursor: "pointer", color: "#1890ff" }}
              onClick={(e) => {
                e.stopPropagation();
                showModal({
                  component: <AbacAttributesPopUp record={record} />,
                });
              }}
            >
              Details
            </span>
          );
        } else return <>{"—"}</>;
      },
    },
    {
      title: "Chain",
      key: "chain",
      filterDropdown: (props: FilterDropdownProps) => (
        <TextColumnFilterDropdown {...props} />
      ),
      onFilter: getTextColumnFilterFn((record: AccessControlData) =>
        record?.chainName ? record.chainName : "",
      ),
      render: (_value: unknown, record: AccessControlData) => {
        if (record.chainId) {
          return (
            <a onClick={() => void navigate(`/chains/${record.chainId}`)}>
              {record.chainName}
            </a>
          );
        }
        return <>—</>;
      },
    },
    {
      title: "Chain Status",
      key: "chainStatus",
      filterDropdown: chainStatusFilter,
      onFilter: (value: React.Key | boolean, record: AccessControlData) => {
        if (
          !record.chainId ||
          !record.deploymentStatus ||
          record.deploymentStatus.length === 0
        ) {
          return value === "DRAFT";
        }
        const statuses = new Set(
          record.deploymentStatus.map((s) => s.toUpperCase()),
        );
        if (statuses.size === 0) {
          return value === "DRAFT";
        } else if (statuses.size === 1) {
          return statuses.has(value as string);
        } else {
          const priority = ["PROCESSING", "FAILED", "DEPLOYED", "DRAFT"];
          for (const status of priority) {
            if (statuses.has(status)) {
              return status === value;
            }
          }
          return false;
        }
      },
      render: (_value: unknown, record: AccessControlData) => {
        if (record.chainId) {
          return (
            <DeploymentsCumulativeState
              chainId={record.chainId}
              isNotificationEnabled={!deployedChainIds.has(record.chainId)}
            />
          );
        }
        return <>—</>;
      },
    },
  ];

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<AccessControlData>(
      "accessControlTable",
      columns,
    );

  const hasUnsavedChanges = (data: AccessControlData): boolean => {
    return (
      data.unsavedChanges &&
      getCumulativeStatus(new Set(data.deploymentStatus)) === "DEPLOYED"
    );
  };

  const buildRowKey = (row: AccessControlData): string => {
    return `${row.chainId}-${row.elementId}`;
  };

  const redeployButtonDisabled = (): boolean => {
    if (
      selectedRowKeys.length === 0 ||
      !accessControlData?.roles ||
      accessControlData?.roles.length === 0
    ) {
      return true;
    }
    const selectedWithUnsavedChanges = accessControlData.roles
      .filter((row) => selectedRowKeys.includes(buildRowKey(row)))
      .filter((row) => hasUnsavedChanges(row));

    return selectedRowKeys.length !== selectedWithUnsavedChanges.length;
  };

  const unsavedChangesButtonDisabled = (): boolean => {
    if (!accessControlData?.roles || accessControlData?.roles.length === 0) {
      return true;
    }

    return !accessControlData.roles.some((row) => hasUnsavedChanges(row));
  };

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        orderedColumns,
        accessControlColumnResize.columnWidths,
        accessControlColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      orderedColumns,
      accessControlColumnResize.columnWidths,
      accessControlColumnResize.createResizeHandlers,
    ],
  );

  return (
    <Flex vertical className={commonStyles["container"]}>
      <Flex className={commonStyles["header"]}>
        <Title level={4} className={commonStyles["title"]}>
          <OverridableIcon
            name="accessControl"
            className={commonStyles["icon"]}
          />
          Access Control
        </Title>
        <Flex
          vertical={false}
          align="center"
          gap={8}
          wrap="wrap"
          className={commonStyles["actions"]}
        >
          <CompactSearch
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder="Search access control..."
            allowClear
            className={commonStyles["searchField"] as string}
          />
          {columnSettingsButton}
          <Tooltip title="Select Unsaved Chains" placement="bottom">
            <Button
              icon={<OverridableIcon name="checkSquare" />}
              onClick={selectUnsavedChains}
              disabled={unsavedChangesButtonDisabled()}
            />
          </Tooltip>
          <ProtectedButton
            require={{ deployment: ["create"] }}
            tooltipProps={{ title: "Redeploy", placement: "bottom" }}
            buttonProps={{
              iconName: "send",
              disabled: redeployButtonDisabled(),
              onClick: () => {
                if (selectedRowKeys.length === 0) {
                  notificationService.info(
                    "No selection",
                    "Please select at least one row to modify",
                  );
                  return;
                }
                const selectedRecords = (accessControlData?.roles ?? []).filter(
                  (record: AccessControlData) => {
                    return selectedRowKeys.includes(buildRowKey(record));
                  },
                );
                if (selectedRecords.length > 0) {
                  void handleBulkDeploy(selectedRecords);
                } else {
                  notificationService.info(
                    "Error",
                    "Selected records not found",
                  );
                }
              },
            }}
          />
          <ProtectedButton
            require={{ chain: ["update"] }}
            tooltipProps={{ title: "Add Roles", placement: "bottom" }}
            buttonProps={{
              iconName: "plus",
              onClick: () => {
                if (selectedRowKeys.length === 0) {
                  notificationService.info(
                    "No selection",
                    "Please select at least one row to modify",
                  );
                  return;
                }
                const selectedRecords = (accessControlData?.roles ?? []).filter(
                  (record: AccessControlData) => {
                    return selectedRowKeys.includes(buildRowKey(record));
                  },
                );
                if (selectedRecords.length > 0) {
                  const validRecords = selectedRecords.filter((record) => {
                    const accessControlType = (
                      record.properties as unknown as AccessControlProperty
                    )?.accessControlType;
                    return accessControlType !== AccessControlType.ABAC;
                  });
                  if (validRecords.length === 0) {
                    notificationService.info(
                      "Error",
                      "Can't apply roles to ABAC endpoint",
                    );
                    return;
                  }
                  showModal({
                    component: (
                      <AddDeleteRolesPopUp
                        records={validRecords}
                        onSuccess={() => void getAccessControl()}
                      />
                    ),
                  });
                } else {
                  notificationService.info(
                    "Error",
                    "Selected record not found",
                  );
                }
              },
            }}
          />
          <ProtectedButton
            require={{ chain: ["update"] }}
            tooltipProps={{ title: "Delete Roles", placement: "bottom" }}
            buttonProps={{
              iconName: "minus",
              onClick: () => {
                if (selectedRowKeys.length === 0) {
                  notificationService.info(
                    "No selection",
                    "Please select at least one row to delete roles",
                  );
                  return;
                }
                const selectedRecords = (accessControlData?.roles ?? []).filter(
                  (record: AccessControlData) => {
                    return selectedRowKeys.includes(buildRowKey(record));
                  },
                );
                if (selectedRecords.length > 0) {
                  const validRecords = selectedRecords.filter((record) => {
                    const accessControlType = (
                      record.properties as unknown as AccessControlProperty
                    )?.accessControlType;
                    return accessControlType !== AccessControlType.ABAC;
                  });
                  if (validRecords.length === 0) {
                    notificationService.info(
                      "Error",
                      "Can't apply roles to ABAC endpoint",
                    );
                    return;
                  }
                  showModal({
                    component: (
                      <AddDeleteRolesPopUp
                        records={validRecords}
                        mode="delete"
                        onSuccess={() => void getAccessControl()}
                      />
                    ),
                  });
                } else {
                  notificationService.info(
                    "Error",
                    "Selected record not found",
                  );
                }
              },
            }}
          />
          <Tooltip title="Refresh" placement="bottom">
            <Button
              icon={<OverridableIcon name="refresh" />}
              onClick={() => void getAccessControl()}
            />
          </Tooltip>
        </Flex>
      </Flex>
      <Flex
        style={{
          flex: "1 1 auto",
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          borderRadius: "8px",
          overflow: "hidden",
        }}
      >
        {currentRecord && (
          <Drawer
            title="Endpoint Details"
            placement="right"
            open={openSidebar}
            closable={false}
            onClose={onClose}
          >
            <Descriptions column={1} size="small" layout="vertical">
              {hasUnsavedChanges(currentRecord) && (
                <Descriptions.Item>
                  <Tag color="blue" style={{ fontSize: 13 }}>
                    <Space>
                      <OverridableIcon name="warning" />
                      Unsaved Changes
                    </Space>
                  </Tag>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="Endpoint">
                {currentRecord.chainId ? (
                  <a
                    href={`/chains/${currentRecord?.chainId}/graph/${currentRecord?.elementId}`}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {(
                      currentRecord.properties as unknown as AccessControlProperty
                    )?.contextPath || "—"}
                  </a>
                ) : (
                  "—"
                )}
              </Descriptions.Item>
              <Descriptions.Item label="Type">
                {(() => {
                  const props = currentRecord.properties as unknown as
                    | AccessControlProperty
                    | undefined;
                  const { externalRoute, privateRoute } = props ?? {};
                  return externalRoute && privateRoute
                    ? "External, Private"
                    : externalRoute
                      ? "External"
                      : privateRoute
                        ? "Private"
                        : "Internal";
                })()}
              </Descriptions.Item>
              <Descriptions.Item label="Access Control Type">
                {(() => {
                  const props = currentRecord.properties as unknown as
                    | AccessControlProperty
                    | undefined;
                  const val = props?.accessControlType;
                  return typeof val === "string"
                    ? val
                    : val != null
                      ? String(val)
                      : "—";
                })()}
              </Descriptions.Item>
              <Descriptions.Item label="Roles">
                {(() => {
                  const props = currentRecord.properties as unknown as
                    | AccessControlProperty
                    | undefined;
                  const roles = props?.roles;
                  return roles && Array.isArray(roles) && roles.length > 0 ? (
                    <span>
                      {roles.map((role, idx) => (
                        <Tag
                          key={`${role}-${idx}`}
                          color="blue"
                          style={{ marginBottom: 4 }}
                        >
                          {role}
                        </Tag>
                      ))}
                    </span>
                  ) : (
                    "—"
                  );
                })()}
              </Descriptions.Item>
              <Descriptions.Item label="Attributes">
                {(currentRecord.properties as unknown as AccessControlProperty)
                  ?.accessControlType === AccessControlType.ABAC ? (
                  <span
                    style={{ cursor: "pointer", color: "#1890ff" }}
                    onClick={() => {
                      if (currentRecord) {
                        showModal({
                          component: (
                            <AbacAttributesPopUp record={currentRecord} />
                          ),
                        });
                      }
                    }}
                  >
                    Details
                  </span>
                ) : (
                  "—"
                )}
              </Descriptions.Item>
              <Descriptions.Item label="Chain">
                {currentRecord.chainId ? (
                  <a
                    onClick={() =>
                      void navigate(`/chains/${currentRecord?.chainId}`)
                    }
                  >
                    {currentRecord.chainName}
                  </a>
                ) : (
                  "—"
                )}
              </Descriptions.Item>
              <Descriptions.Item label="Chain Status">
                {currentRecord.chainId ? (
                  <DeploymentsCumulativeState
                    chainId={currentRecord.chainId}
                    isNotificationEnabled={
                      !deployedChainIds.has(currentRecord.chainId)
                    }
                  />
                ) : (
                  "—"
                )}
              </Descriptions.Item>
              <Descriptions.Item label="Chain ID">
                {currentRecord.chainId || "—"}
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
            <Table<AccessControlData>
              className="flex-table"
              size="small"
              columns={columnsWithResize}
              dataSource={filteredRoles}
              scroll={{
                x: accessControlColumnResize.totalColumnsWidth,
                y: containerHeight - 59 || 400,
              }}
              pagination={false}
              rowKey={(record: AccessControlData) => buildRowKey(record)}
              loading={isLoading}
              onScroll={(event) => void onScroll(event)}
              rowSelection={{
                selectedRowKeys,
                onChange: (newSelectedRowKeys) => {
                  setSelectedRowKeys(newSelectedRowKeys);
                },
              }}
              components={accessControlColumnResize.resizableHeaderComponents}
              rowClassName={(row) =>
                hasUnsavedChanges(row) ? "highlight-row" : ""
              }
              onRow={(row: AccessControlData) => {
                return {
                  onClick: (event: React.MouseEvent<HTMLElement>) => {
                    const target = event.target as HTMLElement;
                    if (
                      target.closest("button") ||
                      target.closest(".ant-dropdown") ||
                      target.closest("input") ||
                      target.closest("a") ||
                      target.closest(".ant-checkbox-wrapper") ||
                      target.closest('span[style*="cursor: pointer"]')
                    ) {
                      return;
                    }
                    showDrawer(row);
                  },
                };
              }}
            />
          </div>
        </Flex>
      </Flex>
    </Flex>
  );
};
