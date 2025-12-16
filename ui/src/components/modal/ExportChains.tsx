import React, { useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { Button, Form, Modal } from "antd";
import Checkbox from "antd/lib/checkbox";
import styles from "./ExportChains.module.css";

export type ExportChainOptions = {
  exportSubchains: boolean;
  exportServices: boolean;
  exportVariables: boolean;
};

type ExportChainsProps = {
  multiple?: boolean;
  onSubmit?: (options: ExportChainOptions) => void;
};

export const ExportChains: React.FC<ExportChainsProps> = ({
  multiple,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [confirmLoading, setConfirmLoading] = useState(false);

  return (
    <Modal
      className={styles.modal}
      title={multiple ? "Export Chains" : "Export Chain"}
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="exportOptionsForm"
          htmlType={"submit"}
          loading={confirmLoading}
        >
          Export
        </Button>,
      ]}
    >
      <p>
        Are you sure you want to start the export with selected options? Note,
        that export of large number of entities might take some time.
      </p>
      <Form<ExportChainOptions>
        id="exportOptionsForm"
        initialValues={{
          exportSubchains: false,
          exportServices: false,
          exportVariables: false,
        }}
        onFinish={(values) => {
          setConfirmLoading(true);
          try {
            onSubmit?.(values);
            closeContainingModal();
          } finally {
            setConfirmLoading(false);
          }
        }}
      >
        <Form.Item
          name="exportSubchains"
          valuePropName="checked"
          label={null}
          style={{ marginBottom: 0 }}
        >
          <Checkbox className={styles.checkbox}>Export related sub-chains</Checkbox>
        </Form.Item>
        <Form.Item
          name="exportServices"
          valuePropName="checked"
          label={null}
          style={{ marginBottom: 0 }}
        >
          <Checkbox className={styles.checkbox}>Export related services</Checkbox>
        </Form.Item>
        <Form.Item
          name="exportVariables"
          valuePropName="checked"
          label={null}
          style={{ marginBottom: 0 }}
        >
          <Checkbox className={styles.checkbox}>Export all common variables</Checkbox>
        </Form.Item>
      </Form>
    </Modal>
  );
};
