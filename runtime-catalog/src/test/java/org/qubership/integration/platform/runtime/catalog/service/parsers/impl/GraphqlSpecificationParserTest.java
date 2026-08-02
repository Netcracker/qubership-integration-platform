package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Covers the persistence-free {@code parseOperations} core for GraphQL: every operation carries a typed
 * GraphqlOperation whose derived method and path reproduce the pre-typed column values.
 */
class GraphqlSpecificationParserTest {

    private static final String SDL = """
            type Query {
              customer(id: ID!): Customer
            }
            type Mutation {
              createCustomer(input: CustomerInput!): Customer!
            }
            type Customer { id: ID! name: String! }
            input CustomerInput { name: String! }
            """;

    private GraphqlSpecificationParser parser;

    @BeforeEach
    void setUp() {
        parser = new GraphqlSpecificationParser(
                null,
                null,
                new Parser(),
                ParserOptions.getDefaultOperationParserOptions(),
                new ObjectMapper());
    }

    @Test
    @DisplayName("parseOperations populates typed GraphqlOperation with derived method and path per operation")
    void parseOperationsPopulatesTypedGraphqlOperation() {
        List<Operation> operations = parser.parseOperations(SDL);

        assertEquals(2, operations.size());
        Map<String, Operation> byName = operations.stream()
                .collect(Collectors.toMap(Operation::getName, o -> o));

        Operation customer = byName.get("customer");
        GraphqlOperation customerTyped = assertInstanceOf(GraphqlOperation.class, customer.getTyped());
        assertEquals("query", customerTyped.operationType());
        assertEquals("customer(id: ID!): Customer", customerTyped.sdl());
        // Anti-regression: method is the operation type and path is the printed field AST.
        assertEquals("query", customer.getMethod());
        assertEquals("customer(id: ID!): Customer", customer.getPath());

        Operation createCustomer = byName.get("createCustomer");
        GraphqlOperation createTyped = assertInstanceOf(GraphqlOperation.class, createCustomer.getTyped());
        assertEquals("mutation", createTyped.operationType());
        assertEquals("createCustomer(input: CustomerInput!): Customer!", createTyped.sdl());
        assertEquals("mutation", createCustomer.getMethod());
        assertEquals("createCustomer(input: CustomerInput!): Customer!", createCustomer.getPath());
    }
}
