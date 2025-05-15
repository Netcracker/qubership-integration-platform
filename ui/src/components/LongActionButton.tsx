import React, { useState } from "react";
import { ButtonProps } from "antd/es/button/button";
import { Button } from "antd";

type LongActionButtonProps = {
  onAction: () => void;
};
export const LongActionButton: React.FC<
  LongActionButtonProps &
    ButtonProps &
    React.RefAttributes<HTMLButtonElement | HTMLAnchorElement>
> = ({ onAction, ...rest }) => {
  const [isInProcess, setIsInProcess] = useState(false);
  const onClick = async () => {
    try {
      setIsInProcess(true);
      onAction();
    } finally {
      setIsInProcess(false);
    }
  }
  return <Button {...rest} loading={isInProcess} onClick={onClick} />;
};
