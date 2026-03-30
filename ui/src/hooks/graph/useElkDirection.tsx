import { useCallback, useState } from "react";

export type ElkDirection = "RIGHT" | "DOWN";

export const useElkDirection = () => {
  const [direction, setDirection] = useState<ElkDirection>("RIGHT");
  const [leftPanel, setLeftPanel] = useState<boolean>(true);
  const [rightPanel, setRightPanel] = useState<boolean>(false);

  const toggleDirection = useCallback(() => {
    setDirection((d) => (d === "RIGHT" ? "DOWN" : "RIGHT"));
  }, []);

  const toggleLeftPanel = useCallback(() => {
    setLeftPanel((prev) => !prev);
  }, []);

  const toggleRightPanel = useCallback(() => {
    setRightPanel((prev) => !prev);
  }, []);

  return {
    direction,
    toggleDirection,
    leftPanel,
    toggleLeftPanel,
    rightPanel,
    toggleRightPanel,
  };
};
