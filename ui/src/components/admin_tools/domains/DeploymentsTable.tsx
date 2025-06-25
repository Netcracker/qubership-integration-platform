import React from 'react';
import { Table, Typography, Spin } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ChainDeployment } from '../../../api/apiTypes.ts';
import tableStyles from './Tables.module.css';
import { DeploymentRuntimeState } from '../../deployment_runtime_states/DeploymentRuntimeState';

interface Props {
  deployments: ChainDeployment[];
  isLoading?: boolean;
}

const DeploymentsTable: React.FC<Props> = ({ deployments, isLoading = false }) => {
  const columns: ColumnsType<ChainDeployment> = [
    {
      title: <span className={tableStyles.columnHeader}>Chain Name</span>,
      dataIndex: 'chainName',
      key: 'chainName',
      render: (text, record) => <Typography.Link href={`/chains/${record.chainId}/deployments`}>{text}</Typography.Link>,
    },
    {
      title: <span className={tableStyles.columnHeader}>Snapshot Name</span>,
      dataIndex: 'snapshotName',
      key: 'snapshotName',
      render: (text) => <Typography.Text type="secondary">{text}</Typography.Text>,
      align: 'right',
    },
    {
      title: <span className={tableStyles.columnHeader}>State</span>,
      key: 'state',
      render: (_, record) => (
        <DeploymentRuntimeState
          name={record.state.status}
          service={record.chainName}
          timestamp={Date.now()}
          runtimeState={{
            status: record.state.status,
            error: '',
            stacktrace: '',
            suspended: record.state.suspended,
          }}
        />
      ),
      align: 'right',
      width: '18%',
    },
  ];

  return (
    <div className={tableStyles.table}>
      <Spin spinning={isLoading}>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={deployments}
          pagination={false}
          size="small"
          className={tableStyles.smallHeaderTable}
        />
      </Spin>
    </div>
  );
};

export default DeploymentsTable;

