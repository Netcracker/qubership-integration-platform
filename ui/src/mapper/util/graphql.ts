import {
  getIntrospectionQuery,
  graphqlSync,
  IntrospectionQuery,
  Kind,
  OperationTypeNode,
  parse,
  visit,
  SourceLocation,
  OperationDefinitionNode,
} from "graphql/index";
import { GraphQLError } from "graphql/error/GraphQLError";
import { GraphQLSchema } from "graphql";

export type GraphQLOperationType = "query" | "mutation";

export type GraphQLOperationInfo = {
  type: GraphQLOperationType;
  name?: string;
  source: string;
};

export type GraphQLRootFieldInfo = {
  name: string;
  location: SourceLocation;
};

export class GraphQLUtil {
  public static getOperationInfo(
    schema: GraphQLSchema,
  ): GraphQLOperationInfo[] {
    const introspectionData = graphqlSync({
      schema,
      source: getIntrospectionQuery(),
    }).data as unknown as IntrospectionQuery;
    const mutationTypeName = introspectionData.__schema.mutationType?.name;
    const queryTypeName = introspectionData.__schema.queryType?.name;
    return introspectionData.__schema.types
      .filter((type) => type.kind === "OBJECT")
      .flatMap((type) => {
        const operationType: GraphQLOperationType | null =
          mutationTypeName && type.name === mutationTypeName
            ? "mutation"
            : queryTypeName && type.name === queryTypeName
              ? "query"
              : null;
        return operationType
          ? type.fields.map((field) => ({
              type: operationType,
              name: field.name,
              source: "",
            }))
          : [];
      });
  }

  public static getOperationInfoFromQuery(
    queryText: string,
  ): GraphQLOperationInfo[] {
    const ast = parse(queryText);
    const operations: GraphQLOperationInfo[] = [];
    visit(ast, {
      OperationDefinition(node: OperationDefinitionNode) {
        if (
          node.operation === OperationTypeNode.QUERY ||
          node.operation === OperationTypeNode.MUTATION
        ) {
          operations.push({
            type: node.operation as GraphQLOperationType,
            name: node.name?.value,
            source: queryText.substring(
              node.loc?.start ?? 0,
              node.loc?.end ?? 0,
            ),
          });
        }
      },
    });
    return operations;
  }

  public static getRootFields(
    queryText: string,
    operationName: string,
  ): string[] {
    const ast = parse(queryText);
    const result: string[] = [];
    visit(ast, {
      OperationDefinition(node: OperationDefinitionNode) {
        if (
          !operationName ||
          node.name?.value === operationName ||
          (!node.name?.value && !operationName)
        ) {
          node.selectionSet.selections.forEach((n) => {
            if (n.kind !== Kind.FIELD) {
              throw new GraphQLError("Fragments in query not supported", {
                nodes: n,
              });
            }
            result.push(n.name.value);
          });
        }
      },
    });
    return result;
  }
}
