import React, { useState } from "react";
import type { ButtonProps } from "antd";
import { Button } from "antd";

type LongActionButtonProps = {
  onSubmit: () => void | Promise<void>;
};

// Forward the ref to the underlying antd Button so wrappers that need the
// trigger node (e.g. Tooltip's positioning) can reach it. Without this the
// Tooltip cannot measure the trigger and mispositions off-screen.
export const LongActionButton = React.forwardRef<
  HTMLButtonElement | HTMLAnchorElement,
  LongActionButtonProps & ButtonProps
>(({ onSubmit, ...rest }, ref) => {
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
  return <Button ref={ref} {...rest} loading={isInProcess} onClick={onClick} />;
});

LongActionButton.displayName = "LongActionButton";
