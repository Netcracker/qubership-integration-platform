import { EMPTY_USER } from '../../response/chainApiUtils';

export interface AsyncApiData {
    asyncapi: string;
    info: {
        title: string;
        version: string;
        description?: string;
        'x-protocol'?: string;
    };
    channels: Record<string, {
        publish?: {
            summary?: string;
            operationId?: string;
            message?: any;
        };
        subscribe?: {
            summary?: string;
            operationId?: string;
            message?: any;
        };
    }>;
    servers?: Record<string, {
        url: string;
        protocol: string;
    }>;
}

export class AsyncApiSpecificationParser {
    /**
     * Parse AsyncAPI content
     */
    static async parseAsyncApiContent(content: string): Promise<AsyncApiData> {
        try {
            let specData: any;

            // Try JSON first
            try {
                specData = JSON.parse(content);
            } catch (jsonError) {
                // Try YAML
                const yaml = require('yaml');
                specData = yaml.parse(content);
            }

            if (!specData.asyncapi) {
                throw new Error('Not a valid AsyncAPI specification');
            }

            return specData as AsyncApiData;
        } catch (error) {
            throw error;
        }
    }

    /**
     * Create operations from AsyncAPI data
     */
    static createOperationsFromAsyncApi(asyncApiData: AsyncApiData, specificationId: string): any[] {
        const operations: any[] = [];

        if (!asyncApiData.channels) {
            return operations;
        }

        const protocol = asyncApiData.info?.['x-protocol'] || 'unknown';

        // Process each channel
        Object.entries(asyncApiData.channels).forEach(([channelName, channel]) => {
            // Publish operations
            if (channel.publish) {
                const operationId = channel.publish.operationId || `publish-${channelName}`;
                const operation = {
                    id: `${specificationId}-${operationId}`,
                    name: operationId,
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: {...EMPTY_USER },
                    modifiedBy: {...EMPTY_USER },
                    method: 'PUBLISH',
                    path: channelName,
                    specification: {
                        summary: channel.publish.summary || `${operationId} operation`,
                        operationId: operationId,
                        protocol: protocol,
                        channel: channelName,
                        operation: 'publish',
                        message: channel.publish.message || {}
                    },
                    requestSchema: {
                        $id: `http://system.catalog/schemas/requests/${operationId}`,
                        $ref: `#/definitions/${operationId}Request`,
                        $schema: "http://json-schema.org/draft-07/schema#",
                        definitions: {
                            [`${operationId}Request`]: {
                                type: "object",
                                properties: {},
                                additionalProperties: false
                            }
                        }
                    },
                    responseSchemas: {
                        $id: `http://system.catalog/schemas/responses/${operationId}`,
                        $ref: `#/definitions/${operationId}Response`,
                        $schema: "http://json-schema.org/draft-07/schema#",
                        definitions: {
                            [`${operationId}Response`]: {
                                type: "object",
                                properties: {},
                                additionalProperties: false
                            }
                        }
                    }
                };
                operations.push(operation);
            }

            // Subscribe operations
            if (channel.subscribe) {
                const operationId = channel.subscribe.operationId || `subscribe-${channelName}`;
                const operation = {
                    id: `${specificationId}-${operationId}`,
                    name: operationId,
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: {...EMPTY_USER },
                    modifiedBy: {...EMPTY_USER },
                    method: 'SUBSCRIBE',
                    path: channelName,
                    specification: {
                        summary: channel.subscribe.summary || `${operationId} operation`,
                        operationId: operationId,
                        protocol: protocol,
                        channel: channelName,
                        operation: 'subscribe',
                        message: channel.subscribe.message || {}
                    },
                    requestSchema: {
                        $id: `http://system.catalog/schemas/requests/${operationId}`,
                        $ref: `#/definitions/${operationId}Request`,
                        $schema: "http://json-schema.org/draft-07/schema#",
                        definitions: {
                            [`${operationId}Request`]: {
                                type: "object",
                                properties: {},
                                additionalProperties: false
                            }
                        }
                    },
                    responseSchemas: {
                        $id: `http://system.catalog/schemas/responses/${operationId}`,
                        $ref: `#/definitions/${operationId}Response`,
                        $schema: "http://json-schema.org/draft-07/schema#",
                        definitions: {
                            [`${operationId}Response`]: {
                                type: "object",
                                properties: {},
                                additionalProperties: false
                            }
                        }
                    }
                };
                operations.push(operation);
            }
        });

        return operations;
    }

    /**
     * Extract address from AsyncAPI data
     */
    static extractAddressFromAsyncApiData(asyncApiData: AsyncApiData): string | null {
        // Check x-protocol first (priority over servers)
        let protocol = asyncApiData.info?.['x-protocol'];

        if (protocol) {
            // Convert protocol to URL format
            const protocolUrls: { [key: string]: string } = {
                'amqp': 'amqp://localhost:5672',
                'mqtt': 'mqtt://localhost:1883',
                'kafka': 'kafka://localhost:9092',
                'redis': 'redis://localhost:6379',
                'nats': 'nats://localhost:4222',
                'custom-protocol': 'custom-protocol://localhost'
            };

            const url = protocolUrls[protocol.toLowerCase()] || `${protocol}://localhost`;
            return url;
        }

        // Check servers if no x-protocol
        const servers = asyncApiData.servers;
        if (servers && Object.keys(servers).length > 0) {
            const firstServerKey = Object.keys(servers)[0];
            const server = servers[firstServerKey];
            if (server.url) {
                return server.url;
            }
        }

        return null;
    }
}
