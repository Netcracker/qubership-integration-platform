import React from "react";
import { Checkbox } from "antd";
import { FormContext } from "../ChainElementModification";
import styles from "./EnhancedPatternPropertiesField.module.css";

interface QueryParametersCheckboxProps {
  formContext?: FormContext;
  disabled?: boolean;
  readonly?: boolean;
}

export const QueryParametersCheckbox: React.FC<
  QueryParametersCheckboxProps
> = ({ formContext, disabled, readonly }) => {
  const checked =
    formContext?.integrationOperationSkipEmptyQueryParameters ?? false;

  const handleChange = (newValue: boolean) => {
    formContext?.updateContext?.({
      integrationOperationSkipEmptyQueryParameters: newValue,
    });
  };

  return (
    <div className={styles.checkboxRow}>
      <Checkbox
        checked={checked}
        onChange={(e) => handleChange(e.target.checked)}
        disabled={disabled || readonly}
      >
        Skip empty query parameters
      </Checkbox>
    </div>
  );
};
