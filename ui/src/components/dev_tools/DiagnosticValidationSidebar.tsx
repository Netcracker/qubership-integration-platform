import React, { ReactNode, useMemo } from "react";
import { Col, Drawer, Listy, Row, Table, Tag } from "antd";
import {
  DiagnosticValidation,
  ValidationImplementationType,
  ValidationSeverity,
} from "../../api/apiTypes.ts";
import {
  buildKeyValuePairsFromObject,
  KeyValuePair,
} from "../../misc/key-value-text-util.ts";

export type DiagnosticValidationSidebarProps = {
  value?: DiagnosticValidation;
  open: boolean;
  onClose: () => void;
};

type ValidationPropertyKey = keyof DiagnosticValidation;

type ValidationPropertyRecord = {
  key: ValidationPropertyKey;
  value: unknown;
};

export const DiagnosticValidationSidebar: React.FC<
  DiagnosticValidationSidebarProps
> = ({ value, open, onClose }): ReactNode => {
  const labels: Partial<Record<ValidationPropertyKey, ReactNode>> = useMemo(
    () => ({
      id: "ID",
      title: "Name",
      description: "Description",
      suggestion: "Hint",
      implementationType: "Source",
      severity: "Severity",
      alertsCount: "Alerts",
      properties: "Parameters",
    }),
    [],
  );

  const implementationTypeLabels: Record<
    ValidationImplementationType,
    ReactNode
  > = {
    BUILT_IN: "Built-in",
    PLUGIN: "Custom",
  };

  const severityColors: Record<ValidationSeverity, string> = {
    [ValidationSeverity.WARNING]: "gold",
    [ValidationSeverity.ERROR]: "red",
  };

  const severityLabels: Record<ValidationSeverity, ReactNode> = {
    [ValidationSeverity.WARNING]: "Warning",
    [ValidationSeverity.ERROR]: "Error",
  };

  const valueRenderers: Partial<
    Record<ValidationPropertyKey, (value: unknown) => ReactNode>
  > = {
    implementationType: (value) =>
      implementationTypeLabels[value as ValidationImplementationType],
    severity: (value) => (
      <Tag
        color={severityColors[value as ValidationSeverity]}
        variant={"outlined"}
      >
        {severityLabels[value as ValidationSeverity]}
      </Tag>
    ),
    properties: (value) => {
      const parameters: KeyValuePair[] = buildKeyValuePairsFromObject(
        value as DiagnosticValidation["properties"],
      );
      return (
        <Table<KeyValuePair>
          className={"details-sidebar-kv-table"}
          size="small"
          sticky={true}
          pagination={false}
          columns={[
            {
              key: "key",
              dataIndex: "key",
              title: "Key",
              sorter: (a, b) => b.key.localeCompare(a.key),
            },
            {
              key: "value",
              dataIndex: "value",
              title: "Value",
              sorter: (a, b) => b.key.localeCompare(a.key),
            },
          ]}
          rowKey={"key"}
          dataSource={parameters}
        />
      );
    },
  };

  const items = useMemo(() => {
    return value
      ? Object.entries(value)
          .map(
            ([key, val]) => ({ key, value: val }) as ValidationPropertyRecord,
          )
          .filter((item) => !!labels[item.key])
      : [];
  }, [labels, value]);

  return (
    <Drawer
      title="Validation Details"
      placement="right"
      size={350}
      open={open}
      onClose={onClose}
      destroyOnHidden
      closable={{ placement: "end" }}
      getContainer={".page-content"}
      rootStyle={{ position: "absolute" }}
      classNames={{ body: "details-sidebar-body" }}
    >
      <Listy<ValidationPropertyRecord>
        classNames={{
          groupHeader: "details-sidebar-properties-group",
        }}
        style={{ height: "100%" }}
        items={items}
        rowKey={"key"}
        group={{
          key: (item) =>
            item.key === "properties" ? "parameters" : "general info",
          title: (groupKey) => `${groupKey}`,
        }}
        itemRender={(item) =>
          item.key === "properties" ? (
            valueRenderers[item.key]!(item.value)
          ) : (
            <Row wrap={false}>
              <Col span={8}>{labels[item.key]}</Col>
              <Col>
                {(valueRenderers[item.key] ?? ((v) => String(v)))(item.value)}
              </Col>
            </Row>
          )
        }
        sticky
      />
    </Drawer>
  );
};
