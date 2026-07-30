package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the persistence-free {@code parseOperations} core for protobuf. The specification slice is
 * always produced (it embeds schemas inline — the documented protobuf limitation); the separate
 * request/response schema fields are only populated when {@code withSchemas=true}.
 */
class ProtobufSpecificationParserCoreTest {

    private static final String PROTO = """
            syntax = "proto3";
            package example;
            message HelloRequest { string name = 1; }
            message HelloReply { string message = 1; }
            service Greeter {
              rpc SayHello (HelloRequest) returns (HelloReply);
            }
            """;

    private static final String PROTO_WITH_JAVA_PACKAGE = """
            syntax = "proto3";
            package acme.payments.v1;
            option java_package = "com.acme.payments.grpc";
            message PayRequest { string id = 1; }
            message PayReply { bool ok = 1; }
            service PaymentService {
              rpc Authorize (PayRequest) returns (PayReply);
            }
            """;

    private ProtobufSpecificationParser parser;

    @BeforeEach
    void setUp() {
        parser = new ProtobufSpecificationParser(null, null, new ObjectMapper());
    }

    private static SpecificationSource protoSource() {
        return protoSource(PROTO, "greeter.proto");
    }

    private static SpecificationSource protoSource(String proto, String name) {
        SpecificationSource source = new SpecificationSource();
        source.setName(name);
        source.setSource(proto);
        return source;
    }

    @Test
    @DisplayName("parseOperations populates typed ProtobufOperation, javaPackage falling back to the proto package")
    void parseOperationsPopulatesTypedProtobufOperation() {
        List<Operation> operations = parser.parseOperations(List.of(protoSource()), false);

        assertEquals(1, operations.size());
        Operation operation = operations.getFirst();

        ProtobufOperation typed = assertInstanceOf(ProtobufOperation.class, operation.getTyped());
        assertEquals("example", typed.packageName());
        assertEquals("Greeter", typed.service());
        assertEquals("SayHello", typed.rpcMethod());
        assertEquals("example", typed.javaPackage());

        // Anti-regression: derived method and path must equal the pre-typed column values.
        assertEquals("SayHello", operation.getMethod());
        assertEquals("example.Greeter", operation.getPath());
    }

    @Test
    @DisplayName("parseOperations reads javaPackage from the java_package option and builds path from it")
    void parseOperationsReadsJavaPackageOption() {
        List<Operation> operations =
                parser.parseOperations(List.of(protoSource(PROTO_WITH_JAVA_PACKAGE, "payments.proto")), false);

        assertEquals(1, operations.size());
        Operation operation = operations.getFirst();

        ProtobufOperation typed = assertInstanceOf(ProtobufOperation.class, operation.getTyped());
        assertEquals("acme.payments.v1", typed.packageName());
        assertEquals("PaymentService", typed.service());
        assertEquals("Authorize", typed.rpcMethod());
        assertEquals("com.acme.payments.grpc", typed.javaPackage());

        // Anti-regression: path is built from java_package, not the proto package.
        assertEquals("Authorize", operation.getMethod());
        assertEquals("com.acme.payments.grpc.PaymentService", operation.getPath());
    }

    @Test
    @DisplayName("parseOperations(withSchemas=true) produces structure and request/response schemas")
    void parseOperationsWithSchemasProducesSchemas() {
        List<Operation> operations = parser.parseOperations(List.of(protoSource()), true);

        assertEquals(1, operations.size());
        Operation operation = operations.getFirst();
        assertEquals("Greeter.SayHello", operation.getName());
        assertEquals("SayHello", operation.getMethod());
        assertNotNull(operation.getSpecification());
        assertNotNull(operation.getRequestSchema());
        assertNotNull(operation.getResponseSchemas());
    }

    @Test
    @DisplayName("parseOperations(withSchemas=false) keeps structure and slice but leaves schema fields null")
    void parseOperationsWithoutSchemasKeepsStructureOnly() {
        List<Operation> withSchemas = parser.parseOperations(List.of(protoSource()), true);
        List<Operation> withoutSchemas = parser.parseOperations(List.of(protoSource()), false);

        assertEquals(withSchemas.size(), withoutSchemas.size());
        Operation full = withSchemas.getFirst();
        Operation structural = withoutSchemas.getFirst();

        assertEquals(full.getName(), structural.getName());
        assertEquals(full.getPath(), structural.getPath());
        assertEquals(full.getMethod(), structural.getMethod());
        assertEquals(full.getSpecification(), structural.getSpecification());

        assertNull(structural.getRequestSchema());
        assertNull(structural.getResponseSchemas());
    }
}
