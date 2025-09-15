import React, { useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Button, Select, List } from "antd";
import { PlusOutlined, DeleteOutlined } from "@ant-design/icons";
import { MappingDescription } from "../../../../mapper/model/model.ts";
import { MappingUtil } from "../../../../mapper/util/mapping.ts";
import { Mapping } from "../../../mapper/Mapping.tsx";
import { api } from "../../../../api/api.ts";
import { Script } from "../../../Script.tsx";
import { FormContext } from "../ChainElementModification.tsx";
import styles from "./CustomArrayField.module.css";

type AfterValidation = {
  id: string;
  code: string;
  type: "responseValidation";
  label: string;
  scheme: string;
  wildcard: boolean;
  contentType: string;
};

type HandleResponse = {
  id: string;
  code: string;
  type: string;
  label: string;
  wildcard: boolean;
};

const actionOptions = [
  { value: "none", label: "None" },
  { value: "script", label: "Scripting" },
  { value: "mapper-2", label: "Mapper" },
];

const defaultCodeOptions = [
  { value: "1xx" },
  { value: "2xx" },
  { value: "3xx" },
  { value: "4xx" },
  { value: "5xx" },
  { value: "Default" },
];

const CustomArrayField: React.FC<FieldProps<[]>> = ({
  name,
  formData = [],
  onChange,
  disabled,
  readonly,
  formContext,
}) => {
  const [selectedIndex, setSelectedIndex] = useState<number | null>(
    formData.length > 0 ? 0 : null,
  );

  const [availableCodes, setAvailableCodes] = useState<{ value: string }[]>(
    name === "afterValidation" ? [] : defaultCodeOptions,
  );

  const [selectedCode, setSelectedCode] = useState<string | undefined>();
  const [validationSchemas, setValidationSchemas] = useState<
    Record<string, AfterValidation>
  >({});

  const operationId = (formContext as FormContext).operationId;

  useEffect(() => {
    let cancelled = false;

    const loadOperationInfo = async () => {
      if (!operationId) return;
      try {
        const operationInfo = await api.getOperationInfo(operationId);
        if (cancelled) return;

        const responseSchemas = operationInfo?.responseSchemas || {};
        if (name === "afterValidation") {
          setValidationSchemas(
            Object.entries(responseSchemas).reduce(
              (acc, [code, mediaTypes]) => {
                Object.entries(mediaTypes as Record<string, object>).forEach(
                  ([contentType, schema]) => {
                    const id = `${code}-${contentType}`;
                    acc[id] = {
                      id,
                      code,
                      type: "responseValidation",
                      label: id,
                      scheme: JSON.stringify(schema, null, 2),
                      wildcard: false,
                      contentType,
                    };
                  },
                );
                return acc;
              },
              {} as Record<string, AfterValidation>,
            ),
          );

          const usedIds = formData.map((f) => f.id as string);

          setAvailableCodes(
            Object.keys(validationSchemas)
              .filter((id) => !usedIds.includes(id))
              .map((id) => ({ value: id })),
          );
        } else {
          const responseCodes = Object.keys(responseSchemas).map((c) => ({
            value: c,
          }));

          const freshCodes = [...availableCodes, ...responseCodes].filter(
            (codes, idx, arr) =>
              arr.findIndex((cc) => cc.value === codes.value) === idx &&
              formData.find((item) => item.code === codes.value) === undefined,
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
  }, [operationId]);

  const handleAdd = () => {
    if (!selectedCode) return;
    const exists = formData.some((f) => f.code === selectedCode);
    if (exists) return;

    let newItem;
    if (name === "afterValidation") {
      newItem = {
        code: validationSchemas[selectedCode].code,
        id: selectedCode,
        label: selectedCode,
        type: "responseValidation",
        wildcard: false,
        contentType: validationSchemas[selectedCode].contentType,
        scheme: validationSchemas[selectedCode].scheme,
      } as AfterValidation;
    } else {
      newItem = {
        code: selectedCode,
        id: selectedCode,
        label: selectedCode,
        type: "none",
        wildcard: false,
      } as HandleResponse;
    }
    const newArray = [...formData, newItem];
    onChange(newArray);
    setSelectedIndex(newArray.length - 1);
    setSelectedCode(undefined);
    setAvailableCodes(
      availableCodes.filter((codes) => codes.value !== selectedCode),
    );
  };

  const handleDelete = (index: number) => {
    setAvailableCodes([
      ...availableCodes,
      {
        value:
          name === "afterValidation"
            ? (formData[index].id as string)
            : (formData[index].code as string),
      },
    ]);
    const newArray = [...formData];
    newArray.splice(index, 1);
    onChange(newArray);
    if (selectedIndex === index) {
      setSelectedIndex(newArray.length > 0 ? 0 : null);
    } else if ((selectedIndex ?? 0) > index) {
      setSelectedIndex((prev) => (prev ?? 1) - 1);
    }
  };

  return (
    <div className={styles.container}>
      <div className={styles.leftColumn}>
        <div className={styles.leftToolbar}>
          <Select
            value={selectedCode}
            onChange={(v) => setSelectedCode(v)}
            disabled={disabled || readonly || availableCodes.length === 0}
            placeholder="Select code"
            options={availableCodes}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
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
                  background: active ? "#f0f6ff" : undefined,
                  borderLeft: active
                    ? "3px solid #0b66ff"
                    : "3px solid transparent",
                  marginBottom: 6,
                  display: "flex",
                  justifyContent: "space-between",
                }}
              >
                <span>{item.label}</span>
                <Button
                  size="small"
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
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
        {selectedIndex != null && formData[selectedIndex] && (
          <>
            {name !== "afterValidation" && (
              <>
                <div className={styles.sectionTitle}>Action</div>
                <Select
                  style={{ marginBottom: 8 }}
                  value={formData[selectedIndex].type as string}
                  onChange={(value) => {
                    onChange(
                      formData.map((item, i) =>
                        i === selectedIndex
                          ? ({ ...item, type: value } as object)
                          : (item as object),
                      ) as [],
                    );
                  }}
                  options={actionOptions}
                />
              </>
            )}
            {formData[selectedIndex].type === "script" && (
              <div>
                <div className={styles.sectionTitle}>Script</div>
                <Script
                  value={String(formData[selectedIndex].script)}
                  onChange={(value) => {
                    formData[selectedIndex].script = value;
                    onChange(formData);
                  }}
                />
              </div>
            )}
            {formData[selectedIndex].type === "responseValidation" && (
              <div>
                <div className={styles.sectionTitle}>Scheme</div>
                <Script
                  value={formData[selectedIndex].scheme as string}
                  readOnly={true}
                  mode={"json"}
                />
              </div>
            )}
            {formData[selectedIndex].type === "mapper-2" &&
              name !== "afterValidation" && (
                <div>
                  <div className={styles.sectionTitle}>Mapper</div>
                  <Mapping
                    elementId={"mapping"}
                    mapping={
                      formData[selectedIndex].mappingDescription &&
                      typeof formData[selectedIndex].mappingDescription ===
                        "object"
                        ? (formData[selectedIndex]
                            .mappingDescription as MappingDescription)
                        : MappingUtil.emptyMapping()
                    }
                    onChange={(value) => {
                      formData[selectedIndex].mappingDescription = value;
                      onChange(formData);
                    }}
                  />
                </div>
              )}
          </>
        )}
      </div>
    </div>
  );
};

export default CustomArrayField;
