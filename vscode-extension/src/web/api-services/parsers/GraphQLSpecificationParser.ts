import { EMPTY_USER } from "../../response/chainApiUtils";

export interface GraphQLData {
    type: 'GRAPHQL';
    schema: string;
    queries: GraphQLOperation[];
    mutations: GraphQLOperation[];
    subscriptions: GraphQLOperation[];
    types: GraphQLType[];
    scalars: string[];
}

export interface GraphQLOperation {
    name: string;
    arguments: string;
    returnType: string;
}

export interface GraphQLType {
    name: string;
    fields: GraphQLField[];
}

export interface GraphQLField {
    name: string;
    type: string;
}

export class GraphQLSpecificationParser {

    /**
     * Parse GraphQL content and extract operations
     */
    static async parseGraphQLContent(content: string): Promise<GraphQLData> {

        const graphqlData: GraphQLData = {
            type: 'GRAPHQL',
            schema: '',
            queries: [],
            mutations: [],
            subscriptions: [],
            types: [],
            scalars: []
        };

        // Extract type definitions (excluding Query, Mutation, Subscription)
        const typeMatches = content.matchAll(/type\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*{([\s\S]*?)}/g);
        for (const match of typeMatches) {
            const typeName = match[1];
            const typeContent = match[2];

            // Skip standard GraphQL types
            if (['Query', 'Mutation', 'Subscription'].includes(typeName)) {
                continue;
            }

            const type: GraphQLType = {
                name: typeName,
                fields: []
            };

            // Extract fields from type
            const fieldMatches = typeContent.matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*([a-zA-Z_][a-zA-Z0-9_\[\]!]*)/g);
            for (const fieldMatch of fieldMatches) {
                const fieldName = fieldMatch[1];
                const fieldType = fieldMatch[2];

                type.fields.push({
                    name: fieldName,
                    type: fieldType
                });
            }

            graphqlData.types.push(type);
        }

