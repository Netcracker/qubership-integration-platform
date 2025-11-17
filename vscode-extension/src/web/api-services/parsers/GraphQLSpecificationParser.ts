import {
    DefinitionNode,
    DocumentNode,
    Kind,
    ObjectTypeDefinitionNode,
    parse,
    print,
    SchemaDefinitionNode
} from "graphql";
import { GraphQLData, GraphQLOperation, GraphQLType } from "./parserTypes";

export class GraphQLSpecificationParser {
    static async parseGraphQLContent(content: string): Promise<GraphQLData> {
        const document = parse(content);

        const schema = this.extractSchemaSDL(document);
        const queryTypeName = this.resolveOperationTypeName(document, "query");
        const mutationTypeName = this.resolveOperationTypeName(document, "mutation");

        const { queries, mutations } = this.collectOperations(document, queryTypeName, mutationTypeName);
        const types = this.collectObjectTypes(document, [queryTypeName, mutationTypeName]);
        const scalars = this.collectScalars(document);

        return {
            type: "GRAPHQL",
            schema,
            queries,
            mutations,
            types,
            scalars
        };
    }

    static createOperationsFromGraphQL(graphqlData: GraphQLData, specificationId: string): any[] {
        const operations: any[] = [];

        for (const query of graphqlData.queries) {
            operations.push(this.buildOperation(specificationId, "query", query));
        }

        for (const mutation of graphqlData.mutations) {
            operations.push(this.buildOperation(specificationId, "mutation", mutation));
        }

        return operations;
    }

    private static buildOperation(specificationId: string, method: string, operation: GraphQLOperation) {
        return {
            id: `${specificationId}-${operation.name}`,
            name: operation.name,
            method,
            path: operation.name,
            specification: {
                operation: operation.sdl
            }
        };
    }

    private static extractSchemaSDL(document: DocumentNode): string {
        const typeDefs = document.definitions.filter(
            (definition) => definition.kind === Kind.SCHEMA_DEFINITION || definition.kind === Kind.SCHEMA_EXTENSION
        );

        if (typeDefs.length === 0) {
            return "";
        }

        const schemaDocument: DocumentNode = {
            kind: Kind.DOCUMENT,
            definitions: typeDefs as DefinitionNode[]
        };

        return print(schemaDocument).trim();
    }

    private static resolveOperationTypeName(document: DocumentNode, operation: OperationType): string {
        const schemaDef = document.definitions.find(
            (definition) => definition.kind === Kind.SCHEMA_DEFINITION
        ) as SchemaDefinitionNode | undefined;

        if (schemaDef) {
            const mapping = schemaDef.operationTypes.find((type) => type.operation === operation);
            if (mapping) {
                return mapping.type.name.value;
            }
        }

        const fallbackName = operation === "query" ? "Query" : "Mutation";
        const typeDef = document.definitions.find(
            (definition) =>
                definition.kind === Kind.OBJECT_TYPE_DEFINITION && definition.name.value === fallbackName
        );

        return typeDef ? fallbackName : "";
    }

    private static collectOperations(
        document: DocumentNode,
        queryTypeName: string,
        mutationTypeName: string
    ): { queries: GraphQLOperation[]; mutations: GraphQLOperation[] } {
        const queries: GraphQLOperation[] = [];
        const mutations: GraphQLOperation[] = [];

        for (const definition of document.definitions) {
            if (definition.kind !== Kind.OBJECT_TYPE_DEFINITION) {
                continue;
            }

            const objectType = definition as ObjectTypeDefinitionNode;
            const name = objectType.name.value;

            if (name === queryTypeName) {
                queries.push(...this.mapFieldsToOperations(objectType));
            }
            if (name === mutationTypeName) {
                mutations.push(...this.mapFieldsToOperations(objectType));
            }
        }

        return { queries, mutations };
    }

    private static mapFieldsToOperations(objectType: ObjectTypeDefinitionNode): GraphQLOperation[] {
        if (!objectType.fields) {
            return [];
        }

        return objectType.fields.map((field) => ({
            name: field.name.value,
            sdl: print(field).trim()
        }));
    }

    private static collectObjectTypes(document: DocumentNode, excludedNames: string[]): GraphQLType[] {
        const types: GraphQLType[] = [];

        for (const definition of document.definitions) {
            if (definition.kind !== Kind.OBJECT_TYPE_DEFINITION) {
                continue;
            }

            const objectType = definition as ObjectTypeDefinitionNode;
            if (excludedNames.includes(objectType.name.value)) {
                continue;
            }

            types.push({
                name: objectType.name.value,
                sdl: print(objectType).trim()
            });
        }

        return types;
    }

    private static collectScalars(document: DocumentNode): string[] {
        return document.definitions
            .filter((definition) => definition.kind === Kind.SCALAR_TYPE_DEFINITION)
            .map((definition) => (definition as any).name.value);
    }
}

type OperationType = "query" | "mutation";
