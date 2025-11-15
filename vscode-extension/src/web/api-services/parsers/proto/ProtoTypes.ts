export type JsonSchema = Record<string, unknown>;

export interface ProtoMethod {
    name: string;
    operationId: string;
    comment?: string;
    requestType: string;
    responseType: string;
    requestStream: boolean;
    responseStream: boolean;
}

export interface ProtoService {
    name: string;
    qualifiedName: string;
    methods: ProtoMethod[];
}

export interface ProtoData {
    type: 'PROTO';
    packageName: string;
    package?: string;
    javaPackage?: string;
    services: ProtoService[];
    typeDefinitions: Record<string, JsonSchema>;
}

export interface ResolvedProtoOperation {
    operationId: string;
    rpcName: string;
    path: string;
    summary?: string;
    serviceName: string;
    requestStream: boolean;
    responseStream: boolean;
    requestType: string;
    responseType: string;
    requestSchema: JsonSchema;
    responseSchema: JsonSchema;
}

