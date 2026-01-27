type JsonValue = Record<string, any>;

const COMPONENTS_PREFIX = "#/components";
const SCHEMAS_PREFIX = "#/components/schemas/";
const MESSAGES_PREFIX = "#/components/messages/";
const DEFINITIONS_PREFIX = "#/definitions/";
const SCHEMA_ID_DOMAIN = "http://system.catalog/schemas/";
const PAYLOAD_FIELD_NAME = "payload";
const HEADERS_FIELD_NAME = "headers";
const TYPE_FIELD_NAME = "type";
const ITEMS_FIELD_NAME = "items";
const PROPERTIES_FIELD_NAME = "properties";
const ADDITIONAL_PROPERTIES_FIELD_NAME = "additionalProperties";
const ALL_OF_FIELD_NAME = "allOf";
const ANY_OF_FIELD_NAME = "anyOf";
const OBJECT_FIELD_TYPE = "object";
const ARRAY_FIELD_TYPE = "array";
const REF_FIELD_NAME = "$ref";
const DEFINITIONS_NODE_NAME = "definitions";
const SCHEMA_ID_NODE_NAME = "$id";
const SCHEMA_HEADER_NODE_NAME = "$schema";
const EMPTY_REF = "#/";
const JSON_SCHEMA_DRAFT_URL = "http://json-schema.org/draft-07/schema#";

