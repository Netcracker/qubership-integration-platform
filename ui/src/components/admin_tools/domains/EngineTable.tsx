import React from "react";
import { Button, Flex, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Engine, RunningStatus } from "../../../api/apiTypes.ts";
import { DeploymentsTable } from "./DeploymentsTable";
import tableStyles from "./Tables.module.css";
import { useDeploymentsForEngine } from "./hooks/useDeploymentsForEngine";
import { RunningStatusValue } from "./RunningStatusValue.tsx";

interface Props {
  engines: Engine[];
  isLoading?: boolean;
  domainName: string;
}

const columns: ColumnsType<Engine> = [
  {
    title: <span className={tableStyles.columnHeader}>Engine Name</span>,
    dataIndex: "name",
    key: "name",
    render: (text) => <Typography.Text strong>{text}</Typography.Text>,
  },
  {
    title: <span className={tableStyles.columnHeader}>Pod address</span>,
    dataIndex: "host",
    key: "host",
    render: (text) => (
      <Typography.Text type="secondary">{text}</Typography.Text>
    ),
    align: "right",
    width: "20%",
  },
  {
    title: <span className={tableStyles.columnHeader}>State</span>,
    dataIndex: "runningStatus",
    key: "runningStatus",
    render: (status: RunningStatus) => (
      <Flex align={"center"} justify={"flex-end"}>
        <RunningStatusValue status={status} />
      </Flex>
    ),
    align: "right",
    width: "15%",
  },
  {
    title: <span className={tableStyles.columnHeader}>Pod status</span>,
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
    width: "18%",
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
      <Typography.Text type="danger">
        Error while loading engines list {engine.name}.{" "}
        <Button onClick={() => void retry()}>Retry</Button>
      </Typography.Text>
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

  React.useEffect(() => {
    if (engines.length > 0) {
      setExpandedRowKeys(engines.map((engine) => engine.id));
    }
  }, [engines]);

  return (
    <div>
      <Spin spinning={isLoading}>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={engines}
          pagination={false}
          size="small"
          expandable={{
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
    </div>
  );
};
