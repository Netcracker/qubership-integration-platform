import { useState } from "react";

export const useElkDirection = () => {
  const [elkDirection, setElkDirection] = useState<"RIGHT" | "DOWN">("RIGHT");

  const onChangeDirection = () => {
    const nextDirection = elkDirection === "RIGHT" ? "DOWN" : "RIGHT";
    setElkDirection(nextDirection);
  };

  return { elkDirection, onChangeDirection };
};
