import {
  DataType,
  Metadata,
  MetadataAware,
  TypeDefinition,
} from "../model/model.ts";
// @ts-expect-error There is no @types/xsdlibrary package exists
import { detectXmlSchema, validateXml, xsd2jsonSchema } from "xsdlibrary";
import toJsonSchema from "to-json-schema";
import { XMLParser } from "fast-xml-parser";
import {
  JSONSchema4,
  JSONSchema4TypeName,
  JSONSchema6,
  JSONSchema6Definition,
  JSONSchema7,
  JSONSchema7TypeName,
} from "json-schema";
import { DataTypes } from "./types.ts";
import { MappingUtil } from "./mapping.ts";
import * as graphql from "graphql";
import * as gql2js from "graphql-2-json-schema";
import { GraphQLUtil } from "./graphql.ts";
import {
  METADATA_DATA_FORMAT_KEY,
  METADATA_SOURCE_FORMAT_KEY,
  METADATA_SOURCE_TYPE_KEY,
  METADATA_SOURCE_XML_NAMESPACES_KEY,
} from "../model/metadata.ts";

export enum SourceFormat {
  XML = "XML",
  JSON = "JSON",
  GRAPHQL = "GraphQL",
  UNSPECIFIED = "unspecified",
}

export enum SourceType {
  SCHEMA = "schema",
  SAMPLE = "sample",
  UNSPECIFIED = "unspecified",
}

export interface XmlNamespace {
  alias: string;
  uri: string;
}

type JsonSchema = (JSONSchema4 | JSONSchema6 | JSONSchema7) & MetadataAware;
type KeysOfUnion<T> = T extends T ? keyof T : never;
type JsonSchemaKey = KeysOfUnion<JsonSchema>;

export class AttributeImporter {
  public static importDataType(
    text: string,
    format: SourceFormat,
    type: SourceType,
    options: Record<string, unknown> = {},
  ): DataType {
    if (format === SourceFormat.UNSPECIFIED) {
      format = this.detectFormat(text);
      if (format === SourceFormat.UNSPECIFIED) {
        throw new Error("Unsupported document format");
      }
    }

    if (type === SourceType.UNSPECIFIED) {
      type = this.detectType(text, format);
      if (type === SourceType.UNSPECIFIED) {
        throw new Error("Unsupported document type");
      }
    }

    const schema = this.convertToJsonSchema(text, format, type, options);
    const definitionMap = new Map<string, TypeDefinition>();
    let dataType = this.buildDataTypeFromJsonSchema(
      schema,
      schema,
      definitionMap,
    );
    dataType = DataTypes.updateDefinitions(
      dataType,
      Array.from(definitionMap.values()),
    );
    const metadata = this.buildAttributeMetadata(format, type);
    dataType = DataTypes.updateMetadata(dataType, metadata);
    return dataType;
  }

  public static detectFormat(text: string): SourceFormat {
    if (this.isJson(text)) {
      return SourceFormat.JSON;
    } else if (this.isXml(text)) {
      return SourceFormat.XML;
    } else if (this.isGraphQLSchema(text)) {
      return SourceFormat.GRAPHQL;
    } else {
      return SourceFormat.UNSPECIFIED;
    }
  }

  public static detectType(text: string, format: SourceFormat): SourceType {
    const detectFn = this.getTypeDetectionFn(format);
    return detectFn(text);
  }

  public static convertToJsonSchema(
    text: string,
    format: SourceFormat,
    type: SourceType,
    options: Record<string, unknown>,
  ): JsonSchema {
    const converterFn = this.getToSchemaConverterFn(format, type);
    return converterFn(text, options);
  }

