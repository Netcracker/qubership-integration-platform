import {
  AllOfType,
  AnyOfType,
  ArrayType,
  Attribute,
  BooleanType,
  CompoundType,
  DataType,
  IntegerType,
  Metadata,
  NullType,
  ObjectSchema,
  ObjectType,
  OneOfType,
  ReferenceType,
  StringType,
  TypeDefinition,
  TypeDefinitionsAware,
} from "../model/model";
import { MappingUtil } from "./mapping.ts";

export interface TypeResolutionResult {
  type: DataType | undefined;
  definitions: TypeDefinition[];
}

export class DataTypes {
  public static nullType(metadata?: Metadata): NullType {
    return { name: "null", metadata };
  }

  public static stringType(metadata?: Metadata): StringType {
    return { name: "string", metadata };
  }

  public static integerType(metadata?: Metadata): IntegerType {
    return { name: "number", metadata };
  }

  public static booleanType(metadata?: Metadata): BooleanType {
    return { name: "boolean", metadata };
  }

  public static arrayType(
    itemType: DataType,
    definitions?: TypeDefinition[],
    metadata?: Metadata,
  ): ArrayType {
    return { name: "array", itemType, definitions, metadata };
  }

  public static objectType(
    schema: ObjectSchema,
    definitions?: TypeDefinition[],
    metadata?: Metadata,
  ): ObjectType {
    return { name: "object", schema, definitions, metadata };
  }

  public static referenceType(
    definitionId: string,
    definitions?: TypeDefinition[],
    metadata?: Metadata,
  ): ReferenceType {
    return { name: "reference", definitionId, definitions, metadata };
  }

  public static allOfType(types: DataType[], metadata?: Metadata): AllOfType {
    return { name: "allOf", types, metadata };
  }

  public static anyOfType(types: DataType[], metadata?: Metadata): AnyOfType {
    return { name: "anyOf", types, metadata };
  }

  public static oneOfType(types: DataType[], metadata?: Metadata): OneOfType {
    return { name: "oneOf", types, metadata };
  }

  public static updateMetadata(type: DataType, metadata: Metadata): DataType {
    return type
      ? {
          ...type,
          metadata: metadata
            ? { ...type.metadata, ...metadata }
            : type.metadata,
        }
      : type;
  }

  public static mergeTypeDefinitions(
    ...definitionLists: (TypeDefinition[] | undefined)[]
  ): TypeDefinition[] {
    return definitionLists
      .filter((definitions) => !!definitions)
      .reduce((l0, l1) => [...l0, ...l1]);
  }

  public static updateDefinitions(
    type: DataType,
    definitions: TypeDefinition[],
  ): DataType {
    return this.typeIsDefinitionsAware(type) && definitions
      ? ({
          ...type,
          definitions: DataTypes.mergeTypeDefinitions(
            type.definitions,
            definitions,
          ),
        } as DataType)
      : type;
  }

  public static resolveType(
    type: DataType | undefined,
    typeDefinitions: TypeDefinition[],
  ): TypeResolutionResult {
    while (type && DataTypes.isReferenceType(type)) {
      typeDefinitions = DataTypes.mergeTypeDefinitions(
        typeDefinitions,
        type.definitions,
      );
      const definition = typeDefinitions.find(
        (d) => d.id === (type as ReferenceType).definitionId,
      );
      // Check for self-referencing type
      if (type === definition?.type) {
        break;
      }
      type = definition?.type;
    }
    return { type, definitions: typeDefinitions };
  }

  public static resolveArrayItemType(
    type: DataType,
    typeDefinitions: TypeDefinition[],
  ): TypeResolutionResult {
    let t: DataType | undefined = type;
    while (t && DataTypes.isArrayType(t)) {
      typeDefinitions = DataTypes.mergeTypeDefinitions(
        typeDefinitions,
        t.definitions,
      );
      const result = DataTypes.resolveType(t.itemType, typeDefinitions);
      t = result.type;
      typeDefinitions = result.definitions;
    }
    return { type: t, definitions: typeDefinitions };
  }

