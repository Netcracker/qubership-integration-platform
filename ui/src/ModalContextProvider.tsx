import React, { PropsWithChildren, useContext } from "react";
import { useModalsContext } from "./Modals.tsx";

const ModalContext = React.createContext<ModalContextFunctions | null>(null);

type ModalContextFunctions = {
  closeContainingModal: () => void;
};

type Props = React.FC<PropsWithChildren<{ modalId: string }>>;

export const useModalContext = () => {
  const context = useContext(ModalContext);
  if (!context) {
    throw new Error(
      "useModalContext must be used within ModalsContextProvider",
    );
  }
  return context;
};

export const ModalContextProvider: Props = ({ children, modalId }) => {
  const { closeModal } = useModalsContext();

  const closeContainingModal = () => {
    closeModal(modalId);
  };

  return (
    <ModalContext.Provider value={{ closeContainingModal }}>
      {children}
    </ModalContext.Provider>
  );
};