  public static buildDataTypeFromJsonSchema(
    schema: JsonSchema,
    root: JsonSchema,
    definitionMap: Map<string, TypeDefinition>,
  ): DataType {
    if (schema.$ref) {
      if (!schema.$ref.startsWith("#/")) {
        throw new Error(`Not supported schema reference kind: ${schema.$ref}`);
      }
      if (definitionMap.has(schema.$ref)) {
        const definitionId = definitionMap.get(schema.$ref)!.id;
        return DataTypes.referenceType(definitionId, [], schema.metadata);
      } else {
        const definitionId = MappingUtil.generateUUID();
        const name = this.getDefinitionName(schema.$ref);
        definitionMap.set(schema.$ref, {
          id: definitionId,
          name,
          type: DataTypes.nullType(),
        });
        const definitionSchema = this.resolveSchema(
          schema.$ref,
          root ?? schema,
        );
        if (!definitionSchema) {
          throw Error(`Schema not found: ${schema.$ref}`);
        }
        const definitionType = this.buildDataTypeFromJsonSchema(
          definitionSchema,
          root,
          definitionMap,
        );
        definitionMap.set(schema.$ref, {
          id: definitionId,
          name,
          type: definitionType,
        });
        return DataTypes.referenceType(definitionId, [], schema.metadata);
      }
    }
    if (schema.enum && !schema.type) {
      throw new Error("Enumerable types not supported");
    }
    if ((schema as JSONSchema7).if) {
      throw new Error("Conditional schemas not supported");
    }
    if (schema.not) {
      throw new Error('"Not" schemas not supported');
    }
    if (Array.isArray(schema.type)) {
      const schemas = (schema.type as JSONSchema7TypeName[]).map((t) => ({
        ...schema,
        type: t,
      }));
      return this.buildCompoundType(
        (types) => DataTypes.oneOfType(types),
        schemas,
        root,
        definitionMap,
      );
    }
    if (schema.allOf) {
      return this.buildCompoundType(
        (types) => DataTypes.allOfType(types),
        schema.allOf as JsonSchema[],
        root,
        definitionMap,
      );
    }
    if (schema.anyOf) {
      return this.buildCompoundType(
        (types) => DataTypes.anyOfType(types),
        schema.anyOf as JsonSchema[],
        root,
        definitionMap,
      );
    }
    if (schema.oneOf) {
      return this.buildCompoundType(
        (types) => DataTypes.oneOfType(types),
        schema.oneOf as JsonSchema[],
        root,
        definitionMap,
      );
    }
    switch (schema.type) {
      case "null":
        return DataTypes.nullType(schema.metadata);
      case "string":
        return DataTypes.stringType(schema.metadata);
      case "integer":
      case "number":
        return DataTypes.integerType(schema.metadata);
      case "boolean":
        return DataTypes.booleanType(schema.metadata);
      case "array":
        return this.buildArrayDataType(schema, root, definitionMap);
      case "object": {
        return DataTypes.objectType(
          {
            id: MappingUtil.generateUUID(),
            attributes: (
              Object.entries(schema.properties ?? {}) as [
                string,
                JSONSchema4 | JSONSchema6 | JSONSchema7 | boolean,
              ][]
            )
              .map(([name, propertySchema]) =>
                typeof propertySchema === "boolean"
                  ? undefined
                  : ([name, propertySchema] as [string, JsonSchema]),
              )
              .filter((i) => !!i)
              .map(([name, propertySchema]) => ({
                id: MappingUtil.generateUUID(),
                name,
                type: this.buildDataTypeFromJsonSchema(
                  propertySchema,
                  root,
                  definitionMap,
                ),
                ...(propertySchema.description ||
                propertySchema.title ||
                propertySchema.examples ||
                "example" in propertySchema
                  ? {
                      metadata: {
                        title: propertySchema.title,
                        description: propertySchema.description,
                        examples: ("example" in propertySchema
                          ? [propertySchema.example]
                          : propertySchema.examples) as unknown[] | undefined,
                      },
                    }
                  : {}),
                defaultValue:
                  typeof propertySchema.default === "object" ||
                  Array.isArray(propertySchema.default)
                    ? JSON.stringify(propertySchema.default)
                    : propertySchema.default?.toString(),
                required:
                  propertySchema.required === true ||
                  (Array.isArray(schema.required) &&
                    schema.required.includes(name)),
              }))
              .slice()
              .sort((a1, a2) => a1.name.localeCompare(a2.name)),
          },
          [],
          schema.metadata,
        );
      }
      case "any":
        throw new Error('"Any" type not supported');
      default:
        return this.buildDataTypeFromJsonSchema(
          { ...schema, type: this.mapSchemaType(schema.type) },
          root,
          definitionMap,
        );
    }
  }