  public static getTypeDefinitions(type: DataType): TypeDefinition[] {
    return this.typeIsDefinitionsAware(type) ? (type.definitions ?? []) : [];
  }

  public static typeIsDefinitionsAware(
    type: DataType,
  ): type is DataType & TypeDefinitionsAware {
    return ["array", "object", "reference", "oneOf", "anyOf", "allOf"].some(
      (name) => name === type?.name,
    );
  }

  public static isCompoundType(
    type: DataType,
  ): type is DataType & CompoundType {
    return (
      type?.name === "anyOf" || type?.name === "oneOf" || type?.name === "allOf"
    );
  }

  public static isPrimitiveType(
    type: DataType,
  ): type is StringType | IntegerType | BooleanType {
    return (
      type?.name === "string" ||
      type?.name === "number" ||
      type?.name === "boolean"
    );
  }

  public static isArrayType(type: DataType): type is ArrayType {
    return type?.name === "array";
  }

  public static isReferenceType(type: DataType): type is ReferenceType {
    return type?.name === "reference";
  }

  public static modifyAttributes(
    type: DataType,
    path: string[],
    typeDefinitions: TypeDefinition[],
    modifyFn: (attributes: Attribute[]) => Attribute[],
  ): DataType {
    const resolutionResult = this.resolveType(type, typeDefinitions);
    const resolvedType = resolutionResult.type;
    const resolvedDefinitions = resolutionResult.definitions;
    switch (resolvedType?.name) {
      case "object": {
        const definitions = DataTypes.mergeTypeDefinitions(
          resolvedDefinitions,
          resolvedType.definitions,
        );
        const attributes = path.length
          ? resolvedType.schema.attributes.map((a) =>
              a.id === path[0]
                ? {
                    ...a,
                    type: this.modifyAttributes(
                      a.type,
                      path.slice(1),
                      definitions,
                      modifyFn,
                    ),
                  }
                : a,
            )
          : modifyFn(resolvedType.schema.attributes);
        return {
          ...resolvedType,
          schema: {
            ...resolvedType.schema,
            id: MappingUtil.generateUUID(),
            attributes,
          },
        };
      }
      case "array": {
        const definitions = DataTypes.mergeTypeDefinitions(
          resolvedDefinitions,
          resolvedType.definitions,
        );
        return this.arrayType(
          this.modifyAttributes(
            resolvedType.itemType,
            path,
            definitions,
            modifyFn,
          ),
          resolvedType.definitions,
          resolvedType.metadata,
        );
      }
      case "anyOf":
      case "allOf":
      case "oneOf": {
        const definitions = DataTypes.mergeTypeDefinitions(
          resolvedDefinitions,
          resolvedType.definitions,
        );
        return {
          ...resolvedType,
          types: resolvedType.types.map((t) =>
            this.modifyAttributes(t, path, definitions, modifyFn),
          ),
        };
      }
      default:
        return type;
    }
  }

  public static same(
    one: DataType | undefined,
    other: DataType | undefined,
    typeDefinitions: TypeDefinition[],
  ): boolean {
    return (
      one === other ||
      (one?.name === other?.name &&
        (one?.name === "string" ||
          one?.name === "number" ||
          one?.name === "boolean" ||
          one?.name === "null" ||
          (one?.name === "array" &&
            DataTypes.arrayTypesAreSame(
              one,
              other as ArrayType,
              typeDefinitions,
            )) ||
          (one?.name === "reference" &&
            DataTypes.referenceTypesAreSame(
              one,
              other as ReferenceType,
              typeDefinitions,
            )) ||
          (one &&
            this.isCompoundType(one) &&
            DataTypes.compoundTypesHaveSameSubtypes(
              one as CompoundType,
              other as CompoundType,
              typeDefinitions,
            )) ||
          (one?.name === "object" &&
            this.objectSchemasAreSame(
              one.schema,
              (other as ObjectType).schema,
              DataTypes.mergeTypeDefinitions(
                one.definitions,
                (other as ObjectType).definitions,
                typeDefinitions,
              ),
            ))))
    );
  }

