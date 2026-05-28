import React, {
  createContext,
  PropsWithChildren,
  useCallback,
} from "react";
import { getSchemaRawByElementType } from "../../modal/chain_element/chainElementSchemaModules.ts";
import { JSONSchema7 } from "json-schema";
import yaml from "js-yaml";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";

const schemas = new Map<string, JSONSchema7>();

export type ElementSchemasContextProps = {
  getSchema: (type: string) => JSONSchema7 | undefined;
};

export const ElementSchemasContext = createContext<ElementSchemasContextProps>({
  getSchema: (type) => schemas.get(type),
});

export const ElementSchemasProvider: React.FC<PropsWithChildren> = ({
  children,
}) => {
  const notificationService = useNotificationService();

  const loadSchema = useCallback(
    (type: string) => {
      try {
        const raw = getSchemaRawByElementType(type);
        if (!raw) {
          return undefined;
        }
        return yaml.load(raw) as JSONSchema7;
      } catch (error) {
        notificationService.errorWithDetails(
          "Failed to parse element schema",
          "",
          error,
        );
        return;
      }
    },
    [notificationService],
  );

  const getSchema = useCallback(
    (type: string) => {
      if (schemas.has(type)) {
        return schemas.get(type);
      }
      const schema = loadSchema(type);
      if (schema) {
        schemas.set(type, schema);
      }
      return schema;
    },
    [loadSchema],
  );

  return (
    <ElementSchemasContext.Provider value={{ getSchema }}>
      {children}
    </ElementSchemasContext.Provider>
  );
};
