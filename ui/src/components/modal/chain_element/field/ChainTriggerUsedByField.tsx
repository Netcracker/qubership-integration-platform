import { FieldProps } from "@rjsf/utils";
import React, { useEffect, useState } from "react";
import { ElementWithChainName } from "../../../../api/apiTypes";
import { useNotificationService } from "../../../../hooks/useNotificationService";
import { api } from "../../../../api/api";
import { Chain, ChainColumn } from "../../../services/ChainColumn";
import { JSONSchema7 } from "json-schema";
import { FormContext } from "../ChainElementModification";
import styles from "../ChainElementModification.module.css";

const ChainTriggerUsedByField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({ id, formData}) => {
  const notificationService = useNotificationService();

  const [chains, setChains] = useState<Chain[]>([]);
  const elementId = formData;

  useEffect(() => {
    const loadChainCallElements = async () => {
      try {
        const elements: ElementWithChainName[] = await api.getElementsByType(
          "any-chain",
          "chain-call-2",
        );

        const chainsUsingElement: Chain[] = [];
        elements.forEach((element: ElementWithChainName) => {
          if (element.properties?.elementId === elementId) {
            chainsUsingElement.push({
              id: element.chainId,
              name: element.chainName,
            });
          }
        });
        setChains(chainsUsingElement);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to load chain call elements",
          error,
        );
      }
    };
    void loadChainCallElements();
  }, [elementId, notificationService]);

  return (
    <>
      <label htmlFor={id} className={styles["field-label"]}>
        Used By
      </label>
      <ChainColumn chains={chains} />
    </>
  );
};

export default ChainTriggerUsedByField;
