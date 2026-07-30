package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the import-only guard that rejects a package-less {@code .proto}. The canonical schema keeps
 * {@code package} required, so the parser refuses the source at import instead of storing an operation with a
 * fabricated {@code null.} namespace. The read path ({@code parseOperations}) still degrades gracefully and is not
 * affected by this guard.
 */
class ProtobufSpecificationParserImportTest {

    // Package-less proto3 with an inter-message reference: this is the shape that NPEs in the type-name resolver
    // once it reaches parseOperations. The guard must reject it before that.
    private static final String PACKAGE_LESS_PROTO = """
            syntax = "proto3";
            message CreateOrderRequest { string id = 1; }
            message CreateOrderResponse { CreateOrderRequest echo = 1; }
            service OrderService {
              rpc CreateOrder (CreateOrderRequest) returns (CreateOrderResponse);
            }
            """;

    private static final String PACKAGED_PROTO = """
            syntax = "proto3";
            package example.orders.v1;
            message CreateOrderRequest { string id = 1; }
            message CreateOrderResponse { CreateOrderRequest echo = 1; }
            service OrderService {
              rpc CreateOrder (CreateOrderRequest) returns (CreateOrderResponse);
            }
            """;

    private ProtobufSpecificationParser parser;

    @BeforeEach
    void setUp() {
        SystemModelRepository systemModelRepository = mock(SystemModelRepository.class);
        when(systemModelRepository.save(any(SystemModel.class))).thenAnswer(inv -> inv.getArgument(0));

        ParserUtils parserUtils = mock(ParserUtils.class);
        when(parserUtils.defineVersionName(any(), any())).thenReturn("1.0.0");
        when(parserUtils.defineVersion(any(), any())).thenReturn("1.0.0");

        parser = new ProtobufSpecificationParser(systemModelRepository, parserUtils, new ObjectMapper());
    }

    @Test
    @DisplayName("import rejects a package-less .proto, naming the file and the package requirement")
    void importRejectsPackageLessProto() {
        SpecificationSource source = protoSource("order.proto", PACKAGE_LESS_PROTO);
        ApiGroup group = group();
        List<SpecificationSource> sources = List.of(source);
        HashSet<String> processedIds = new HashSet<>();

        SpecificationImportException exception = assertThrows(SpecificationImportException.class,
                () -> parser.enrichSpecificationGroup(
                        group, sources, processedIds, false, false, message -> { }));

        assertTrue(exception.getMessage().contains("order.proto"), "message must name the offending file");
        assertTrue(exception.getMessage().contains("package"), "message must mention the package requirement");
    }

    @Test
    @DisplayName("import accepts a .proto that declares a package")
    void importAcceptsPackagedProto() {
        SpecificationSource source = protoSource("order.proto", PACKAGED_PROTO);

        SystemModel model = parser.enrichSpecificationGroup(
                group(), List.of(source), new HashSet<>(), false, false, message -> { });

        assertEquals(1, model.getOperations().size());
        assertEquals("OrderService.CreateOrder", model.getOperations().getFirst().getName());
    }

    private static ApiGroup group() {
        ApiGroup group = ApiGroup.builder().name("orders").build();
        group.setId("grp-id");
        return group;
    }

    private static SpecificationSource protoSource(String name, String proto) {
        SpecificationSource source = new SpecificationSource();
        source.setName(name);
        source.setSource(proto);
        source.setMainSource(true);
        return source;
    }
}
