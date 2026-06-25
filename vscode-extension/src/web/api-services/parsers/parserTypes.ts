export interface OpenApiData {
  openapi?: string;
  swagger?: string;
  info: {
    title: string;
    version: string;
    description?: string;
  };
  servers?: Array<{
    url: string;
    description?: string;
    name?: string;
    variables?: Record<string, { default?: string }>;
    protocol?: string;
  }>;
  paths: {
    [path: string]: {
      [method: string]: {
        operationId?: string;
        summary?: string;
        description?: string;
        tags?: string[];
        parameters?: any[];
        requestBody?: any;
        responses?: any;
      };
    };
  };
}

/**
 * AsyncAPI message (2.x shape, also the target of the 3.0 -> 2.x normalization).
 */
export interface AsyncApiMessage {
  payload?: any;
  headers?: any;
  $ref?: string;
  name?: string;
  oneOf?: Array<Record<string, any>>;
  allOf?: Array<Record<string, any>>;
  anyOf?: Array<Record<string, any>>;
}

/**
 * AsyncAPI operation object (2.x shape). `action` is populated when an operation
 * originates from a normalized 3.0 document (`send`/`receive`).
 */
export interface AsyncApiOperationObject {
  summary?: string;
  operationId?: string;
  message?: AsyncApiMessage;
  action?: string;
  maasClassifierName?: string;
  "x-maas-classifier-name"?: string;
  parameters?: any;
}

export interface AsyncApiChannel {
  publish?: AsyncApiOperationObject;
  subscribe?: AsyncApiOperationObject;
  description?: string;
  parameters?: any;
  bindings?: Record<string, any>;
}

export interface AsyncApiServer {
  url: string;
  protocol: string;
  "x-maas-instance"?: string;
}

/**
 * AsyncAPI 2.x document (canonical internal model). 3.0 documents are normalized
 * into this shape by {@link AsyncApiV3Normalizer}, mirroring the runtime-catalog backend.
 */
export interface AsyncApiData {
  asyncapi: string;
  info: {
    title: string;
    version: string;
    description?: string;
    "x-protocol"?: string;
  };
  components?: Record<string, any>;
  channels: Record<string, AsyncApiChannel>;
  servers?: Record<string, AsyncApiServer>;
}

/** AsyncAPI 3.0 channel reference (`{ "$ref": "#/channels/<key>" }`). */
export interface AsyncApiV3ChannelRef {
  $ref?: string;
}

/** AsyncAPI 3.0 reply object. */
export interface AsyncApiV3Reply {
  channel?: AsyncApiV3ChannelRef;
  messages?: Array<Record<string, any>>;
  address?: any;
}

/** AsyncAPI 3.0 top-level operation. */
export interface AsyncApiV3Operation {
  action?: string;
  channel?: AsyncApiV3ChannelRef;
  summary?: string;
  operationId?: string;
  messages?: Array<Record<string, any>>;
  reply?: AsyncApiV3Reply;
  "x-maas-classifier-name"?: string;
}

/** AsyncAPI 3.0 channel. */
export interface AsyncApiV3Channel {
  address?: string;
  description?: string;
  messages?: Record<string, any>;
  parameters?: any;
  bindings?: Record<string, any>;
  servers?: any;
}

/** AsyncAPI 3.0 server (`host` + `pathname` instead of 2.x `url`). */
export interface AsyncApiV3Server {
  host?: string;
  pathname?: string;
  protocol?: string;
  "x-maas-instance"?: string;
}

/** AsyncAPI 3.0 document (before normalization to {@link AsyncApiData}). */
export interface AsyncApiV3Data {
  asyncapi: string;
  info?: {
    title?: string;
    version?: string;
    description?: string;
    "x-protocol"?: string;
  };
  servers?: Record<string, AsyncApiV3Server>;
  channels?: Record<string, AsyncApiV3Channel>;
  operations?: Record<string, AsyncApiV3Operation>;
  components?: Record<string, any>;
}

export interface GraphQLData {
  type: "GRAPHQL";
  schema: string;
  queries: GraphQLOperation[];
  mutations: GraphQLOperation[];
  types: GraphQLType[];
  scalars: string[];
}

export interface GraphQLOperation {
  name: string;
  sdl: string;
}

export interface GraphQLType {
  name: string;
  sdl: string;
}
