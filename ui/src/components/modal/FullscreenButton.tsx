import { Button } from "antd";
import React from "react";
import { OverridableIcon } from "../../icons/IconProvider";

type FullscreenButtonProps = {
  isFullscreen: boolean;
  onClick: () => void;
};

export const FullscreenButton: React.FC<FullscreenButtonProps> = ({
  isFullscreen,
  onClick,
}) => {
  return (
    <Button
      type="text"
      icon={
        <OverridableIcon
          name={isFullscreen ? "fullscreenExit" : "fullscreen"}
        />
      }
      size="small"
      style={{ flexShrink: 0 }}
      onClick={(e) => {
        onClick();
        e.currentTarget.blur();
      }}
    />
  );
};
