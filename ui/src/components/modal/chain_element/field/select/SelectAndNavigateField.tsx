import { Button, Empty, Flex, Select, Tooltip } from "antd";
import { OverridableIcon } from "../../../../../icons/IconProvider";
import type { SelectProps } from "antd";
import React, { MouseEventHandler } from "react";
import styles from "../../ChainElementModification.module.css";
import { VSCodeExtensionApi } from "../../../../../api/rest/vscodeExtensionApi";
import { api } from "../../../../../api/api";

type SelectFieldProps = {
  id?: string;
  title: string;
  required?: boolean;
  selectValue: SelectProps<string>["value"];
  selectOptions: SelectProps<string>["options"];
  selectOnChange: SelectProps<string>["onChange"];
  selectDisabled: boolean;
  selectOptionLabelProp?: string;
  selectNotFoundMessage?: string;
  buttonTitle: string;
  buttonDisabled: boolean;
  buttonOnClick: string | MouseEventHandler<HTMLElement>;
};

export const SelectAndNavigateField: React.FC<SelectFieldProps> = (props) => {
  const handleClick = (navigationPath: string) => {
    if (api instanceof VSCodeExtensionApi) {
      void api.navigateInNewTab(navigationPath);
    } else {
      window.open(navigationPath, "_blank");
    }
  };

  return (
    <div>
      <label htmlFor={props.id} className={styles["field-label"]}>
        {props.required ? (
          <span className={styles["field-required"]}> *</span>
        ) : null}
        {props.title}
      </label>
      <Flex gap={4}>
        <Select<string>
          value={props.selectValue}
          options={props.selectOptions}
          optionLabelProp={props.selectOptionLabelProp}
          onChange={props.selectOnChange}
          disabled={props.selectDisabled}
          {...(props.selectNotFoundMessage && {
            notFoundContent: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={props.selectNotFoundMessage}
              />
            ),
          })}
        />
        <Tooltip title={props.buttonTitle}>
          <Button
            icon={<OverridableIcon name="send" />}
            disabled={props.buttonDisabled}
            onClick={
              typeof props.buttonOnClick === "string"
                ? () => {
                    handleClick(props.buttonOnClick as string);
                  }
                : props.buttonOnClick
            }
          />
        </Tooltip>
      </Flex>
    </div>
  );
};
