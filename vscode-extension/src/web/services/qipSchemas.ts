export type QipSchemaType =
  | "SPECIFICATION"
  | "SPECIFICATION_GROUP"
  | "SERVICE"
  | "CHAIN";

export const QIP_SCHEMA_URLS = {
  SPECIFICATION: "http://qubership.org/schemas/product/qip/specification",
  SPECIFICATION_GROUP:
    "http://qubership.org/schemas/product/qip/specification-group",
  SERVICE: "http://qubership.org/schemas/product/qip/service",
  CHAIN: "http://qubership.org/schemas/product/qip/chain",
} as const;

export function getQipSchemaType(schemaUrl: string): QipSchemaType | null {
  for (const [type, url] of Object.entries(QIP_SCHEMA_URLS)) {
    if (schemaUrl === url) {
      return type as QipSchemaType;
    }
  }
  return null;
}

export function isQipSchema(schemaUrl: string): boolean {
  return getQipSchemaType(schemaUrl) !== null;
}

export function getSchemaUrl(type: QipSchemaType): string {
  return QIP_SCHEMA_URLS[type];
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  schemaType?: QipSchemaType;
}

export interface ValidationError {
  path: string;
  message: string;
  data?: any;
}

export const QIP_SCHEMAS = {
  SPECIFICATION: {
    $schema: "http://json-schema.org/draft-07/schema#",
    type: "object",
    required: ["$schema", "id", "name", "content"],
    properties: {
      $schema: {
        type: "string",
        enum: [QIP_SCHEMA_URLS.SPECIFICATION],
      },
      id: {
        type: "string",
        pattern:
          "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}-.*$",
      },
      name: {
        type: "string",
        minLength: 1,
      },
      content: {
        type: "object",
        required: ["version", "source", "operations"],
        properties: {
          deprecated: { type: "boolean" },
          version: { type: "string" },
          source: {
            type: "string",
            enum: ["MANUAL", "DISCOVERED", "CUSTOMER_MANUAL"],
          },
          operations: {
            type: "array",
            items: {
              type: "object",
              required: ["id", "name", "method", "path", "specification"],
              properties: {
                id: { type: "string" },
                name: { type: "string" },
                method: {
                  type: "string",
                  enum: [
                    "GET",
                    "POST",
                    "PUT",
                    "DELETE",
                    "PATCH",
                    "HEAD",
                    "OPTIONS",
                  ],
                },
                path: { type: "string" },
                specification: {
                  type: "object",
                  properties: {
                    name: { type: "string" },
                    input: { type: "string" },
                    output: { type: "string" },
                  },
                },
              },
            },
          },
          specificationSources: {
            type: "array",
            items: {
              type: "object",
              required: ["id", "name", "fileName"],
              properties: {
                id: { type: "string" },
                name: { type: "string" },
                fileName: { type: "string" },
                mainSource: { type: "boolean" },
              },
            },
          },
          parentId: { type: "string" },
        },
      },
    },
  },

  SPECIFICATION_GROUP: {
    $schema: "http://json-schema.org/draft-07/schema#",
    type: "object",
    required: ["$schema", "id", "name", "content"],
    properties: {
      $schema: {
        type: "string",
        enum: [QIP_SCHEMA_URLS.SPECIFICATION_GROUP],
      },
      id: { type: "string" },
      name: { type: "string" },
      content: {
        type: "object",
        required: ["version"],
        properties: {
          version: { type: "string" },
          specifications: {
            type: "array",
            items: { type: "string" },
          },
        },
      },
    },
  },

  SERVICE: {
    $schema: "http://json-schema.org/draft-07/schema#",
    type: "object",
    required: ["$schema", "id", "name"],
    properties: {
      $schema: {
        type: "string",
        enum: [QIP_SCHEMA_URLS.SERVICE],
      },
      id: { type: "string" },
      name: { type: "string" },
      content: {
        type: "object",
        properties: {
          version: { type: "string" },
          description: { type: "string" },
          integrationSystemType: {
            type: "string",
            enum: ["EXTERNAL", "INTERNAL", "IMPLEMENTED"],
          },
          protocol: { type: "string" },
          extendedProtocol: { type: "string" },
          specification: { type: "string" },
          activeEnvironmentId: { type: "string" },
          environments: { type: "array" },
          labels: { type: "array" },
          migrations: { type: "array" },
          specificationGroups: {
            type: "array",
            items: { type: "string" },
          },
        },
      },
    },
  },

  CHAIN: {
    $schema: "http://json-schema.org/draft-07/schema#",
    type: "object",
    required: ["$schema", "id", "name", "content"],
    properties: {
      $schema: {
        type: "string",
        enum: [QIP_SCHEMA_URLS.CHAIN],
      },
      id: { type: "string" },
      name: { type: "string" },
      content: {
        type: "object",
        required: ["version"],
        properties: {
          version: { type: "string" },
          elements: {
            type: "array",
            items: {
              type: "object",
              properties: {
                id: { type: "string" },
                type: { type: "string" },
                configuration: { type: "object" },
              },
            },
          },
        },
      },
    },
  },
} as const;
