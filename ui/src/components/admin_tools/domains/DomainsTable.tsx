import React, { useMemo, useState } from "react";
import { Flex, Table, Button, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { EngineTable } from "./EngineTable";
import { useEngines } from "./hooks/useEngines";
import { treeExpandIcon } from "../../table/TreeExpandIcon";
import tableStyles from "./Tables.module.css";
import { EngineDomain } from "../../../api/apiTypes.ts";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import { TableToolbar } from "../../table/TableToolbar.tsx";
import { CompactSearch } from "../../table/CompactSearch.tsx";
import { matchesByFields } from "../../table/tableSearch.ts";
import commonStyles from "../CommonStyle.module.css";

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
  const [searchTerm, setSearchTerm] = useState("");

  const filteredDomains = useMemo(
    () => domains.filter((d) => domainMatchesSearch(d, searchTerm)),
    [domains, searchTerm],
  );

  const columns: ColumnsType<EngineDomain> = useMemo(
    () => [
      {
        title: <span className={tableStyles.columnHeader}>Domain</span>,
        dataIndex: "name",
        key: "name",
      },
      {
        title: <span className={tableStyles.columnHeader}>Version</span>,
        dataIndex: "version",
        key: "version",
        align: "right",
      },
      {
        title: (
          <span className={tableStyles.columnHeader}>Desired engines</span>
        ),
        dataIndex: "replicas",
        key: "replicas",
        align: "right",
      },
      {
        title: <span className={tableStyles.columnHeader}>Namespace</span>,
        dataIndex: "namespace",
        key: "namespace",
        align: "right",
      },
    ],
    [],
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

  React.useEffect(() => {
    if (filteredDomains.length > 0) {
      setExpandedRowKeys(filteredDomains.map((domain) => domain.id));
    } else {
      setExpandedRowKeys([]);
    }
  }, [filteredDomains]);

  return (
    <Flex vertical gap={8} style={{ width: "100%" }}>
      <TableToolbar
        leading={
          <CompactSearch
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder="Search domains..."
            allowClear
            className={commonStyles.searchField as string}
          />
        }
        trailing={columnSettingsButton}
      />
      <Table
        columns={columnsWithResize}
        dataSource={filteredDomains}
        loading={isLoading}
        pagination={false}
        className={tableStyles.mainTable}
        scroll={{ x: scrollX }}
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
    </Flex>
  );
};

export default DomainsTable;
