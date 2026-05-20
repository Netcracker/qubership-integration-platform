import React, { ReactNode } from "react";
import { Difference } from "../../mapper/util/compare.ts";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { Collapse, Flex, Modal } from "antd";
import { DataTypeDifferencesView } from "./DataTypeDifferencesView.tsx";

export type LoadConfirmationDialogProps = {
  differences?: Difference[];
  onSubmit?: () => void;
};

export const LoadConfirmationDialog: React.FC<LoadConfirmationDialogProps> = ({
  differences,
  onSubmit,
}): ReactNode => {
  const { closeContainingModal } = useModalContext();

  return (
    <Modal
      title="Warning"
      open={true}
      okText={"Apply"}
      onOk={() => {
        onSubmit?.();
        closeContainingModal();
      }}
      onCancel={closeContainingModal}
    >
      <Flex vertical gap={16}>
        <span>Applying new data schema may impose breaking changes.</span>
        <Collapse
          size={"small"}
          items={[
            {
              key: "details",
              label: "Details",
              children: (
                <DataTypeDifferencesView
                  differences={differences ?? []}
                  style={{ height: "30vh" }}
                />
              ),
            },
          ]}
        />
      </Flex>
    </Modal>
  );
};
