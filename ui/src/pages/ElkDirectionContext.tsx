import React, { PropsWithChildren, useContext } from "react";
import { useElkDirection } from "../hooks/graph/useElkDirection.tsx";

const ElkDirectionContext = React.createContext<ReturnType<
  typeof useElkDirection
> | null>(null);

export const useElkDirectionContext = () => {
  const context = useContext(ElkDirectionContext);
  if (!context) {
    throw new Error(
      "useElkDirectionContext must be used within ElkDirectionContext",
    );
  }
  return context;
};

export const ElkDirectionContextProvider: React.FC<
  PropsWithChildren<{ elkDirectionControl: ReturnType<typeof useElkDirection> }>
> = ({ children, elkDirectionControl }) => {
  return (
    <ElkDirectionContext.Provider value={elkDirectionControl}>
      {children}
    </ElkDirectionContext.Provider>
  );
};
