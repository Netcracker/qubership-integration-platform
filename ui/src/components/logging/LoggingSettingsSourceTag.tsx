import { Tag, Tooltip } from "antd";
import React, { useCallback } from "react";
import { OverridableIcon } from "../../icons/IconProvider";

type LoggingSettingsSourceTagProps = {
  isCustom?: boolean;
  isConsulDefault?: boolean;
};
export const LoggingSettingsSourceTag: React.FC<
  LoggingSettingsSourceTagProps
> = ({ isCustom, isConsulDefault }) => {
  const loggingSettingsSourceLabel = useCallback((): string => {
    if (isCustom) {
      return "Custom";
    } else if (isConsulDefault) {
      return "Default (Consul)";
    }

    return "Default (Fallback)";
  }, [isCustom, isConsulDefault]);

  const getColor = useCallback((): string => {
    if (isCustom) {
      return "blue";
    } else if (isConsulDefault) {
      return "green";
    } else {
      return "orange";
    }
  }, [isCustom, isConsulDefault]);

  return (
    <Tag style={{ fontSize: "14px", padding: "5px 12px" }} color={getColor()}>
      <div style={{ display: "flex", gap: "4px" }}>
        {loggingSettingsSourceLabel()}
        {!isCustom && !isConsulDefault && (
          <Tooltip
            placement="right"
            title='No logging settings found in the Consul, therefore standard CIP parameters are used for chain deployments.
                To adjust them, toggle "Override default properties" switch, specify desired options and save them via [Apply] button.'
          >
            <OverridableIcon name="info" />
          </Tooltip>
        )}
      </div>
    </Tag>
  );
};
