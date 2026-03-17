import React from "react";
import { Button, Flex } from "antd";
import { MaasFormActionsProps } from "./types.ts";
import styles from "./Maas.module.css";

export const MaasFormActions: React.FC<MaasFormActionsProps> = ({
  createInProgress,
  isFormValid,
  onCreate,
  onReset,
}) => {
  return (
    <Flex justify="space-between" className={styles["actionsContainer"]}>
      <Button
        type="primary"
        loading={createInProgress}
        disabled={createInProgress || !isFormValid}
        onClick={onCreate}
      >
        Create
      </Button>
      <Button
        disabled={createInProgress}
        onClick={onReset}
      >
        Reset
      </Button>
    </Flex>
  );
};
