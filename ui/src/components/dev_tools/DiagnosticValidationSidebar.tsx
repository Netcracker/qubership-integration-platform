import React, { ReactNode, useMemo } from "react";
import { Table, Tag } from "antd";
import {
  DiagnosticValidation,
  ValidationImplementationType,
  ValidationSeverity,
} from "../../api/apiTypes.ts";
import {
  buildKeyValuePairsFromObject,
  KeyValuePair,
} from "../../misc/key-value-text-util.ts";
import { DetailsSidebar, renderOptional } from "../DetailsSidebar.tsx";

export type DiagnosticValidationSidebarProps = {
  value?: DiagnosticValidation;
  open: boolean;
  onClose: () => void;
};

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

export const DiagnosticValidationSidebar: React.FC<
  DiagnosticValidationSidebarProps
> = ({ value, open, onClose }): ReactNode => {
  const sections = useMemo(
    () => [
      {
        key: "general-info",
        title: "general info",
        items: [
          {
            label: "ID",
            value: renderOptional(value?.id),
          },
          {
            label: "Name",
            value: renderOptional(value?.title),
          },
          {
            label: "Description",
            value: renderOptional(value?.description),
          },
          {
            label: "Hint",
            value: renderOptional(value?.suggestion),
          },
          {
            label: "Source",
            value: renderOptional(
              value?.implementationType,
              (v) => implementationTypeLabels[v],
            ),
          },
          {
            label: "Severity",
            value: renderOptional(value?.severity, (v) => (
              <Tag color={severityColors[v]} variant={"outlined"}>
                {severityLabels[v]}
              </Tag>
            )),
          },
          {
            label: "Alerts",
            value: renderOptional(value?.alertsCount),
          },
        ],
      },
      {
        key: "parameters",
        title: "parameters",
        items: [
          {
            label: "Parameters",
            showLabel: false,
            value: (
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
                dataSource={buildKeyValuePairsFromObject(
                  value?.properties ?? {},
                )}
              />
            ),
          },
        ],
      },
    ],
    [
      value?.alertsCount,
      value?.description,
      value?.id,
      value?.implementationType,
      value?.properties,
      value?.severity,
      value?.suggestion,
      value?.title,
    ],
  );

  return (
    <DetailsSidebar
      title={"Validation Details"}
      open={open}
      onClose={onClose}
      sections={sections}
    />
  );
};
