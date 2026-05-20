import {
  ArrayType,
  Attribute,
  AttributeKind,
  AttributeReference,
  CompoundType,
  DataType,
  MessageSchema,
  Metadata,
  ObjectType,
  TypeDefinition,
} from "../model/model.ts";
import { Attributes } from "./attributes.ts";
import { DataTypes } from "./types.ts";
import { MappingUtil } from "./mapping.ts";
import {
  METADATA_DATA_FORMAT_KEY,
  METADATA_SOURCE_XML_NAMESPACES_KEY,
  XmlNamespace,
} from "../model/metadata.ts";

export interface RenamingDetail {
  from: string;
  to: string;
}

export interface AttributeDetail {
  kind: AttributeKind;
  path: Attribute[];
  definitions: TypeDefinition[];
}

export function isAttributeDetail(obj: unknown): obj is AttributeDetail {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "kind" in obj &&
    "path" in obj &&
    Array.isArray(obj.path) &&
    "definitions" in obj &&
    Array.isArray(obj.definitions)
  );
}

export class MessageSchemaUtil {
  public static findAttribute(
    schema: MessageSchema,
    predicate: (
      attribute: Attribute,
      kind: AttributeKind,
      path: Attribute[],
    ) => boolean,
  ): AttributeDetail | null {
    for (const kind of ["body", "header", "property"] as AttributeKind[]) {
      const definitions =
        kind === "body" && schema.body
          ? DataTypes.getTypeDefinitions(schema.body)
          : [];
      for (const attribute of this.getMessageSchemaAttributes(schema, kind)) {
        const result = Attributes.walk(attribute, (a, p) => {
          const pth = kind === "body" ? p.slice(1) : p;
          return predicate(a, kind, pth)
            ? { kind, path: pth, definitions }
            : null;
        });
        if (result) {
          return result;
        }
      }
    }
    return null;
  }

  public static findAttributeByPath(
    schema: MessageSchema,
    kind: AttributeKind,
    path: string[],
  ): AttributeDetail | null {
    return this.findAttribute(
      schema,
      (_attribute, knd, pth) =>
        knd === kind &&
        pth.length === path.length &&
        pth.every((a, i) => a.name === path[i]),
    );
  }

  public static getMessageSchemaAttributes(
    schema: MessageSchema,
    kind: AttributeKind,
  ): Attribute[] {
    const key = this.getMessageSchemaKey(kind);
    const value = key ? schema[key] : undefined;
    return key === "body"
      ? value
        ? [Attributes.wrapType(value as DataType)]
        : []
      : (value as Attribute[]);
  }

  public static getMessageSchemaKey(
    kind: AttributeKind,
  ): keyof MessageSchema | null {
    switch (kind) {
      case "property":
        return "properties";
      case "header":
        return "headers";
      case "body":
        return "body";
      default:
        return null;
    }
  }

  public static restorePath(
    schema: MessageSchema,
    reference: AttributeReference,
  ): Attribute[] | null {
    const scope = this.getScope(schema, reference.kind);
    return Attributes.restorePath(Attributes.wrapType(scope), reference.path);
  }

  public static attributeExists(
    schema: MessageSchema,
    reference: AttributeReference,
  ): boolean {
    const scope = this.getScope(schema, reference.kind);
    return Attributes.pathExists(Attributes.wrapType(scope), reference.path);
  }

  public static getScope(schema: MessageSchema, kind: AttributeKind): DataType {
    const key = this.getMessageSchemaKey(kind);
    if (!key) {
      return DataTypes.nullType();
    }
    const value = schema[key];
    return key === "body"
      ? (value as DataType)
      : DataTypes.objectType({ id: "", attributes: value as Attribute[] });
  }

  public static removeAttribute(
    messageSchema: MessageSchema,
    kind: AttributeKind,
    path: Attribute[],
  ): MessageSchema {
    const scope = this.getScope(messageSchema, kind);
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    if (kind === "body" && messageSchema.body) {
      typeDefinitions.push(...DataTypes.getTypeDefinitions(messageSchema.body));
    }
    const id = path?.slice(-1).pop()?.id;
    const result = id
      ? DataTypes.modifyAttributes(
          scope,
          path.slice(0, -1).map((a) => a.id),
          typeDefinitions,
          (attributes) => attributes.filter((a) => a.id !== id),
        )
      : scope;
    const key = MessageSchemaUtil.getMessageSchemaKey(kind);
    if (!key) {
      return { ...messageSchema };
    }
    const value =
      kind === "body" ? result : (result as ObjectType).schema.attributes;
    return { ...messageSchema, [key]: value };
  }

