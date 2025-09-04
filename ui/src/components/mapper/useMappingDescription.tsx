import {
  Attribute,
  AttributeKind,
  AttributeReference,
  Constant,
  ConstantReference,
  DataType,
  MappingAction,
  MappingDescription,
  SchemaKind,
  TypeDefinition,
} from "../../mapper/model/model.ts";
import { useCallback, useEffect, useState } from "react";
import { MappingUtil } from "../../mapper/util/mapping.ts";
import { MessageSchemaUtil } from "../../mapper/util/schema.ts";
import { exportAsJsonSchema } from "../../mapper/json-schema/json-schema.ts";
import { formatDate } from "../../misc/format-utils.ts";
import { downloadFile } from "../../misc/download-utils.ts";
import { DataTypes } from "../../mapper/util/types.ts";
import { Attributes } from "../../mapper/util/attributes.ts";
import { MappingActions } from "../../mapper/util/actions.ts";
import { XmlNamespace } from "../../mapper/model/metadata.ts";

function removeConnectionForActions(
  actions: MappingAction[],
  source: ConstantReference | AttributeReference,
  target: AttributeReference,
): MappingAction[] {
  return actions
    .map((action) =>
      MappingActions.referencesAreEqual(action.target, target)
        ? {
            ...action,
            sources: action.sources.filter(
              (src) => !MappingActions.referencesAreEqual(src, source),
            ),
          }
        : action,
    )
    .filter((action) => action.sources.length);
}

export type UseMappingDescriptionProps = {
  mapping?: MappingDescription;
  onChange?: (mappingDescription: MappingDescription) => void;
};

export type ErrorHandler = (error: unknown) => void;

export type Connection = {
  source: ConstantReference | AttributeReference;
  target: AttributeReference;
};

