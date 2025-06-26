import React from "react";
import { Table, Typography, Spin, Button } from "antd";
import type { ColumnsType } from "antd/es/table";
import type { Engine } from "../../../api/apiTypes.ts";
import DeploymentsTable from "./DeploymentsTable";
import tableStyles from "./Tables.module.css";
import { useDeploymentsForEngine } from "./hooks/useDeploymentsForEngine";
import { DeploymentRuntimeState } from "../../deployment_runtime_states/DeploymentRuntimeState.tsx";

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
    render: (text: string) => (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "flex-end",
        }}
      >
        <DeploymentRuntimeState
          name={text}
          service={""}
          timestamp={Date.now()}
          runtimeState={{
            status: text,
            error: "",
            stacktrace: "",
            suspended: false,
          }}
        />
      </div>
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
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "flex-end",
          }}
        >
          <DeploymentRuntimeState
            name={statusText}
            service={""}
            timestamp={Date.now()}
            runtimeState={{
              status: statusText,
              error: "",
              stacktrace: "",
              suspended: false,
            }}
          />
        </div>
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
        <Button onClick={() => retry()}>Retry</Button>
      </Typography.Text>
    );
  }

  return <DeploymentsTable deployments={deployments} isLoading={isLoading} />;
};

const EngineTable: React.FC<Props> = ({
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

export default EngineTable;