  public static arrayTypesAreSame(
    one: ArrayType,
    other: ArrayType,
    typeDefinitions: TypeDefinition[],
  ): boolean {
    const definitions = DataTypes.mergeTypeDefinitions(
      one.definitions,
      other.definitions,
      typeDefinitions,
    );
    return DataTypes.same(one.itemType, other.itemType, definitions);
  }

  public static referenceTypesAreSame(
    one: ReferenceType,
    other: ReferenceType,
    typeDefinitions: TypeDefinition[],
  ): boolean {
    if (one.definitionId === other.definitionId) {
      return true;
    }
    const oneTypeResolutionResult = this.resolveType(
      one,
      DataTypes.mergeTypeDefinitions(one.definitions, typeDefinitions),
    );
    const otherTypeResolutionResult = this.resolveType(
      other,
      DataTypes.mergeTypeDefinitions(other.definitions, typeDefinitions),
    );
    return DataTypes.same(
      oneTypeResolutionResult.type,
      otherTypeResolutionResult.type,
      DataTypes.mergeTypeDefinitions(
        oneTypeResolutionResult.definitions,
        otherTypeResolutionResult.definitions,
      ),
    );
  }

  public static compoundTypesHaveSameSubtypes(
    one: CompoundType,
    other: CompoundType,
    typeDefinitions: TypeDefinition[],
  ): boolean {
    return (
      one.types.length === other.types.length &&
      one.types.length ===
        new Set(
          one.types.map((t) =>
            other.types.findIndex((t1) =>
              this.same(
                t,
                t1,
                DataTypes.mergeTypeDefinitions(
                  one.definitions,
                  other.definitions,
                  typeDefinitions,
                ),
              ),
            ),
          ),
        ).size
    );
  }

  public static objectSchemasAreSame(
    one: ObjectSchema,
    other: ObjectSchema,
    typeDefinitions: TypeDefinition[],
  ): boolean {
    if (one === other) {
      return true;
    }
    const oneTypeMap = new Map(one.attributes.map((a) => [a.name, a.type]));
    const otherTypeMap = new Map(other.attributes.map((a) => [a.name, a.type]));
    return (
      oneTypeMap.size === otherTypeMap.size &&
      Array.from(oneTypeMap.entries()).every(([name, type]) =>
        DataTypes.same(type, otherTypeMap.get(name), typeDefinitions),
      )
    );
  }

  public static buildTypeName(
    type: DataType,
    definitions: TypeDefinition[],
  ): string {
    switch (type?.name) {
      case "array":
        return `array of ${this.buildTypeName(
          type.itemType,
          DataTypes.mergeTypeDefinitions(definitions, type.definitions),
        )}`;
      case "reference":
        return (
          DataTypes.mergeTypeDefinitions(definitions, type.definitions).find(
            (definition) => definition.id === type.definitionId,
          )?.name ?? type.definitionId
        );
      case "anyOf":
        return `any of ${this.describeNestedTypes(type)}`;
      case "allOf":
        return `all of ${this.describeNestedTypes(type)}`;
      case "oneOf":
        return `one of ${this.describeNestedTypes(type)}`;
      default:
        return type?.name;
    }
  }

  private static describeNestedTypes(type: CompoundType): string {
    const c = type?.types.length;
    return c === 0 ? "..." : `${c} ${c === 1 ? "type" : "types"}`;
  }

  public static isComplexType(
    type: DataType,
    typeDefinitions: TypeDefinition[],
  ): boolean {
    const result = this.resolveType(type, typeDefinitions);
    return (
      (result.type?.name === "array" &&
        this.isComplexType(result.type.itemType, result.definitions)) ||
      result.type?.name === "object" ||
      result.type?.name === "reference"
    );
  }
}
