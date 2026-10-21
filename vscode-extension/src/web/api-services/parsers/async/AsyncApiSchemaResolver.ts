import { deepClone, isObject } from "./asyncJsonUtils";

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
// The keywords that give a schema an object shape without `type`, as the backend reads them.
const OBJECT_SHAPE_FIELD_NAMES = [
  PROPERTIES_FIELD_NAME,
  ADDITIONAL_PROPERTIES_FIELD_NAME,
  ALL_OF_FIELD_NAME,
  ANY_OF_FIELD_NAME,
  "oneOf",
];
const REF_FIELD_NAME = "$ref";
const DEFINITIONS_NODE_NAME = "definitions";
const SCHEMA_ID_NODE_NAME = "$id";
const SCHEMA_HEADER_NODE_NAME = "$schema";
const EMPTY_REF = "#/";
const JSON_SCHEMA_DRAFT_URL = "http://json-schema.org/draft-07/schema#";

// The name becomes part of the $id URI, and a message name is free text. Encodes everything outside
// the RFC 3986 unreserved set, as the backend does.
function encodeName(name: string): string {
  return encodeURIComponent(name).replace(
    /[!'()*]/g,
    (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`,
  );
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

    return this.resolveSchemaNode(schemaRef, schemaNode, clonedComponents);
  }

  // The walk reads the components and never changes them, so they are not copied.
  resolveSchema(
    name: string,
    schema: JsonValue,
    components: JsonValue,
  ): ResolvedSchema {
    return this.resolveSchemaNode(
      encodeName(name),
      deepClone(schema),
      components,
    );
  }

  private resolveSchemaNode(
    schemaRef: string,
    schemaNode: JsonValue,
    componentsNode: JsonValue,
  ): ResolvedSchema {
    this.resolveInlineRef(schemaNode, componentsNode);
    // A message that is an alias of another message has its payload only now.
    this.convertPayloadToSchemaNode(schemaNode);

    const schemaRefs = this.getNestedRefs(
      schemaNode,
      componentsNode,
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

  private resolveInlineRef(
    schemaNode: JsonValue,
    componentsNode: JsonValue,
  ): void {
    const ref = schemaNode[REF_FIELD_NAME];
    if (
      typeof ref !== "string" ||
      !ref.startsWith(`${COMPONENTS_PREFIX}/`) ||
      TYPE_FIELD_NAME in schemaNode
    ) {
      return;
    }
    const resolved = this.findComponent(ref, componentsNode);
    if (!isObject(resolved)) {
      return;
    }
    for (const key of Object.keys(schemaNode)) {
      delete schemaNode[key];
    }
    Object.assign(schemaNode, deepClone(resolved));
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

    // A schema's own definitions stay beside the components it references.
    const definitions: JsonValue = isObject(schemaNode[DEFINITIONS_NODE_NAME])
      ? { ...schemaNode[DEFINITIONS_NODE_NAME] }
      : {};
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

    const objectShapeFields = OBJECT_SHAPE_FIELD_NAMES.filter(
      (field) => field in schemaNode,
    );

    if (objectShapeFields.length > 0) {
      for (const field of objectShapeFields) {
        this.collectSchemaNodeProperties(
          result,
          schemaNode[field],
          componentsNode,
          visited,
        );
      }
    } else if (isObject(schemaNode[ITEMS_FIELD_NAME])) {
      this.collectSchemaNodeProperties(
        result,
        schemaNode[ITEMS_FIELD_NAME],
        componentsNode,
        visited,
      );
    } else if (this.isComponentRef(schemaNode[REF_FIELD_NAME])) {
      const originalRef = schemaNode[REF_FIELD_NAME] as string;
      const refKey = this.rewriteRef(originalRef);
      schemaNode[REF_FIELD_NAME] = refKey;
      const definitionKey = refKey.replace(DEFINITIONS_PREFIX, "");
      if (!visited.has(definitionKey)) {
        visited.add(definitionKey);
        const refNode = this.getSchemaNodeFromComponents(
          originalRef,
          componentsNode,
        );
        this.convertPayloadToSchemaNode(refNode);
        result.set(definitionKey, refNode);
        const nestedRefs = this.getNestedRefs(refNode, componentsNode, visited);
        nestedRefs.forEach((value, key) => result.set(key, value));
      }
    }
    // Property dependencies apply next to any shape above, so they are walked
    // whichever branch ran.
    this.collectSchemaNodeProperties(
      result,
      schemaNode.dependencies,
      componentsNode,
      visited,
    );

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
    if (this.isComponentRef(property[REF_FIELD_NAME])) {
      refKey = property[REF_FIELD_NAME] as string;
      const rewritten = this.rewriteRef(refKey);
      property[REF_FIELD_NAME] = rewritten;
      const definitionKey = rewritten.replace(DEFINITIONS_PREFIX, "");
      if (!visited.has(definitionKey)) {
        visited.add(definitionKey);
        const componentNode = this.getSchemaNodeFromComponents(
          refKey,
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
      if (isObject(items) && this.isComponentRef(items[REF_FIELD_NAME])) {
        const originalRef = items[REF_FIELD_NAME] as string;
        const rewritten = this.rewriteRef(originalRef);
        items[REF_FIELD_NAME] = rewritten;
        const definitionKey = rewritten.replace(DEFINITIONS_PREFIX, "");
        if (!visited.has(definitionKey)) {
          visited.add(definitionKey);
          const componentNode = this.getSchemaNodeFromComponents(
            originalRef,
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
    const component = this.findComponent(ref, componentsNode);
    return isObject(component) ? deepClone(component) : {};
  }

  private findComponent(ref: string, componentsNode: JsonValue): unknown {
    // Resolve against the original `#/components/...` path so a messages ref and
    // a same-named schema do not collide, and nested segments still navigate.
    const sanitizedRef = ref.replace(COMPONENTS_PREFIX, "");
    const segments = sanitizedRef.split("/").filter(Boolean);
    let current: any = componentsNode;
    for (const segment of segments) {
      if (isObject(current) && segment in current) {
        current = current[segment];
      } else {
        return undefined;
      }
    }
    return current;
  }

  // A reference to an external document, a URL, or a local definition stays as written.
  private isComponentRef(ref: unknown): ref is string {
    return (
      typeof ref === "string" &&
      (ref.startsWith(SCHEMAS_PREFIX) || ref.startsWith(MESSAGES_PREFIX))
    );
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
