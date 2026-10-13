export type QipSchemaType =
  | "SPECIFICATION"
  | "SPECIFICATION_GROUP"
  | "SERVICE"
  | "CHAIN";

export const CONF_MODEL_BASE_URL =
  "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/"; // NOSONAR - JSON Schema identifier, never fetched

export const QIP_SCHEMA_URLS = {
  SPECIFICATION: `${CONF_MODEL_BASE_URL}specification`,
  SPECIFICATION_GROUP: `${CONF_MODEL_BASE_URL}specification-group`,
  SERVICE: `${CONF_MODEL_BASE_URL}service`,
  CHAIN: `${CONF_MODEL_BASE_URL}chain`,
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
