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

export interface AsyncApiData {
  asyncapi: string;
  info: {
    title: string;
    version: string;
    description?: string;
    "x-protocol"?: string;
  };
  components?: Record<string, any>;
  channels: Record<
    string,
    {
      publish?: {
        summary?: string;
        operationId?: string;
        message?: any;
        "x-maas-classifier-name"?: string;
      };
      subscribe?: {
        summary?: string;
        operationId?: string;
        message?: any;
        "x-maas-classifier-name"?: string;
      };
    }
  >;
  servers?: Record<
    string,
    {
      url: string;
      protocol: string;
    }
  >;
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
