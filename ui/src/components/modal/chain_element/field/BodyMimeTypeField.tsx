import React, { useMemo, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import type { RJSFSchema } from "@rjsf/utils";
import { Select, Input, Button, SelectProps } from "antd";
import styles from "./BodyParametersField.module.css";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import { FormContext } from "../ChainElementModification";
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

  const [collapsed, setCollapsed] = useState(formData?.length === 0);

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
        <div>
          <div className={styles.tableHeader}>
            <div
              className={styles.tableHeaderLeft}
              onClick={() => setCollapsed((s) => !s)}
            >
              <span className={styles.iconWrapper}>
                {collapsed ? (
                  <OverridableIcon name="right" />
                ) : (
                  <OverridableIcon name="down" />
                )}
              </span>
              <span>Parameters</span>
              <span className={styles.badge}>{bodyFormData.length}</span>
            </div>
            <div>
              <Button
                size="small"
                type="text"
                icon={<OverridableIcon name="plus" />}
                onClick={handleAddRow}
                disabled={disabled || readonly}
                style={{ marginLeft: 8 }}
              />
            </div>
          </div>

          {!collapsed &&
            (bodyFormData.length === 0 ? (
              <div className={styles.noEntries}>
                No entries. Click <b>+</b> to add.
              </div>
            ) : (
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
                    <th className={styles.th}></th>
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
                      <td className={styles.td}>
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
            ))}
        </div>
      )}
    </div>
  );
};

export default BodyMimeTypeField;
