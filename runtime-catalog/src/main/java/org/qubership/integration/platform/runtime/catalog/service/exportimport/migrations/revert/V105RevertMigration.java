package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.qubership.integration.platform.io.readers.migrations.revert.RevertMigration;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.INTEGRATION_SYSTEM_TYPE;
import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.removeMigrationVersion;

/**
 * Puts a service's type back into {@code content.integrationSystemType} for {@code QIP_EXPORT_LEGACY_FORMAT}, restores
 * the plain service {@code $schema}, and strips {@code 105} from {@code content.migrations} so a re-import runs the
 * forward migration again instead of computing an empty version set.
 *
 * <p>The two scopes differ on purpose. {@code supportsDocument} is <b>broad</b>: every document stamped from the
 * service migration list, context and MCP services included, because {@code ContextServiceDtoMapper} stamps them from
 * that same list and a kept 105 claim makes their legacy export unimportable by an older QIP. The write is
 * <b>narrow</b>: only a document whose {@code $schema} states one of the three service types gets a type field, or a
 * context service ends up carrying a service type it never had. {@link ServiceTypeFiles#typeFromSchemaUri} is what
 * decides that, so a foreign URI whose schema file name spells a type is written back as well. Gating the whole
 * migration narrowly instead is circular, because {@code revert()} never runs on a document
 * {@code supportsDocument} rejected.
 *
 * <p>The restored {@code $schema} is what V104 and V103 then see: {@code FileMigrationService} sorts reverts by
 * version descending and re-evaluates {@code supportsDocument} on each intermediate result. It never reaches the
 * exported file, because {@link V101RevertMigration} runs last and rebuilds each node from {@code id}, {@code name},
 * and the children of {@code content}.
 */
@Component
public class V105RevertMigration implements RevertMigration {

    private static final String SCHEMA = "$schema";

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
        // A copy on both paths: FileMigrationService feeds each result into the next migration, and a returned alias
        // of the input makes the chain mutate a node an earlier step still holds.
        if (!(node.get(CONTENT) instanceof ObjectNode)) {
            return node.deepCopy();
        }
        ObjectNode result = node.deepCopy();
        ObjectNode content = (ObjectNode) result.get(CONTENT);
        serviceTypeFiles.typeFromDocumentSchema(result).ifPresent(type -> {
            content.put(INTEGRATION_SYSTEM_TYPE, type.name());
            result.set(SCHEMA, TextNode.valueOf(serviceSchemaUri));
        });
        removeMigrationVersion(content, String.valueOf(getVersion()));
        return result;
    }
}
