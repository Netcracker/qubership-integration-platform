package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil.removeMigrationVersion;

/**
 * Reverts the #553 move of a service's type out of the document and into the file name, for
 * {@code QIP_EXPORT_LEGACY_FORMAT}: the type goes back into {@code content.integrationSystemType}, the per-type
 * {@code $schema} back to the plain service one, and {@code 105} is stripped from {@code content.migrations} so a
 * re-import runs the forward migration again instead of computing an empty version set.
 *
 * <p>The two scopes differ on purpose. {@code supportsDocument} is <b>broad</b> — every document stamped from the
 * service migration list, context and MCP services included, because {@code ContextServiceDtoMapper} stamps them from
 * that same list and a kept 105 claim makes their legacy export unimportable by an older QIP. The write is
 * <b>narrow</b>: only a document whose {@code $schema} is one of the three per-type URIs gets a type field, or a
 * context service ends up carrying a service type it never had. Gating the whole migration narrowly instead is
 * circular — {@code revert()} never runs on a document {@code supportsDocument} rejected.
 *
 * <p>The restored {@code $schema} is what V104 and V103 then see: {@code FileMigrationService} sorts reverts by
 * version descending and re-evaluates {@code supportsDocument} on each intermediate result. It does not reach the
 * exported file, because {@link V101RevertMigration} runs last and rebuilds each node from {@code id}, {@code name},
 * and the children of {@code content}; it keeps the document in the plain service shape for the rest of the chain.
 */
@Component
public class V105RevertMigration implements RevertMigration {

    private static final String SCHEMA = "$schema";
    private static final String INTEGRATION_SYSTEM_TYPE = "integrationSystemType";

    private final ServiceDocumentMatcher serviceDocumentMatcher;
    private final ServiceTypeFiles serviceTypeFiles;
    private final String serviceSchemaUri;

    @Autowired
    public V105RevertMigration(
            ServiceDocumentMatcher serviceDocumentMatcher,
            ServiceTypeFiles serviceTypeFiles,
            ApplicationJsonSchemaProperties schemas
    ) {
        this.serviceDocumentMatcher = serviceDocumentMatcher;
        this.serviceTypeFiles = serviceTypeFiles;
        this.serviceSchemaUri = schemas.getService();
    }

    @Override
    public int getVersion() {
        return 105;
    }

    @Override
    public boolean supportsDocument(ObjectNode node) {
        return serviceDocumentMatcher.matches(node);
    }

    @Override
    public ObjectNode revert(ObjectNode node) {
        if (!(node.get(CONTENT) instanceof ObjectNode)) {
            return node;
        }
        ObjectNode result = node.deepCopy();
        ObjectNode content = (ObjectNode) result.get(CONTENT);
        typeFromSchema(result).ifPresent(type -> {
            content.put(INTEGRATION_SYSTEM_TYPE, type.name());
            result.set(SCHEMA, TextNode.valueOf(serviceSchemaUri));
        });
        removeMigrationVersion(content, String.valueOf(getVersion()));
        return result;
    }

    private Optional<IntegrationSystemType> typeFromSchema(ObjectNode node) {
        JsonNode schema = node.get(SCHEMA);
        return schema != null && schema.isTextual()
                ? serviceTypeFiles.typeFromSchemaUri(schema.asText())
                : Optional.empty();
    }
}
