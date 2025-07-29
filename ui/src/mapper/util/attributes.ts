import {
  Attribute,
  CompoundType,
  DataType,
  ObjectSchema,
  TypeDefinition,
} from "../model/model";
import { DataTypes } from "./types.ts";

export class Attributes {
  public static buildAttribute(
    id: string,
    name: string,
    type: DataType,
    defaultValue?: string,
    required?: boolean,
  ): Attribute {
    return { id, name, type, defaultValue, required };
  }

  public static wrapType(type: DataType): Attribute {
    return this.buildAttribute("", "", type);
  }

  public static walk<T>(
    attribute: Attribute,
    walkFn: (attr: Attribute, path: Attribute[]) => T,
    path: Attribute[] = [],
  ): T | undefined {
    const p = [...path, attribute];
    const res = walkFn(attribute, p);
    if (res) {
      return res;
    }
    const typeDefinitions = this.extractTypeDefinitions(path);
    if (this.attributeSchemaPresentsInPath(attribute, path, typeDefinitions)) {
      return undefined;
    }
    for (const attr of this.getChildAttributes(attribute, typeDefinitions)) {
      const r = this.walk(attr, walkFn, p);
      if (r) {
        return r;
      }
    }
    return undefined;
  }

  public static attributeSchemaPresentsInPath(
    attribute: Attribute,
    path: Attribute[],
    typeDefinitions: TypeDefinition[],
  ): boolean {
    const schema = this.resolveAttributeSchema(attribute, typeDefinitions);
    if (!schema) {
      return false;
    }
    return path.some(
      (a) => this.resolveAttributeSchema(a, typeDefinitions)?.id === schema.id,
    );
  }

  public static extractTypeDefinitions(
    attributes: Attribute[],
  ): TypeDefinition[] {
    return (attributes ?? [])
      .map((attribute) => attribute?.type)
      .filter((type) => !!type)
      .map((type) => DataTypes.getTypeDefinitions(type))
      .reduce((defs1, defs2) => [...defs1, ...defs2], []);
  }

  public static resolveAttributeType(
    attribute: Attribute,
    typeDefinitions: TypeDefinition[],
  ): DataType | undefined {
    const result = DataTypes.resolveType(attribute?.type, typeDefinitions);
    return result.type && DataTypes.isArrayType(result.type)
      ? DataTypes.resolveArrayItemType(result.type, result.definitions).type
      : result.type;
  }

  public static resolveAttributeSchema(
    attribute: Attribute,
    typeDefinitions: TypeDefinition[],
  ): ObjectSchema | null {
    const type = this.resolveAttributeType(attribute, typeDefinitions);
    return type?.name === "object" ? type.schema : null;
  }

  public static getChildAttributes(
    attribute: Attribute,
    typeDefinitions: TypeDefinition[],
  ): Attribute[] {
    const type = this.resolveAttributeType(attribute, typeDefinitions);
    return type?.name === "object"
      ? type.schema.attributes
      : type && DataTypes.isCompoundType(type)
        ? (type as CompoundType).types
            .map((t) =>
              this.getChildAttributes(Attributes.wrapType(t), [
                ...((type as CompoundType).definitions ?? []),
                ...typeDefinitions,
              ]),
            )
            .reduce((l1, l2) => [...l1, ...l2], [])
        : [];
  }

  public static restorePath(
    attribute: Attribute,
    path: string[],
  ): Attribute[] | null {
    const p: Attribute[] = [attribute];
    for (const id of path) {
      const typeDefinitions = this.extractTypeDefinitions(p);
      const attributes = this.getChildAttributes(attribute, typeDefinitions);
      const attr = attributes.find((a) => a.id === id);
      if (!attr) {
        return null;
      }
      attribute = attr;
      p.push(attribute);
    }
    return p.slice(1);
  }

  public static pathExists(attribute: Attribute, path: string[]): boolean {
    return this.restorePath(attribute, path) !== null;
  }
}
