import React from "react";
import { Button, Flex, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Engine, RunningStatus } from "../../../api/apiTypes.ts";
import { DeploymentsTable } from "./DeploymentsTable";
import { treeExpandIcon } from "../../table/TreeExpandIcon";
import { useDeploymentsForEngine } from "./hooks/useDeploymentsForEngine";
import { RunningStatusValue } from "./RunningStatusValue.tsx";
import { useColumnsWithResizeAndScroll } from "../../table/useColumnsWithResizeAndScroll.tsx";
import layoutStyles from "./DomainsTablesLayout.module.css";

interface Props {
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
    align: "right",
  },
  {
    title: "State",
    dataIndex: "runningStatus",
    key: "runningStatus",
    render: (status: RunningStatus) => (
      <Flex align={"center"} justify={"flex-end"}>
        <RunningStatusValue status={status} />
      </Flex>
    ),
    align: "right",
  },
  {
    title: "Pod status",
    dataIndex: "ready",
    key: "ready",
    render: (ready: boolean) => {
      const statusText = ready ? "Ready" : "Not Ready";
      return (
        <Flex align={"center"} justify={"flex-end"}>
          <Tag color={ready ? "green" : "red"}>{statusText.toUpperCase()}</Tag>
        </Flex>
      );
    },
    align: "right",
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
        <Typography.Text type="danger" style={{ margin: 0 }}>
          Error while loading engines list {engine.name}.
        </Typography.Text>
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

  const { columnsWithResize, components } = useColumnsWithResizeAndScroll(
    columns,
    {
      name: 220,
      host: 200,
      runningStatus: 160,
      ready: 140,
    },
  );

  return (
    <div className={layoutStyles.nestedTableHost}>
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
              <div className={layoutStyles.nestedExpandWrap}>
                <DeploymentsForEngine engine={engine} domainName={domainName} />
              </div>
            ),
            expandedRowKeys: expandedRowKeys,
            onExpandedRowsChange: (expandedKeys) =>
              setExpandedRowKeys(expandedKeys as React.Key[]),
            rowExpandable: () => true,
          }}
        />
      </Spin>
    </div>
  );
};
