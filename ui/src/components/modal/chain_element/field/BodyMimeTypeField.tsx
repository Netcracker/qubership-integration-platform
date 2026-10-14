import React, { useMemo } from "react";
import { FieldProps } from "@rjsf/utils";
import type { RJSFSchema } from "@rjsf/utils";
import { Select, Input, Button, SelectProps } from "antd";
import styles from "./BodyParametersField.module.css";
import { CollapsibleSection } from "../../../CollapsibleSection.tsx";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import { FormContext } from "../ChainElementModificationContext";
import {
  BodyFormEntry,
  createEmptyBodyFormEntry,
  toBodyFormData,
} from "../../../../misc/body-form-data-utils.ts";

const MIME_TYPE_OPTIONS: SelectProps["options"] = [
  { label: "Inherit", value: "Inherit" },
  { label: "None", value: "None" },
  { label: "multipart/form-data", value: "multipart/form-data" },
  {
    label: "application/x-www-form-urlencoded",
    value: "application/x-www-form-urlencoded",
  },
];

const BodyMimeTypeField: React.FC<
  FieldProps<string | undefined, RJSFSchema, FormContext>
> = ({ formData, onChange, disabled, readonly, fieldPathId, registry }) => {
  const bodyMimeType = formData ?? "Inherit";
  const formContext = registry.formContext;

  const bodyFormData = useMemo(
    () => toBodyFormData(formContext.bodyFormData),
    [formContext.bodyFormData],
  );

  const updateBodyFormData = (nextFormData: BodyFormEntry[]) => {
    formContext.updateContext?.({
      bodyFormData: nextFormData,
    });
  };

  const handleMimeTypeChange = (value: string | undefined) => {
    onChange(value === "Inherit" ? undefined : value, fieldPathId.path);

    // Clear form data if switching to None or Inherit
    if (value === "Inherit" || value === "None") {
      formContext.updateContext?.({ bodyFormData: [] });
    }
  };

  const handleAddRow = () => {
    const newEntry = createEmptyBodyFormEntry();
    const newFormData = [...bodyFormData, newEntry];
    updateBodyFormData(newFormData);
  };

  const handleDeleteRow = (index: number) => {
    const newFormData = bodyFormData.filter((_, i) => i !== index);
    updateBodyFormData(newFormData);
  };

  const handleFieldChange = (
    index: number,
    field: keyof BodyFormEntry,
    value: string,
  ) => {
    const newFormData = [...bodyFormData];
    const entry = newFormData[index] ?? createEmptyBodyFormEntry();
    newFormData[index] = { ...entry, [field]: value };
    updateBodyFormData(newFormData);
  };

  const showTable = Boolean(bodyMimeType && bodyMimeType !== "None");
  const isMultipartFormData = bodyMimeType === "multipart/form-data";

  return (
    <div>
      <div className={styles.header}>
        <div className={styles.leftHeader}>
          <span className={styles.title}>Body</span>
        </div>
        <div>
          <Select
            value={bodyMimeType}
            onChange={handleMimeTypeChange}
            options={MIME_TYPE_OPTIONS}
            disabled={disabled || readonly}
            style={{ width: 280 }}
            size="small"
            placeholder="Inherit"
          />
        </div>
      </div>

      {showTable && (
        <CollapsibleSection
          title="Parameters"
          count={bodyFormData.length}
          onAdd={handleAddRow}
          addDisabled={disabled || readonly}
        >
          <table className={styles.table}>
            <thead>
              <tr>
                <th className={styles.th}>Name</th>
                {isMultipartFormData && (
                  <th className={styles.th}>MIME Type</th>
                )}
                {isMultipartFormData && (
                  <th className={styles.th}>File Name</th>
                )}
                <th className={styles.th}>Value</th>
                <th className={`${styles.th} ${styles.actionsCol}`}></th>
              </tr>
            </thead>
            <tbody>
              {bodyFormData.map((entry: BodyFormEntry, idx: number) => (
                <tr key={idx}>
                  <td className={styles.td}>
                    <Input
                      value={entry.name}
                      onChange={(e) =>
                        handleFieldChange(idx, "name", e.target.value)
                      }
                      disabled={disabled || readonly}
                      placeholder="Name"
                    />
                  </td>
                  {isMultipartFormData && (
                    <td className={styles.td}>
                      <Input
                        value={entry.mimeType}
                        onChange={(e) =>
                          handleFieldChange(idx, "mimeType", e.target.value)
                        }
                        disabled={disabled || readonly}
                        placeholder="text/plain"
                      />
                    </td>
                  )}
                  {isMultipartFormData && (
                    <td className={styles.td}>
                      <Input
                        value={entry.fileName}
                        onChange={(e) =>
                          handleFieldChange(idx, "fileName", e.target.value)
                        }
                        disabled={disabled || readonly}
                        placeholder="File Name"
                      />
                    </td>
                  )}
                  <td className={styles.td}>
                    <Input
                      value={entry.value}
                      onChange={(e) =>
                        handleFieldChange(idx, "value", e.target.value)
                      }
                      disabled={disabled || readonly}
                      placeholder="Value"
                    />
                  </td>
                  <td className={styles.tdAction}>
                    <Button
                      size="small"
                      type="text"
                      icon={<OverridableIcon name="delete" />}
                      onClick={() => handleDeleteRow(idx)}
                      disabled={disabled || readonly}
                      className={styles.deleteBtn}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CollapsibleSection>
      )}
    </div>
  );
};

export default BodyMimeTypeField;