  public static updateAttribute(
    messageSchema: MessageSchema,
    kind: AttributeKind,
    path: Attribute[],
    attribute: Attribute,
  ): MessageSchema {
    const scope =
      this.getScope(messageSchema, kind) ??
      DataTypes.objectType(MappingUtil.emptyObjectSchema(), [], {
        [METADATA_DATA_FORMAT_KEY]: "JSON",
      });
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    if (kind === "body" && messageSchema.body) {
      typeDefinitions.push(...DataTypes.getTypeDefinitions(messageSchema.body));
    }
    const result = DataTypes.modifyAttributes(
      scope,
      path.map((a) => a.id),
      typeDefinitions,
      (attributes) => {
        const attributeWithSameNameExists = attributes.find(
          (a) =>
            a.id !== attribute.id &&
            a.name !== undefined &&
            a.name === attribute.name,
        );
        if (attributeWithSameNameExists) {
          const entityKind =
            kind === "body" || path?.length ? "attribute" : kind;
          throw Error(
            `${entityKind.charAt(0).toUpperCase() + entityKind.slice(1)} "${attribute.name}" already exists`,
          );
        }
        const attributeExists = attributes.find((a) => a.id === attribute.id);
        return attributeExists
          ? attributes.map((a) => (a.id === attribute.id ? attribute : a))
          : [...attributes, attribute];
      },
    );
    const key = this.getMessageSchemaKey(kind);
    if (!key) {
      return { ...messageSchema };
    }
    const value =
      kind === "body" ? result : (result as ObjectType).schema.attributes;
    return { ...messageSchema, [key]: value };
  }

  public static clearAttributes(
    messageSchema: MessageSchema,
    kind: AttributeKind,
    path: Attribute[],
  ): MessageSchema {
    const scope = this.getScope(messageSchema, kind);
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    if (kind === "body" && messageSchema.body) {
      typeDefinitions.push(...DataTypes.getTypeDefinitions(messageSchema.body));
    }
    const result = DataTypes.modifyAttributes(
      scope,
      path.map((a) => a.id),
      typeDefinitions,
      () => [],
    );
    const key = this.getMessageSchemaKey(kind);
    if (!key) {
      return { ...messageSchema };
    }
    const value =
      key === "body"
        ? path.length
          ? result
          : null
        : (result as ObjectType).schema.attributes;
    return { ...messageSchema, [key]: value };
  }

  public static replaceAttributeType(
    messageSchema: MessageSchema,
    kind: AttributeKind,
    path: Attribute[],
    type: DataType,
    removeDefaultValue = false,
  ): MessageSchema {
    const scope = this.getScope(messageSchema, kind);
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    if (kind === "body" && messageSchema.body) {
      typeDefinitions.push(...DataTypes.getTypeDefinitions(messageSchema.body));
    }
    const id = path?.slice(-1).pop()?.id;
    const result = id
      ? DataTypes.modifyAttributes(
          scope,
          path.slice(0, -1).map((a) => a.id),
          typeDefinitions,
          (attributes) =>
            attributes.map((a) =>
              a.id === id
                ? {
                    ...a,
                    type,
                    defaultValue: removeDefaultValue
                      ? undefined
                      : a.defaultValue,
                  }
                : a,
            ),
        )
      : type;
    const key = this.getMessageSchemaKey(kind);
    if (!key) {
      return { ...messageSchema };
    }
    const value =
      key === "body"
        ? path.length
          ? result
          : type
        : (result as ObjectType).schema.attributes;
    return { ...messageSchema, [key]: value };
  }