function isObject(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function deepClone<T>(value: T): T {
  if (value === undefined || value === null) {
    return value as T;
  }
  return JSON.parse(JSON.stringify(value));
}

export interface ResolvedSchema {
  name: string;
  schema: JsonValue;
}

export class AsyncApiSchemaResolver {
  resolveRef(schemaRef: string, components: JsonValue): ResolvedSchema | null {
    if (!schemaRef.startsWith(COMPONENTS_PREFIX) || !components) {
      return null;
    }

    const clonedComponents = deepClone(components);
    const schemaNode = this.getSchemaNode(schemaRef, clonedComponents);
    this.convertPayloadToSchemaNode(schemaNode);

    const schemaRefs = this.getNestedRefs(
      schemaNode,
      clonedComponents,
      new Set(),
    );
    const resolvedSchema = this.getResolvedSchema(
      schemaRef,
      schemaNode,
      schemaRefs,
    );

    return {
      name: schemaRef.replace(MESSAGES_PREFIX, "").replace(SCHEMAS_PREFIX, ""),
      schema: resolvedSchema,
    };
  }

  private getSchemaNode(
    schemaRef: string,
    componentsNode: JsonValue,
  ): JsonValue {
    const path = schemaRef.replace(COMPONENTS_PREFIX, "");
    const segments = path.split("/").filter(Boolean);

    let current: any = componentsNode;
    for (const segment of segments) {
      if (isObject(current) && segment in current) {
        current = current[segment];
      } else {
        return {};
      }
    }

    if (!isObject(current)) {
      return {};
    }

    return deepClone(current);
  }

  private convertPayloadToSchemaNode(componentNode: JsonValue): void {
    if (!isObject(componentNode)) {
      return;
    }

    const payloadNode = componentNode[PAYLOAD_FIELD_NAME];
    if (isObject(payloadNode)) {
      for (const [key, value] of Object.entries(payloadNode)) {
        componentNode[key] = value;
      }
      delete componentNode[PAYLOAD_FIELD_NAME];
    }
    if (componentNode[HEADERS_FIELD_NAME] !== undefined) {
      delete componentNode[HEADERS_FIELD_NAME];
    }
  }

  private getResolvedSchema(
    schemaRef: string,
    schemaNode: JsonValue,
    schemaRefs: Map<string, JsonValue>,
  ): JsonValue {
    const resolvedSchema = deepClone(schemaNode);

    const definitions: JsonValue = {};
    for (const [key, value] of schemaRefs.entries()) {
      definitions[key] = value;
    }

    resolvedSchema[DEFINITIONS_NODE_NAME] = definitions;
    resolvedSchema[SCHEMA_ID_NODE_NAME] = SCHEMA_ID_DOMAIN.concat(
      schemaRef.replace(MESSAGES_PREFIX, "").replace(SCHEMAS_PREFIX, ""),
    );
    resolvedSchema[SCHEMA_HEADER_NODE_NAME] = JSON_SCHEMA_DRAFT_URL;

    return resolvedSchema;
  }

  private getNestedRefs(
    schemaNode: JsonValue,
    componentsNode: JsonValue,
    visited: Set<string>,
  ): Map<string, JsonValue> {
    const result = new Map<string, JsonValue>();
    if (!isObject(schemaNode)) {
      return result;
    }

    const schemaType = schemaNode[TYPE_FIELD_NAME];

    if (schemaType === OBJECT_FIELD_TYPE) {
      const propertiesNode = schemaNode[PROPERTIES_FIELD_NAME];
      this.collectSchemaNodeProperties(
        result,
        propertiesNode,
        componentsNode,
        visited,
      );

      const additionalNode = schemaNode[ADDITIONAL_PROPERTIES_FIELD_NAME];
      this.collectSchemaNodeProperties(
        result,
        additionalNode,
        componentsNode,
        visited,
      );
    } else if (
      schemaType === ARRAY_FIELD_TYPE &&
      schemaNode[ITEMS_FIELD_NAME]
    ) {
      const itemsNode = schemaNode[ITEMS_FIELD_NAME];
      if (
        isObject(itemsNode) &&
        typeof itemsNode[REF_FIELD_NAME] === "string"
      ) {
        const refKey = this.rewriteRef(itemsNode[REF_FIELD_NAME] as string);
        itemsNode[REF_FIELD_NAME] = refKey;
        const definitionKey = refKey.replace(DEFINITIONS_PREFIX, "");
        if (!visited.has(definitionKey)) {
          visited.add(definitionKey);
          const refNode = this.getSchemaNodeFromComponents(
            itemsNode[REF_FIELD_NAME] as string,
            componentsNode,
          );
          this.convertPayloadToSchemaNode(refNode);
          result.set(definitionKey, refNode);
          const nestedRefs = this.getNestedRefs(
            refNode,
            componentsNode,
            visited,
          );
          nestedRefs.forEach((value, key) => result.set(key, value));
        }
      }
    } else if (typeof schemaNode[REF_FIELD_NAME] === "string") {
      const refKey = this.rewriteRef(schemaNode[REF_FIELD_NAME] as string);
      schemaNode[REF_FIELD_NAME] = refKey;
      const definitionKey = refKey.replace(DEFINITIONS_PREFIX, "");
      if (!visited.has(definitionKey)) {
        visited.add(definitionKey);
        const refNode = this.getSchemaNodeFromComponents(
          refKey,
          componentsNode,
        );
        this.convertPayloadToSchemaNode(refNode);
        result.set(definitionKey, refNode);
        const nestedRefs = this.getNestedRefs(refNode, componentsNode, visited);
        nestedRefs.forEach((value, key) => result.set(key, value));
      }
    }

    return result;
  }

  private collectSchemaNodeProperties(
    target: Map<string, JsonValue>,
    node: unknown,
    componentsNode: JsonValue,
    visited: Set<string>,
  ): void {
    if (!node) {
      return;
    }

    if (Array.isArray(node)) {
      node.forEach((item) => {
        if (isObject(item)) {
          this.collectSchemaNodeProperties(
            target,
            item,
            componentsNode,
            visited,
          );
        }
      });
      return;
    }

    if (isObject(node)) {
      const iterableRefs = this.getIterablePropertyRefs(
        node,
        componentsNode,
        visited,
      );
      iterableRefs.forEach((value, key) => target.set(key, value));

      const directRefs = this.getRefs(node, componentsNode, visited);
      directRefs.forEach((value, key) => target.set(key, value));

      Object.values(node).forEach((value) => {
        if (isObject(value)) {
          this.collectSchemaNodeProperties(
            target,
            value,
            componentsNode,
            visited,
          );
        } else if (Array.isArray(value)) {
          value.forEach((item) => {
            if (isObject(item)) {
              this.collectSchemaNodeProperties(
                target,
                item,
                componentsNode,
                visited,
              );
            }
          });
        }
      });
    }
  }

  private getIterablePropertyRefs(
    property: JsonValue,
    componentsNode: JsonValue,
    visited: Set<string>,
  ): Map<string, JsonValue> {
    const result = new Map<string, JsonValue>();
    const iterableField = [
      PROPERTIES_FIELD_NAME,
      ALL_OF_FIELD_NAME,
      ANY_OF_FIELD_NAME,
    ].find((field) => field in property);

    if (!iterableField) {
      return result;
    }

    const iterableNode = property[iterableField];
    if (Array.isArray(iterableNode)) {
      iterableNode.forEach((item) => {
        if (isObject(item)) {
          const refs = this.getRefs(item, componentsNode, visited);
          refs.forEach((value, key) => result.set(key, value));
        }
      });
    } else if (isObject(iterableNode)) {
      Object.values(iterableNode).forEach((value) => {
        if (isObject(value)) {
          const refs = this.getRefs(value, componentsNode, visited);
          refs.forEach((val, key) => result.set(key, val));
        }
      });
    }

    return result;
  }

  private getRefs(
    property: JsonValue,
    componentsNode: JsonValue,
    visited: Set<string>,
  ): Map<string, JsonValue> {
    const result = new Map<string, JsonValue>();

    if (Array.isArray(property)) {
      property.forEach((item) => {
        if (isObject(item)) {
          const refs = this.getRefs(item, componentsNode, visited);
          refs.forEach((value, key) => result.set(key, value));
        }
      });
      return result;
    }

    if (!isObject(property)) {
      return result;
    }

    let refKey = EMPTY_REF;
    if (typeof property[REF_FIELD_NAME] === "string") {
      refKey = property[REF_FIELD_NAME] as string;
      const rewritten = this.rewriteRef(refKey);
      property[REF_FIELD_NAME] = rewritten;
      const definitionKey = rewritten.replace(DEFINITIONS_PREFIX, "");
      if (!visited.has(definitionKey)) {
        visited.add(definitionKey);
        const componentNode = this.getSchemaNodeFromComponents(
          rewritten,
          componentsNode,
        );
        this.convertPayloadToSchemaNode(componentNode);
        result.set(definitionKey, componentNode);
        const nestedRefs = this.getNestedRefs(
          componentNode,
          componentsNode,
          visited,
        );
        nestedRefs.forEach((value, key) => result.set(key, value));
      }
    }

    if (property[ITEMS_FIELD_NAME]) {
      const items = property[ITEMS_FIELD_NAME];
      if (isObject(items) && typeof items[REF_FIELD_NAME] === "string") {
        const rewritten = this.rewriteRef(items[REF_FIELD_NAME] as string);
        items[REF_FIELD_NAME] = rewritten;
        const definitionKey = rewritten.replace(DEFINITIONS_PREFIX, "");
        if (!visited.has(definitionKey)) {
          visited.add(definitionKey);
          const componentNode = this.getSchemaNodeFromComponents(
            rewritten,
            componentsNode,
          );
          this.convertPayloadToSchemaNode(componentNode);
          result.set(definitionKey, componentNode);
          const nestedRefs = this.getNestedRefs(
            componentNode,
            componentsNode,
            visited,
          );
          nestedRefs.forEach((value, key) => result.set(key, value));
        }
      }
    }

    return result;
  }

  private getSchemaNodeFromComponents(
    ref: string,
    componentsNode: JsonValue,
  ): JsonValue {
    const sanitizedRef = ref.replace(COMPONENTS_PREFIX, "");
    const segments = sanitizedRef.split("/").filter(Boolean);
    let current: any = componentsNode;
    for (const segment of segments) {
      if (isObject(current) && segment in current) {
        current = current[segment];
      } else {
        return {};
      }
    }
    return isObject(current) ? deepClone(current) : {};
  }

  private rewriteRef(currentRef: string): string {
    if (currentRef.includes(SCHEMAS_PREFIX)) {
      return currentRef.replace(SCHEMAS_PREFIX, DEFINITIONS_PREFIX);
    }
    if (currentRef.includes(MESSAGES_PREFIX)) {
      return currentRef.replace(MESSAGES_PREFIX, DEFINITIONS_PREFIX);
    }
    return currentRef;
  }
}
