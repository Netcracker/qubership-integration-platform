import React from "react";
import { Button, Descriptions, Flex, Modal, Tag } from "antd";
import { useModalContext } from "../../ModalContextProvider";
import { DiagnosticValidation, ValidationSeverity } from "../../api/apiTypes";
import {
  KeyValuePropertiesTable,
  KeyValueRow,
} from "./KeyValuePropertiesTable";

enum ValidationImplementationType {
  BUILT_IN = "BUILT_IN",
  PLUGIN = "PLUGIN",
}

const IMPLEMENTATION_TYPE_TO_LABEL: { [key: string]: string } = {
  [ValidationImplementationType.BUILT_IN]: "Built-in",
  [ValidationImplementationType.PLUGIN]: "Custom",
};

const SEVERITY_COLOR: { [key: string]: string } = {
  [ValidationSeverity.WARNING]: "gold",
  [ValidationSeverity.ERROR]: "red",
};

const SEVERITY_LABEL: { [key: string]: string } = {
  [ValidationSeverity.WARNING]: "Warning",
  [ValidationSeverity.ERROR]: "Error",
};

export const DiagnosticValidationModal: React.FC<DiagnosticValidation> = (
  props,
) => {
  const { closeContainingModal } = useModalContext();

  const buildProperties = (properties: {
    [key: string]: unknown;
  }): KeyValueRow[] =>
    Object.entries(properties).map(([key, value]) => ({
      key,
      value: value as string,
    }));

  return (
    <Modal
      title="Validation Details"
      open={true}
      onCancel={closeContainingModal}
      width={600}
      footer={[
        <Button key="close" onClick={closeContainingModal}>
          Close
        </Button>,
      ]}
    >
      <Flex vertical gap={16}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="ID">
            <span style={{ fontFamily: "monospace", fontSize: 12 }}>
              {props.id}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="Name">{props.title}</Descriptions.Item>
          <Descriptions.Item label="Description">
            {props.description}
          </Descriptions.Item>
          <Descriptions.Item label="Hint">{props.suggestion}</Descriptions.Item>
          <Descriptions.Item label="Source">
            <Tag>{IMPLEMENTATION_TYPE_TO_LABEL[props.implementationType]}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Severity">
            <Tag color={SEVERITY_COLOR[props.severity]}>
              {SEVERITY_LABEL[props.severity]}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Alerts">
            {props.alertsCount}
          </Descriptions.Item>
        </Descriptions>
        <KeyValuePropertiesTable rows={buildProperties(props.properties)} />
      </Flex>
    </Modal>
  );
};
