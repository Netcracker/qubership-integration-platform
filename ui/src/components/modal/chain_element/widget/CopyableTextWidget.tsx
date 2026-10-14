import React from "react";
import { WidgetProps } from "@rjsf/utils";
import { Button, Input, Tooltip } from "antd";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import { copyToClipboard } from "../../../../misc/clipboard-util.ts";
import { message } from "../../../../misc/antd-app.ts";

const CopyableTextWidget: React.FC<WidgetProps> = ({
  id,
  value,
  onChange,
  disabled,
  readonly,
}) => {
  const text = typeof value === "string" ? value : "";

  return (
    <Input
      id={id}
      value={text}
      readOnly={readonly}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      suffix={
        <Tooltip title="Copy to clipboard">
          <Button
            size="small"
            type="text"
            aria-label="Copy to clipboard"
            icon={<OverridableIcon name="copy" />}
            onClick={() =>
              void copyToClipboard(text).then(() =>
                message.info("Copied to clipboard"),
              )
            }
          />
        </Tooltip>
      }
    />
  );
};

export default CopyableTextWidget;
