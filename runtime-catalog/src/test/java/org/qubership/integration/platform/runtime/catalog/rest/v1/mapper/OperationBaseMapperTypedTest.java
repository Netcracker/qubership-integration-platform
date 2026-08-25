package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.OperationBaseDTO;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class OperationBaseMapperTypedTest {

    private final OperationBaseMapper mapper = createMapper();

    private static OperationBaseMapper createMapper() {
        OperationBaseMapper mapper = Mappers.getMapper(OperationBaseMapper.class);
        // ChainBaseMapper is Spring-autowired; wire it by hand since these tests run without a context.
        // The flat-field cases never set chains, so a plain instance that returns null for null input suffices.
        ReflectionTestUtils.setField(mapper, "chainBaseMapper", Mappers.getMapper(ChainBaseMapper.class));
        return mapper;
    }

    private OperationBaseDTO toDTO(TypedOperation typed) {
        Operation operation = new Operation();
        operation.setTyped(typed);
        return mapper.toDTO(operation);
    }

    @Test
    void openapiExposesSummaryAndDeprecationOnly() {
        OperationBaseDTO dto = toDTO(new OpenapiOperation("Add a new pet", "/pet", "post", false));

        assertEquals("openapi", dto.getOperationKind());
        assertEquals("Add a new pet", dto.getSummary());
        assertFalse(dto.getIsDeprecated());
        assertEquals("POST", dto.getMethod());
        assertEquals("/pet", dto.getPath());

        assertNull(dto.getChannel());
        assertNull(dto.getOperationType());
        assertNull(dto.getBinding());
        assertNull(dto.getRpcMethod());
        assertNull(dto.getPackage());
        assertNull(dto.getService());
    }

    @Test
    void asyncapiExposesChannelAndSummaryOnly() {
        OperationBaseDTO dto = toDTO(new AsyncapiOperation("Publish an event", "orders.created", "publish"));

        assertEquals("asyncapi", dto.getOperationKind());
        assertEquals("orders.created", dto.getChannel());
        assertEquals("Publish an event", dto.getSummary());
        assertEquals("publish", dto.getMethod());
        assertEquals("orders.created", dto.getPath());

        assertNull(dto.getIsDeprecated());
        assertNull(dto.getOperationType());
        assertNull(dto.getBinding());
        assertNull(dto.getRpcMethod());
        assertNull(dto.getPackage());
        assertNull(dto.getService());
    }

    @Test
    void wsdlExposesBindingOnly() {
        OperationBaseDTO dto = toDTO(new WsdlOperation("SOAP", "PetServiceSoap"));

        assertEquals("wsdl", dto.getOperationKind());
        assertEquals("PetServiceSoap", dto.getBinding());
        assertEquals("POST", dto.getMethod());
        assertEquals("", dto.getPath());

        assertNull(dto.getChannel());
        assertNull(dto.getSummary());
        assertNull(dto.getIsDeprecated());
        assertNull(dto.getOperationType());
        assertNull(dto.getRpcMethod());
        assertNull(dto.getPackage());
        assertNull(dto.getService());
    }

    @Test
    void graphqlExposesOperationTypeOnly() {
        OperationBaseDTO dto = toDTO(new GraphqlOperation("query", "customer(id: ID!): Customer"));

        assertEquals("graphql", dto.getOperationKind());
        assertEquals("query", dto.getOperationType());
        assertEquals("query", dto.getMethod());
        assertEquals("customer(id: ID!): Customer", dto.getPath());

        assertNull(dto.getChannel());
        assertNull(dto.getSummary());
        assertNull(dto.getIsDeprecated());
        assertNull(dto.getBinding());
        assertNull(dto.getRpcMethod());
        assertNull(dto.getPackage());
        assertNull(dto.getService());
    }

    @Test
    void protobufExposesPackageServiceAndRpcMethodOnly() {
        OperationBaseDTO dto = toDTO(new ProtobufOperation(
                "acme.payments.v1", "PaymentService", "Authorize", "com.acme.payments.grpc"));

        assertEquals("protobuf", dto.getOperationKind());
        assertEquals("acme.payments.v1", dto.getPackage());
        assertEquals("PaymentService", dto.getService());
        assertEquals("Authorize", dto.getRpcMethod());
        assertEquals("Authorize", dto.getMethod());
        assertEquals("com.acme.payments.grpc.PaymentService", dto.getPath());

        assertNull(dto.getChannel());
        assertNull(dto.getSummary());
        assertNull(dto.getIsDeprecated());
        assertNull(dto.getOperationType());
        assertNull(dto.getBinding());
    }
}
