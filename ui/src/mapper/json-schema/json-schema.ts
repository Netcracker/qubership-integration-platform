import {
  JSONSchema7,
  JSONSchema7Definition,
  JSONSchema7Type,
} from "json-schema";
import {
  DataType,
  MetadataAware,
  ObjectSchema,
  TypeDefinition,
} from "../model/model.ts";
import { MetadataUtil } from "../util/metadata.ts";
import { DESCRIPTION_KEY, EXAMPLES_KEY } from "../model/metadata.ts";

interface TypeWithUri {
  uri: string;
  type: DataType;
}

export function exportAsJsonSchema(
  type: DataType,
  typeDefinitions: TypeDefinition[],
): JSONSchema7 {
  const typeDefinitionMap = new Map<string, TypeWithUri>(
    typeDefinitions?.map((d) => [
      d.id,
      { uri: `#/definitions/${d.name}`, type: d.type },
    ]),
  );
  const typeSchema = buildTypeSchema(type, "#", typeDefinitionMap);
  const result = {
    ...typeSchema,
    $schema: "https://json-schema.org/draft/2019-09/schema",
  };
  const definitions = {
    ...typeSchema?.definitions,
    ...buildTypeDefinitions(
      typeDefinitions,
      "#/definitions",
      typeDefinitionMap,
    ),
  };
  if (Object.keys(definitions).length > 0) {
    result.definitions = definitions;
  }
  return result;
}

function buildTypeSchema(
  type: DataType,
  path: string,
  typeDefinitionMap: Map<string, TypeWithUri>,
): JSONSchema7 | undefined {
  switch (type.name) {
    case "string":
      return { type: "string", ...buildMetadata(type) };
    case "number":
      return { type: "number", ...buildMetadata(type) };
    case "boolean":
      return { type: "boolean", ...buildMetadata(type) };
    case "null":
      return { type: "null", ...buildMetadata(type) };
    case "array": {
      const p = `${path}/definitions`;
      const m = updateTypeDefinitionMap(typeDefinitionMap, p, type.definitions);
      return {
        type: "array",
        items: buildTypeSchema(type.itemType, p, m),
        ...buildTypeDefinitionsAsPartOfSchema(type.definitions, p, m),
        ...buildMetadata(type),
      };
    }
    case "object": {
      const p = `${path}/definitions`;
      const m = updateTypeDefinitionMap(typeDefinitionMap, p, type.definitions);
      return {
        type: "object",
        properties: buildObjectProperties(type.schema, `${path}/properties`, m),
        required: type.schema?.attributes
          ?.filter((a) => a.required)
          .map((a) => a.name),
        ...buildTypeDefinitionsAsPartOfSchema(type.definitions, p, m),
        ...buildMetadata(type),
      };
    }
    case "allOf": {
      const p = `${path}/definitions`;
      const m = updateTypeDefinitionMap(typeDefinitionMap, p, type.definitions);
      return {
        allOf: type.types
          .map((t, i) => buildTypeSchema(t, `${path}/allOf[${i}]`, m))
          .filter((i) => !!i),
        ...buildTypeDefinitionsAsPartOfSchema(type.definitions, p, m),
        ...buildMetadata(type),
      };
    }
    case "anyOf": {
      const p = `${path}/definitions`;
      const m = updateTypeDefinitionMap(typeDefinitionMap, p, type.definitions);
      return {
        anyOf: type.types
          .map((t, i) => buildTypeSchema(t, `${path}/anyOf[${i}]`, m))
          .filter((i) => !!i),
        ...buildTypeDefinitionsAsPartOfSchema(type.definitions, p, m),
        ...buildMetadata(type),
      };
    }
    case "oneOf": {
      const p = `${path}/definitions`;
      const m = updateTypeDefinitionMap(typeDefinitionMap, p, type.definitions);
      return {
        oneOf: type.types
          .map((t, i) => buildTypeSchema(t, `${path}/oneOf[${i}]`, m))
          .filter((i) => !!i),
        ...buildTypeDefinitionsAsPartOfSchema(type.definitions, p, m),
        ...buildMetadata(type),
      };
    }
    case "reference": {
      const p = `${path}/definitions`;
      const m = updateTypeDefinitionMap(typeDefinitionMap, p, type.definitions);
      return {
        $ref: m.get(type.definitionId)?.uri,
        ...buildTypeDefinitionsAsPartOfSchema(type.definitions, p, m),
      };
    }
    default:
      return undefined;
  }
}

function updateTypeDefinitionMap(
  typeDefinitionMap: Map<string, TypeWithUri>,
  path: string,
  typeDefinitions: TypeDefinition[] | undefined,
): Map<string, TypeWithUri> {
  const result = new Map<string, TypeWithUri>(typeDefinitionMap);
  for (const definition of typeDefinitions ?? []) {
    result.set(definition.id, {
      uri: `${path}/${definition.name}`,
      type: definition.type,
    });
  }
  return result;
}

function buildTypeDefinitions(
  definitions: TypeDefinition[] | undefined,
  path: string,
  typeDefinitionMap: Map<string, TypeWithUri>,
): { [key: string]: JSONSchema7Definition } | undefined {
  if (!definitions || definitions.length === 0) {
    return undefined;
  }
  const result: { [key: string]: JSONSchema7Definition } = {};
  for (const definition of definitions) {
    const schema = buildTypeSchema(
      definition.type,
      `${path}/${definition.name}`,
      typeDefinitionMap,
    );
    if (schema) {
      result[definition.name] = schema;
    }
  }
  return result;
}

function buildTypeDefinitionsAsPartOfSchema(
  definitions: TypeDefinition[] | undefined,
  path: string,
  typeDefinitionMap: Map<string, TypeWithUri>,
): Partial<JSONSchema7> {
  const defs = buildTypeDefinitions(definitions, path, typeDefinitionMap);
  return defs ? { definitions: defs } : {};
}

function buildMetadata(obj: MetadataAware): Partial<JSONSchema7> {
  const result: Partial<JSONSchema7> = {};
  const description = MetadataUtil.getString(obj, DESCRIPTION_KEY);
  if (description) {
    result.description = description;
  }
  const examples = MetadataUtil.getValue(obj, EXAMPLES_KEY) as
    | JSONSchema7Type
    | undefined;
  if (examples) {
    result.examples = examples;
  }
  return result;
}

function buildObjectProperties(
  schema: ObjectSchema,
  path: string,
  m: Map<string, TypeWithUri>,
): { [key: string]: JSONSchema7Definition } {
  const properties: { [key: string]: JSONSchema7Definition } = {};
  for (const attribute of schema.attributes) {
    properties[attribute.name] = {
      ...buildTypeSchema(attribute.type, `${path}/${attribute.name}`, m),
      ...(attribute.defaultValue === undefined
        ? {}
        : { default: attribute.defaultValue }),
      ...buildMetadata(attribute),
    };
  }
  return properties;
}
