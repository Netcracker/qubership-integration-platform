package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.model.exportimport.system.ApiOperationDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V103ServiceImportFileMigrationTest {

    private final ApiOperationDtoMapper apiOperationDtoMapper = new ApiOperationDtoMapper();
    private final V103ServiceImportFileMigration migration =
            new V103ServiceImportFileMigration(apiOperationDtoMapper);
    private final YAMLMapper mapper = new YAMLMapper();

    @Test
    void turnsAnOpenapiSpecificationNodeIntoAnApiNode() throws JsonProcessingException {
        ObjectNode result = migrate("""
                ---
                id: "spec-1"
                name: "1.0.0"
                migrationProtocol: "HTTP"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "group-1"
                  operations:
                  - id: "op-1"
                    name: "addPet"
                    method: "GET"
                    path: "/pets"
                    specification:
                      summary: "List pets"
                    requestSchema:
                      body: {}
                    responseSchemas:
                      "200": {}
                  specificationSources:
                  - id: "src-1"
                    name: "api.yaml"
                    fileName: "source-spec-1/api.yaml"
                    mainSource: true
                """);

        assertEquals("openapi", result.path("content").path("specificationType").asText());

        JsonNode operation = result.path("content").path("operations").path(0);
        assertEquals("openapi", operation.path("type").asText());
        assertEquals("op-1", operation.path("id").asText());
        assertEquals("addPet", operation.path("name").asText());
        assertEquals("get", operation.path("method").asText(), "openapi method is lowercased for the schema enum");
        assertEquals("/pets", operation.path("path").asText());
        assertEquals("List pets", operation.path("summary").asText());
        assertFalse(operation.has("requestSchema"), "de-materialized request schema is dropped");
        assertFalse(operation.has("responseSchemas"), "de-materialized response schemas are dropped");
        assertEquals("List pets", operation.path("specification").path("summary").asText(),
                "the specification slice (MaaS classifier home) survives");
    }

    @Test
    void renamesSourceFieldsToTheApiShape() throws JsonProcessingException {
        ObjectNode content = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "HTTP"
                content:
                  specificationSources:
                  - id: "src-1"
                    name: "api.yaml"
                    fileName: "source-spec-1/api.yaml"
                    mainSource: true
                """).path("content").deepCopy();

        assertFalse(content.has("specificationSources"), "the old array name is gone");
        JsonNode source = content.path("specifications").path(0);
        assertEquals("source-spec-1/api.yaml", source.path("filePath").asText());
        assertTrue(source.path("isRoot").asBoolean());
        assertFalse(source.has("fileName"));
        assertFalse(source.has("mainSource"));
        assertEquals("src-1", source.path("id").asText(), "the source id survives as a QIP field");
    }

    @Test
    void typesAsyncapiOperationsByChannelAndMethod() throws JsonProcessingException {
        JsonNode operation = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "KAFKA"
                content:
                  operations:
                  - id: "op-1"
                    method: "publish"
                    path: "user/notify"
                """).path("content").path("operations").path(0);

        assertEquals("asyncapi", operation.path("type").asText());
        assertEquals("publish", operation.path("method").asText());
        assertEquals("user/notify", operation.path("channel").asText());
        assertFalse(operation.has("path"), "asyncapi carries a channel, not a path");
    }

    @Test
    void typesSoapOperationsAsWsdlWithSystemProtocolAndNullBinding() throws JsonProcessingException {
        // WSDL binding is reparse-only and stays absent, but the column-derived backfill fills protocol from the
        // system protocol (SOAP). The type discriminator and the POST/"" derivation still hold.
        JsonNode operation = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "SOAP"
                content:
                  operations:
                  - id: "op-1"
                    name: "sayHello"
                    method: "POST"
                    path: ""
                    specification:
                      summary: "Say hello"
                """).path("content").path("operations").path(0);

        assertEquals("wsdl", operation.path("type").asText());
        assertEquals("SOAP", operation.path("protocol").asText(), "protocol is backfilled from the system protocol");
        assertFalse(operation.has("binding"), "binding is reparse-only and stays absent in the node");
        assertFalse(operation.has("method"), "wsdl carries neither method nor path in the node");
        assertFalse(operation.has("path"));
        assertEquals("Say hello", operation.path("specification").path("summary").asText(),
                "the specification slice survives");

        Operation reconstructed = reconstruct(operation);
        assertEquals("POST", reconstructed.getMethod(), "wsdl derives the constant POST method");
        assertEquals("", reconstructed.getPath(), "wsdl derives the empty path");
    }

    @Test
    void typesGraphqlOperationsByOperationType() throws JsonProcessingException {
        JsonNode operation = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "GRAPHQL"
                content:
                  operations:
                  - id: "op-1"
                    name: "products"
                    method: "query"
                    path: "products: [Product]"
                """).path("content").path("operations").path(0);

        assertEquals("graphql", operation.path("type").asText());
        assertEquals("query", operation.path("operationType").asText(), "the legacy method column becomes operationType");
        assertFalse(operation.has("method"), "graphql carries operationType, not method");
        assertFalse(operation.has("path"), "the graphql sdl is server-only and not exported");

        Operation reconstructed = reconstruct(operation);
        assertEquals("query", reconstructed.getMethod(), "graphql method derives from operationType");
        assertNull(reconstructed.getPath(), "graphql path derives from the server-only sdl, absent after export");
    }

    @Test
    void typesGrpcOperationsByServiceAndRpcMethod() throws JsonProcessingException {
        JsonNode operation = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "GRPC"
                content:
                  operations:
                  - id: "op-1"
                    name: "Pay"
                    method: "Pay"
                    path: "payments.PaymentService"
                    specification:
                      responses:
                        "200":
                          content:
                            application/json:
                              schema:
                                $id: "http://system.catalog/schemas/responses/payments.PaymentService.Pay"
                """).path("content").path("operations").path(0);

        assertEquals("protobuf", operation.path("type").asText());
        assertEquals("PaymentService", operation.path("service").asText());
        assertEquals("Pay", operation.path("rpcMethod").asText());
        assertEquals("payments", operation.path("package").asText(), "the proto package is recovered from the $id tail");
        assertFalse(operation.has("method"), "protobuf carries rpcMethod, not method");
        assertFalse(operation.has("path"), "protobuf path is derived from package.service, not stored");

        Operation reconstructed = reconstruct(operation);
        assertEquals("Pay", reconstructed.getMethod(), "grpc method derives from rpcMethod");
        assertEquals("payments.PaymentService", reconstructed.getPath(), "grpc path derives from package.service");
    }

    @Test
    void readsTheProtocolFromContentAfterV101HasRelocatedIt() throws JsonProcessingException {
        // A pre-V101 archive has V101 move every root field, the scratch protocol included, under content.
        JsonNode operation = migrate("""
                ---
                id: "spec-1"
                content:
                  migrationProtocol: "HTTP"
                  operations:
                  - id: "op-1"
                    method: "POST"
                    path: "/pets"
                """).path("content").path("operations").path(0);

        assertEquals("openapi", operation.path("type").asText());
        assertEquals("post", operation.path("method").asText());
    }

    @Test
    void removesTheScratchProtocolField() throws JsonProcessingException {
        ObjectNode result = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "HTTP"
                content:
                  operations: []
                """);

        assertFalse(result.has("migrationProtocol"));
        assertFalse(result.path("content").has("migrationProtocol"));
    }

    @Test
    void leavesAServiceDocumentUntouched() throws JsonProcessingException {
        // The service document runs through the same list and carries content.protocol; without a scratch field
        // V103 must not mistake that real protocol for its typing signal.
        ObjectNode node = read("""
                ---
                id: "system-1"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                """);

        ObjectNode result = migration.makeMigration(node);

        assertEquals(node, result, "the service document is returned unchanged");
        assertFalse(result.path("content").has("specificationType"));
    }

    @Test
    void leavesASpecificationGroupDocumentUntouched() throws JsonProcessingException {
        ObjectNode node = read("""
                ---
                id: "group-1"
                content:
                  synchronization: false
                  parentId: "system-1"
                """);

        ObjectNode result = migration.makeMigration(node);

        assertEquals(node, result, "the specification group document is returned unchanged");
        assertFalse(result.path("content").has("specificationType"));
    }

    @Test
    void stripsTheScratchProtocolWhenContentIsNotAnObject() throws JsonProcessingException {
        // A malformed model node hits the content early return; the scratch field must still not leak into a file.
        ObjectNode result = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "HTTP"
                content: "not-an-object"
                """);

        assertFalse(result.has("migrationProtocol"), "the scratch field is stripped on the early return");
        assertEquals("not-an-object", result.path("content").asText(), "the malformed content is left as-is");
    }

    @Test
    void stripsTheScratchProtocolWhenTheProtocolIsUnparseable() throws JsonProcessingException {
        ObjectNode result = migrate("""
                ---
                id: "spec-1"
                migrationProtocol: "NONSENSE"
                content:
                  operations: []
                """);

        assertFalse(result.has("migrationProtocol"), "an unparseable protocol still gets its scratch field stripped");
        assertFalse(result.path("content").has("specificationType"), "nothing is typed");
    }

    private ObjectNode migrate(String yaml) throws JsonProcessingException {
        return migration.makeMigration(read(yaml));
    }

    // Rebuilds the entity from a migrated operation node so getMethod/getPath expose the derived columns.
    private Operation reconstruct(JsonNode operationNode) throws JsonProcessingException {
        ApiOperationDto dto = mapper.treeToValue(operationNode, ApiOperationDto.class);
        return apiOperationDtoMapper.toEntity(dto);
    }

    private ObjectNode read(String yaml) throws JsonProcessingException {
        JsonNode node = mapper.readTree(yaml);
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) node;
    }
}
