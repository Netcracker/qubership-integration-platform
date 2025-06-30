import React, { useState } from "react";
import { FilterItemState } from "./FilterItem";

export const useFilter = (): [
  FilterItemState[],
  React.Dispatch<React.SetStateAction<FilterItemState[]>>,
] => {
  const [filterItemStates, setFilterItemStates] = useState<FilterItemState[]>(
    [],
  );

  return [filterItemStates, setFilterItemStates];
};
