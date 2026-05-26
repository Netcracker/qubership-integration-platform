import React from "react";
import { Spin } from "antd";
import { ModalWithFullscreenToggle } from "../../modal/ModalWithFullscreenToggle.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import styles from "./ChainDiffPopup.module.css";
import { ComparableItem, useChainDiff } from "./useChainDiff.tsx";
import { ChainDiffView } from "./ChainDiffView.tsx";

export type ChainDiffProps = {
  item1: ComparableItem;
  item2: ComparableItem;
};

export const ChainDiffPopup: React.FC<ChainDiffProps> = ({
  item1,
  item2,
}): React.ReactNode => {
  const { closeContainingModal } = useModalContext();
  const {
    isLoading,
    chain1,
    chain2,
    changes,
    selectedChangeId,
    setSelectedChangeId,
  } = useChainDiff(item1, item2);

  return (
    <ModalWithFullscreenToggle
      title={"Chain compare"}
      centered
      open={true}
      onCancel={closeContainingModal}
      footer={null}
    >
      {isLoading ? (
        <Spin className={styles.loader} size={"large"}></Spin>
      ) : (
        <ChainDiffView
          style={{ height: "100%" }}
          chain1={chain1}
          chain2={chain2}
          changes={changes}
          selectedChangeId={selectedChangeId}
          onSelectChange={setSelectedChangeId}
        />
      )}
    </ModalWithFullscreenToggle>
  );
};
