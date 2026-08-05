package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDocumentMatcherTest {

    private final ServiceDocumentMatcher matcher = TestRevertMigrations.matcher();
    private final YAMLMapper mapper = new MapperAutoConfiguration().yamlExportImportMapper();

    /**
     * A service exported after #553 carries a per-type {@code $schema}. Every revert migration is gated on this
     * matcher, so a URI missing from its set takes V105, V104, and V103 out at once.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml"})
    void matchesAServiceCarryingAPerTypeSchema(String schemaUri) throws JsonProcessingException {
        ObjectNode service = read("""
                ---
                id: "system-1"
                $schema: "%s"
                content:
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 103, 104, 105]"
                """.formatted(schemaUri));

        assertTrue(matcher.matches(service));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/service.schema.yaml",
            "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml"})
    void keepsMatchingTheDocumentsThatShareTheServiceMigrationList(String schemaUri) throws JsonProcessingException {
        ObjectNode service = read("""
                ---
                id: "system-1"
                $schema: "%s"
                content:
                  migrations: "[100, 101, 102, 103, 104]"
                """.formatted(schemaUri));

        assertTrue(matcher.matches(service));
    }

    @Test
    void rejectsAChainDocument() throws JsonProcessingException {
        ObjectNode chain = read("""
                ---
                id: "chain-1"
                $schema: "http://qubership.org/schemas/product/qip/chain.schema.yaml"
                content:
                  elements: []
                  migrations: "[103, 104, 108]"
                """);

        assertFalse(matcher.matches(chain), "a chain numbers its own migrations independently");
    }

    @Test
    void rejectsADocumentWithNoContent() throws JsonProcessingException {
        ObjectNode service = read("""
                ---
                id: "system-1"
                $schema: "http://qubership.org/schemas/product/qip/external-service.schema.yaml"
                """);

        assertFalse(matcher.matches(service));
    }

    private ObjectNode read(String yaml) throws JsonProcessingException {
        JsonNode node = mapper.readTree(yaml);
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) node;
    }
}
