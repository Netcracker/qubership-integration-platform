import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Button, Flex, Select, SelectProps, Tooltip } from "antd";
import { FormContext } from "../ChainElementModification";
import { api } from "../../../../api/api";
import { useNotificationService } from "../../../../hooks/useNotificationService";
import { Specification, SpecificationGroup } from "../../../../api/apiTypes";
import { JSONSchema7 } from "json-schema";
import { VSCodeExtensionApi } from "../../../../api/rest/vscodeExtensionApi";
import { Icon } from "../../../../IconProvider";

const SpecificationField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = (props) => {
  const notificationService = useNotificationService();
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [specIdToGroupIdMap, setSpecIdToGroupIdMap] = useState<
    Map<string, string>
  >(new Map());

  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [specificationId, setSpecificationId] = useState<string | undefined>(
    props.formData,
  );
  const [specificationGroupId, setSpecificationGroupId] = useState<
    string | undefined
  >(props.formContext?.integrationSpecificationGroupId);
  const systemId = props.formContext!.integrationSystemId;

  const buildSpecificationOptions = (
    specifications: Specification[] | undefined,
  ): SelectProps["options"] => {
    return (
      specifications?.map((spec) => ({
        label: spec.name,
        value: spec.id,
      })) ?? []
    );
  };

  const buildSpecToGroupMap = (
    groups: SpecificationGroup[],
  ): Map<string, string> => {
    const result: Map<string, string> = new Map();
    for (const group of groups) {
      for (const spec of group.specifications) {
        result.set(spec.id, group.id);
      }
    }
    return result;
  };

  useEffect(() => {
    const loadSpecificationGroups = async () => {
      setIsLoading(true);
      try {
        if (systemId) {
          const groups = await api.getApiSpecifications(systemId);

          const groupOptions: SelectProps["options"] =
            groups?.map((group) => ({
              label: <span>{group.name}</span>,
              title: group.name,
              options: buildSpecificationOptions(group.specifications),
            })) ?? [];
          setSpecIdToGroupIdMap(buildSpecToGroupMap(groups));
          setOptions(groupOptions);
        } else {
          setSpecIdToGroupIdMap(new Map());
          setOptions([]);
        }
      } catch (error) {
        setSpecIdToGroupIdMap(new Map());
        setOptions([]);
        notificationService.requestFailed(
          "Failed to load specification groups",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    };

    loadSpecificationGroups();
  }, [systemId, notificationService]);

  const title = props.uiSchema?.["ui:title"] ?? props.schema?.title ?? "";

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
      const specGroupId = specIdToGroupIdMap.get(newValue);
      setSpecificationId(newValue);
      setSpecificationGroupId(specGroupId);
      const context: Record<string, unknown> = {
        integrationSpecificationId: newValue,
        integrationSpecificationGroupId: specGroupId,
        integrationOperationId: null,
        integrationOperationPath: null,
        integrationOperationMethod: null,
      };

      props.formContext!.updateContext(context);
    },
    [props.formContext, specIdToGroupIdMap],
  );

  const onNavigationButtonClick = useCallback(() => {
    const path = `/services/systems/${systemId}/specificationGroups/${specificationGroupId}/specifications/${specificationId}/operations`;
    if (api instanceof VSCodeExtensionApi) {
      void api.navigateInNewTab(path);
    } else {
      window.open(path, "_blank");
    }
  }, [systemId, specificationGroupId, specificationId]);

  return (
    <div>
      <label htmlFor={props.id} style={labelStyle}>
        {props.required ? <span style={requiredStyle}> *</span> : null}
        {title}
      </label>
      <Flex gap={4}>
        <Select
          value={props.formData}
          options={options}
          onChange={handleChange}
          disabled={isLoading}
        />
        <Tooltip title="Go to specification">
          <Button
            icon={<Icon name="send" />}
            disabled={!(specificationGroupId && specificationId)}
            onClick={onNavigationButtonClick}
          />
        </Tooltip>
      </Flex>
    </div>
  );
};

export default SpecificationField;