export const useMappingDescription = ({
  mapping,
  onChange,
}: UseMappingDescriptionProps) => {
  const [mappingDescription, setMappingDescription] =
    useState<MappingDescription>(MappingUtil.emptyMapping());

  useEffect(() => {
    setMappingDescription(mapping ?? MappingUtil.emptyMapping());
  }, [mapping]);

  const updateMapping = useCallback(
    (
      updateFn: (
        mappingDescription: MappingDescription,
      ) => Partial<MappingDescription>,
      removeDanglingActions: boolean,
      onError: ErrorHandler,
    ) => {
      setMappingDescription((mapping) => {
        try {
          const changes = updateFn(mapping);
          mapping = { ...mapping, ...changes };
          if (removeDanglingActions) {
            mapping = MappingUtil.removeDanglingActions(mapping);
          }
          onChange?.(mapping);
        } catch (error) {
          onError(error);
        }
        return mapping;
      });
    },
    [onChange],
  );

  const clearConstants = useCallback(() => {
    updateMapping(
      () => ({ constants: [] }),
      true,
      () => {},
    );
  }, [updateMapping]);

  const clearTree = useCallback(
    (schemaKind: SchemaKind, kind: AttributeKind, path: Attribute[]) => {
      updateMapping(
        (mapping) => ({
          [schemaKind]: MessageSchemaUtil.clearAttributes(
            mapping[schemaKind],
            kind,
            path,
          ),
        }),
        true,
        () => {},
      );
    },
    [updateMapping],
  );

  const removeConstant = useCallback(
    (constantId: string) => {
      updateMapping(
        (mapping) => ({
          constants: mapping.constants.filter(
            (constant) => constant.id !== constantId,
          ),
        }),
        true,
        () => {},
      );
    },
    [updateMapping],
  );

  const removeAttribute = useCallback(
    (schemaKind: SchemaKind, kind: AttributeKind, path: Attribute[]) => {
      updateMapping(
        (mapping) => ({
          [schemaKind]: MessageSchemaUtil.removeAttribute(
            mapping[schemaKind],
            kind,
            path,
          ),
        }),
        true,
        () => {},
      );
    },
    [updateMapping],
  );

  const updateBodyType = useCallback(
    (schemaKind: SchemaKind, type: DataType | undefined | null) => {
      updateMapping(
        (mapping) => ({
          [schemaKind]: { ...mapping[schemaKind], body: type },
        }),
        true,
        () => {},
      );
    },
    [updateMapping],
  );

  const exportDataType = useCallback(
    (type: DataType, definitions: TypeDefinition[]) => {
      const schema = exportAsJsonSchema(type, definitions);
      const text = JSON.stringify(schema);
      const blob = new Blob([text], { type: "application/json" });
      const timestamp = formatDate(new Date());
      const fileName = `schema-${timestamp}.json`;
      const file = new File([blob], fileName, { type: "application/json" });
      downloadFile(file);
    },
    [],
  );

  const addConstant = useCallback(
    (data: Omit<Partial<Constant>, "id">, onError: ErrorHandler) => {
      updateMapping(
        (mapping) => {
          const constantWithSameNameExists = MappingUtil.findConstant(
            mapping,
            (constant) => constant.name === (data.name ?? ""),
          );
          if (constantWithSameNameExists) {
            throw new Error(`Constant "${data.name ?? ""}" already exists.`);
          }
          return {
            constants: [
              ...mapping.constants,
              {
                id: MappingUtil.generateUUID(),
                name: "",
                type: DataTypes.stringType(),
                valueSupplier: { kind: "given", value: "" },
                ...data,
              },
            ],
          };
        },
        false,
        onError,
      );
    },
    [updateMapping],
  );

  const addAttribute = useCallback(
    (
      schemaKind: SchemaKind,
      kind: AttributeKind,
      path: Attribute[],
      data: Omit<Partial<Attribute>, "id">,
      onError: ErrorHandler,
    ) => {
      updateMapping(
        (mapping) => ({
          [schemaKind]: MessageSchemaUtil.updateAttribute(
            mapping[schemaKind],
            kind,
            path,
            {
              ...Attributes.buildAttribute(
                MappingUtil.generateUUID(),
                "",
                DataTypes.stringType(),
              ),
              ...data,
            },
          ),
        }),
        false,
        onError,
      );
    },
    [updateMapping],
  );

  const updateAttribute = useCallback(
    (
      schemaKind: SchemaKind,
      kind: AttributeKind,
      path: Attribute[],
      changes: Omit<Partial<Attribute>, "id">,
      onError: ErrorHandler,
    ) => {
      updateMapping(
        (mapping) =>
          path.length === 0
            ? {}
            : {
                [schemaKind]: MessageSchemaUtil.updateAttribute(
                  mapping[schemaKind],
                  kind,
                  path.slice(0, -1),
                  { ...path.slice(-1).pop()!, ...changes },
                ),
              },
        false,
        onError,
      );
    },
    [updateMapping],
  );

  const updateConstant = useCallback(
    (
      id: string,
      changes: Omit<Partial<Constant>, "id">,
      onError: ErrorHandler,
    ) => {
      updateMapping(
        (mapping) => {
          const constantWithSameNameExists = MappingUtil.findConstant(
            mapping,
            (constant) => constant.id !== id && constant.name === changes.name,
          );
          if (constantWithSameNameExists) {
            throw new Error(`Constant "${changes.name}" already exists.`);
          }
          return {
            constants: mapping.constants.map((constant) =>
              constant.id === id ? { ...constant, ...changes } : constant,
            ),
          };
        },
        false,
        onError,
      );
    },
    [updateMapping],
  );

  const updateActions = useCallback(
    (updateFn: (action: MappingAction) => MappingAction) => {
      updateMapping(
        (mapping) => ({ actions: mapping.actions.map(updateFn) }),
        false,
        () => {},
      );
    },
    [updateMapping],
  );

  const createOrUpdateMappingActionForTarget = useCallback(
    (
      affectedActions: MappingAction[],
      target: AttributeReference,
      sources: (ConstantReference | AttributeReference)[],
    ) => {
      updateMapping(
        (mapping) => {
          const affectedActionIds = new Set(affectedActions.map((a) => a.id));
          const actions: MappingAction[] = [];
          if (affectedActionIds.size !== 0) {
            if (sources.length > 0) {
              actions.push(
                ...mapping.actions.map((a) =>
                  affectedActionIds.has(a.id) ? { ...a, sources } : a,
                ),
              );
            } else {
              actions.push(
                ...mapping.actions.filter((a) => !affectedActionIds.has(a.id)),
              );
            }
          } else {
            const action: MappingAction = {
              id: MappingUtil.generateUUID(),
              sources,
              target,
              transformation: undefined,
            };
            actions.push(...mapping.actions, action);
          }
          return { actions };
        },
        false,
        () => {},
      );
    },
    [updateMapping],
  );

  const createOrUpdateMappingActionsForSource = useCallback(
    (
      source: ConstantReference | AttributeReference,
      targets: AttributeReference[],
    ) => {
      updateMapping(
        (mapping) => {
          const actions =
            mapping.actions
              .map((action) => {
                const targetMatches = targets.some((t) =>
                  MappingActions.referencesAreEqual(t, action.target),
                );
                const hasSource = action.sources?.some((s) =>
                  MappingActions.referencesAreEqual(s, source),
                );
                if (hasSource) {
                  return targetMatches
                    ? action
                    : action.sources.length === 1
                      ? null
                      : {
                          ...action,
                          sources: action.sources?.filter(
                            (s) =>
                              !MappingActions.referencesAreEqual(s, source),
                          ),
                        };
                } else {
                  return targetMatches
                    ? { ...action, sources: [...action.sources, source] }
                    : action;
                }
              })
              .filter((a) => !!a) ?? [];
          const targetsToAddActions = targets.filter(
            (target) =>
              !mapping.actions.some((a) =>
                MappingActions.referencesAreEqual(a.target, target),
              ),
          );
          const newActions = targetsToAddActions.map((target) => ({
            id: MappingUtil.generateUUID(),
            sources: [source],
            target,
            transformation: undefined,
          }));
          return { actions: [...actions, ...newActions] };
        },
        false,
        () => {},
      );
    },
    [updateMapping],
  );

  const updateXmlNamespaces = useCallback(
    (schemaKind: SchemaKind, path: Attribute[], namespaces: XmlNamespace[]) => {
      updateMapping(
        (mapping) => ({
          [schemaKind]: MessageSchemaUtil.updateXmlNamespaces(
            mapping[schemaKind],
            path,
            namespaces,
          ),
        }),
        false,
        () => {},
      );
    },
    [updateMapping],
  );

  const removeConnection = useCallback(
    (
      source: ConstantReference | AttributeReference,
      target: AttributeReference,
    ) => {
      updateMapping(
        (mapping) => ({
          actions: removeConnectionForActions(mapping.actions, source, target),
        }),
        false,
        () => {},
      );
    },
    [updateMapping],
  );

  const removeConnections = useCallback(
    (connections: Connection[]) => {
      updateMapping(
        (mapping) => ({
          actions: connections.reduce(
            (actions, connection) =>
              removeConnectionForActions(
                actions,
                connection.source,
                connection.target,
              ),
            mapping.actions,
          ),
        }),
        false,
        () => {},
      );
    },
    [updateMapping],
  );

  return {
    mappingDescription,
    clearConstants,
    clearTree,
    removeConstant,
    removeAttribute,
    updateBodyType,
    exportDataType,
    addConstant,
    addAttribute,
    updateAttribute,
    updateConstant,
    updateActions,
    createOrUpdateMappingActionForTarget,
    createOrUpdateMappingActionsForSource,
    updateXmlNamespaces,
    removeConnection,
    removeConnections,
  };
};
