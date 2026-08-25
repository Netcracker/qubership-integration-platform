package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.API_GROUPS;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_GROUPS;
import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.renameField;

/**
 * Renames the inline group list on a legacy (pre-multi-file) service document from
 * {@code content.specificationGroups} to {@code content.apiGroups}.
 *
 * <p>This must run before {@code ServiceDeserializer} reads that field, in either of the two places it does:
 * the discriminator that picks the legacy inline-group branch, and the raw-node loop that walks the inline groups
 * themselves. {@code IntegrationSystemContentDto} is {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so without
 * this migration an old archive's {@code specificationGroups} key is silently dropped after the DTO field rename:
 * the discriminator then sees an empty list and the import takes the multi-file branch instead of the legacy one.
 *
 * <p>Service, group, and model documents all run through the same migration list; a group or model document has no
 * {@code content.specificationGroups} field, so {@link org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil#renameField}
 * leaves them untouched.
 */
@Slf4j
@Component
public class V104ServiceImportFileMigration implements ServiceImportFileMigration {

    @Override
    public int getVersion() {
        return 104;
    }

    @Override
    public boolean isIdempotent() {
        // The rename is a no-op once it has run, so the rollout path can run it without knowing the document shape.
        return true;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Applying service migration: {}", getVersion());
        ObjectNode result = fileNode.deepCopy();
        if (result.get(CONTENT) instanceof ObjectNode content) {
            renameField(content, SPECIFICATION_GROUPS, API_GROUPS);
        }
        return result;
    }
}
