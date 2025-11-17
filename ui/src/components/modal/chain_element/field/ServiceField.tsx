import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Button, Flex, Select, SelectProps, Tooltip } from "antd";
import { useServices } from "../../../../hooks/useServices";
import { IntegrationSystem } from "../../../../api/apiTypes";
import { FormContext } from "../ChainElementModification";
import { JSONSchema7 } from "json-schema";
import { useNotificationService } from "../../../../hooks/useNotificationService";
import { Icon } from "../../../../IconProvider";
import { VSCodeExtensionApi } from "../../../../api/rest/vscodeExtensionApi";
import { api } from "../../../../api/api";
import { ServiceTag } from "./ServiceTag";
import { capitalize } from "../../../../misc/format-utils";
import {
  isAsyncProtocol,
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../misc/protocol-utils";

const ServiceField: React.FC<FieldProps<string, JSONSchema7, FormContext>> = ({
  id,
  formData,
  schema,
  required,
  uiSchema,
  formContext,
}) => {
  const { services } = useServices();
  const [serviceId, setServiceId] = useState(formData);
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
          label: <><ServiceTag value={capitalize(service.type)}/>{service.name}</>,
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
      setServiceId(newValue);
      const newService: IntegrationSystem = servicesMap.get(newValue)!;
      const protocol = normalizeProtocol(newService?.protocol) ?? "http";
      const isAsync = isAsyncProtocol(protocol);
      const isHttp = isHttpProtocol(protocol);

      formContext?.updateContext({
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
    [formContext, servicesMap],
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
    <div>
      <label htmlFor={id} style={labelStyle}>
        {required ? <span style={requiredStyle}> *</span> : null}
        {title}
      </label>
      <Flex gap={4}>
        <Select
          value={formData}
          options={options}
          onChange={handleChange}
          disabled={isLoading}
        />
        <Tooltip title="Go to service">
          <Button
            icon={<Icon name="send" />}
            disabled={!serviceId}
            onClick={onNavigationButtonClick}
          />
        </Tooltip>
      </Flex>
    </div>
  );
};

export default ServiceField;
