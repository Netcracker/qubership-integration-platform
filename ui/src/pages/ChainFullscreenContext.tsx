import React, {
  type PropsWithChildren,
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
} from "react";

type ChainFullscreenContextValue = {
  fullscreen: boolean;
  toggleFullscreen: () => void;
};

const ChainFullscreenContext =
  createContext<ChainFullscreenContextValue | null>(null);

export const useChainFullscreenContext =
  (): ChainFullscreenContextValue | null => useContext(ChainFullscreenContext);

export const ChainFullscreenContextProvider: React.FC<PropsWithChildren> = ({
  children,
}) => {
  const [fullscreen, setFullscreen] = useState(false);
  const toggleFullscreen = useCallback(
    () => setFullscreen((prev) => !prev),
    [],
  );

  const value = useMemo(
    () => ({ fullscreen, toggleFullscreen }),
    [fullscreen, toggleFullscreen],
  );

  return (
    <ChainFullscreenContext.Provider value={value}>
      {children}
    </ChainFullscreenContext.Provider>
  );
};
