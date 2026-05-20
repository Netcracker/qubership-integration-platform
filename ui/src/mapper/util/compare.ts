import {
  ArrayType,
  DataType,
  ObjectSchema,
  ObjectType,
  TypeDefinition,
} from "../model/model.ts";
import { DataTypes, TypeResolutionResult } from "./types.ts";

export type CompareContext = {
  type: DataType;
  definitions: TypeDefinition[];
};

export type DifferenceDetails = {
  feature: string;
  first: string;
  second: string;
};

export type Difference = {
  path: string[];
  first: CompareContext | null;
  second: CompareContext | null;
  details?: DifferenceDetails;
};

export function compareAttributes(
  schema1: ObjectSchema,
  definitions1: TypeDefinition[],
  schema2: ObjectSchema,
  definitions2: TypeDefinition[],
  path: string[],
): Difference[] {
  if (schema1 === schema2) {
    return [];
  }
  const attrMap1 = new Map(schema1.attributes.map((a) => [a.name, a]));
  const attrMap2 = new Map(schema2.attributes.map((a) => [a.name, a]));
  const differences: Difference[] = [];
  Array.from(attrMap1.entries()).forEach(([name, attr]) => {
    const pth = [...path, name];
    const first: CompareContext = {
      type: attr.type,
      definitions: definitions1,
    };
    if (attrMap2.has(name)) {
      const a = attrMap2.get(name)!;
      differences.push(
        ...compareDataTypes(
          first,
          { type: a.type, definitions: definitions2 },
          pth,
        ),
      );
      if (!!attr.required !== !!a.required) {
        differences.push({
          path: pth,
          first: {
            type: attr.type,
            definitions: definitions1,
          },
          second: {
            type: a.type,
            definitions: definitions2,
          },
          details: {
            feature: "required",
            first: `${attr.required}`,
            second: `${a.required}`,
          },
        });
      }
      attrMap2.delete(name);
    } else {
      differences.push({ path: pth, first, second: null });
    }
  });
  differences.push(
    ...Array.from(attrMap2.entries()).map(([name, attr]) => ({
      path: [...path, name],
      first: null,
      second: { type: attr.type, definitions: definitions2 },
    })),
  );
  return differences;
}

export function buildTypeChangeDetails(
  first: TypeResolutionResult,
  second: TypeResolutionResult,
): DifferenceDetails {
  return {
    feature: "type",
    first: DataTypes.buildTypeName(first.type, first.definitions),
    second: DataTypes.buildTypeName(second.type, second.definitions),
  };
}

export function buildTypeChange(
  path: string[],
  first: TypeResolutionResult,
  second: TypeResolutionResult,
): Difference {
  return {
    path,
    first: first.type
      ? { type: first.type, definitions: first.definitions }
      : null,
    second: second.type
      ? {
          type: second.type,
          definitions: second.definitions,
        }
      : null,
    details:
      first.type && second.type
        ? buildTypeChangeDetails(first, second)
        : undefined,
  };
}
export function compareDataTypes(
  first: CompareContext,
  second: CompareContext,
  path: string[],
): Difference[] {
  const resolvedFirst = DataTypes.resolveType(first.type, first.definitions);
  const resolvedSecond = DataTypes.resolveType(second.type, second.definitions);
  if (
    resolvedFirst.type === resolvedSecond.type ||
    resolvedFirst.type?.name === resolvedSecond.type?.name
  ) {
    if (
      !resolvedFirst.type ||
      DataTypes.isPrimitiveType(resolvedFirst.type) ||
      resolvedFirst.type?.name === "null"
    ) {
      return [];
    }
    if (resolvedFirst.type.name === "array") {
      return compareDataTypes(
        {
          type: resolvedFirst.type.itemType,
          definitions: resolvedFirst.definitions,
        },
        {
          type: (resolvedSecond.type as ArrayType).itemType,
          definitions: resolvedSecond.definitions,
        },
        path,
      );
    }
    if (resolvedFirst.type.name === "object") {
      return compareAttributes(
        resolvedFirst.type.schema,
        resolvedFirst.definitions,
        (resolvedSecond.type as ObjectType).schema,
        resolvedSecond.definitions,
        path,
      );
    }
    if (
      !DataTypes.same(resolvedFirst.type, resolvedSecond.type, [
        ...(resolvedFirst.definitions ?? []),
        ...(resolvedSecond.definitions ?? []),
      ])
    ) {
      return [buildTypeChange(path, resolvedFirst, resolvedSecond)];
    }
    return [];
  } else {
    return [buildTypeChange(path, resolvedFirst, resolvedSecond)];
  }
}

export function isBreakingChange(change: Difference): boolean {
  // The only non-breaking change is addition of a new field.
  // Actually, changing a field that is not mapped directly or indirectly also doesn't break the mapping.
  // But it is more difficult to check.
  return !!change.first && change.first.type.name !== "null";
}

export function hasBreakingChanges(changes: Difference[]): boolean {
  return changes.some((change) => isBreakingChange(change));
}
