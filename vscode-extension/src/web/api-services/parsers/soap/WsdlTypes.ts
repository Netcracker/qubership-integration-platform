export type WsdlVersion = "1.1" | "2.0";

export interface WsdlEndpoint {
    serviceName?: string;
    endpointName?: string;
    address?: string;
}

export interface WsdlQualifiedName {
    namespace?: string;
    name: string;
    raw?: string;
}

export interface WsdlMessagePart {
    name?: string;
    element?: WsdlQualifiedName;
    type?: WsdlQualifiedName;
}

export interface WsdlMessage {
    name: string;
    namespace?: string;
    parts: WsdlMessagePart[];
}

export interface WsdlOperationDetails {
    name: string;
    input?: WsdlQualifiedName;
    output?: WsdlQualifiedName;
}

export interface WsdlSchemaEntry {
    uri?: string;
    targetNamespace?: string;
    element: Element;
}

export interface WsdlParseResult {
    type: "WSDL";
    version: WsdlVersion;
    targetNamespace?: string;
    serviceNames: string[];
    operations: string[];
    endpoints: WsdlEndpoint[];
    operationDetails: Record<string, WsdlOperationDetails>;
    messages: Record<string, WsdlMessage>;
    schemas: WsdlSchemaEntry[];
}

