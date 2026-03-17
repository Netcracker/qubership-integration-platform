import { type FC, useEffect, useMemo, useState } from "react";
import { Button, Checkbox, Select, List } from "antd";
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import type { MappingDescription } from "../../../../mapper/model/model.ts";
import { MappingUtil } from "../../../../mapper/util/mapping.ts";
import { Mapping } from "../../../mapper/Mapping.tsx";
import { api } from "../../../../api/api.ts";
import { isHttpProtocol } from "../../../../misc/protocol-utils.ts";
import { Script } from "../../../Script.tsx";
import type { FormContext } from "../ChainElementModificationContext.ts";
import styles from "./CustomArrayField.module.css";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import { useVSCodeTheme } from "../../../../hooks/useVSCodeTheme.ts";

type BaseItem = {
  code: string;
  label: string;
};

// Async Api Trigger "after" element
export type RequestValidation = BaseItem & {
  schema: string;
};

// Service Call "afterValidation" element (Validations tab for http)
export type ResponseValidation = BaseItem & {
  contentType: string;
  id: string;
  schema: string;
  type: string;
};

// Service Call "after" element (Handle Response tab)
export type ResponseHandler = BaseItem & {
  id: string;
  wildcard?: boolean;
  type: string;
  script?: string;
  mappingDescription?: MappingDescription;
  throwException?: boolean;
};

type ArrayItem = RequestValidation | ResponseValidation | ResponseHandler;

type ValidationItem = RequestValidation | ResponseValidation;

const hasSchema = (item: ArrayItem): item is ValidationItem => "schema" in item;

const actionOptions = [
  { value: "none", label: "None" },
  { value: "script", label: "Scripting" },
  { value: "mapper-2", label: "Mapper" },
];

const changeActionType = (
  handler: ResponseHandler,
  type: string,
): ResponseHandler => {
  const base = {
    code: handler.code,
    id: handler.id,
    label: handler.label,
    wildcard: handler.wildcard,
    type,
  };
  switch (type) {
    case "script":
      return { ...base, script: handler.script };
    case "mapper-2":
      return {
        ...base,
        mappingDescription: handler.mappingDescription,
        throwException: handler.throwException,
      };
    default:
      return base;
  }
};

const defaultCodeOptions = [
  { value: "1xx" },
  { value: "2xx" },
  { value: "3xx" },
  { value: "4xx" },
  { value: "5xx" },
  { value: "Default" },
];

const CustomArrayField: FC<
  FieldProps<ArrayItem[], RJSFSchema, FormContext>
