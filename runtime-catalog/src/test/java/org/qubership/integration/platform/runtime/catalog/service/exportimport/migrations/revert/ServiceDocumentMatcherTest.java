package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDocumentMatcherTest {

    private final ServiceDocumentMatcher matcher = TestRevertMigrations.matcher();

    /**
     * A current-format service carries a per-type {@code $schema}. Every revert migration is gated on this matcher, so
     * a URI missing from its set takes V105, V104, and V103 out at once.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
            "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml"})
    @DisplayName("a service carrying a per-type $schema matches")
    void matchesAPerTypeSchema(String schemaUri) {
        ObjectNode service = GoldenServiceCorpus.read("""
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
    @DisplayName("the documents that share the service migration list keep matching")
    void matchesTheSharedDocuments(String schemaUri) {
        ObjectNode service = GoldenServiceCorpus.read("""
                ---
                id: "system-1"
                $schema: "%s"
                content:
                  migrations: "[100, 101, 102, 103, 104]"
                """.formatted(schemaUri));

        assertTrue(matcher.matches(service));
    }

    @Test
    @DisplayName("a chain document is rejected")
    void rejectsAChainDocument() {
        ObjectNode chain = GoldenServiceCorpus.read("""
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
    @DisplayName("a document with no content is rejected")
    void rejectsADocumentWithNoContent() {
        ObjectNode service = GoldenServiceCorpus.read("""
                ---
                id: "system-1"
                $schema: "http://qubership.org/schemas/product/qip/external-service.schema.yaml"
                """);

        assertFalse(matcher.matches(service));
    }
}
