import { EMPTY_USER } from "../../response/chainApiUtils";

export interface WsdlData {
    type: 'WSDL';
    targetNamespace?: string;
    name?: string;
    portType?: {
        name: string;
        operations: string[];
    };
    service?: {
        name: string;
        portName?: string;
        address?: string;
    };
}

export class SoapSpecificationParser {
    
    /**
     * Parse WSDL content and extract operations
     */
    static async parseWsdlContent(content: string): Promise<WsdlData> {
        
        const wsdlData: WsdlData = {
            type: 'WSDL'
        };

        // Extract targetNamespace
        const targetNamespaceMatch = content.match(/targetNamespace="([^"]+)"/);
        if (targetNamespaceMatch) {
            wsdlData.targetNamespace = targetNamespaceMatch[1];
        }

        // Extract name
        const nameMatch = content.match(/<wsdl:definitions[^>]*name="([^"]+)"/);
        if (nameMatch) {
            wsdlData.name = nameMatch[1];
        }

        // Extract operations from portType
        const portTypeMatch = content.match(/<wsdl:portType[^>]*name="([^"]+)">([\s\S]*?)<\/wsdl:portType>/);
        if (portTypeMatch) {
            wsdlData.portType = {
                name: portTypeMatch[1],
                operations: []
            };

            const operationMatches = portTypeMatch[2].matchAll(/<wsdl:operation[^>]*name="([^"]+)">/g);
            for (const match of operationMatches) {
                wsdlData.portType.operations.push(match[1]);
            }
        }

        // Extract service, port name and soap:address
        const serviceMatch = content.match(/<wsdl:service[^>]*name="([^"]+)">([\s\S]*?)<\/wsdl:service>/);
        if (serviceMatch) {
            wsdlData.service = {
                name: serviceMatch[1]
            };

            // Extract port name from service
            const portMatch = serviceMatch[2].match(/<wsdl:port[^>]*name="([^"]+)"/);
            if (portMatch) {
                wsdlData.service.portName = portMatch[1];
            }

            // Extract soap:address location from service
            const soapAddressMatch = serviceMatch[2].match(/<soap:address[^>]*location="([^"]+)"/);
            if (soapAddressMatch) {
                wsdlData.service.address = soapAddressMatch[1];
            }
        }

        return wsdlData;
    }

    /**
     * Create operations from WSDL data
     */
    static createOperationsFromWsdl(wsdlData: WsdlData, specificationId: string): any[] {
        const operations: any[] = [];
        
        if (wsdlData.portType && wsdlData.portType.operations) {
            for (const operationName of wsdlData.portType.operations) {
                const operation = {
                    id: `${specificationId}-${operationName}`,
                    name: operationName,
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: {...EMPTY_USER},
                    modifiedBy: {...EMPTY_USER},
                    method: 'POST',
                    path: '/',
                    specification: {
                        summary: `${operationName} operation`,
                        operationId: operationName,
                        requestBody: {
                            content: {
                                "application/json": {
                                    "$id": `http://system.catalog/schemas/requests/${operationName}`,
                                    "$ref": `#/definitions/${operationName}Request`,
                                    "$schema": "http://json-schema.org/draft-07/schema#",
                                    definitions: {
                                        [`${operationName}Request`]: {
                                            type: "object",
                                            properties: {},
                                            additionalProperties: false
                                        }
                                    }
                                }
                            }
                        },
                        responses: {
                            "200": {
                                content: {
                                    "application/json": {
                                        "$id": `http://system.catalog/schemas/responses/${operationName}`,
                                        "$ref": `#/definitions/${operationName}Response`,
                                        "$schema": "http://json-schema.org/draft-07/schema#",
                                        definitions: {
                                            [`${operationName}Response`]: {
                                                type: "object",
                                                properties: {},
                                                additionalProperties: false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    requestSchema: {
                        "application/json": {
                            "$id": `http://system.catalog/schemas/requests/${operationName}`,
                            "$ref": `#/definitions/${operationName}Request`,
                            "$schema": "http://json-schema.org/draft-07/schema#",
                            definitions: {
                                [`${operationName}Request`]: {
                                    type: "object",
                                    properties: {},
                                    additionalProperties: false
                                }
                            }
                        }
                    },
                    responseSchemas: {
                        "200": {
                            "application/json": {
                                "$id": `http://system.catalog/schemas/responses/${operationName}`,
                                "$ref": `#/definitions/${operationName}Response`,
                                "$schema": "http://json-schema.org/draft-07/schema#",
                                definitions: {
                                    [`${operationName}Response`]: {
                                        type: "object",
                                        properties: {},
                                        additionalProperties: false
                                    }
                                }
                            }
                        }
                    }
                };
                
                operations.push(operation);
            }
        }
        
        return operations;
    }

    /**
     * Extract address from WSDL data
     */
    static extractAddressFromWsdlData(wsdlData: WsdlData): string | null {
        if (wsdlData.service && wsdlData.service.address) {
            return wsdlData.service.address;
        } else {
            return 'https://soap.example.com/ws';
        }
    }
}
