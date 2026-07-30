package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ApiOperationDto;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiOperationDtoMapperTest {

    private static final String JAVA_PACKAGE = "com.acme.payments.grpc";
    private static final String SDL = "customer(id: ID!): Customer";

    private YAMLMapper yamlMapper;
    private ApiOperationDtoMapper mapper;

    @BeforeEach
    void setUp() {
        yamlMapper = new MapperAutoConfiguration().yamlExportImportMapper();
        mapper = new ApiOperationDtoMapper();
    }

    @Test
    void exportsOpenapiOperationAsTheFlatTypedShape() throws Exception {
        Operation operation = Operation.builder()
                .id("op-1")
                .name("getPet")
                .typed(new OpenapiOperation("Add a new pet", "/pets/{id}", "get", false))
                .build();

        JsonNode exported = yamlMapper.valueToTree(mapper.toDto(operation));

        assertEquals("openapi", exported.path("type").asText());
        assertEquals("Add a new pet", exported.path("summary").asText());
        assertEquals("/pets/{id}", exported.path("path").asText());
        assertEquals("get", exported.path("method").asText(), "openapi method stays lowercase in the file");
        assertFalse(exported.path("isDeprecated").asBoolean(), "isDeprecated belongs to the openapi shape");
        assertFalse(exported.has("typed"), "the typed payload must be flattened, not nested");
        assertFalse(exported.has("operationKind"), "the file uses type, not the REST operationKind");
        assertFalse(exported.has("channel"), "channel does not belong to an openapi operation");
    }

    @Test
    void exportsTheProtobufJavaPackageAlongsideThePackage() throws Exception {
        Operation operation = Operation.builder()
                .id("op-grpc")
                .name("Authorize")
                .typed(new ProtobufOperation("acme.payments.v1", "PaymentService", "Authorize", JAVA_PACKAGE))
                .build();

        JsonNode exported = yamlMapper.valueToTree(mapper.toDto(operation));

        assertEquals("acme.payments.v1", exported.path("package").asText());
        assertEquals(JAVA_PACKAGE, exported.path("javaPackage").asText(),
                "javaPackage reconstructs path when it differs from the proto package");
        assertFalse(exported.has("path"), "path holds the package.service join, so it is not exported for protobuf");
    }

    @Test
    void exportsTheGraphqlSdlAndOperationType() throws Exception {
        Operation operation = Operation.builder()
                .id("op-gql")
                .name("customer")
                .typed(new GraphqlOperation("query", SDL))
                .build();

        JsonNode exported = yamlMapper.valueToTree(mapper.toDto(operation));

        assertEquals("query", exported.path("operationType").asText());
        assertEquals(SDL, exported.path("sdl").asText(), "sdl reconstructs the graphql path");
        assertFalse(exported.has("path"), "path holds the sdl blob, so it is not exported under path for graphql");
    }

    @Test
    void keepsTheGraphqlPathThroughAnExportImportRoundTrip() throws Exception {
        GraphqlOperation typed = new GraphqlOperation("query", SDL);
        Operation operation = Operation.builder().id("op-gql").name("customer").typed(typed).build();

        String exported = yamlMapper.writeValueAsString(mapper.toDto(operation));
        Operation restored = mapper.toEntity(yamlMapper.readValue(exported, ApiOperationDto.class));

        assertInstanceOf(GraphqlOperation.class, restored.getTyped());
        assertEquals(SDL, ((GraphqlOperation) restored.getTyped()).sdl());
        assertEquals(typed.derivePath(), restored.getPath(), "graphql path must survive the round trip");
        assertEquals(typed.deriveMethod(), restored.getMethod());
    }

    @Test
    void keepsTheProtobufPathThroughAnExportImportRoundTrip() throws Exception {
        // java_package differs from the proto package, so path derives from javaPackage, not package
        ProtobufOperation typed =
                new ProtobufOperation("acme.payments.v1", "PaymentService", "Authorize", JAVA_PACKAGE);
        Operation operation = Operation.builder().id("op-grpc").name("Authorize").typed(typed).build();

        String exported = yamlMapper.writeValueAsString(mapper.toDto(operation));
        Operation restored = mapper.toEntity(yamlMapper.readValue(exported, ApiOperationDto.class));

        assertInstanceOf(ProtobufOperation.class, restored.getTyped());
        assertEquals(JAVA_PACKAGE, ((ProtobufOperation) restored.getTyped()).javaPackage());
        assertEquals("com.acme.payments.grpc.PaymentService", restored.getPath(),
                "protobuf path derives from java_package, not the proto package, and must survive the round trip");
        assertEquals(typed.deriveMethod(), restored.getMethod());
    }

    @Test
    void keepsTheSpecificationSliceThroughAnExportImportRoundTrip() throws Exception {
        JsonNode specification = yamlMapper.createObjectNode().put("type", "object");
        Operation operation = Operation.builder()
                .id("op-1")
                .name("getPet")
                .typed(new OpenapiOperation("Add a new pet", "/pets/{id}", "get", false))
                .specification(specification)
                .build();

        String exported = yamlMapper.writeValueAsString(mapper.toDto(operation));
        ApiOperationDto reimported = yamlMapper.readValue(exported, ApiOperationDto.class);
        Operation restored = mapper.toEntity(reimported);

        assertEquals(specification, restored.getSpecification(), "the specification slice must survive the round trip");
        assertInstanceOf(OpenapiOperation.class, restored.getTyped());
        OpenapiOperation typed = (OpenapiOperation) restored.getTyped();
        assertEquals("Add a new pet", typed.summary());
        assertEquals("/pets/{id}", typed.path());
        assertEquals("get", typed.method());
        assertNull(restored.getRequestSchema(), "de-materialized schemas never travel with the DTO");
    }
}
