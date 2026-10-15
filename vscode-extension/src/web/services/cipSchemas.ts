export type CipSchemaType =
  | "SPECIFICATION"
  | "SPECIFICATION_GROUP"
  | "SERVICE"
  | "CHAIN";

export const CONF_MODEL_BASE_URL =
  "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/"; // NOSONAR - JSON Schema identifier, never fetched

export const CIP_SCHEMA_URLS = {
  SPECIFICATION: `${CONF_MODEL_BASE_URL}specification`,
  SPECIFICATION_GROUP: `${CONF_MODEL_BASE_URL}specification-group`,
  SERVICE: `${CONF_MODEL_BASE_URL}service`,
  CHAIN: `${CONF_MODEL_BASE_URL}chain`,
} as const;

export function getCipSchemaType(schemaUrl: string): CipSchemaType | null {
  for (const [type, url] of Object.entries(CIP_SCHEMA_URLS)) {
    if (schemaUrl === url) {
      return type as CipSchemaType;
    }
  }
  return null;
}

export function isCipSchema(schemaUrl: string): boolean {
  return getCipSchemaType(schemaUrl) !== null;
}

export function getSchemaUrl(type: CipSchemaType): string {
  return CIP_SCHEMA_URLS[type];
}
