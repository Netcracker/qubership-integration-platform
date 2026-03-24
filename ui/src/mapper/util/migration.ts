import {
  AttributeReference,
  DataType,
  MappingAction,
  SchemaKind,
} from "../model/model.ts";
import { Attributes } from "./attributes.ts";
import { MappingUtil } from "./mapping.ts";

export function migratePath(
  path: string[],
  from: DataType,
  to: DataType,
): string[] | null {
  const restoredPath = Attributes.restorePath(
    Attributes.wrapType(from),
    path,
  )?.map((a) => a.name);
  return restoredPath
    ? Attributes.resolvePath(restoredPath, Attributes.wrapType(to))
    : null;
}

export function migrateAttributeReference(
  reference: AttributeReference,
  pathPrefix: string[],
  from: DataType,
  to: DataType,
): AttributeReference | null {
  const startsWithPrefix =
    reference.path.length >= pathPrefix.length &&
    pathPrefix.every((s, i) => reference.path[i] === s);
  if (!startsWithPrefix) {
    return null;
  }
  const pathSuffix = reference.path.slice(pathPrefix.length);
  const migratedSuffix = migratePath(pathSuffix, from, to);
  return migratedSuffix
    ? { ...reference, path: [...pathPrefix, ...migratedSuffix] }
    : null;
}

export function migrateAction(
  action: MappingAction,
  schemaKind: SchemaKind,
  pathPrefix: string[],
  from: DataType,
  to: DataType,
): MappingAction | null {
  const sources =
    schemaKind === SchemaKind.SOURCE
      ? action.sources
          .map((source) =>
            MappingUtil.isAttributeReference(source)
              ? migrateAttributeReference(source, pathPrefix, from, to)
              : source,
          )
          .filter((source) => !!source)
      : action.sources;
  const target =
    schemaKind === SchemaKind.SOURCE
      ? action.target
      : migrateAttributeReference(action.target, pathPrefix, from, to);
  return sources.length === 0 || !target
    ? null
    : {
        ...action,
        sources,
        target,
      };
}

export function migrateActions(
  actions: MappingAction[],
  schemaKind: SchemaKind,
  pathPrefix: string[],
  from: DataType | null,
  to: DataType | null,
): MappingAction[] {
  if (!from || !to) {
    return actions;
  }
  return actions.map(
    (action) =>
      migrateAction(action, schemaKind, pathPrefix, from, to) ?? action,
  );
}