> = ({
  name,
  formData = [],
  onChange,
  disabled,
  readonly,
  registry,
  fieldPathId,
}) => {
  const [selectedIndex, setSelectedIndex] = useState<number | null>(
    formData.length > 0 ? 0 : null,
  );

  const formContext = registry.formContext;
  const elementType = formContext?.elementType;

  const readOnlyMode = useMemo(
    () => elementType === "async-api-trigger" || name === "afterValidation",
    [name, elementType],
  );

  const [availableCodes, setAvailableCodes] = useState<{ value: string }[]>(
    readOnlyMode ||
      !isHttpProtocol(formContext?.integrationOperationProtocolType as string)
      ? []
      : defaultCodeOptions,
  );

  const [selectedCode, setSelectedCode] = useState<string | undefined>();
  const [validationSchemas, setValidationSchemas] = useState<
    Record<string, ValidationItem>
  >({});

  const { isDark, colors, palette } = useVSCodeTheme();

  const themeColors = useMemo(
    () => ({
      activeBackground:
        colors?.["list.activeSelectionBackground"] ??
        colors?.["list.hoverBackground"] ??
        palette?.listHover ??
        (isDark ? "#264f78" : "#f0f6ff"),
      activeBorder:
        colors?.["focusBorder"] ??
        palette?.buttonBg ??
        (isDark ? "#007acc" : "#0b66ff"),
    }),
    [isDark, colors, palette],
  );

  const operationId = formContext?.integrationOperationId;

  useEffect(() => {
    let cancelled = false;

    const loadOperationInfo = async () => {
      if (!operationId) return;
      try {
        const operationInfo = await api.getOperationInfo(operationId);
        if (cancelled) return;

        const responseSchemas = operationInfo?.responseSchemas ?? {};

        if (readOnlyMode) {
          const schemas: Record<string, ValidationItem> = {};

          for (const [code, schema] of Object.entries(responseSchemas)) {
            if (elementType === "async-api-trigger") {
              schemas[code] = {
                code,
                label: code,
                schema: JSON.stringify(schema, null, 2),
              };
            } else {
              for (const [contentType, validationSchema] of Object.entries(
                schema as Record<string, object>,
              )) {
                const id = `${code}-${contentType}`;
                schemas[id] = {
                  id,
                  code,
                  type: "responseValidation",
                  label: id,
                  schema: JSON.stringify(validationSchema, null, 2),
                  contentType,
                };
              }
            }
          }

          setValidationSchemas(schemas);

          const usedLabels = new Set(formData.map((f) => f.label));
          setAvailableCodes(
            Object.keys(schemas)
              .filter((id) => !usedLabels.has(id))
              .map((id) => ({ value: id })),
          );
        } else {
          const responseCodes = Object.keys(responseSchemas).map((c) => ({
            value: c,
          }));
          const usedCodes = new Set(formData.map((item) => item.code));
          const seen = new Set<string>();
          const freshCodes = [...availableCodes, ...responseCodes].filter(
            (entry) => {
              if (seen.has(entry.value) || usedCodes.has(entry.value))
                return false;
              seen.add(entry.value);
              return true;
            },
          );

          setAvailableCodes(freshCodes);
        }
      } catch (err) {
        console.error("Failed to fetch operation info:", err);
      }
    };

    void loadOperationInfo();

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [operationId, readOnlyMode]);

  const handleAdd = () => {
    if (!selectedCode) return;
    if (formData.some((f) => f.label === selectedCode)) return;

    let newItem: ArrayItem;
    if (readOnlyMode) {
      const source = validationSchemas[selectedCode];
      if (elementType === "async-api-trigger") {
        newItem = {
          code: source.code,
          label: selectedCode,
          schema: source.schema,
        };
      } else {
        const vs = source as ResponseValidation;
        newItem = {
          code: vs.code,
          id: selectedCode,
          label: selectedCode,
          type: "responseValidation",
          contentType: vs.contentType,
          schema: vs.schema,
        };
      }
    } else {
      newItem = {
        code: selectedCode,
        id: selectedCode,
        label: selectedCode,
        type: "none",
        wildcard: false,
      };
    }

    const newArray = [...formData, newItem];
    onChange(newArray, fieldPathId.path);
    setSelectedIndex(newArray.length - 1);
    setSelectedCode(undefined);
    setAvailableCodes(availableCodes.filter((c) => c.value !== selectedCode));
  };

  const handleDelete = (index: number) => {
    const item = formData[index];
    const returnValue = readOnlyMode ? item.label : item.code;
    setAvailableCodes([...availableCodes, { value: returnValue }]);

    const newArray = [...formData];
    newArray.splice(index, 1);
    onChange(newArray, fieldPathId.path);

    if (selectedIndex === index) {
      setSelectedIndex(newArray.length > 0 ? 0 : null);
    } else if ((selectedIndex ?? 0) > index) {
      setSelectedIndex((prev) => (prev ?? 1) - 1);
    }
  };

  const selectedItem = selectedIndex != null ? formData[selectedIndex] : null;
  const handlerItem =
    selectedItem && !readOnlyMode ? (selectedItem as ResponseHandler) : null;

  return (
    <div className={styles.container}>
      <div className={styles.leftColumn}>
        <div className={styles.leftToolbar}>
          <Select
            value={selectedCode}
            onChange={setSelectedCode}
            disabled={disabled || readonly || availableCodes.length === 0}
            placeholder="Select code"
            options={availableCodes}
          />
          <Button
            type="primary"
            icon={<OverridableIcon name="plus" />}
            onClick={handleAdd}
            disabled={
              !selectedCode ||
              disabled ||
              readonly ||
              availableCodes.length === 0
            }
          >
            Add
          </Button>
        </div>

        <List
          dataSource={formData}
          renderItem={(item, idx) => {
            const active = selectedIndex === idx;
            return (
              <List.Item
                onClick={() => setSelectedIndex(idx)}
                style={{
                  cursor: "pointer",
                  padding: "6px 8px",
                  borderLeft: active
                    ? `3px solid ${themeColors.activeBorder}`
                    : "3px solid transparent",
                  marginBottom: 6,
                  display: "flex",
                  justifyContent: "space-between",
                  background: active ? themeColors.activeBackground : undefined,
                }}
              >
                <span>{item.label}</span>
                <Button
                  size="small"
                  type="text"
                  danger
                  icon={<OverridableIcon name="delete" />}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDelete(idx);
                  }}
                  disabled={disabled || readonly}
                />
              </List.Item>
            );
          }}
        />
      </div>

      <div className={styles.rightColumn}>
        {handlerItem && (
          <>
            <div className={styles.sectionTitle}>Action</div>
            <Select
              style={{ marginBottom: 8 }}
              value={handlerItem.type}
              onChange={(value) => {
                onChange(
                  formData.map((item, i) =>
                    i === selectedIndex
                      ? changeActionType(item as ResponseHandler, value)
                      : item,
                  ),
                  fieldPathId.path,
                );
              }}
              options={actionOptions}
            />
            {handlerItem.type === "script" && (
              <div>
                <Script
                  value={handlerItem.script ?? ""}
                  onChange={(value) => {
                    onChange(
                      formData.map((item, i) =>
                        i === selectedIndex ? { ...item, script: value } : item,
                      ) as ArrayItem[],
                      fieldPathId.path,
                    );
                  }}
                />
              </div>
            )}
            {handlerItem.type === "mapper-2" && (
              <div>
                <Checkbox
                  checked={handlerItem.throwException ?? true}
                  onChange={(e) => {
                    onChange(
                      formData.map((item, i) =>
                        i === selectedIndex
                          ? { ...item, throwException: e.target.checked }
                          : item,
                      ) as ArrayItem[],
                      fieldPathId.path,
                    );
                  }}
                  style={{ marginBottom: 8 }}
                >
                  Throw exception on transformation failure
                </Checkbox>
                <Mapping
                  elementId="mapping"
                  mapping={
                    handlerItem.mappingDescription &&
                    typeof handlerItem.mappingDescription === "object"
                      ? handlerItem.mappingDescription
                      : MappingUtil.emptyMapping()
                  }
                  onChange={(value) => {
                    onChange(
                      formData.map((item, i) =>
                        i === selectedIndex
                          ? { ...item, mappingDescription: value }
                          : item,
                      ) as ArrayItem[],
                      fieldPathId.path,
                    );
                  }}
                />
              </div>
            )}
          </>
        )}
        {selectedItem && readOnlyMode && hasSchema(selectedItem) && (
          <div>
            <div className={styles.sectionTitle}>Schema</div>
            <Script value={selectedItem.schema} readOnly mode="json" />
          </div>
        )}
      </div>
    </div>
  );
};

export default CustomArrayField;
