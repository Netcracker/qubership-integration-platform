import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { SelectProps } from "antd";
import { FormContext } from "../../ChainElementModification.tsx";
import { api } from "../../../../../api/api.ts";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import { Specification, SpecificationGroup } from "../../../../../api/apiTypes.ts";
import { JSONSchema7 } from "json-schema";
import { VSCodeExtensionApi } from "../../../../../api/rest/vscodeExtensionApi.ts";
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
  const systemId = props.registry.formContext?.integrationSystemId as string;

  const buildSpecificationOptions = (
    groupName: string,
    specifications: Specification[] | undefined,
  ): SelectProps["options"] => {
    return (
      specifications?.map((spec) => ({
        label: spec.name,
        selectedLabel: (
          <>
            <SelectTag value={groupName} width={200} /> {spec.name}
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
              options: buildSpecificationOptions(group.name, group.specifications),
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

      props.registry.formContext.updateContext?.(context);
    },
    [props.registry.formContext, specIdToGroupIdMap],
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
      buttonOnClick={onNavigationButtonClick}
    />
  );
};

export default SpecificationField;
