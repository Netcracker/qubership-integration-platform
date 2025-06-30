import { useCallback, useState } from "react";

export type ElkDirection = "RIGHT" | "DOWN";

export const useElkDirection = () => {
  const [direction, setDirection] = useState<ElkDirection>("RIGHT");

  const toggleDirection = useCallback(() => {
    setDirection((d) => (d === "RIGHT" ? "DOWN" : "RIGHT"));
  }, []);

  return { direction, toggleDirection };
};
