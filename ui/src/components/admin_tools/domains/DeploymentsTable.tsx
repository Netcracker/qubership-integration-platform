import React from "react";
import { Table, Typography, Spin } from "antd";
import type { ColumnsType } from "antd/es/table";
import type { ChainDeployment } from "../../../api/apiTypes.ts";
import { DeploymentRuntimeState } from "../../deployment_runtime_states/DeploymentRuntimeState";
import { useColumnsWithResizeAndScroll } from "../../table/useColumnsWithResizeAndScroll.tsx";
import layoutStyles from "./DomainsTablesLayout.module.css";

interface Props {
  deployments: ChainDeployment[];
  isLoading?: boolean;
}

const deploymentColumns: ColumnsType<ChainDeployment> = [
  {
    title: "Chain Name",
    dataIndex: "chainName",
    key: "chainName",
    render: (text, record) => (
      <Typography.Link href={`/chains/${record.chainId}/deployments`}>
        {text}
      </Typography.Link>
    ),
  },
  {
    title: "Snapshot Name",
    dataIndex: "snapshotName",
    key: "snapshotName",
    render: (text) => (
      <Typography.Text type="secondary">{text}</Typography.Text>
    ),
    align: "right",
  },
  {
    title: "State",
    key: "state",
    render: (_: unknown, record: ChainDeployment) => (
      <DeploymentRuntimeState
        name={record.state.status}
        service={record.chainName}
        timestamp={Date.now()}
        runtimeState={record.state}
      />
    ),
    align: "right",
  },
];

export const DeploymentsTable: React.FC<Props> = ({
  deployments,
  isLoading = false,
}) => {
  const { columnsWithResize, components } = useColumnsWithResizeAndScroll(
    deploymentColumns,
    {
      chainName: 240,
      snapshotName: 200,
      state: 220,
    },
  );

  return (
    <div className={layoutStyles.nestedTableHost}>
      <Spin spinning={isLoading}>
        <Table
          rowKey="id"
          className={layoutStyles.nestedTable}
          columns={columnsWithResize}
          dataSource={deployments}
          pagination={false}
          size="small"
          tableLayout="fixed"
          components={components}
        />
      </Spin>
    </div>
  );
};
