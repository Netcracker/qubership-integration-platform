import React, { useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Select, Input, Button } from "antd";
import styles from "./BodyParametersField.module.css";
import { Icon } from "../../../../IconProvider.tsx";
import { FormContext } from "../ChainElementModification";

interface BodyFormEntry {
  fieldName?: string;
  mimeType?: string;
  name: string;
  fileName?: string;
  value: string;
}

const MIME_TYPE_OPTIONS = [
  { label: 'Inherit', value: undefined },
  { label: 'None', value: 'None' },
  { label: 'multipart/form-data', value: 'multipart/form-data' },
  { label: 'application/x-www-form-urlencoded', value: 'application/x-www-form-urlencoded' },
];

const BodyMimeTypeField: React.FC<FieldProps<string, any, FormContext>> = ({
  formData,
  onChange,
  formContext,
  disabled,
  readonly,
}) => {
  const bodyMimeType = formData;
  
  // Get bodyFormData from formContext (synced by ChainElementModification)
  const bodyFormData = (formContext as any)?.bodyFormData || [];
  
  const [collapsed, setCollapsed] = useState(bodyFormData.length === 0);

  const handleMimeTypeChange = (value: string) => {
    onChange(value);
    
    // Clear form data if switching to None or Inherit
    if (!value || value === 'None') {
      formContext?.updateContext?.({ bodyFormData: [] });
    }
  };

  const handleAddRow = () => {
    const newEntry: BodyFormEntry = {
      name: '',
      value: '',
      fileName: '',
      mimeType: 'text/plain',
    };
    
    const newFormData = [...bodyFormData, newEntry];
    formContext?.updateContext?.({ bodyFormData: newFormData });
  };

  const handleDeleteRow = (index: number) => {
    const newFormData = bodyFormData.filter((_: any, i: number) => i !== index);
    formContext?.updateContext?.({ bodyFormData: newFormData });
  };

  const handleFieldChange = (index: number, field: keyof BodyFormEntry, value: string) => {
    const newFormData = [...bodyFormData];
    // Ensure the entry exists
    if (!newFormData[index]) {
      newFormData[index] = { name: '', value: '', fileName: '', mimeType: 'text/plain' };
    }
    newFormData[index] = { ...newFormData[index], [field]: value };
    formContext?.updateContext?.({ bodyFormData: newFormData });
  };

  const showTable = bodyMimeType && bodyMimeType !== 'None';
  const isMultipartFormData = bodyMimeType === 'multipart/form-data';

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
              onClick={() => setCollapsed(s => !s)}
            >
              <span className={styles.iconWrapper}>
                {collapsed ? <Icon name="right" /> : <Icon name="down" />}
              </span>
              <span>Form Data</span>
              <span className={styles.badge}>{bodyFormData.length}</span>
            </div>
            <div>
              <Button
                size="small"
                type="text"
                icon={<Icon name="plus" />}
                onClick={handleAddRow}
                disabled={disabled || readonly}
                style={{ marginLeft: 8 }}
              />
            </div>
          </div>

          {!collapsed && (
            bodyFormData.length === 0 ? (
              <div className={styles.noEntries}>
                No entries. Click <b>+</b> to add.
              </div>
            ) : (
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th className={styles.th}>Name</th>
                    {isMultipartFormData && <th className={styles.th}>MIME Type</th>}
                    {isMultipartFormData && <th className={styles.th}>File Name</th>}
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
                          onChange={(e) => handleFieldChange(idx, 'name', e.target.value)}
                          disabled={disabled || readonly}
                          placeholder="Name"
                        />
                      </td>
                      {isMultipartFormData && (
                        <td className={styles.td}>
                          <Input
                            value={entry.mimeType}
                            onChange={(e) => handleFieldChange(idx, 'mimeType', e.target.value)}
                            disabled={disabled || readonly}
                            placeholder="text/plain"
                          />
                        </td>
                      )}
                      {isMultipartFormData && (
                        <td className={styles.td}>
                          <Input
                            value={entry.fileName}
                            onChange={(e) => handleFieldChange(idx, 'fileName', e.target.value)}
                            disabled={disabled || readonly}
                            placeholder="File Name"
                          />
                        </td>
                      )}
                      <td className={styles.td}>
                        <Input
                          value={entry.value}
                          onChange={(e) => handleFieldChange(idx, 'value', e.target.value)}
                          disabled={disabled || readonly}
                          placeholder="Value"
                        />
                      </td>
                      <td className={styles.td}>
                        <Button
                          size="small"
                          type="text"
                          icon={<Icon name="delete" />}
                          onClick={() => handleDeleteRow(idx)}
                          disabled={disabled || readonly}
                          className={styles.deleteBtn}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          )}
        </div>
      )}
    </div>
  );
};

export default BodyMimeTypeField;

