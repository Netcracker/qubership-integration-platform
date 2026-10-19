import { createContext, type FC, type ReactNode, useContext } from "react";
import { createPortal } from "react-dom";

const ChainHeaderSlotContext = createContext<HTMLElement | null>(null);

export const ChainHeaderSlotProvider = ChainHeaderSlotContext.Provider;

/**
 * Renders its children into the chain page header through a portal, so they
 * commit in the same render as the tab that owns them.
 */
export const ChainHeaderActions: FC<{ children?: ReactNode }> = ({
  children,
}) => {
  const slot = useContext(ChainHeaderSlotContext);
  return slot ? createPortal(children, slot) : null;
};
