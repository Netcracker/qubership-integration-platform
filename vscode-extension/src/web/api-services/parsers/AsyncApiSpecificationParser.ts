import { ContentParser } from './ContentParser';
import { AsyncApiOperationResolver } from './async/AsyncApiOperationResolver';
import { AsyncApiData } from './parserTypes';

export class AsyncApiSpecificationParser {
    /**
     * Parse AsyncAPI content
     */
    static async parseAsyncApiContent(content: string): Promise<AsyncApiData> {
        const specData = ContentParser.parseContentWithErrorHandling(content, 'AsyncApiSpecificationParser');

        if (!specData.asyncapi) {
            throw new Error('Not a valid AsyncAPI specification');
        }

        return specData as AsyncApiData;
    }

    /**
     * Create operations from AsyncAPI data
     */
    static createOperationsFromAsyncApi(asyncApiData: AsyncApiData, specificationId: string): any[] {
        const operations: any[] = [];

        if (!asyncApiData.channels) {
            return operations;
        }

        const protocol = this.resolveProtocol(asyncApiData);

        const operationResolver = new AsyncApiOperationResolver();

        // Process each channel
        Object.entries(asyncApiData.channels).forEach(([channelName, channel]) => {
            // Publish operations
            if (channel.publish) {
                const operationId = channel.publish.operationId || `publish-${channelName}`;
                const resolvedData = operationResolver.resolve(
                    protocol,
                    channelName,
                    operationId,
                    channel,
                    channel.publish,
                    asyncApiData.components
                );
                const operation = {
                    id: `${specificationId}-${operationId}`,
                    name: operationId,
                    method: 'publish',
                    path: channelName,
                    specification: resolvedData.specification,
                    requestSchema: resolvedData.requestSchemas,
                    responseSchemas: resolvedData.responseSchemas
                };
                operations.push(operation);
            }

            // Subscribe operations
            if (channel.subscribe) {
                const operationId = channel.subscribe.operationId || `subscribe-${channelName}`;
                const resolvedData = operationResolver.resolve(
                    protocol,
                    channelName,
                    operationId,
                    channel,
                    channel.subscribe,
                    asyncApiData.components
                );
                const operation = {
                    id: `${specificationId}-${operationId}`,
                    name: operationId,
                    method: 'subscribe',
                    path: channelName,
                    specification: resolvedData.specification,
                    requestSchema: resolvedData.requestSchemas,
                    responseSchemas: resolvedData.responseSchemas
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
        if (servers) {
            const mainServer = (servers as Record<string, { url?: string }>).main;
            if (mainServer?.url) {
                return mainServer.url;
            }

            const serverEntries = Object.entries(servers as Record<string, { url?: string }>);
            if (serverEntries.length > 0) {
                const [, firstServer] = serverEntries[0];
                if (firstServer?.url) {
                    return firstServer.url;
                }
            }
        }

        return null;
    }

    private static resolveProtocol(asyncApiData: AsyncApiData): string {
        const infoProtocol = asyncApiData.info?.['x-protocol'];
        if (infoProtocol) {
            return infoProtocol.toLowerCase();
        }

        const servers = asyncApiData.servers;
        if (servers) {
            const mainServer = (servers as Record<string, { protocol?: string }>).main;
            if (mainServer?.protocol) {
                return mainServer.protocol.toLowerCase();
            }

            const serverEntries = Object.values(servers) as Array<{ protocol?: string }>;
            if (serverEntries.length > 0 && serverEntries[0]?.protocol) {
                return serverEntries[0].protocol!.toLowerCase();
            }
        }

        return 'unknown';
    }
}
