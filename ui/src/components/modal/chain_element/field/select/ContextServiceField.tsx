import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import { FormContext } from "../../ChainElementModification";
import { SelectAndNavigateField } from "./SelectAndNavigateField";
import React, { useCallback, useEffect, useState } from "react";
import { SelectProps } from "antd";
import { useNotificationService } from "../../../../../hooks/useNotificationService";
import { api } from "../../../../../api/api";
import { ContextSystem } from "../../../../../api/apiTypes";
import { VSCodeExtensionApi } from "../../../../../api/rest/vscodeExtensionApi";

const ContextServiceField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = (props) => {
  const [serviceId, setServiceId] = useState<string | undefined>(
    props.formData,
  );
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const notificationService = useNotificationService();
  const [isLoading, setIsLoading] = useState<boolean>(false);

  useEffect(() => {
    const loadContextServices = async () => {
      setIsLoading(true);
      try {
        const services = await api.getContextServices();

        const options: SelectProps["options"] = services.map(
          (service: ContextSystem) => ({
            label: service.name,
            value: service.id,
          }),
        );
        setOptions(options);
      } catch (error) {
        setOptions([]);
        notificationService.requestFailed(
          "Failed to load context services",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    };

    void loadContextServices();
  }, [notificationService]);

  const handleChange = useCallback(
    (newValue: string) => {
      setServiceId(newValue);

      props.registry.formContext.updateContext?.({
        contextServiceId: newValue,
      });
    },
    [props.registry.formContext],
  );

  const onNavigationButtonClick = useCallback(() => {
    const path = `/services/context/${serviceId}/parameters`;
    if (api instanceof VSCodeExtensionApi) {
      void api.navigateInNewTab(path);
    } else {
      window.open(path, "_blank");
    }
  }, [serviceId]);

  const title = props.uiSchema?.["ui:title"] ?? props.schema?.title ?? "";

  return (
    <SelectAndNavigateField
      id={props.id}
      title={title}
      required={props.required}
      selectValue={props.formData}
      selectOptions={options}
      selectOnChange={handleChange}
      selectDisabled={isLoading}
      buttonTitle="Go to context service"
      buttonDisabled={!serviceId}
      buttonOnClick={onNavigationButtonClick}
    />
  );
};

export default ContextServiceField;
