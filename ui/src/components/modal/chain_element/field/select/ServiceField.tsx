import React, { useCallback, useEffect, useMemo, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { SelectProps } from "antd";
import { useServices } from "../../../../../hooks/useServices.ts";
import {
  IntegrationSystem,
  IntegrationSystemType,
} from "../../../../../api/apiTypes.ts";
import { FormContext } from "../../ChainElementModification.tsx";
import { JSONSchema7 } from "json-schema";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import { VSCodeExtensionApi } from "../../../../../api/rest/vscodeExtensionApi.ts";
import { api } from "../../../../../api/api.ts";
import { SelectTag } from "./SelectTag.tsx";
import { capitalize } from "../../../../../misc/format-utils.ts";
import {
  isAsyncProtocol,
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../../misc/protocol-utils.ts";
import { SelectAndNavigateField } from "./SelectAndNavigateField.tsx";

const ServiceField: React.FC<FieldProps<string, JSONSchema7, FormContext>> = ({
  id,
  formData,
  schema,
  required,
  uiSchema,
  registry,
}) => {
  const elementType = registry.formContext.elementType;
  const { services } = useServices();
  const [serviceId, setServiceId] = useState(formData);
  const [servicesMap, setServicesMap] = useState<
    Map<string, IntegrationSystem>
  >(new Map());
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const notificationService = useNotificationService();
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const filteredServices = useMemo(() => {
    if (!services) return [];

    switch (elementType) {
      case "http-trigger":
        return services.filter(
          (s) => s.type === IntegrationSystemType.IMPLEMENTED,
        );

      case "async-api-trigger":
        return services.filter(
          (s) =>
            s.type !== IntegrationSystemType.IMPLEMENTED &&
            (s.protocol === "amqp" || s.protocol === "kafka"),
        );

      case "service-call":
        return services.filter(
          (s) => s.type !== IntegrationSystemType.IMPLEMENTED,
        );

      default:
        return services;
    }
  }, [services, elementType]);

  useEffect(() => {
    setIsLoading(true);
    try {
      setServicesMap(
        new Map(filteredServices.map((service) => [service.id, service])),
      );

      const serviceOptions: SelectProps["options"] =
        filteredServices?.map((service: IntegrationSystem) => ({
          label: (
            <>
              <SelectTag value={capitalize(service.type)} />
              {service.name}
            </>
          ),
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
  }, [filteredServices, notificationService]);

  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  const handleChange = useCallback(
    (newValue: string) => {
      setServiceId(newValue);
      const newService: IntegrationSystem = servicesMap.get(newValue)!;
      const protocol = normalizeProtocol(newService?.protocol) ?? "http";
      const isAsync = isAsyncProtocol(protocol);
      const isHttp = isHttpProtocol(protocol);

      registry.formContext?.updateContext?.({
        integrationSystemId: newValue,
        systemType: newService.type.toString(),
        integrationOperationProtocolType: protocol,
        integrationSpecificationGroupId: null,
        integrationSpecificationId: null,
        integrationOperationId: null,
        integrationOperationPath: null,
        integrationOperationMethod: null,
        integrationOperationPathParameters: isHttp ? {} : undefined,
        integrationOperationQueryParameters: isHttp ? {} : undefined,
        integrationAdditionalParameters: isHttp ? {} : undefined,
        integrationOperationAsyncProperties: isAsync ? {} : undefined,
        integrationGqlQuery: undefined,
        integrationGqlOperationName: undefined,
        integrationGqlVariablesJSON: undefined,
        integrationGqlQueryHeader: undefined,
        integrationGqlVariablesHeader: undefined,
      });
    },
    [registry, servicesMap],
  );

  const onNavigationButtonClick = useCallback(() => {
    const path = `/services/systems/${serviceId}/parameters`;
    if (api instanceof VSCodeExtensionApi) {
      void api.navigateInNewTab(path);
    } else {
      window.open(path, "_blank");
    }
  }, [serviceId]);

  return (
    <SelectAndNavigateField
      id={id}
      title={title}
      required={required}
      selectValue={formData}
      selectOptions={options}
      selectOnChange={handleChange}
      selectDisabled={isLoading}
      buttonTitle="Go to service"
      buttonDisabled={!serviceId}
      buttonOnClick={onNavigationButtonClick}
    />
  );
};

export default ServiceField;
