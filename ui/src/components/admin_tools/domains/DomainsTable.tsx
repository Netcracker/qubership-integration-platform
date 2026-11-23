import { Table } from "antd";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Flex, Button, Space, Tag, Tooltip, Typography } from "antd";
import { EngineTable } from "./EngineTable";
import { useEngines } from "./hooks/useEngines";
import { treeExpandIcon } from "../../table/TreeExpandIcon";
import { DomainType, EngineDomain } from "../../../api/apiTypes.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { confirmAndRun } from "../../../misc/confirm-utils.ts";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../../table/useColumnSettingsButton.tsx";
import { useTableConfiguration } from "../../table/useTableConfiguration.tsx";
import { tableScroll } from "../../table/tableScroll.ts";
import { TableToolbar } from "../../table/TableToolbar.tsx";
import { matchesByFields } from "../../table/tableSearch.ts";
import { AdminToolsHeader } from "../AdminToolsHeader.tsx";
import { TablePageLayout } from "../../TablePageLayout.tsx";
import layoutStyles from "./DomainsTablesLayout.module.css";
import { createActionsColumnBase } from "../../table/actionsColumn.ts";

interface Props {
  onRefresh?: () => Promise<void>;
  domains: EngineDomain[];
  isLoading?: boolean;
}

const EnginesForDomain: React.FC<{
  domain: EngineDomain;
  reloadKey: number;
}> = ({ domain, reloadKey }) => {
  const { engines, isLoading, error, retry } = useEngines(
    domain.name,
    reloadKey,
  );

  if (error) {
    return (
      <Flex align="center" gap={8} wrap="wrap">
        <Typography.Text type="danger" style={{ margin: 0 }}>
          Error while loading list of engines
        </Typography.Text>
        <Button onClick={() => void retry()}>Retry</Button>
      </Flex>
    );
  }

  return (
    <EngineTable
      engines={engines}
      isLoading={isLoading}
      domainName={domain.name}
      reloadKey={reloadKey}
    />
  );
};

const DomainExpandIcon = treeExpandIcon<EngineDomain>();

function domainMatchesSearch(domain: EngineDomain, term: string): boolean {
  return matchesByFields(term, [
    domain.name,
    domain.version ?? "",
    domain.namespace ?? "",
    domain.replicas ?? "",
  ]);
}

const DomainsTable: React.FC<Props> = ({
  domains,
  isLoading = false,
  onRefresh,
}) => {
  const [tableData, setTableData] = useState<EngineDomain[]>([]);
  const [filteredData, setFilteredData] = useState<EngineDomain[]>([]);
  const [searchTerm, setSearchTerm] = useState("");
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const notificationsService = useNotificationService();

  const toggleRow = useCallback((domain: EngineDomain) => {
    setExpandedRowKeys((keys) =>
      keys.includes(domain.id)
        ? keys.filter((key) => key !== domain.id)
        : [...keys, domain.id],
    );
  }, []);

  const refresh = useCallback(async () => {
    setReloadKey((key) => key + 1);
    await onRefresh?.();
  }, [onRefresh]);

  useEffect(() => {
    setTableData(domains);
  }, [domains]);

  useEffect(() => {
    setFilteredData(
      tableData.filter((d) => domainMatchesSearch(d, searchTerm)),
    );
  }, [tableData, searchTerm]);

  const deleteDomain = useCallback(
    async (name: string) => {
      try {
        await api.deleteDomain(name);
        setTableData((items) => items.filter((domain) => domain.name !== name));
      } catch (e) {
        notificationsService.requestFailed(
          `Failed to delete micro domain ${name}`,
          e,
        );
      }
    },
    [notificationsService],
  );

  const confirmDeleteMicroDomain = useCallback(
    (name: string) => {
      confirmAndRun({
        title: "Delete domain",
        content: "Are you sure you want to permanently delete this domain?",
        onOk: () => void deleteDomain(name),
      });
    },
    [deleteDomain],
  );

  const columns: ColumnsTypeWithSettings<EngineDomain> = useMemo(
    () => [
      {
        title: "Domain",
        dataIndex: "name",
        key: "name",
        settings: {
          visibilityLocked: true,
          orderLocked: true,
        },
        render: (_: unknown, domain: EngineDomain) => (
          <>
            <DomainExpandIcon
              prefixCls="ant-table"
              expanded={expandedRowKeys.includes(domain.id)}
              expandable
              record={domain}
              onExpand={toggleRow}
            />
            <Space size={"small"}>
              <OverridableIcon name="domains" />
              <span>{domain.name}</span>
              {domain.type === DomainType.MICRO && <Tag>micro</Tag>}
            </Space>
          </>
        ),
      },
      {
        title: "Version",
        dataIndex: "version",
        key: "version",
      },
      {
        title: "Desired engines",
        dataIndex: "replicas",
        key: "replicas",
      },
      {
        title: "Namespace",
        dataIndex: "namespace",
        key: "namespace",
      },
      {
        ...createActionsColumnBase<EngineDomain>(),
        render: (_: unknown, domain: EngineDomain) =>
          domain.type === DomainType.MICRO ? (
            <Tooltip title="Delete domain">
              <Button
                className={layoutStyles.deleteButton}
                size={"small"}
                type={"text"}
                icon={<OverridableIcon name="delete" />}
                onClick={() => confirmDeleteMicroDomain(domain.name)}
              />
            </Tooltip>
          ) : (
            <></>
          ),
      },
    ],
    [confirmDeleteMicroDomain, expandedRowKeys, toggleRow],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<EngineDomain>(
      "domainsAdminTable",
      columns,
    );

  const {
    columnsWithResize,
    scrollX,
    components,
    handleTableChange: handleConfiguredTableChange,
  } = useTableConfiguration(
    orderedColumns,
    {
      name: 200,
      version: 120,
      replicas: 140,
      namespace: 220,
    },
    {},
    "domainsAdminTable",
  );

  return (
    <Flex vertical className={layoutStyles.tableSection}>
      <AdminToolsHeader
        title="Domains"
        toolbar={
          <TableToolbar
            refresh={
              onRefresh ? { onRefresh: refresh, loading: isLoading } : undefined
            }
            variant="admin"
            search={{
              value: searchTerm,
              onChange: setSearchTerm,
              placeholder: "Search domains",
            }}
            columnSettingsButton={columnSettingsButton}
          />
        }
      />
      <TablePageLayout>
        <Table<EngineDomain>
          className={`flex-table ${layoutStyles.mainTable}`}
          size="small"
          tableLayout="fixed"
          columns={columnsWithResize}
          dataSource={filteredData}
          loading={isLoading}
          pagination={false}
          scroll={tableScroll(scrollX, filteredData.length)}
          components={components}
          onChange={handleConfiguredTableChange}
          expandable={{
            showExpandColumn: false,
            expandedRowRender: (record) => (
              <EnginesForDomain domain={record} reloadKey={reloadKey} />
            ),
            expandedRowKeys,
          }}
          rowKey="id"
        />
      </TablePageLayout>
    </Flex>
  );
};

export default DomainsTable;
