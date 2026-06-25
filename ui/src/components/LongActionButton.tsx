import React, { useState } from "react";
import type { ButtonProps } from "antd";
import { Button } from "antd";

type LongActionButtonProps = {
  onSubmit: () => void | Promise<void>;
};
export const LongActionButton: React.FC<
  LongActionButtonProps &
    ButtonProps &
    React.RefAttributes<HTMLButtonElement | HTMLAnchorElement>
> = ({ onSubmit, ...rest }) => {
  const [isInProcess, setIsInProcess] = useState(false);
  const onClick = () => {
    setIsInProcess(true);
    try {
      const result = onSubmit();
      if (result instanceof Promise) {
        void result.finally(() => setIsInProcess(false));
      } else {
        setIsInProcess(false);
      }
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
    } catch (error) {
      setIsInProcess(false);
    }
  };
  return <Button {...rest} loading={isInProcess} onClick={onClick} />;
};
