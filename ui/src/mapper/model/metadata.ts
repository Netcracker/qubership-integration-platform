export enum SourceFormat {
  XML = "XML",
  JSON = "JSON",
  GRAPHQL = "GraphQL",
  UNSPECIFIED = "unspecified",
}

export interface XmlNamespace {
  alias: string;
  uri: string;
}

export function isXmlNamespace(obj: unknown): obj is XmlNamespace {
  return (
    obj !== undefined &&
    obj !== null &&
    typeof obj === "object" &&
    "alias" in obj &&
    "uri" in obj &&
    typeof obj.alias === "string" &&
    typeof obj.uri === "string"
  );
}

export function isXmlNamespaces(obj: unknown): obj is XmlNamespace[] {
  return Array.isArray(obj) && (obj.length === 0 || obj.every(isXmlNamespace));
}

export const METADATA_SOURCE_FORMAT_KEY = "sourceFormat";
export const METADATA_SOURCE_TYPE_KEY = "sourceType";
export const METADATA_DATA_FORMAT_KEY = "dataFormat";
export const METADATA_SOURCE_XML_NAMESPACES_KEY = "xmlNamespaces";
export const READONLY_KEY = "readonly";
export const DESCRIPTION_KEY = "description";
export const EXAMPLES_KEY = "examples";