  public static buildCompoundType(
    builder: (types: DataType[]) => DataType,
    schemas: JsonSchema[],
    root: JsonSchema,
    definitionMap: Map<string, TypeDefinition>,
  ): DataType {
    const typeDefinitions = Array.from(definitionMap.values());
    const types = schemas
      .map((schema) =>
        this.buildDataTypeFromJsonSchema(schema, root, definitionMap),
      )
      .filter(
        (value, index, self) =>
          self.findIndex((v) => DataTypes.same(v, value, typeDefinitions)) ===
          index,
      );
    return types.length === 1 ? types[0] : builder(types);
  }

  public static buildArrayDataType(
    schema: JsonSchema,
    root: JsonSchema,
    definitionMap: Map<string, TypeDefinition>,
  ): DataType {
    if ("prefixItems" in schema) {
      if (schema.items || schema.additionalItems) {
        throw new Error("Array type with prefix not supported");
      }
      return this.buildTupleDataType(
        schema.prefixItems as JsonSchema[],
        root,
        definitionMap,
      );
    } else if (Array.isArray(schema.items)) {
      if (schema.additionalItems) {
        throw new Error("Array type with prefix not supported");
      }
      return this.buildTupleDataType(
        schema.items as JsonSchema[],
        root,
        definitionMap,
      );
    } else if (typeof schema.items === "boolean") {
      // Boolean items field without prefixItems or has no sense.
      throw new Error('Boolean "items" field without "prefixItems"');
    } else {
      return DataTypes.arrayType(
        this.buildDataTypeFromJsonSchema(
          schema.items ?? { type: "object" },
          root,
          definitionMap,
        ),
      );
    }
  }

  public static buildTupleDataType(
    schemas: JsonSchema[],
    root: JsonSchema,
    definitionMap: Map<string, TypeDefinition>,
  ): DataType {
    return DataTypes.objectType({
      id: MappingUtil.generateUUID(),
      attributes: schemas
        .filter((i) => !!i)
        .map((value, index) => ({
          id: MappingUtil.generateUUID(),
          name: `[item${index}]`,
          type: this.buildDataTypeFromJsonSchema(value, root, definitionMap),
        })),
    });
  }

  public static isJson(text: string): boolean {
    try {
      JSON.parse(text);
      return true;
    } catch {
      return false;
    }
  }

  public static isXml(text: string): boolean {
    try {
      // eslint-disable-next-line @typescript-eslint/no-unsafe-call
      validateXml(text);
      return true;
    } catch {
      return false;
    }
  }

  public static isGraphQLSchema(text: string): boolean {
    try {
      graphql.parse(text, { noLocation: true });
      return true;
    } catch {
      return false;
    }
  }

  public static getTypeDetectionFn(
    format: SourceFormat,
  ): (text: string) => SourceType {
    switch (format) {
      case SourceFormat.JSON:
        return (text) => this.getJsonDocumentType(text);
      case SourceFormat.XML:
        return (text) => this.getXmlDocumentType(text);
      case SourceFormat.GRAPHQL:
        return () => SourceType.SCHEMA;
      default:
        throw new Error(`Unsupported format: ${format}`);
    }
  }

  public static getJsonDocumentType(text: string): SourceType {
    const obj = JSON.parse(text) as unknown;
    const containsSchema =
      obj !== undefined &&
      obj !== null &&
      typeof obj === "object" &&
      "$schema" in obj;
    return containsSchema ? SourceType.SCHEMA : SourceType.SAMPLE;
  }

  public static getXmlDocumentType(text: string): SourceType {
    // eslint-disable-next-line @typescript-eslint/no-unsafe-call
    const schemaName = detectXmlSchema(text) as string;
    switch (schemaName) {
      case "xml":
        return SourceType.SAMPLE;
      case "xsd":
        return SourceType.SCHEMA;
      default:
        throw new Error("Unsupported type");
    }
  }

  public static getToSchemaConverterFn(
    format: SourceFormat,
    type: SourceType,
  ): (text: string, options: Record<string, unknown>) => JsonSchema {
    switch (format) {
      case SourceFormat.JSON:
        switch (type) {
          case SourceType.SAMPLE:
            return (text) => toJsonSchema(JSON.parse(text)) as JsonSchema;
          case SourceType.SCHEMA:
            return (text) => JSON.parse(text) as JsonSchema;
          default:
            throw new Error(`Unsupported document type: ${type}`);
        }
      case SourceFormat.XML:
        switch (type) {
          case SourceType.SAMPLE:
            return (text) => this.buildJsonSchemaForXml(text);
          case SourceType.SCHEMA:
            return (text) => this.convertXmlSchemaToJsonSchema(text);
          default:
            throw new Error(`Unsupported document type: ${type}`);
        }
      case SourceFormat.GRAPHQL:
        switch (type) {
          case SourceType.SCHEMA:
            return (text, options) =>
              this.buildJsonSchemaForGraphQLSchema(text, options);
          default:
            throw new Error(`Unsupported document type: ${type}`);
        }
      default:
        throw new Error(`Unsupported document format: ${format}`);
    }
  }

