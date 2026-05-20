import {
  Constant,
  MappingDescription,
  Transformation,
  DataType,
  MappingAction,
  CompoundType,
  TypeDefinition,
} from "../model/model.ts";
import { AttributeDetail, isAttributeDetail } from "../util/schema.ts";
import { map, VerificationError, Verifier } from "./model.ts";
import { Attributes } from "../util/attributes.ts";
import { DataTypes } from "../util/types.ts";
import { MappingActions } from "../util/actions.ts";

export interface MappingActionDetail {
  action: MappingAction;
  mapping: MappingDescription;
}

export interface TransformationParameterDetail extends MappingActionDetail {
  parameterValue: string;
  parameterIndex: number;
}

export interface DataTypeDetail {
  name: string;
  type: DataType;
  definitions: TypeDefinition[];
}

export function getSources(
  entity: MappingActionDetail,
): (AttributeDetail | Constant | undefined)[] {
  return MappingActions.getSourcesDetail(entity.action, entity.mapping);
}

function getTarget(entity: MappingActionDetail): AttributeDetail {
  return MappingActions.getTargetDetail(entity.action, entity.mapping);
}

export function sources(
  verifier: Verifier<(AttributeDetail | Constant)[]>,
): Verifier<MappingActionDetail> {
  return map((entity) => getSources(entity), verifier);
}

export function target(
  verifier: Verifier<AttributeDetail>,
): Verifier<MappingActionDetail> {
  return map((entity) => getTarget(entity), verifier);
}

export function transformation(
  verifier: Verifier<Transformation>,
): Verifier<MappingActionDetail> {
  return map((entity) => entity.action.transformation, verifier);
}

export function parameters(
  verifier: Verifier<string[]>,
): Verifier<Transformation> {
  return map((entity) => entity.parameters, verifier);
}

export function transformationParameters(
  verifier: Verifier<TransformationParameterDetail[]>,
): Verifier<MappingActionDetail> {
  return map(
    (entity) =>
      entity?.action?.transformation?.parameters?.map(
        (parameterValue, parameterIndex) => ({
          ...entity,
          parameterValue,
          parameterIndex,
        }),
      ),
    verifier,
  );
}

export function dataType(
  verifier: Verifier<DataTypeDetail>,
): Verifier<AttributeDetail | Constant> {
  return map((entity) => {
    if (isAttributeDetail(entity)) {
      const typeDefinitions = DataTypes.mergeTypeDefinitions(
        entity.definitions,
        Attributes.extractTypeDefinitions(entity.path),
      );
      const entityType = entity.path.slice(-1).pop()?.type;
      const { type, definitions } = entityType
        ? DataTypes.resolveType(entityType, typeDefinitions)
        : { type: undefined, definitions: [] };
      return { name: type?.name, type, definitions };
    } else {
      const { type, definitions } = DataTypes.resolveType(entity.type, []);
      return { name: type?.name, type, definitions };
    }
  }, verifier);
}

export function finalType(
  verifier: Verifier<DataTypeDetail>,
): Verifier<DataTypeDetail> {
  return map((entity) => {
    const result = DataTypes.resolveArrayItemType(
      entity.type,
      entity.definitions,
    );
    return {
      name: result.type?.name,
      type: result.type,
      definitions: result.definitions,
    };
  }, verifier);
}

class IsPrimitiveType extends Verifier<DataTypeDetail> {
  constructor() {
    super();
  }

  verify(entity: DataTypeDetail): VerificationError[] {
    return this.isPrimitiveType(entity.type, entity.definitions)
      ? []
      : [
          {
            message: `Not a primitive type: ${DataTypes.buildTypeName(entity.type, entity.definitions)}`,
          },
        ];
  }

  private isPrimitiveType(
    type: DataType,
    definitions: TypeDefinition[],
  ): boolean {
    return (
      DataTypes.isPrimitiveType(type) ||
      (DataTypes.isCompoundType(type) &&
        this.isPrimitiveCompoundType(type as CompoundType, definitions))
    );
  }

  private isPrimitiveCompoundType(
    type: CompoundType,
    definitions: TypeDefinition[],
  ): boolean {
    return type.types.every((t) => {
      const typeDefinitions = DataTypes.mergeTypeDefinitions(
        type.definitions,
        definitions,
      );
      const result = DataTypes.resolveType(t, typeDefinitions);
      return (
        result.type && this.isPrimitiveType(result.type, result.definitions)
      );
    });
  }
}

export function primitiveType(): Verifier<DataTypeDetail> {
  return new IsPrimitiveType();
}

class IsCompoundType extends Verifier<DataTypeDetail> {
  constructor() {
    super();
  }

  verify(entity: DataTypeDetail): VerificationError[] {
    return DataTypes.isCompoundType(entity.type)
      ? []
      : [
          {
            message: `Not a compound type: ${DataTypes.buildTypeName(entity.type, entity.definitions)}`,
          },
        ];
  }
}

export function compoundType(): Verifier<DataTypeDetail> {
  return new IsCompoundType();
}

export function itemType(
  verifier: Verifier<DataTypeDetail>,
): Verifier<DataTypeDetail> {
  return map((entity) => {
    const result = DataTypes.resolveType(entity.type, entity.definitions);
    const type = result.type?.name === "array" ? result.type.itemType : null;
    const definitions =
      result.type?.name === "array"
        ? DataTypes.mergeTypeDefinitions(
            result.type.definitions,
            result.definitions,
          )
        : result.definitions;
    return { name: type?.name, type, definitions };
  }, verifier);
}

export function hasName<T extends { name: string }>(
  verifier: Verifier<string>,
): Verifier<T> {
  return map((entity) => entity?.name, verifier);
}
