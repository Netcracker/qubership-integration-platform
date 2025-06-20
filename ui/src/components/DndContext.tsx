import React, {
  createContext,
  PropsWithChildren,
  useContext,
  useState,
} from "react";
import { Element } from "../api/apiTypes.ts";

const DnDContext = createContext<
  [Element | null, React.Dispatch<React.SetStateAction<Element | null>>]
>([null, () => {}]);

export const DnDProvider: React.FC<PropsWithChildren> = ({ children }) => {
  const [type, setType] = useState<Element | null>(null);

  return (
    <DnDContext.Provider value={[type, setType]}>
      {children}
    </DnDContext.Provider>
  );
};

export const useDnD = () => {
  return useContext(DnDContext);
};
