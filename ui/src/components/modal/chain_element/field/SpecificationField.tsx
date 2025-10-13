import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Select, SelectProps } from "antd";
import { FormContext } from "../ChainElementModification";
import { api } from "../../../../api/api";
import { useNotificationService } from "../../../../hooks/useNotificationService";
import { Specification, SpecificationGroup } from "../../../../api/apiTypes";
import { JSONSchema7 } from "json-schema";

const SpecificationField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = (props) => {
  const notificationService = useNotificationService();
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [specIdToGroupIdMap, setSpecIdToGroupIdMap] = useState<
    Map<string, string>
  >(new Map());

  const [isLoading, setIsLoading] = useState<boolean>(false);
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
      const context: Record<string, unknown> = {
        integrationSpecificationId: newValue,
        integrationSpecificationGroupId: specIdToGroupIdMap.get(newValue),
        integrationOperationId: null,
        integrationOperationPath: null,
        integrationOperationMethod: null,
        integrationOperationProtocolType: null,
      };

      props.formContext!.updateContext(context);
    },
    [props.formContext, specIdToGroupIdMap],
  );

  return (
    <div>
      <label htmlFor={props.id} style={labelStyle}>
        {props.required ? <span style={requiredStyle}> *</span> : null}
        {title}
      </label>
      <Select
        value={props.formData}
        options={options}
        onChange={handleChange}
        disabled={isLoading}
      />
    </div>
  );
};

export default SpecificationField;