  public static getDefinitionName(path: string): string {
    return path.split("/").pop() ?? "";
  }

  public static resolveSchema(path: string, root: JsonSchema): JsonSchema {
    return path
      .substring(2)
      .split("/")
      .reduce(
        // @ts-expect-error Didn't find a way to get value by key from schema without triggering a type checker
        (schema, key) => schema[key as JsonSchemaKey] as JsonSchema,
        root,
      );
  }

  public static buildAttributeMetadata(
    format: SourceFormat,
    type: SourceType,
  ): Metadata {
    return {
      [METADATA_SOURCE_FORMAT_KEY]: format,
      [METADATA_SOURCE_TYPE_KEY]: type,
      [METADATA_DATA_FORMAT_KEY]: this.getDataFormat(format),
    };
  }

  public static getDataFormat(format: SourceFormat) {
    return format === SourceFormat.XML ? "XML" : "JSON";
  }

  public static convertXmlToJson(text: string): unknown {
    const options = {
      allowBooleanAttributes: true,
      attributeNamePrefix: "@",
      ignorePiTags: true,
      ignoreAttributes: false,
      ignoreDeclaration: true,
    };
    const parser = new XMLParser(options);
    return parser.parse(text) as unknown;
  }

  public static buildJsonSchemaForXml(text: string): JsonSchema {
    const jsonObj = this.convertXmlToJson(text);

    const newDefSchema = (
      schema: toJsonSchema.JSONSchema3or4,
      type: JSONSchema4TypeName,
      value: unknown,
    ) => {
      schema.type = type;

      if (!value || typeof value !== "object") {
        return schema;
      }

      const keys = Object.keys(value);

      if (keys.indexOf("@array") !== -1) {
        schema.type = "array";
        schema.items = { type: "object", properties: schema.properties };
        delete schema.properties;
      }

      const nsKeys = keys.filter((key) => key.startsWith("@xmlns:"));
      const namespaces: XmlNamespace[] = nsKeys.map((key) => ({
        alias: key.substring(7),
        // @ts-expect-error Can't figure out how to get rid of error
        uri: String(value[key] ?? ""),
      }));
      nsKeys.forEach((key) => {
        if (schema.properties) {
          delete schema.properties[key];
        }
      });
      if (namespaces.length > 0) {
        schema.metadata = { [METADATA_SOURCE_XML_NAMESPACES_KEY]: namespaces };
      }
      return schema;
    };

    const options: toJsonSchema.Options = {
      arrays: { mode: "first" },
      postProcessFnc: (type, schema, value) =>
        newDefSchema(schema, type, value),
    };
    return toJsonSchema(jsonObj, options) as JsonSchema;
  }

