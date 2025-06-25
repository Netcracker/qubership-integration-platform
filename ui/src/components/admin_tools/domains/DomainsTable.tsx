import React from 'react';
import { Table, Button, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import EngineTable from './EngineTable';
import { useEngines } from './hooks/useEngines';
import tableStyles from './Tables.module.css';
import { EngineDomain } from "../../../api/apiTypes.ts";

interface Props {
  domains: EngineDomain[];
  isLoading?: boolean;
}

const EnginesForDomain: React.FC<{ domain: EngineDomain }> = ({ domain }) => {
  const { engines, isLoading, error, retry } = useEngines(domain.name);

  if (error) {
    return <Typography.Text type="danger">Error while loading list of engines<Button onClick={retry}>Retry</Button></Typography.Text>;
  }

  return <EngineTable engines={engines} isLoading={isLoading} domainName={domain.name}/>;
};

const DomainsTable: React.FC<Props> = ({ domains, isLoading = false }) => {
  const columns: ColumnsType<EngineDomain> = [
    {
      title: <span className={tableStyles.columnHeader}>Domain</span>,
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: <span className={tableStyles.columnHeader}>Version</span>,
      dataIndex: 'version',
      key:  'version',
      align: 'right',
    },
    {
      title: <span className={tableStyles.columnHeader}>Desired engines</span>,
      dataIndex: 'replicas',
      key: 'replicas',
      align: 'right',
      width: '12%',
    },
    {
      title: <span className={tableStyles.columnHeader}>Namespace</span>,
      dataIndex: 'namespace',
      key: 'namespace',
      align: 'right',
      width: '25%',
    },
  ];

  const [expandedRowKeys, setExpandedRowKeys] = React.useState<React.Key[]>([]);

  React.useEffect(() => {
    if (domains.length > 0) {
      setExpandedRowKeys(domains.map(domain => domain.id));
    }
  }, [domains]);

  return (
    <Table
      columns={columns}
      dataSource={domains}
      loading={isLoading}
      pagination={false}
      className={tableStyles.mainTable}
      expandable={{
        expandedRowRender: (record) => <EnginesForDomain domain={record} />,
        expandedRowKeys: expandedRowKeys,
        onExpandedRowsChange: (expandedKeys) => setExpandedRowKeys(expandedKeys as React.Key[]),
        rowExpandable: () => true,
      }}
      rowKey="id"
    />
  );
};

export default DomainsTable;
