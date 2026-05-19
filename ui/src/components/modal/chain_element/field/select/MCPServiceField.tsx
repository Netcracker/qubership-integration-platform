import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import { FormContext } from "../../ChainElementModificationContext.ts";
import styles from "../../ChainElementModification.module.css";
import React, { useCallback, useEffect, useState } from "react";
import { MCPSystem } from "../../../../../api/apiTypes.ts";
import { Select, SelectProps } from "antd";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import { api } from "../../../../../api/api.ts";

export const MCPServiceField: React.FC<
  FieldProps<string[], JSONSchema7, FormContext>
> = ({ id, formData, schema, required, uiSchema, registry }) => {
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [systems, setSystems] = useState<MCPSystem[]>([]);
  const [options, setOptions] = useState<SelectProps<string>["options"]>([]);
  const notificationService = useNotificationService();

  const loadSystems = useCallback(async () => {
    try {
      setIsLoading(true);
      const result = await api.getMcpSystems(true);
      setSystems(result);
    } catch (e) {
      notificationService.requestFailed("Failed to load MCP services", e);
      setSystems([]);
    } finally {
      setIsLoading(false);
    }
  }, [notificationService]);

  useEffect(() => {
    void loadSystems();
  }, [loadSystems]);

  useEffect(() => {
    setOptions(
      systems.map((system) => ({
        labelString: system.name,
        label: system.name,
        value: system.id,
      })),
    );
  }, [systems]);

  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  const handleChange = useCallback(
    (newValue: string[]) => {
      registry.formContext?.updateContext?.({
        mcpServiceIds: newValue,
      });
    },
    [registry.formContext],
  );

  return (
    <div>
      <label htmlFor={id} className={styles["field-label"]}>
        {required ? <span className={styles["field-required"]}> *</span> : null}
        {title}
      </label>
      <Select<string[]>
        mode={"multiple"}
        value={formData}
        options={options}
        onChange={handleChange}
        loading={isLoading}
        showSearch={true}
        optionFilterProp="labelString"
      />
    </div>
  );
};
