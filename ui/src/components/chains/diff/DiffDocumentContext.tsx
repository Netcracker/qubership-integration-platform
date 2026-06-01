import React, { createContext, PropsWithChildren } from "react";

export type DiffDocumentType = "left" | "right";

export type DiffDocumentContextProps = {
  type: DiffDocumentType;
};

export const DiffDocumentContext = createContext<DiffDocumentContextProps>({
  type: "left",
});

export type DiffDocumentContextProviderProps = PropsWithChildren & {
  type: DiffDocumentType;
};

export const DiffDocumentContextProvider: React.FC<
  DiffDocumentContextProviderProps
> = ({ type, children }): React.ReactNode => {
  return (
    <DiffDocumentContext.Provider value={{ type }}>
      {children}
    </DiffDocumentContext.Provider>
  );
};