  public static updateDataFormat(
    messageSchema: MessageSchema,
    path: Attribute[],
    format: string,
  ): MessageSchema {
    const metadataChanges: Metadata = {
      [METADATA_DATA_FORMAT_KEY]: format,
    };

    if (path.length === 0) {
      let body = messageSchema.body;
      if (body) {
        body = { ...body, metadata: { ...body.metadata, ...metadataChanges } };
      }
      return { ...messageSchema, body };
    } else {
      const a = path.slice(-1).pop()!;
      const attribute = {
        ...a,
        type: {
          ...a.type,
          metadata: { ...a.type.metadata, ...metadataChanges },
        },
      };
      return this.updateAttribute(
        messageSchema,
        "body",
        path.slice(0, -1),
        attribute,
      );
    }
  }

  public static updateXmlNamespaces(
    messageSchema: MessageSchema,
    path: Attribute[],
    xmlNamespaces: XmlNamespace[],
  ): MessageSchema {
    if (path.length === 0) {
      const body = messageSchema.body;
      if (body?.name === "object" && body.schema?.attributes.length > 0) {
        path = [body.schema.attributes[0]];
      } else {
        return messageSchema;
      }
    }

    const metadataChanges: Metadata = {
      [METADATA_SOURCE_XML_NAMESPACES_KEY]: xmlNamespaces,
    };

    const a = path.slice(-1).pop()!;
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    const type = this.upsertMetadataToDataType(
      a?.type,
      typeDefinitions,
      metadataChanges,
    );
    const attribute = { ...a, type };
    messageSchema = this.updateAttribute(
      messageSchema,
      "body",
      path.slice(0, -1),
      attribute,
    );
    return this.updateNamespaces(messageSchema, path, xmlNamespaces);
  }

  private static upsertMetadataToDataType(
    type: DataType,
    definitions: TypeDefinition[],
    metadataChanges: Metadata,
  ): DataType {
    const resolveResult = DataTypes.resolveType(type, definitions);
    if (resolveResult.type?.name === "array") {
      const itemType = this.upsertMetadataToDataType(
        resolveResult.type.itemType,
        resolveResult.definitions,
        metadataChanges,
      );
      return DataTypes.arrayType(
        itemType,
        resolveResult.definitions,
        resolveResult.type.metadata,
      );
    } else if (
      resolveResult.type &&
      DataTypes.typeIsDefinitionsAware(resolveResult.type)
    ) {
      return {
        ...resolveResult.type,
        metadata: { ...resolveResult.type.metadata, ...metadataChanges },
        definitions: [
          ...(resolveResult.definitions ?? []),
          ...(resolveResult.type.definitions ?? []),
        ],
      } as DataType;
    } else if (resolveResult.type) {
      return {
        ...resolveResult.type,
        metadata: { ...resolveResult.type.metadata, ...metadataChanges },
      };
    } else {
      return type;
    }
  }

  private static updateNamespaces(
    messageSchema: MessageSchema,
    path: Attribute[],
    xmlNamespaces: XmlNamespace[],
  ): MessageSchema {
    const type = path.slice(-1)?.pop()?.type ?? messageSchema.body;
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    let resolveResult = type
      ? DataTypes.resolveType(type, typeDefinitions)
      : { type: undefined, definitions: [] };
    while (resolveResult.type?.name === "array") {
      resolveResult = DataTypes.resolveType(
        resolveResult.type.itemType,
        resolveResult.definitions,
      );
    }
    const metadata = resolveResult.type?.metadata;
    const namespaces =
      (metadata?.[METADATA_SOURCE_XML_NAMESPACES_KEY] as XmlNamespace[]) ?? [];
    const renamingDetails = this.getXmlNamespacesRenamingDetails(
      namespaces,
      xmlNamespaces,
    );
    return this.renameXmlEntities(messageSchema, "body", path, renamingDetails);
  }

  private static getXmlNamespacesRenamingDetails(
    before: XmlNamespace[],
    after: XmlNamespace[],
  ): RenamingDetail[] {
    return after
      .map((ns) => {
        const from = before.find(
          (n) => n.uri === ns.uri && n.alias !== ns.alias,
        )?.alias;
        return from ? { from, to: ns.alias } : undefined;
      })
      .filter((i) => i !== undefined);
  }

