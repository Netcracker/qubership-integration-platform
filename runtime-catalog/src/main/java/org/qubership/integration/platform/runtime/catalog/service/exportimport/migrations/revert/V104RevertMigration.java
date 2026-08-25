package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.io.readers.migrations.revert.RevertMigration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.API_GROUPS;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_GROUPS;
import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.removeMigrationVersion;
import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.renameField;

/**
 * Reverts the api-group rename for {@code QIP_EXPORT_LEGACY_FORMAT}: on the service document, {@code content.apiGroups}
 * (the inline legacy group list) is renamed back to {@code content.specificationGroups} and {@code 104} is stripped
 * from {@code content.migrations}, so a re-import runs the forward migration again instead of computing an empty
 * version set.
 *
 * <p>The group's own document needs nothing here. A legacy export carries no {@code $schema} on any document:
 * {@link V101RevertMigration} runs last (descending version order), rebuilds each node from {@code id}, {@code name},
 * and the children of {@code content}, and drops every other root field along the way.
 *
 * <p>The strip has to reach every document stamped with the service migration list, including a bare service and a
 * context or MCP service, so {@link ServiceDocumentMatcher} owns the decision instead of a local field check.
 */
@Component
public class V104RevertMigration implements RevertMigration {

    private final ServiceDocumentMatcher serviceDocumentMatcher;

    @Autowired
    public V104RevertMigration(ServiceDocumentMatcher serviceDocumentMatcher) {
        this.serviceDocumentMatcher = serviceDocumentMatcher;
    }

    @Override
    public int getVersion() {
        return 104;
    }

    @Override
    public boolean supportsDocument(ObjectNode node) {
        return serviceDocumentMatcher.matches(node);
    }

    @Override
    public ObjectNode revert(ObjectNode node) {
        ObjectNode result = node.deepCopy();
        if (!(result.get(CONTENT) instanceof ObjectNode content)) {
            return result;
        }
        renameField(content, API_GROUPS, SPECIFICATION_GROUPS);
        removeMigrationVersion(content, String.valueOf(getVersion()));
        return result;
    }
}
