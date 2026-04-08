import React, { useEffect, useMemo, useState, useRef } from "react";
import {
  Modal,
  Form,
  Input,
  Badge,
  Button,
  Flex,
  Switch,
  Select,
  Table,
  Segmented,
  Tooltip,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { EntityLabels } from "../../labels/EntityLabels";
import {
  Environment,
  EnvironmentRequest,
  EnvironmentSourceType,
} from "../../../api/apiTypes";
import { useServiceContext } from "../detail/ServiceParametersPage";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { environmentLabelOptions } from "../utils.tsx";
import { isAmqpProtocol, isKafkaProtocol } from "../../../misc/protocol-utils";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import tableStyles from "../../admin_tools/domains/Tables.module.css";

interface EnvironmentParamsModalProps {
  open: boolean;
  environment: Environment | null;
  onClose: () => void;
  onSave: (envRequest: EnvironmentRequest) => Promise<void>;
  saving: boolean;
}

type EnvPropRow =
  | { rowKey: string; kind: "saved"; propKey: string; value: string }
  | {
      rowKey: string;
      kind: "draft";
      id: string;
      propKey: string;
      value: string;
    };

export const EnvironmentParamsModal: React.FC<EnvironmentParamsModalProps> = ({
  open,
  environment,
  onClose,
  onSave,
  saving,
}) => {
  const [form] = Form.useForm();
  const [showProperties, setShowProperties] = useState(false);
  const [showAsKeyValue, setShowAsKeyValue] = useState(false);
  const [propertiesObj, setPropertiesObj] = useState<Record<string, string>>(
    {},
  );
  const [propertiesText, setPropertiesText] = useState<string>("");
  const [addingRows, setAddingRows] = useState<
    Array<{ key: string; value: string; id: string }>
  >([]);
  const [isEditingText, setIsEditingText] = useState(false);
  const keyInputRefs = useRef<{
    [id: string]: HTMLInputElement | import("antd").InputRef | null;
  }>({});
  const valueInputRefs = useRef<{
    [key: string]: HTMLInputElement | import("antd").InputRef | null;
  }>({});
  const [focusRowId, setFocusRowId] = useState<string | null>(null);
  const [editingValueKey, setEditingValueKey] = useState<string | null>(null);
  const [hoverValueKey, setHoverValueKey] = useState<string | null>(null);

  const [currentSourceType, setCurrentSourceType] =
    useState<EnvironmentSourceType>(
      environment?.sourceType || EnvironmentSourceType.MANUAL,
    );
  const system = useServiceContext();
  const protocol = system?.protocol || "";
  const isAsyncProtocolSupported =
    isKafkaProtocol(protocol) || isAmqpProtocol(protocol);

  useEffect(() => {
    if (!isEditingText) {
      setPropertiesText(
        Object.entries(propertiesObj)
          .map(([key, value]) => `${key}=${value};`)
          .join("\n"),
      );
    }
  }, [propertiesObj, isEditingText]);

  useEffect(() => {
    if (open && environment) {
      form.setFieldsValue({
        name: environment.name,
        address: environment.address,
        labels: environment.labels,
      });
      setPropertiesObj(
        Object.fromEntries(
          Object.entries(environment.properties || {}).map(([k, v]) => [
            k,
            String(v),
          ]),
        ),
      );
      setAddingRows([]);
      setCurrentSourceType(
        environment.sourceType || EnvironmentSourceType.MANUAL,
      );
    } else if (!open) {
      setPropertiesObj({});
      setPropertiesText("");
      setAddingRows([]);
    }
  }, [open, environment?.id, environment, form]);

  useEffect(() => {
    setCurrentSourceType(
      environment?.sourceType || EnvironmentSourceType.MANUAL,
    );
  }, [environment?.sourceType, open]);

  useEffect(() => {
    if (!open || isAsyncProtocolSupported) return;
    if (
      currentSourceType === EnvironmentSourceType.MAAS ||
      currentSourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER
    ) {
      setCurrentSourceType(EnvironmentSourceType.MANUAL);
    }
  }, [currentSourceType, open, isAsyncProtocolSupported]);

  useEffect(() => {
    if (focusRowId && keyInputRefs.current[focusRowId]) {
      keyInputRefs.current[focusRowId]?.focus();
      setFocusRowId(null);
    }
  }, [addingRows, focusRowId]);

  useEffect(() => {
    if (editingValueKey && valueInputRefs.current[editingValueKey]) {
      valueInputRefs.current[editingValueKey]?.focus();
    }
  }, [editingValueKey]);

  const handleAddRow = (record: { key: string; value: string; id: string }) => {
    if (!record.key || !record.value) return;
    setPropertiesObj((prev) => ({ ...prev, [record.key]: record.value }));
    const newId = crypto.randomUUID();
    setAddingRows((rows) =>
      rows
        .filter((r) => r.id !== record.id)
        .concat({ key: "", value: "", id: newId }),
    );
    setFocusRowId(newId);
  };

  const updateDraftRowField = (
    id: string,
    field: "key" | "value",
    value: string,
  ) => {
    setAddingRows((rows) =>
      rows.map((row) => (row.id === id ? { ...row, [field]: value } : row)),
    );
  };

  const commitDraftRow = (record: Extract<EnvPropRow, { kind: "draft" }>) => {
    handleAddRow({
      id: record.id,
      key: record.propKey,
      value: record.value,
    });
  };

  const commitEditedPropertyValue = (propKey: string, value: string) => {
    setPropertiesObj((prev) => ({
      ...prev,
      [propKey]: value,
    }));
    setEditingValueKey(null);
  };

  const handleSave = () => {
    void (async () => {
      try {
        const values = (await form.validateFields()) as {
          name: string;
          address?: string;
          labels?: string[];
          sourceType?: EnvironmentSourceType;
        };
        const draftProps = Object.fromEntries(
          addingRows
            .filter((r) => r.key && r.value)
            .map((r) => [r.key, r.value]),
        );
        const mergedProps = { ...propertiesObj, ...draftProps };
        const envRequest: EnvironmentRequest = {
          name: values.name,
          address:
            currentSourceType !== EnvironmentSourceType.MAAS &&
            currentSourceType !== EnvironmentSourceType.MAAS_BY_CLASSIFIER
              ? values.address
              : undefined,
          labels: values.labels?.map((l) => ({ name: l, technical: false })),
          properties: mergedProps,
          sourceType: currentSourceType,
        };
        await onSave(envRequest);
        setPropertiesObj({});
        setAddingRows([]);
        onClose();
      } catch {
        // do nothing
      }
    })();
  };

  const tableBackground =
    "var(--table-bg, var(--vscode-editor-background, var(--vscode-panel-background, #ffffff)))";
  const tableBorderColor =
    "var(--vscode-editorGroup-border, var(--vscode-border, #d9d9d9))";
  const tableForeground = "var(--vscode-foreground, rgba(0, 0, 0, 0.88))";
  const mutedColor = "var(--vscode-descriptionForeground, rgba(0, 0, 0, 0.6))";

  const iconBtnStyle = {
    width: 28,
    height: 28,
    padding: 0,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
  };

  const valueCellButtonStyle = {
    display: "flex",
    alignItems: "center",
    minHeight: 32,
    width: "100%",
    paddingInline: 0,
    justifyContent: "flex-start",
  };

  const propertiesTableData = useMemo((): EnvPropRow[] => {
    const saved = Object.entries(propertiesObj).map(([propKey, value]) => ({
      rowKey: `saved:${propKey}`,
      kind: "saved" as const,
      propKey,
      value,
    }));
    const drafts = addingRows.map((r) => ({
      rowKey: `draft:${r.id}`,
      kind: "draft" as const,
      id: r.id,
      propKey: r.key,
      value: r.value,
    }));
    return [...saved, ...drafts];
  }, [propertiesObj, addingRows]);

  const propertiesColumns: ColumnsType<EnvPropRow> = [
    {
      title: <span className={tableStyles.columnHeader}>Key</span>,
      key: "col-key",
      width: "35%",
      render: (_, record) =>
        record.kind === "saved" ? (
          record.propKey
        ) : (
          <Input
            ref={(el) => {
              if (el) keyInputRefs.current[record.id] = el;
            }}
            value={record.propKey}
            onChange={(e) =>
              updateDraftRowField(record.id, "key", e.target.value)
            }
            onPressEnter={() => commitDraftRow(record)}
            style={{ minWidth: 80 }}
          />
        ),
    },
    {
      title: <span className={tableStyles.columnHeader}>Value</span>,
      key: "col-value",
      width: "60%",
      render: (_, record) => {
        if (record.kind === "draft") {
          return (
            <Input
              value={record.value}
              onChange={(e) =>
                updateDraftRowField(record.id, "value", e.target.value)
              }
              onPressEnter={() => commitDraftRow(record)}
              style={{ minWidth: 80 }}
            />
          );
        }
        const propKey = record.propKey;
        return editingValueKey === propKey ? (
          <Input
            ref={(el) => {
              if (el) valueInputRefs.current[propKey] = el;
            }}
            defaultValue={record.value}
            onBlur={(e) => commitEditedPropertyValue(propKey, e.target.value)}
            onPressEnter={(e) =>
              commitEditedPropertyValue(propKey, e.currentTarget.value)
            }
            style={{ width: "100%" }}
          />
        ) : (
          <Button
            type="text"
            style={valueCellButtonStyle}
            onMouseEnter={() => setHoverValueKey(propKey)}
            onMouseLeave={() => setHoverValueKey(null)}
            onClick={() => setEditingValueKey(propKey)}
          >
            <span style={{ flex: 1 }}>{record.value}</span>
            {hoverValueKey === propKey && (
              <OverridableIcon
                name="edit"
                style={{ marginLeft: 8, color: mutedColor }}
              />
            )}
          </Button>
        );
      },
    },
    {
      title: "",
      key: "col-actions",
      width: 56,
      align: "center",
      render: (_, record) => (
        <Button
          type="text"
          icon={<OverridableIcon name="delete" />}
          danger
          style={iconBtnStyle}
          onClick={() => {
            if (record.kind === "saved") {
              setPropertiesObj((prev) => {
                const copy = { ...prev };
                delete copy[record.propKey];
                return copy;
              });
            } else {
              setAddingRows((rows) => rows.filter((r) => r.id !== record.id));
            }
          }}
        />
      ),
    },
  ];

  const propertiesExpandedPanelStyle = {
    flex: 1,
    minHeight: 0,
    display: "flex",
    flexDirection: "column" as const,
  };

  const sourceTypeSegmentedOptions = [
    { label: "Manual", value: EnvironmentSourceType.MANUAL },
    { label: "MaaS", value: EnvironmentSourceType.MAAS_BY_CLASSIFIER },
  ];

  const sourceTypeSegmented = (
    <Segmented
      disabled={!isAsyncProtocolSupported}
      options={sourceTypeSegmentedOptions}
      value={currentSourceType}
      onChange={(val) => {
        setCurrentSourceType(val as EnvironmentSourceType);
      }}
      style={{ minWidth: 120, width: "auto" }}
    />
  );

  const isMaasSelected =
    currentSourceType === EnvironmentSourceType.MAAS ||
    currentSourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER;

  return (
    <Modal
      open={open}
      title="Edit Environment"
      onCancel={onClose}
      onOk={handleSave}
      okText="Save"
      cancelText="Cancel"
      width={900}
      confirmLoading={saving}
      rootClassName={`environment-params-modal${showProperties ? " environment-params-modal--expanded" : ""}`}
    >
      <Form form={form} layout="vertical">
        {environment?.labels && environment?.labels?.length > 0 && (
          <div style={{ marginBottom: 12 }}>
            <EntityLabels
              labels={environment.labels.map((l) => ({
                name: l.name,
                technical: false,
              }))}
            />
          </div>
        )}

        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: "Name is required" }]}
        >
          <Input />
        </Form.Item>

        <Form.Item label="Labels" name="labels">
          <Select
            mode="multiple"
            allowClear
            style={{ width: "100%" }}
            options={environmentLabelOptions}
            placeholder="Select environment labels"
          />
        </Form.Item>

        <Form.Item label="Source type">
          {isAsyncProtocolSupported ? (
            sourceTypeSegmented
          ) : (
            <Tooltip title="MaaS is only available for Kafka and AMQP protocols.">
              <span style={{ display: "inline-block" }}>
                {sourceTypeSegmented}
              </span>
            </Tooltip>
          )}
        </Form.Item>
        {!isAsyncProtocolSupported ? (
          <div
            style={{
              marginTop: -12,
              marginBottom: 12,
              marginLeft: 2,
              fontSize: 12,
              lineHeight: 1.4,
              color: mutedColor,
            }}
          >
            MaaS is only available for Kafka and AMQP protocols.
          </div>
        ) : null}
        {isMaasSelected && (
          <div
            style={{
              display: "flex",
              alignItems: "center",
              marginTop: -4,
              marginBottom: 12,
              color: mutedColor,
            }}
          >
            {!isVsCode && (
              <OverridableIcon
                name="questionCircle"
                style={{ marginRight: 8, fontSize: 18 }}
              />
            )}
            <span>
              This type allows the use of the MaaS classifier to obtain
              connection parameters when creating a chain snapshot.
            </span>
          </div>
        )}

        <div
          style={{
            transition: "max-height 0.3s, opacity 0.3s",
            maxHeight: !isMaasSelected ? 100 : 0,
            opacity: !isMaasSelected ? 1 : 0,
            overflow: "hidden",
            pointerEvents: !isMaasSelected ? "auto" : "none",
          }}
        >
          <Form.Item
            label="Address"
            name="address"
            rules={
              currentSourceType === EnvironmentSourceType.MANUAL
                ? [{ required: true, message: "Address is required" }]
                : []
            }
          >
            <Input />
          </Form.Item>
        </div>

        <div
          style={{
            display: "flex",
            flexDirection: "column",
            ...(showProperties ? { flex: 1, minHeight: 0 } : {}),
          }}
        >
          <div
            style={{
              flex: "none",
              display: "flex",
              alignItems: "center",
              userSelect: "none",
              marginBottom: showProperties ? 8 : 0,
            }}
          >
            <Button
              type="text"
              aria-expanded={showProperties}
              aria-label={
                showProperties ? "Collapse properties" : "Expand properties"
              }
              style={{
                marginRight: 8,
                display: "inline-flex",
                alignItems: "center",
                justifyContent: "center",
                color: mutedColor,
                padding: 0,
              }}
              onClick={() => setShowProperties((prev) => !prev)}
            >
              <OverridableIcon
                name={showProperties ? "down" : "right"}
                style={{ fontSize: 14 }}
              />
            </Button>
            <b>Properties</b>
            <Badge
              count={Object.keys(propertiesObj).length}
              style={{
                backgroundColor: "var(--vscode-button-background, #1677ff)",
                marginLeft: 8,
              }}
            />
            {showProperties && (
              <>
                <Button
                  icon={<OverridableIcon name="plus" />}
                  size="small"
                  style={{ marginLeft: 16 }}
                  onClick={() => {
                    const newId = crypto.randomUUID();
                    setAddingRows((rows) => [
                      ...rows,
                      { key: "", value: "", id: newId },
                    ]);
                    setFocusRowId(newId);
                  }}
                />
                <Switch
                  checked={showAsKeyValue}
                  onChange={setShowAsKeyValue}
                  style={{ marginLeft: 16 }}
                  size="small"
                />
                <span style={{ marginLeft: 8, color: mutedColor }}>
                  Show as Key Value
                </span>
              </>
            )}
          </div>

          {showProperties ? (
            <div
              data-testid="environment-properties-panel"
              style={{
                ...propertiesExpandedPanelStyle,
                ...(showAsKeyValue
                  ? {
                      background: tableBackground,
                      border: `1px solid ${tableBorderColor}`,
                      borderRadius: 6,
                    }
                  : {
                      background: "transparent",
                      border: "none",
                      borderRadius: 0,
                    }),
              }}
            >
              {showAsKeyValue ? (
                <Input.TextArea
                  value={propertiesText}
                  onChange={(e) => {
                    const lines = e.target.value.split("\n");
                    const obj: Record<string, string> = {};
                    lines.forEach((line) => {
                      const match = /^([^=;]+)=([^;]*);?$/.exec(line);
                      if (match) obj[match[1]] = match[2];
                    });
                    setPropertiesObj(obj);
                    setPropertiesText(e.target.value);
                  }}
                  onFocus={() => setIsEditingText(true)}
                  onBlur={() => setIsEditingText(false)}
                  autoSize={{ minRows: 6, maxRows: 16 }}
                  style={{
                    fontFamily: "monospace",
                    marginTop: 8,
                    background: tableBackground,
                    color: tableForeground,
                    borderColor: tableBorderColor,
                  }}
                />
              ) : (
                <Flex vertical style={{ flex: 1, minHeight: 0 }}>
                  <Table<EnvPropRow>
                    className={`environment-params-properties-table flex-table ${tableStyles.mainTable}`}
                    rowKey="rowKey"
                    size="small"
                    pagination={false}
                    tableLayout="fixed"
                    dataSource={propertiesTableData}
                    columns={propertiesColumns}
                    scroll={{ y: "" }}
                    style={{
                      width: "100%",
                      flex: 1,
                      minHeight: 0,
                    }}
                  />
                </Flex>
              )}
            </div>
          ) : null}
        </div>
      </Form>
    </Modal>
  );
};
