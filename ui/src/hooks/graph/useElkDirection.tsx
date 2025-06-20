import { useState } from "react";

export type ElkDirection = "RIGHT" | "DOWN";

export const useElkDirection = () => {
  const [elkDirection, setElkDirection] = useState<ElkDirection>("RIGHT");

  const onChangeDirection = () => {
    const nextDirection: ElkDirection = elkDirection === "RIGHT" ? "DOWN" : "RIGHT";
    setElkDirection(nextDirection);
  };

  return { elkDirection, onChangeDirection };
};
