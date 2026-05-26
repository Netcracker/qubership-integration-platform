import React from "react";
import { ModalWithFullscreenToggle } from "../../modal/ModalWithFullscreenToggle.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";

import { ComparableItem } from "./useChainDiff.tsx";
import { ChainDiffView } from "./ChainDiffView.tsx";

export type ChainDiffProps = {
  item1: ComparableItem;
  item2: ComparableItem;
  editable1?: boolean;
  editable2?: boolean;
};

export const ChainDiffPopup: React.FC<ChainDiffProps> = ({
  item1,
  item2,
  editable1,
  editable2,
}): React.ReactNode => {
  const { closeContainingModal } = useModalContext();
  return (
    <ModalWithFullscreenToggle
      title={"Chain compare"}
      centered
      open={true}
      onCancel={closeContainingModal}
      footer={null}
    >
      <ChainDiffView
        style={{ height: "100%" }}
        item1={item1}
        item2={item2}
        editable1={editable1}
        editable2={editable2}
      />
    </ModalWithFullscreenToggle>
  );
};
