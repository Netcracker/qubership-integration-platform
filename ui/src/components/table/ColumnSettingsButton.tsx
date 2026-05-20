import React from "react";
import { ColumnFilterProps, ColumnsFilter } from "./ColumnsFilter";
import { Button, Dropdown, Tooltip } from "antd";
import { OverridableIcon } from "../../icons/IconProvider";

export const ColumnSettingsButton: React.FC<ColumnFilterProps> = (props) => {
  return (
    <Dropdown
      popupRender={() => <ColumnsFilter {...props} />}
      trigger={["click"]}
    >
      <Tooltip title="Column settings" placement="bottom">
        <Button icon={<OverridableIcon name="settings" />} />
      </Tooltip>
    </Dropdown>
  );
};
