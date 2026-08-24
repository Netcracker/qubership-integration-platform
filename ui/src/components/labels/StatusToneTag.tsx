import React from "react";
import { Tag, theme } from "antd";
import type { PresetStatusColor } from "../../types/antd.ts";

/**
 * The tones a status reads as. `neutral` is the one antd has no preset for:
 * it covers a state that is neither good nor bad, such as pending or ignored.
 */
export type StatusTone = PresetStatusColor | "neutral";

/**
 * A status tag in the solid variant the application uses for every status.
 * The neutral tone is painted from theme tokens, so it follows the light, dark
 * and high-contrast themes rather than a fixed grey.
 */
export const StatusToneTag: React.FC<{
  tone: StatusTone;
  children: React.ReactNode;
}> = ({ tone, children }) => {
  const { token } = theme.useToken();
  const neutral = tone === "neutral";

  return (
    <Tag
      variant="solid"
      color={neutral ? undefined : tone}
      style={
        neutral
          ? {
              backgroundColor: token.colorFillQuaternary,
              borderColor: token.colorBorderSecondary,
              color: token.colorTextSecondary,
            }
          : undefined
      }
    >
      {children}
    </Tag>
  );
};
