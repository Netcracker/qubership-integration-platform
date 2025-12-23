import { Button, Flex, Select, Tooltip } from "antd";
import { OverridableIcon } from "../../../../../icons/IconProvider";
import { SelectProps } from "rc-select/lib/Select";
import React, { MouseEventHandler } from "react";
import styles from "../../ChainElementModification.module.css";

type SelectFieldProps = {
  id?: string;
  title: string;
  required?: boolean;
  selectValue: SelectProps["value"];
  selectOptions: SelectProps["options"];
  selectOnChange: SelectProps["onChange"];
  selectDisabled: boolean;
  selectOptionLabelProp?: string;
  buttonTitle: string;
  buttonDisabled: boolean;
  buttonOnClick: MouseEventHandler<HTMLElement>;
};

export const SelectAndNavigateField: React.FC<SelectFieldProps> = (props) => {
  return (
    <div>
      <label htmlFor={props.id} className={styles["field-label"]}>
        {props.required ? <span className={styles["field-required"]}> *</span> : null}
        {props.title}
      </label>
      <Flex gap={4}>
        <Select
          value={props.selectValue}
          options={props.selectOptions}
          optionLabelProp={props.selectOptionLabelProp}
          onChange={props.selectOnChange}
          disabled={props.selectDisabled}
        />
        <Tooltip title="Go to service">
          <Button
            icon={<OverridableIcon name="send" />}
            disabled={props.buttonDisabled}
            onClick={props.buttonOnClick}
          />
        </Tooltip>
      </Flex>
    </div>
  );
};