        // Extract Query type
        const queryMatches = content.matchAll(/type\s+Query\s*{([\s\S]*?)}/g);
        for (const match of queryMatches) {
            const queryContent = match[1];

            // Parse each line separately
            const lines = queryContent.split('\n');
            for (const line of lines) {
                const trimmedLine = line.trim();
                if (trimmedLine && !trimmedLine.startsWith('#')) {
                    // Look for: fieldName: Type or fieldName(args): Type
                    const fieldMatch = trimmedLine.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:\(([^)]*)\))?\s*:\s*(.+)/);
                    if (fieldMatch) {
                        const queryName = fieldMatch[1];
                        const args = fieldMatch[2] || '';
                        const returnType = fieldMatch[3];

                        // Check for duplicates
                        if (queryName && returnType && !graphqlData.queries.some(q => q.name === queryName)) {
                            graphqlData.queries.push({
                                name: queryName,
                                arguments: args,
                                returnType: returnType
                            });
                        }
                    }
                }
            }
        }

        // Extract Mutation type
        const mutationMatches = content.matchAll(/type\s+Mutation\s*{([\s\S]*?)}/g);
        for (const match of mutationMatches) {
            const mutationContent = match[1];

            // Parse each line separately
            const lines = mutationContent.split('\n');
            for (const line of lines) {
                const trimmedLine = line.trim();
                if (trimmedLine && !trimmedLine.startsWith('#')) {
                    // Look for: fieldName: Type or fieldName(args): Type
                    const fieldMatch = trimmedLine.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:\(([^)]*)\))?\s*:\s*(.+)/);
                    if (fieldMatch) {
                        const mutationName = fieldMatch[1];
                        const args = fieldMatch[2] || '';
                        const returnType = fieldMatch[3];

                        // Check for duplicates
                        if (mutationName && returnType && !graphqlData.mutations.some(m => m.name === mutationName)) {
                            graphqlData.mutations.push({
                                name: mutationName,
                                arguments: args,
                                returnType: returnType
                            });
                        }
                    }
                }
            }
        }

        // Extract Subscription type
        const subscriptionMatches = content.matchAll(/type\s+Subscription\s*{([\s\S]*?)}/g);
        for (const match of subscriptionMatches) {
            const subscriptionContent = match[1];

            // Parse each line separately
            const lines = subscriptionContent.split('\n');
            for (const line of lines) {
                const trimmedLine = line.trim();
                if (trimmedLine && !trimmedLine.startsWith('#')) {
                    // Look for: fieldName: Type or fieldName(args): Type
                    const fieldMatch = trimmedLine.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:\(([^)]*)\))?\s*:\s*(.+)/);
                    if (fieldMatch) {
                        const subscriptionName = fieldMatch[1];
                        const args = fieldMatch[2] || '';
                        const returnType = fieldMatch[3];

                        // Check for duplicates
                        if (subscriptionName && returnType && !graphqlData.subscriptions.some(s => s.name === subscriptionName)) {
                            graphqlData.subscriptions.push({
                                name: subscriptionName,
                                arguments: args,
                                returnType: returnType
                            });
                        }
                    }
                }
            }
        }

        // Extract scalar definitions
        const scalarMatches = content.matchAll(/scalar\s+([a-zA-Z_][a-zA-Z0-9_]*)/g);
        for (const match of scalarMatches) {
            graphqlData.scalars.push(match[1]);
        }

        return graphqlData;
    }

    /**
     * Create operations from GraphQL data
     */
    static createOperationsFromGraphQL(graphqlData: GraphQLData, specificationId: string): any[] {
        const operations: any[] = [];

        // Create operations from queries
        for (const query of graphqlData.queries) {
            const operation = {
                id: `${specificationId}-${query.name}`,
                name: query.name,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: {...EMPTY_USER},
                modifiedBy: {...EMPTY_USER},
                method: 'query',
                path: query.arguments ? `${query.name}(${query.arguments}): ${query.returnType}` : `${query.name}: ${query.returnType}`,
                specification: {
                    summary: `${query.name} query operation`,
                    operationId: query.name,
                    operation: query.arguments ? `${query.name}(${query.arguments}): ${query.returnType}` : `${query.name}: ${query.returnType}`
                }
            };

            operations.push(operation);
        }

        // Create operations from mutations
        for (const mutation of graphqlData.mutations) {
            const operation = {
                id: `${specificationId}-${mutation.name}`,
                name: mutation.name,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: {...EMPTY_USER},
                modifiedBy: {...EMPTY_USER},
                method: 'mutation',
                path: mutation.arguments ? `${mutation.name}(${mutation.arguments}): ${mutation.returnType}` : `${mutation.name}: ${mutation.returnType}`,
                specification: {
                    summary: `${mutation.name} mutation operation`,
                    operationId: mutation.name,
                    operation: mutation.arguments ? `${mutation.name}(${mutation.arguments}): ${mutation.returnType}` : `${mutation.name}: ${mutation.returnType}`
                }
            };

            operations.push(operation);
        }

        // Create operations from subscriptions
        for (const subscription of graphqlData.subscriptions) {
            const operation = {
                id: `${specificationId}-${subscription.name}`,
                name: subscription.name,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: {...EMPTY_USER},
                modifiedBy: {...EMPTY_USER},
                method: 'subscription',
                path: subscription.arguments ? `${subscription.name}(${subscription.arguments}): ${subscription.returnType}` : `${subscription.name}: ${subscription.returnType}`,
                specification: {
                    summary: `${subscription.name} subscription operation`,
                    operationId: subscription.name,
                    operation: subscription.arguments ? `${subscription.name}(${subscription.arguments}): ${subscription.returnType}` : `${subscription.name}: ${subscription.returnType}`
                }
            };

            operations.push(operation);
        }

        return operations;
    }
}