  public static mapSchemaType(
    type: string | undefined | null,
  ): JSONSchema7TypeName {
    if (type === undefined || type === null) {
      return "null";
    }
    // http://books.xmlschemata.org/relaxng/relax-CHP-19.html
    const typeMap = new Map<string, JSONSchema7TypeName>([
      ["anyURI", "string"], // XSD URI (Uniform Resource Identifier)
      ["base64Binary", "string"], // XSD Binary content coded as "base64"
      ["boolean", "boolean"], // XSD Boolean (true or false)
      ["byte", "number"], // XSD Signed value of 8 bits
      ["date", "string"], // XSD Gregorian calendar date
      ["dateTime", "string"], // XSD Instant of time (Gregorian calendar)
      ["decimal", "number"], // XSD Decimal numbers
      ["double", "number"], // XSD IEEE 64-bit floating-point
      ["duration", "string"], // XSD Time durations
      ["ENTITIES", "string"], // XSD Whitespace-separated list of unparsed entity references
      ["ENTITY", "string"], // XSD Reference to an unparsed entity
      ["float", "number"], // XSD IEEE 32-bit floating-point
      ["gDay", "string"], // XSD Recurring period of time: monthly day
      ["gMonth", "string"], // XSD Recurring period of time: yearly month
      ["gMonthDay", "string"], // XSD Recurring period of time: yearly day
      ["gYear", "string"], // XSD Period of one year
      ["gYearMonth", "string"], // XSD Period of one month
      ["hexBinary", "string"], // XSD Binary contents coded in hexadecimal
      ["ID", "string"], // XSD Definition of unique identifiers
      ["IDREF", "string"], // XSD Definition of references to unique identifiers
      ["IDREFS", "string"], // XSD Definition of lists of references to unique identifiers
      ["int", "number"], // XSD 32-bit signed integers
      ["integer", "number"], // XSD Signed integers of arbitrary length
      ["language", "string"], // XSD RFC 1766 language codes
      ["long", "number"], // XSD 64-bit signed integers
      ["Name", "string"], // XSD XML 1.O name
      ["NCName", "string"], // XSD Unqualified names
      ["negativeInteger", "number"], // XSD Strictly negative integers of arbitrary length
      ["NMTOKEN", "string"], // XSD XML 1.0 name token (NMTOKEN)
      ["NMTOKENS", "string"], // XSD List of XML 1.0 name tokens (NMTOKEN)
      ["nonNegativeInteger", "number"], // XSD Integers of arbitrary length positive or equal to zero
      ["nonPositiveInteger", "number"], // XSD Integers of arbitrary length negative or equal to zero
      ["normalizedString", "string"], // XSD Whitespace-replaced strings
      ["NOTATION", "string"], // XSD Emulation of the XML 1.0 feature
      ["positiveInteger", "number"], // XSD Strictly positive integers of arbitrary length
      ["QName", "string"], // XSD Namespaces in XML-qualified names
      ["short", "number"], // XSD 32-bit signed integers
      ["string", "string"], // XSD Any string
      ["time", "string"], // XSD Point in time recurring each day
      ["token", "string"], // XSD Whitespace-replaced and collapsed strings
      ["unsignedByte", "number"], // XSD Unsigned value of 8 bits
      ["unsignedInt", "number"], // XSD Unsigned integer of 32 bits
      ["unsignedLong", "number"], // XSD Unsigned integer of 64 bits
      ["unsignedShort", "number"], // XSD Unsigned integer of 16 bits
    ]);
    return typeMap.get(type) ?? "string";
  }

  public static convertXmlSchemaToJsonSchema(text: string): JsonSchema {
    // eslint-disable-next-line @typescript-eslint/no-unsafe-argument,@typescript-eslint/no-unsafe-call
    const schema = JSON.parse(xsd2jsonSchema(text)) as JsonSchema;
    return this.postprocessSchema(schema);
  }

