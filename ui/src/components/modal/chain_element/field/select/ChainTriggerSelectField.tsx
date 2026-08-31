import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import React, { useCallback, useEffect, useState } from "react";
import { FormContext } from "../../ChainElementModificationContext";
import { SelectAndNavigateField } from "./SelectAndNavigateField";
import { SelectProps } from "antd";
import { useNotificationService } from "../../../../../hooks/useNotificationService";
import { api } from "../../../../../api/api";
import { ElementWithChainName } from "../../../../../api/apiTypes";
import { SelectTag } from "./SelectTag";
import styles from "./selectOptionValue.module.css";

const ChainTriggerSelectField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({ id, formData, schema, required, uiSchema, onChange, fieldPathId }) => {
  const [elementId, setElementId] = useState<string | undefined>(formData);
  const [elementsMap, setElementsMap] = useState<
    Map<string, ElementWithChainName>
  >(new Map());
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const notificationService = useNotificationService();
  const [navigationPath, setNavigationPath] = useState<string>("");

  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  useEffect(() => {
    const loadChainTriggerElements = async () => {
      setIsLoading(true);

      try {
        const elements: ElementWithChainName[] = await api.getElementsByType(
          "any-chain",
          "chain-trigger-2",
        );
        setElementsMap(
          new Map(elements.map((element) => [element.id, element])),
        );
        // Ordered the way the option reads: chain tag first, then trigger name.
        const orderedElements = [...elements].sort(
          (a, b) =>
            a.chainName.localeCompare(b.chainName) ||
            a.name.localeCompare(b.name),
        );
        setOptions(
          orderedElements.map((element) => ({
            value: element.id,
            labelString: element.name,
            label: (
              <span className={styles.row}>
                <span className={styles.chainCol}>
                  <SelectTag value={element.chainName} />
                </span>
                <span className={styles.text}>{element.name}</span>
              </span>
            ),
          })),
        );
      } catch (error) {
        notificationService.requestFailed(
          "Failed to load chain trigger elements",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    };
    void loadChainTriggerElements();
  }, [notificationService]);

  const handleChange = useCallback(
    (newValue: string) => {
      setElementId(newValue);
      onChange(newValue, fieldPathId.path);
    },
    [fieldPathId.path, onChange],
  );

  useEffect(() => {
    if (elementId) {
      const chainId = elementsMap.get(elementId)?.chainId;
      setNavigationPath(`/chains/${chainId}`);
    }
  }, [elementId, elementsMap]);

  return (
    <SelectAndNavigateField
      id={id}
      title={title}
      required={required}
      selectValue={elementId}
      selectOptions={options}
      selectOnChange={handleChange}
      selectDisabled={schema.readOnly || isLoading}
      selectNotFoundMessage="Nothing Found"
      buttonTitle="Go to chain"
      buttonDisabled={!elementId}
      buttonOnClick={navigationPath}
      selectOptionFilterProp="labelString"
    />
  );
};

export default ChainTriggerSelectField;
