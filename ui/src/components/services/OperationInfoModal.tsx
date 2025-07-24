import React from 'react';
import { Modal, Tabs } from 'antd';
import { OperationInfo } from '../../api/apiTypes';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { prism } from 'react-syntax-highlighter/dist/esm/styles/prism';
import styles from './Services.module.css';

interface OperationInfoModalProps {
  visible: boolean;
  onClose: () => void;
  operationInfo?: OperationInfo;
  loading?: boolean;
}

export const OperationInfoModal: React.FC<OperationInfoModalProps> = ({
  visible,
  onClose,
  operationInfo,
  loading,
}) => {
  const prismStyle = prism as Record<string, unknown>;

  const renderJsonTabContent = (data: unknown) => (
    <SyntaxHighlighter language="json" style={prismStyle} className={styles.codeBlock}>
      {loading ? '{}' : JSON.stringify(data, null, 2)}
    </SyntaxHighlighter>
  );

  return (
    <Modal
      open={visible}
      onCancel={onClose}
      onOk={onClose}
      title="Operation info"
      width={700}
      footer={null}
      destroyOnHidden
    >
      <Tabs
        defaultActiveKey="specification"
        items={[
          {
            key: 'specification',
            label: 'Specification',
            children: renderJsonTabContent(operationInfo?.specification),
          },
          {
            key: 'request',
            label: 'Request schema',
            children: renderJsonTabContent(operationInfo?.requestSchema),
          },
          {
            key: 'response',
            label: 'Response schemas',
            children: renderJsonTabContent(operationInfo?.responseSchemas),
          },
        ]}
      />
    </Modal>
  );
};
