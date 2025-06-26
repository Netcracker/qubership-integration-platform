import { useState } from "react";
import { FilterItemState } from "./FilterItem";

export const useFilter = () => {
  const [filterItemStates, setFilterItemStates] = useState<FilterItemState[]>([]);

  return [ filterItemStates, setFilterItemStates ];
}
