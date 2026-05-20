import React from "react";
import { Spin } from "antd";
import { ModalWithFullscreenToggle } from "../../modal/ModalWithFullscreenToggle.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import styles from "./ChainDiffPopup.module.css";
import { useChainDiff } from "./useChainDiff.tsx";
import { ChainDiffView } from "./ChainDiffView.tsx";

export type ChainDiffProps = {
  chainId1: string;
  chainId2: string;
};

export const ChainDiffPopup: React.FC<ChainDiffProps> = ({
  chainId1,
  chainId2,
}): React.ReactNode => {
  const { closeContainingModal } = useModalContext();
  const {
    isLoading,
    chain1,
    chain2,
    changes,
    selectedChangeId,
    setSelectedChangeId,
  } = useChainDiff(chainId1, chainId2);

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
