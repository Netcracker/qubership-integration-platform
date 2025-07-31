import React, {
  createContext,
  PropsWithChildren,
  useContext,
  useState,
} from "react";
import { LibraryElement } from "../api/apiTypes.ts";

const DnDContext = createContext<
  [LibraryElement | null, React.Dispatch<React.SetStateAction<LibraryElement | null>>]
>([null, () => {}]);

export const DnDProvider: React.FC<PropsWithChildren> = ({ children }) => {
  const [type, setType] = useState<LibraryElement | null>(null);

  return (
    <DnDContext.Provider value={[type, setType]}>
      {children}
    </DnDContext.Provider>
  );
};

export const useDnD = () => {
  return useContext(DnDContext);
};
