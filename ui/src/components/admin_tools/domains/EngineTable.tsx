import React from "react";
import { Alert, Button, Flex, Spin, Table, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Engine, RunningStatus } from "../../../api/apiTypes.ts";
import { DeploymentsTable } from "./DeploymentsTable";
import { treeExpandIcon } from "../../table/TreeExpandIcon";
import { useDeploymentsForEngine } from "./hooks/useDeploymentsForEngine";
import { RunningStatusValue } from "./RunningStatusValue.tsx";
import { useTableConfiguration } from "../../table/useTableConfiguration.tsx";
import layoutStyles from "./DomainsTablesLayout.module.css";
import { PodStatusValue } from "./PodStatusValue.tsx";

interface Props {
  onRefresh?: () => Promise<void>;
  engines: Engine[];
  isLoading?: boolean;
  domainName: string;
}

const columns: ColumnsType<Engine> = [
  {
    title: "Engine Name",
    dataIndex: "name",
    key: "name",
    render: (text) => <Typography.Text strong>{text}</Typography.Text>,
  },
  {
    title: "Pod address",
    dataIndex: "host",
    key: "host",
    render: (text) => (
      <Typography.Text type="secondary">{text}</Typography.Text>
    ),
  },
  {
    title: "State",
    dataIndex: "runningStatus",
    key: "runningStatus",
    render: (status: RunningStatus) => (
        <RunningStatusValue status={status} />
    ),
  },
  {
    title: "Pod status",
    dataIndex: "ready",
    key: "ready",
    render: (ready: boolean) => {
      return <PodStatusValue ready={ready} />;
    },
  },
];

const DeploymentsForEngine: React.FC<{
  engine: Engine;
  domainName: string;
}> = ({ engine, domainName }) => {
  const { deployments, isLoading, error, retry } = useDeploymentsForEngine(
    domainName,
    engine.host,
  );
  if (error) {
    return (
      <Flex align="center" gap={8} wrap="wrap">
        <Alert title={`Error while loading engines list ${engine.name}`} />
        <Button onClick={() => void retry()}>Retry</Button>
      </Flex>
    );
  }

  return <DeploymentsTable deployments={deployments} isLoading={isLoading} />;
};

export const EngineTable: React.FC<Props> = ({
  engines,
  isLoading = false,
  domainName,
}) => {
  const [expandedRowKeys, setExpandedRowKeys] = React.useState<React.Key[]>([]);

  const { columnsWithResize, components } = useTableConfiguration(
    columns,
    {
      name: 220,
      host: 200,
      runningStatus: 160,
      ready: 140,
    },
    {},
    "enginesTable",
  );

  return (
      <Spin spinning={isLoading}>
        <Table
          rowKey="id"
          className={layoutStyles.nestedTable}
          columns={columnsWithResize}
          dataSource={engines}
          pagination={false}
          size="small"
          tableLayout="fixed"
          components={components}
          expandable={{
            expandIcon: treeExpandIcon(),
            expandedRowRender: (engine) => (
                <DeploymentsForEngine engine={engine} domainName={domainName} />
            ),
            expandedRowKeys: expandedRowKeys,
            onExpandedRowsChange: (expandedKeys) =>
              setExpandedRowKeys(expandedKeys as React.Key[]),
            rowExpandable: () => true,
          }}
        />
      </Spin>
  );
};
