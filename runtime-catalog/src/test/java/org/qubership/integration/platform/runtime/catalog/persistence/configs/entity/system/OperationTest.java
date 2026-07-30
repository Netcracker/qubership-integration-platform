package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OperationTest {

    @Test
    void everyAccessorReturnsNullWhenTypedIsNull() {
        Operation operation = new Operation();

        assertNull(operation.getTyped());
        assertNull(operation.getOperationKind());
        assertNull(operation.getChannel());
        assertNull(operation.getSummary());
        assertNull(operation.getIsDeprecated());
        assertNull(operation.getOperationType());
        assertNull(operation.getBinding());
        assertNull(operation.getRpcMethod());
        assertNull(operation.getPackage());
        assertNull(operation.getService());
    }

    @Test
    void openapiAccessorsDelegateAndOtherProtocolsStayNull() {
        Operation operation = new Operation();
        operation.setTyped(new OpenapiOperation("Add a new pet", "/pet", "post", true));

        assertEquals("openapi", operation.getOperationKind());
        assertEquals("Add a new pet", operation.getSummary());
        assertEquals(Boolean.TRUE, operation.getIsDeprecated());

        assertNull(operation.getChannel());
        assertNull(operation.getOperationType());
        assertNull(operation.getBinding());
        assertNull(operation.getRpcMethod());
        assertNull(operation.getPackage());
        assertNull(operation.getService());
    }

    @Test
    void asyncapiAccessorsDelegate() {
        Operation operation = new Operation();
        operation.setTyped(new AsyncapiOperation("Order placed", "orders", "publish"));

        assertEquals("asyncapi", operation.getOperationKind());
        assertEquals("Order placed", operation.getSummary());
        assertEquals("orders", operation.getChannel());
        assertNull(operation.getIsDeprecated());
    }

    @Test
    void graphqlAccessorsDelegate() {
        Operation operation = new Operation();
        operation.setTyped(new GraphqlOperation("query", "customer(id: ID!): Customer"));

        assertEquals("graphql", operation.getOperationKind());
        assertEquals("query", operation.getOperationType());
        assertNull(operation.getChannel());
        assertNull(operation.getSummary());
    }

    @Test
    void wsdlAccessorsDelegate() {
        Operation operation = new Operation();
        operation.setTyped(new WsdlOperation("http", "PetBinding"));

        assertEquals("wsdl", operation.getOperationKind());
        assertEquals("PetBinding", operation.getBinding());
        assertNull(operation.getRpcMethod());
    }

    @Test
    void protobufAccessorsDelegate() {
        Operation operation = new Operation();
        operation.setTyped(new ProtobufOperation(
                "acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc"));

        assertEquals("protobuf", operation.getOperationKind());
        assertEquals("acme.payments.v1", operation.getPackage());
        assertEquals("PaymentService", operation.getService());
        assertEquals("Authorize", operation.getRpcMethod());
        assertNull(operation.getChannel());
    }
}