  private static renameXmlEntities(
    messageSchema: MessageSchema,
    kind: AttributeKind,
    path: Attribute[],
    renamingDetails: RenamingDetail[],
  ): MessageSchema {
    const scope = MessageSchemaUtil.getScope(messageSchema, kind);
    const typeDefinitions = Attributes.extractTypeDefinitions(path);
    if (kind === "body" && messageSchema.body) {
      typeDefinitions.push(...DataTypes.getTypeDefinitions(messageSchema.body));
    }
    const result = DataTypes.modifyAttributes(
      scope,
      path.slice(0, -1).map((a) => a.id),
      typeDefinitions,
      (attributes) =>
        attributes.map((a) =>
          this.renameXmlEntity(a, renamingDetails, typeDefinitions),
        ),
    );
    const key = MessageSchemaUtil.getMessageSchemaKey(kind);
    if (!key) {
      return { ...messageSchema };
    }
    const value =
      key === "body" ? result : (result as ObjectType).schema.attributes;
    return { ...messageSchema, [key]: value };
  }

  private static renameXmlEntity(
    attribute: Attribute,
    renamingDetails: RenamingDetail[],
    typeDefinitions: TypeDefinition[],
  ): Attribute {
    for (const renamingDetail of renamingDetails) {
      const prefix =
        (attribute.name.startsWith("@") ? "@" : "") +
        (renamingDetail.from ? renamingDetail.from + ":" : "");
      const replacement =
        (attribute.name.startsWith("@") ? "@" : "") +
        (renamingDetail.to ? renamingDetail.to + ":" : "");
      if (
        attribute.name.startsWith(prefix) &&
        (renamingDetail.from || !attribute.name.includes(":"))
      ) {
        const name = replacement + attribute.name.slice(prefix.length);
        const type = this.renameXmlEntitiesInDataType(
          attribute.type,
          renamingDetails,
          typeDefinitions,
        );
        return { ...attribute, name, type };
      }
    }
    return {
      ...attribute,
      type: this.renameXmlEntitiesInDataType(
        attribute.type,
        renamingDetails,
        typeDefinitions,
      ),
    };
  }

  private static renameXmlEntitiesInDataType(
    type: DataType,
    renamingDetails: RenamingDetail[],
    typeDefinitions: TypeDefinition[],
  ): DataType {
    const result = DataTypes.resolveType(type, typeDefinitions);
    const resolvedType = result.type;
    const definitions = result.definitions;
    switch (resolvedType?.name) {
      case "object": {
        const attributes = resolvedType.schema.attributes.map((a) =>
          this.renameXmlEntity(
            a,
            renamingDetails,
            DataTypes.mergeTypeDefinitions(
              definitions,
              resolvedType.definitions,
            ),
          ),
        );
        return {
          ...resolvedType,
          schema: {
            ...resolvedType.schema,
            id: MappingUtil.generateUUID(),
            attributes,
          },
        };
      }
      case "array":
        return DataTypes.arrayType(
          this.renameXmlEntitiesInDataType(
            resolvedType.itemType,
            renamingDetails,
            DataTypes.mergeTypeDefinitions(
              definitions,
              resolvedType.definitions,
            ),
          ),
        );
      case "anyOf":
      case "allOf":
      case "oneOf":
        return {
          ...resolvedType,
          types: resolvedType.types.map((t) =>
            this.renameXmlEntitiesInDataType(
              t,
              renamingDetails,
              DataTypes.mergeTypeDefinitions(
                definitions,
                resolvedType.definitions,
              ),
            ),
          ),
        };
      default:
        return type;
    }
  }

  public static replaceIdsInSuggestedDataType(dataType: DataType): DataType {
    if ((dataType as CompoundType).types) {
      (dataType as CompoundType).types.forEach((type) =>
        this.replaceIdsInSuggestedDataType(type),
      );
    }
    if ((dataType as ArrayType).itemType) {
      this.replaceIdsInSuggestedDataType((dataType as ArrayType).itemType);
    }
    if ((dataType as ObjectType).schema) {
      const obj = dataType as ObjectType;
      obj.schema.id = MappingUtil.generateUUID();
      if (obj.schema.attributes) {
        obj.schema.attributes.forEach((attr) => {
          attr.id = MappingUtil.generateUUID();
          this.replaceIdsInSuggestedDataType(attr.type);
        });
      }
    }

    return dataType;
  }
}
