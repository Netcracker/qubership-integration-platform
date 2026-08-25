package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.API_GROUPS;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.DEPENDENCIES;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.DEPLOY_ACTION;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.ELEMENTS;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.INTEGRATION_SYSTEM_TYPE;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_GROUPS;
import static org.qubership.integration.platform.io.readers.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;

/**
 * Recognizes the three documents that stamp {@code content.migrations} from the service migration list: service,
 * context service, and MCP service. A revert migration that strips its own version has to match all three, or a
 * legacy export keeps claiming a version the importing QIP does not have and is rejected outright. It must also
 * leave a chain alone, because the chain sequence numbers its own migrations independently.
 *
 * <p>The match reads {@code $schema}, the type tag every exporter stamps and only {@link V101RevertMigration} — last
 * in the revert order — strips. Matching on content fields instead misses a service whose type, protocol, and
 * environments are all absent, which is what {@code @JsonInclude(NON_EMPTY)} leaves on a bare service. The field
 * shape is kept as a fallback for a document that carries no {@code $schema} at all.
 *
 * <p>The URI set holds the per-type service schemas as well as the plain one. A current-format service carries an
 * {@code external-service}, {@code internal-service}, or {@code implemented-service} URI, and the set is what every
 * revert migration is gated on: leave those out and such a document matches nothing, which silences V105, V104, and
 * V103 at once.
 */
@Component
public class ServiceDocumentMatcher {

    private static final String SCHEMA = "$schema";
    private static final String PROTOCOL = "protocol";
    private static final String ENVIRONMENTS = "environments";

    private final Set<String> serviceSchemas;

    @Autowired
    public ServiceDocumentMatcher(ApplicationJsonSchemaProperties schemas) {
        // copyOf, not Set.of: every URI is operator-configurable, and Set.of would fail bean creation on two
        // properties pointed at the same value.
        this.serviceSchemas = Set.copyOf(List.of(
                schemas.getService(),
                schemas.getExternalService(),
                schemas.getInternalService(),
                schemas.getImplementedService(),
                schemas.getContextService(),
                schemas.getMcpService()));
    }

    public boolean matches(ObjectNode node) {
        if (!(node.get(CONTENT) instanceof ObjectNode content)) {
            return false;
        }
        JsonNode schema = node.get(SCHEMA);
        if (schema != null && schema.isTextual()) {
            return serviceSchemas.contains(schema.asText());
        }
        return !isChain(content) && (content.has(IMPORT_MIGRATIONS_FIELD) || hasServiceFields(content));
    }

    private static boolean isChain(ObjectNode content) {
        return content.has(ELEMENTS) || content.has(DEPENDENCIES) || content.has(DEPLOY_ACTION);
    }

    // Read both group-list names: the fallback also sees a document V104 has already reverted.
    private static boolean hasServiceFields(ObjectNode content) {
        return content.has(INTEGRATION_SYSTEM_TYPE) || content.has(PROTOCOL) || content.has(ENVIRONMENTS)
                || content.has(API_GROUPS) || content.has(SPECIFICATION_GROUPS);
    }
}
