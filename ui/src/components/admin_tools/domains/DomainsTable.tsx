import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Flex, Table, Button, Typography, Space, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import { EngineTable } from "./EngineTable";
import { useEngines } from "./hooks/useEngines";
import { treeExpandIcon } from "../../table/TreeExpandIcon";
import { DomainType, EngineDomain } from "../../../api/apiTypes.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import { TableToolbar } from "../../table/TableToolbar.tsx";
import { matchesByFields } from "../../table/tableSearch.ts";
import { AdminToolsHeader } from "../AdminToolsHeader.tsx";
import { TablePageLayout } from "../../TablePageLayout.tsx";

/** rc-table expand icon column; not in `columns` but affects horizontal layout. */
const DOMAINS_EXPAND_COLUMN_WIDTH = 48;

interface Props {
  domains: EngineDomain[];
  isLoading?: boolean;
}

const EnginesForDomain: React.FC<{ domain: EngineDomain }> = ({ domain }) => {
  const { engines, isLoading, error, retry } = useEngines(domain.name);

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
    />
  );
};

function domainMatchesSearch(domain: EngineDomain, term: string): boolean {
  return matchesByFields(term, [
    domain.name,
    domain.version ?? "",
    domain.namespace ?? "",
    domain.replicas ?? "",
  ]);
}

const DomainsTable: React.FC<Props> = ({ domains, isLoading = false }) => {
  const [tableData, setTableData] = useState<EngineDomain[]>([]);
  const [filteredData, setFilteredData] = useState<EngineDomain[]>([]);
  const [searchTerm, setSearchTerm] = useState("");
  const notificationsService = useNotificationService();

  useEffect(() => {
    setTableData(domains);
  }, [domains]);

  useEffect(() => {
    setFilteredData(
      tableData.filter((d) => domainMatchesSearch(d, searchTerm)),
    );
  }, [tableData, searchTerm]);

  const deleteMicroDomain = useCallback(
    async (name: string) => {
      try {
        await api.deleteMicroDomain(name);
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

  const columns: ColumnsType<EngineDomain> = useMemo(
    () => [
      {
        title: "Domain",
        dataIndex: "name",
        key: "name",
        render: (_: unknown, domain: EngineDomain) => {
          return domain.type === DomainType.MICRO ? (
            <Space size={"small"}>
              {domain.name}
              <Tag>micro</Tag>
              <Button
                size={"small"}
                type={"text"}
                icon={<OverridableIcon name="delete" />}
                onClick={() => void deleteMicroDomain(domain.name)}
              />
            </Space>
          ) : (
            domain.name
          );
        },
      },
      {
        title: "Version",
        dataIndex: "version",
        key: "version",
        align: "right",
      },
      {
        title: "Desired engines",
        dataIndex: "replicas",
        key: "replicas",
        align: "right",
      },
      {
        title: "Namespace",
        dataIndex: "namespace",
        key: "namespace",
        align: "right",
      },
    ],
    [deleteMicroDomain],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<EngineDomain>(
      "domainsAdminTable",
      columns,
    );

  const domainsColumnResize = useTableColumnResize({
    name: 200,
    version: 120,
    replicas: 140,
    namespace: 220,
  });

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        orderedColumns,
        domainsColumnResize.columnWidths,
        domainsColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      orderedColumns,
      domainsColumnResize.columnWidths,
      domainsColumnResize.createResizeHandlers,
    ],
  );

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        domainsColumnResize.columnWidths,
        { expandColumnWidth: DOMAINS_EXPAND_COLUMN_WIDTH },
      ),
    [columnsWithResize, domainsColumnResize.columnWidths],
  );

  const [expandedRowKeys, setExpandedRowKeys] = React.useState<React.Key[]>([]);

  useEffect(() => {
    if (filteredData.length > 0) {
      setExpandedRowKeys(filteredData.map((domain) => domain.id));
    } else {
      setExpandedRowKeys([]);
    }
  }, [filteredData]);

  return (
    <Flex vertical style={{ width: "100%", flex: 1, minHeight: 0 }}>
      <AdminToolsHeader
        title="Domains"
        iconName="domains"
        toolbar={
          <TableToolbar
            variant="admin"
            search={{
              value: searchTerm,
              onChange: setSearchTerm,
              placeholder: "Search domains...",
              allowClear: true,
            }}
            columnSettingsButton={columnSettingsButton}
          />
        }
      />
      <TablePageLayout>
        <Table<EngineDomain>
          className="flex-table"
          size="small"
          columns={columnsWithResize}
          dataSource={filteredData}
          loading={isLoading}
          pagination={false}
          style={{ flex: 1, minHeight: 0 }}
          scroll={
            filteredData.length > 0 ? { x: scrollX, y: "" } : { x: scrollX }
          }
          components={domainsColumnResize.resizableHeaderComponents}
          expandable={{
            expandIcon: treeExpandIcon(),
            expandedRowRender: (record) => <EnginesForDomain domain={record} />,
            expandedRowKeys: expandedRowKeys,
            onExpandedRowsChange: (expandedKeys) =>
              setExpandedRowKeys(expandedKeys as React.Key[]),
            rowExpandable: () => true,
          }}
          rowKey="id"
        />
      </TablePageLayout>
    </Flex>
  );
};

export default DomainsTable;
