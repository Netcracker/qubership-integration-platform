// A unit case for the GraphQL document shape the golden import cannot carry: an SDL with no
// `schema` definition.
//
// `tests/web/import/graphql.test.ts` imports a fixture that declares `schema { query: OrderQuery,
// mutation: OrderMutation }`, which is what takes `resolveOperationTypeName` through its mapping
// arm and `extractSchemaSDL` through `print`. Most SDL in the wild declares neither and relies on
// the `Query` and `Mutation` root types by name, and that path — `extractSchemaSDL`'s empty return
// and `resolveOperationTypeName`'s fallback — is the one no fixture with a `schema` definition can
// reach. Adding a second fixture would mean a second golden, so the case goes straight onto the
// parser.
//
// One arm stays white after this case: `mapFieldsToOperations`'s `!objectType.fields` guard.
// `graphql`'s `parse` gives an object type with no field block an empty `fields` array rather than
// none, so no document reaches it.
import { GraphQLSpecificationParser } from "../../../src/web/api-services/parsers/GraphQLSpecificationParser";

const SCHEMALESS_SDL = `
type Query {
  orders(limit: Int = 20): [Order!]!
}

type Mutation {
  createOrder(name: String!): Order
}

type Order {
  id: ID!
  name: String
}
`;

describe("GraphQLSpecificationParser", () => {
  it("falls back to the Query and Mutation root types when no schema definition is declared", async () => {
    const data = await GraphQLSpecificationParser.parseGraphQLContent(
      SCHEMALESS_SDL,
    );

    expect(data.schema).toBe("");
    expect(data.queries.map((query) => query.name)).toEqual(["orders"]);
    expect(data.mutations.map((mutation) => mutation.name)).toEqual([
      "createOrder",
    ]);

    const operations = GraphQLSpecificationParser.createOperationsFromGraphQL(
      data,
      "spec-1",
    );
    expect(operations).toEqual([
      {
        id: "spec-1-orders",
        name: "orders",
        method: "query",
        path: "orders",
        specification: { operation: "orders(limit: Int = 20): [Order!]!" },
      },
      {
        id: "spec-1-createOrder",
        name: "createOrder",
        method: "mutation",
        path: "createOrder",
        specification: { operation: "createOrder(name: String!): Order" },
      },
    ]);
  });
});
