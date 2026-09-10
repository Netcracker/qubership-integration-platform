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
