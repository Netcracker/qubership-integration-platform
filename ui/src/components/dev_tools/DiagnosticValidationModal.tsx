import React from "react";
import { Button, Form, Modal, Typography } from "antd";
import { useModalContext } from "../../ModalContextProvider";
import { DiagnosticValidation, ValidationSeverity } from "../../api/apiTypes";
import {
  KeyValuePropertiesTable,
  KeyValueRow,
} from "./KeyValuePropertiesTable";

const { Text } = Typography;

enum ValidationImplementationType {
  BUILT_IN = "BUILT_IN",
  PLUGIN = "PLUGIN",
}

const IMPLEMENTATION_TYPE_TO_LABEL: { [key: string]: string } = {
  [ValidationImplementationType.BUILT_IN]: "Built-in",
  [ValidationImplementationType.PLUGIN]: "Custom",
};

const SEVERITY_TO_LABEL: { [key: string]: string } = {
  [ValidationSeverity.WARNING]: "Warning",
  [ValidationSeverity.ERROR]: "Error",
};

export const DiagnosticValidationModal: React.FC<DiagnosticValidation> = (
  props,
) => {
  const { closeContainingModal } = useModalContext();

  const buildProperties = (properties: {
    [key: string]: unknown;
  }): KeyValueRow[] => {
    return Object.entries(properties).map(([key, value]) => ({
      key: key,
      value: value as string,
    }));
  };

  return (
    <Modal
      title="Validation Details"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
      ]}
    >
      <Form<DiagnosticValidation> initialValues={props} layout="vertical">
        <Form.Item label="Id">
          <Text>{props.id}</Text>
        </Form.Item>
        <Form.Item label="Name">
          <Text>{props.title}</Text>
        </Form.Item>
        <Form.Item label="Description">
          <Text>{props.description}</Text>
        </Form.Item>
        <Form.Item label="Hint">
          <Text>{props.suggestion}</Text>
        </Form.Item>
        <Form.Item label="Validation Source">
          <Text>{IMPLEMENTATION_TYPE_TO_LABEL[props.implementationType]}</Text>
        </Form.Item>
        <Form.Item label="Severity">
          <Text>{SEVERITY_TO_LABEL[props.severity]}</Text>
        </Form.Item>
        <Form.Item label="Alerts">
          <Text>{props.alertsCount}</Text>
        </Form.Item>
      </Form>
      <KeyValuePropertiesTable rows={buildProperties(props.properties)} />
    </Modal>
  );
};
