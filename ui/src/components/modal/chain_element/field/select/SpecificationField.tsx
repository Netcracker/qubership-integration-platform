import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { SelectProps } from "antd";
import { FormContext } from "../../ChainElementModification.tsx";
import { api } from "../../../../../api/api.ts";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import {
  Specification,
  SpecificationGroup,
} from "../../../../../api/apiTypes.ts";
import { JSONSchema7 } from "json-schema";
import { SelectAndNavigateField } from "./SelectAndNavigateField.tsx";
import { SelectTag } from "./SelectTag.tsx";

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
  >(props.registry.formContext?.integrationSpecificationGroupId);
  const [navigationPath, setNavigationPath] = useState<string>("");

  const systemId = props.registry.formContext?.integrationSystemId as string;
  const updateContext = props.registry.formContext.updateContext;

  const buildSpecificationOptions = (
    groupName: string,
    specifications: Specification[] | undefined,
  ): SelectProps["options"] => {
    return (
      specifications?.map((spec) => ({
        label: spec.name,
        selectedLabel: (
          <>
            <SelectTag value={groupName} /> {spec.name}
          </>
        ),
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

  const handleChange = useCallback(
    (newValue: string) => {
      const specGroupId = specIdToGroupIdMap.get(newValue);
      setSpecificationId(newValue);
      setSpecificationGroupId(specGroupId);
      const context: Record<string, unknown> = {
        integrationSpecificationId: newValue,
        integrationSpecificationGroupId: specGroupId,
        integrationOperationId: "",
        integrationOperationPath: "",
        integrationOperationMethod: "",
        integrationOperationPathParameters: {},
        integrationOperationQueryParameters: {},
      };

      updateContext?.(context);
    },
    [updateContext, specIdToGroupIdMap],
  );

  useEffect(() => {
    const loadLatestSpecification = async () => {
      if (systemId && !props.formData) {
        const latestSpec: Specification =
          await api.getLatestApiSpecification(systemId);
        handleChange(latestSpec.id);
      }
    };
    void loadLatestSpecification();
  }, [handleChange, systemId, props.formData]);

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
              options: buildSpecificationOptions(
                group.name,
                group.specifications,
              ),
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

    void loadSpecificationGroups();
  }, [systemId, notificationService]);

  const title = props.uiSchema?.["ui:title"] ?? props.schema?.title ?? "";

  useEffect(() => {
    setNavigationPath(
      `/services/systems/${systemId}/specificationGroups/${specificationGroupId}/specifications/${specificationId}/operations`,
    );
  }, [systemId, specificationGroupId, specificationId]);

  return (
    <SelectAndNavigateField
      id={props.id}
      title={title}
      required={props.required}
      selectValue={props.formData}
      selectOptions={options}
      selectOnChange={handleChange}
      selectDisabled={isLoading}
      selectOptionLabelProp="selectedLabel"
      buttonTitle="Go to specification"
      buttonDisabled={!(specificationGroupId && specificationId)}
      buttonOnClick={navigationPath}
    />
  );
};

export default SpecificationField;
