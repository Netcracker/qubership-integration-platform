import { useCallback, useState } from "react";

export type ElkDirection = "RIGHT" | "DOWN";

export const useElkDirection = () => {
  const [direction, setDirection] = useState<ElkDirection>("RIGHT");
  const [rightPanel, setRightPanel] = useState<boolean>(false);

  const toggleDirection = useCallback(() => {
    setDirection((d) => (d === "RIGHT" ? "DOWN" : "RIGHT"));
  }, []);

  const toggleRightPanel = useCallback(() => {
    setRightPanel((prev) => !prev);
  }, []);

  return { direction, toggleDirection, rightPanel, toggleRightPanel };
};
