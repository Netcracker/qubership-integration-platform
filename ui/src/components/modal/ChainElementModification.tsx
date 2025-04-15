import React, { useEffect, useState } from "react";
import { Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import styles from "./ChainElementModification.module.css";
import { api } from "../../api/api.ts";
import { Element } from "../../api/apiTypes.ts";
import TextArea from "antd/lib/input/TextArea";

type ElementModificationProps = {
  nodeType: string;
};

export const ChainElementModification: React.FC<ElementModificationProps> = ({
  nodeType,
}) => {
  const [libraryElement, setLibraryElement] = useState<Element>();
  const { closeContainingModal } = useModalContext();

  useEffect(() => {
    loadLibraryElement();
  }, []);

  const loadLibraryElement = async () => {
    const libraryElement = await api.getLibraryElementByType(nodeType);
    console.log(libraryElement);
    setLibraryElement(libraryElement);
  };

  return (
    <Modal
      open
      onCancel={closeContainingModal}
      className={styles.modal}
      width="80vw"
    >
      <TextArea value={JSON.stringify(libraryElement)} />
    </Modal>
  );
};