  public static postprocessSchema(schema: JsonSchema): JsonSchema {
    if (!schema) {
      return schema;
    }
    const schemaMembers = new Set<JsonSchemaKey>([
      "$id",
      "$ref",
      "$schema",
      "$comment",
      "$defs",
      "type",
      "enum",
      "const",
      "multipleOf",
      "maximum",
      "exclusiveMaximum",
      "minimum",
      "exclusiveMinimum",
      "maxLength",
      "minLength",
      "pattern",
      "items",
      "additionalItems",
      "maxItems",
      "minItems",
      "uniqueItems",
      "contains",
      "maxProperties",
      "minProperties",
      "required",
      "properties",
      "patternProperties",
      "additionalProperties",
      "dependencies",
      "propertyNames",
      "if",
      "then",
      "else",
      "allOf",
      "anyOf",
      "oneOf",
      "not",
      "format",
      "contentMediaType",
      "contentEncoding",
      "definitions",
      "title",
      "description",
      "default",
      "readOnly",
      "writeOnly",
      "examples",
    ]);

    const changes: Partial<JsonSchema> = {};

    for (const key of [
      "$defs",
      "properties",
      "patternProperties",
      "dependencies",
      "definitions",
    ] as JsonSchemaKey[]) {
      // @ts-expect-error Didn't find a way to get value by key from schema without triggering a type checker
      const value = schema[key] as Record<string, unknown>;
      if (!value) {
        continue;
      }
      const modifiedValue: Record<string, unknown> = {};
      for (const [name, sch] of Object.entries(value)) {
        modifiedValue[name] =
          typeof sch === "object"
            ? this.postprocessSchema(sch as JsonSchema)
            : (sch as JsonSchema);
      }
      // @ts-expect-error Didn't find a way to set value by key to schema without triggering a type checker
      changes[key] = modifiedValue;
    }

    for (const key of [
      "additionalItems",
      "additionalProperties",
      "propertyNames",
      "if",
      "then",
      "else",
      "not",
      "contains",
      "items",
    ] as JsonSchemaKey[]) {
      // @ts-expect-error Didn't find a way to get value by key from schema without triggering a type checker
      const value = schema[key] as unknown;
      if (!value || typeof value !== "object") {
        continue;
      }
      // @ts-expect-error Didn't find a way to set value by key to schema without triggering a type checker
      changes[key] = this.postprocessSchema(value as JsonSchema);
    }

    for (const key of [
      "items",
      "prefixItems",
      "allOf",
      "anyOf",
      "oneOf",
    ] as JsonSchemaKey[]) {
      // @ts-expect-error Didn't find a way to get value by key from schema without triggering a type checker
      const value = schema[key] as unknown;
      if (value && Array.isArray(value)) {
        // @ts-expect-error Didn't find a way to set value by key to schema without triggering a type checker
        changes[key] = value.map((i) =>
          typeof i === "object"
            ? this.postprocessSchema(i as JsonSchema)
            : (i as unknown),
        );
      }
    }

    const properties: JsonSchema["properties"] = {};
    for (const [key] of Object.entries(schema)) {
      if (!schemaMembers.has(key)) {
        properties[key.startsWith("@") ? key : `@${key}`] = { type: "string" };
      }
    }
    // @ts-expect-error Didn't find a way to set value to schema without triggering a type checker
    changes.properties = { ...changes.properties, ...properties };

    // Special case: attribute with name 'type'
    if (
      schema.type &&
      !Array.isArray(schema.type) &&
      [
        "string",
        "number",
        "integer",
        "boolean",
        "object",
        "array",
        "null",
      ].indexOf(schema.type) < 0
    ) {
      changes["type"] = schema.properties
        ? "object"
        : schema.items !== undefined
          ? "array"
          : "string";
      changes["properties"] = {
        ...(changes["properties"] ?? {}),
        "@type": { type: "string" },
      };
    }

    return { ...schema, ...changes } as JsonSchema;
  }

  public static collectXmlNamespaces(text: string): XmlNamespace[] {
    const options = {
      attributeNamePrefix: "",
      attributesGroupName: "@",
      ignoreAttributes: false,
    };
    const parser = new XMLParser(options);
    const obj = parser.parse(text) as unknown;
    const namespaceMap = new Map<string, string>();
    this.extractNamespacesRecursively(obj, namespaceMap);
    return [...namespaceMap].map(([alias, uri]) => ({ alias, uri }));
  }

  public static extractNamespacesRecursively(
    obj: unknown,
    namespaceMap: Map<string, string>,
  ) {
    if (obj === undefined || obj === null || typeof obj !== "object") {
      return;
    }
    for (const [key, value] of Object.entries(obj)) {
      if (key === "@") {
        for (const [k, v] of Object.entries(value as object)) {
          if (k.startsWith("xmlns:")) {
            namespaceMap.set(k.substring(6), v as string);
          }
        }
      } else {
        this.extractNamespacesRecursively(value, namespaceMap);
      }
    }
  }

  public static buildJsonSchemaForGraphQLSchema(
    text: string,
    options: Record<string, unknown>,
  ): JsonSchema {
    const schema = graphql.buildSchema(text);
    const introspectionData = graphql.graphqlSync({
      schema,
      source: graphql.getIntrospectionQuery(),
    }).data as unknown;
    const jsonSchema = gql2js.fromIntrospectionQuery(
      introspectionData as graphql.IntrospectionQuery,
      {
        nullableArrayItems: true,
      },
    );
    const processedSchema = this.postprocessGraphQLDefinition(jsonSchema);
    const dataType = this.buildGraphQLOperationDataType(
      processedSchema as JSONSchema6,
      options,
    );
    return {
      $schema: jsonSchema.$schema,
      $id: jsonSchema.$id,
      type: "object",
      definitions: {
        ...((processedSchema as JSONSchema6).definitions ?? {}),
        GraphQLError: {
          type: "object",
          properties: {
            message: { type: "string" },
            path: { type: "array", items: { type: "object" } },
            locations: {
              type: "array",
              items: { $ref: "#/definitions/GraphQLSourceLocation" },
            },
          },
        },
        GraphQLSourceLocation: {
          type: "object",
          properties: {
            row: { type: "number" },
            column: { type: "number" },
          },
        },
      },
      properties: {
        data: dataType,
        errors: {
          type: "array",
          items: { $ref: "#/definitions/GraphQLError" },
        },
        extensions: {
          type: "object",
        },
      },
    };
  }

