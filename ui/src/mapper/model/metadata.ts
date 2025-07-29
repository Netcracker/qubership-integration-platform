export enum SourceFormat {
  XML = 'XML',
  JSON = 'JSON',
  GRAPHQL = 'GraphQL',
  UNSPECIFIED = 'unspecified',
}

export enum SourceType {
  SCHEMA = 'schema',
  SAMPLE = 'sample',
  UNSPECIFIED = 'unspecified',
}

export interface XmlNamespace {
  alias: string;
  uri: string;
}

export const METADATA_SOURCE_FORMAT_KEY = 'sourceFormat';
export const METADATA_SOURCE_TYPE_KEY = 'sourceType';
export const METADATA_DATA_FORMAT_KEY = 'dataFormat';
export const METADATA_SOURCE_XML_NAMESPACES_KEY = 'xmlNamespaces';
export const READONLY_KEY = 'readonly';
export const DESCRIPTION_KEY = 'description';
export const EXAMPLES_KEY = 'examples';
