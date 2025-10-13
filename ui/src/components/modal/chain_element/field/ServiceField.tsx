import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Select, SelectProps } from "antd";
import { useServices } from "../../../../hooks/useServices";
import { IntegrationSystem } from "../../../../api/apiTypes";
import { FormContext } from "../ChainElementModification";
import { JSONSchema7 } from "json-schema";
import { useNotificationService } from "../../../../hooks/useNotificationService";

const ServiceField: React.FC<FieldProps<string, JSONSchema7, FormContext>> = ({
  id,
  formData,
  schema,
  required,
  uiSchema,
  formContext,
}) => {
  const { services } = useServices();
  const [servicesMap, setServicesMap] = useState<
    Map<string, IntegrationSystem>
  >(new Map());
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const notificationService = useNotificationService();
  const [isLoading, setIsLoading] = useState<boolean>(false);

  useEffect(() => {
    setIsLoading(true);
    try {
      setServicesMap(new Map(services.map((service) => [service.id, service])));

      const serviceOptions: SelectProps["options"] =
        services?.map((service: IntegrationSystem) => ({
          label: `${service.type} ${service.name}`,
          value: service.id,
        })) ?? [];
      setOptions(serviceOptions);
    } catch (error) {
      setServicesMap(new Map());
      setOptions([]);
      notificationService.requestFailed("Failed to load services", error);
    } finally {
      setIsLoading(false);
    }
  }, [services, notificationService]);

  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  const labelStyle: React.CSSProperties = {
    display: "block",
    marginBottom: 6,
    fontWeight: 500,
  };

  const requiredStyle: React.CSSProperties = {
    color: "#ff4d4f",
    marginRight: 4,
  };

  const handleChange = useCallback(
    (newValue: string) => {
      const newService: IntegrationSystem = servicesMap.get(newValue)!;

      formContext?.updateContext({
        integrationSystemId: newValue,
        systemType: newService.type.toString(),
        integrationSpecificationGroupId: null,
        integrationSpecificationId: null,
        integrationOperationId: null,
      });
    },
    [formContext, servicesMap],
  );

  return (
    <div>
      <label htmlFor={id} style={labelStyle}>
        {required ? <span style={requiredStyle}> *</span> : null}
        {title}
      </label>
      <Select
        value={formData}
        options={options}
        onChange={handleChange}
        disabled={isLoading}
      />
    </div>
  );
};

export default ServiceField;