  private static buildGraphQLOperationDataType(
    schema: JSONSchema6,
    options: Record<string, unknown>,
  ): JSONSchema6Definition {
    const operationTypes = ["Query", "Mutation"]
      .map((name) => (schema.properties?.[name] as JSONSchema6)?.properties)
      .filter((properties) => !!properties)
      .reduce((p1, p2) => ({ ...p1, ...p2 }), {});
    if (options.query) {
      const operationNames = GraphQLUtil.getRootFields(
        options.query as string,
        options.operation as string,
      );
      const properties: Record<string, JSONSchema6Definition> = {};
      for (const [name, definition] of Object.entries(operationTypes)) {
        if (operationNames.indexOf(name) >= 0) {
          properties[name] = definition;
        }
      }
      return { type: "object", properties };
    } else {
      return operationTypes[options.operation as string];
    }
  }

  private static postprocessGraphQLDefinitions(
    definitions: Record<string, JSONSchema6Definition>,
  ): Record<string, JSONSchema6Definition> {
    if (!definitions) {
      return definitions;
    }
    return Object.entries(definitions)
      .map(
        ([key, definition]) =>
          [key, this.postprocessGraphQLDefinition(definition)] as [
            string,
            JSONSchema6Definition,
          ],
      )
      .reduce(
        (res, entry) => ({
          ...res,
          [entry[0]]: entry[1],
        }),
        {},
      );
  }

  private static postprocessGraphQLDefinition(
    definition: JSONSchema6Definition,
  ): JSONSchema6Definition {
    if (!definition || typeof definition !== "object") {
      return definition;
    }
    const isParametrizedField =
      (!definition.type || definition?.type === "object") &&
      definition?.properties?.return &&
      definition?.properties?.arguments;
    definition = isParametrizedField
      ? {
          ...(definition.properties?.return as JSONSchema6),
          description: definition.description,
        }
      : { ...definition };

    for (const key of [
      "properties",
      "patternProperties",
      "dependencies",
      "definitions",
    ] as (keyof JSONSchema6)[]) {
      const value = definition[key] as Record<string, JSONSchema6Definition>;
      if (!value) {
        continue;
      }
      // @ts-expect-error Didn't find a way to set value by key to schema without triggering a type checker
      definition[key] = this.postprocessGraphQLDefinitions(value);
    }

    for (const key of [
      "additionalItems",
      "additionalProperties",
      "propertyNames",
      "if",
      "then",
      "else",
      "not",
      "contains",
      "items",
    ] as (keyof JSONSchema6)[]) {
      const value = definition[key];
      if (!value || typeof value !== "object") {
        continue;
      }
      // @ts-expect-error Didn't find a way to set value by key to schema without triggering a type checker
      definition[key] = this.postprocessGraphQLDefinition(
        value as JSONSchema6Definition,
      );
    }

    for (const key of [
      "items",
      "prefixItems",
      "allOf",
      "anyOf",
      "oneOf",
    ] as (keyof JSONSchema6)[]) {
      const value = definition[key];
      if (value && Array.isArray(value)) {
        // @ts-expect-error Didn't find a way to set value by key to schema without triggering a type checker
        definition[key] = value.map((i) =>
          this.postprocessGraphQLDefinition(i as JSONSchema6Definition),
        );
      }
    }

    // Handle enums
    if (definition.type && definition.anyOf) {
      delete definition.anyOf;
    }

    // Handle nullable types
    if (
      !definition.type &&
      definition.anyOf?.length === 2 &&
      definition.anyOf.some((i) => typeof i === "object" && i.type === "null")
    ) {
      definition = definition.anyOf.find(
        (i) => !(typeof i === "object" && i.type === "null"),
      )!;
    }
    return definition;
  }
}
