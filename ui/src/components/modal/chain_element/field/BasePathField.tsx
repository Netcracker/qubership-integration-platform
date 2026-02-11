import React, { useEffect, useState } from "react";
import { JSONSchema7 } from "json-schema";
import { FormContext } from "../ChainElementModification";
import { FieldProps } from "@rjsf/utils";
import { Input } from "antd";
import { api } from "../../../../api/api";
import { Environment, IntegrationSystem } from "../../../../api/apiTypes";
import { getAppName } from "../../../../appConfig";
import styles from "../ChainElementModification.module.css";

export const buildPrefix = (isExternalRoute: boolean | undefined): string => {
  return isExternalRoute ? `/${getAppName()}-routes/` : "/routes/";
};

export const concatPaths = (paths: string[]): string => {
  return paths
    .filter((path) => !!path)
    .map((s) => (s.endsWith("/") ? s.substring(0, s.length - 1) : s))
    .map((s) => (s.startsWith("/") ? s : "/" + s))
    .join("");
};

const BasePathField: React.FC<FieldProps<string, JSONSchema7, FormContext>> = ({
  id,
  registry,
}) => {
  const [address, setAddress] = useState<string>();
  const systemId = registry.formContext?.integrationSystemId;

  useEffect(() => {
    const loadEnvironment = async () => {
      if (systemId) {
        const system: IntegrationSystem = await api.getService(systemId);

        if (system.activeEnvironmentId) {
          const env: Environment = await api.getEnvironment(
            systemId,
            system.activeEnvironmentId,
          );
          setAddress(env.address);
        }
      }
    };

    void loadEnvironment();
  }, [systemId]);

  return (
    <>
      <label htmlFor={id} className={styles["field-label"]}>
        Base path
      </label>
      <Input
        disabled={true}
        value={concatPaths([
          buildPrefix(registry.formContext.externalRoute),
          address ?? "",
        ])}
      />
    </>
  );
};

export default BasePathField;
