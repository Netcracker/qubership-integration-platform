/**
 * Utility for getting default environment properties based on protocol
 * Based on legacy UI implementation
 */

export class EnvironmentDefaultProperties {
    /**
     * Get default properties for manual environments
     */
    static getDefaultProperties(protocol: string): Record<string, string> {
        switch (protocol?.toLowerCase()) {
            case 'amqp':
                return {
                    password: '',
                    username: '',
                    routingKey: '',
                    acknowledgeMode: 'AUTO',
                };
            case 'kafka':
                return {
                    key: '',
                    sslProtocol: '',
                    saslMechanism: '',
                    saslJaasConfig: '',
                    securityProtocol: '',
                    sslEnabledProtocols: '',
                    sslEndpointAlgorithm: '',
                };
            case 'http':
            case 'soap':
                return {
                    connectTimeout: '120000',
                    soTimeout: '120000',
                    connectionRequestTimeout: '120000',
                    responseTimeout: '120000',
                    getWithBody: 'false',
                    deleteWithBody: 'false',
                };
            case 'grpc':
                return {
                    connectTimeout: '120000',
                    soTimeout: '120000',
                };
            default:
                return {};
        }
    }

    /**
     * Get default properties for MaaS environments
     */
    static getMaasDefaultProperties(protocol: string): Record<string, string> {
        switch (protocol?.toLowerCase()) {
            case 'amqp':
                return {
                    routingKey: '',
                    acknowledgeMode: 'AUTO',
                };
            case 'kafka':
                return {
                    // MaaS environments typically have fewer properties
                    // Only essential ones are pre-filled
                };
            default:
                return {};
        }
    }
}
