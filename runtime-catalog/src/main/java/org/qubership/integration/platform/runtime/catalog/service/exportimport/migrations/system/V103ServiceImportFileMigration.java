package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.io.model.exportimport.system.ApiOperationDto;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.migration.TypedOperationBackfill;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.MIGRATION_PROTOCOL;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SPECIFICATION_TYPE;
import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.renameField;

/**
 * Converts a legacy {@code specification}-shaped model node to the {@code api} shape: typed operations, renamed
 * source fields ({@code specifications}/{@code filePath}/{@code isRoot}) and a {@code specificationType}, dropping
 * the de-materialized {@code requestSchema}/{@code responseSchemas}.
 *
 * <p>Typing needs the operation protocol, which lives only in the service file. The deserializer stamps it on the
 * model node in a {@link org.qubership.integration.platform.io.model.exportimport.ExportImportConstants#MIGRATION_PROTOCOL}
 * scratch field, read here from the root or, once V101 has relocated it, from {@code content}. That scratch field is
 * also the model-node discriminator: service and specification-group documents run through the same migration list
 * but never carry it, so they fall through untouched.
 *
 * <p>Typed derivation reuses {@link TypedOperationBackfill} (column-derived, no reparse) and
 * {@link ApiOperationDtoMapper}, so a migrated operation matches an exported one byte for byte. WSDL is the one gap:
 * {@code protocol}/{@code binding} are reparse-only and absent from the node, so its operations keep the type
 * discriminator with those two fields null, to fill on a later reparse.
 */
@Slf4j
@Component
public class V103ServiceImportFileMigration implements ServiceImportFileMigration {

    private static final String OPERATIONS = "operations";
    private static final String SPECIFICATION_SOURCES = "specificationSources";
    private static final String SPECIFICATIONS = "specifications";
    private static final String FILE_NAME = "fileName";
    private static final String FILE_PATH = "filePath";
    private static final String MAIN_SOURCE = "mainSource";
    private static final String IS_ROOT = "isRoot";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String METHOD = "method";
    private static final String PATH = "path";
    private static final String SPECIFICATION = "specification";

    // Column-derived path only; the reparse collaborators are unused, so null, exactly as the Flyway backfill wires it.
    private static final TypedOperationBackfill BACKFILL = new TypedOperationBackfill(null, null);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ApiOperationDtoMapper apiOperationDtoMapper;

    @Autowired
    public V103ServiceImportFileMigration(ApiOperationDtoMapper apiOperationDtoMapper) {
        this.apiOperationDtoMapper = apiOperationDtoMapper;
    }

    @Override
    public int getVersion() {
        return 103;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) {
        OperationProtocol protocol = readProtocol(fileNode);
        ObjectNode result = fileNode.deepCopy();
        // Strip the scratch protocol on every path — it must never reach a file, even on the early returns.
        removeScratchProtocol(result);
        if (protocol == null) {
            // No scratch protocol: either a service/group document or a protocol-less model. Nothing to type.
            return result;
        }
        log.debug("Applying service migration: {}", getVersion());
        if (!(result.path(CONTENT) instanceof ObjectNode content)) {
            return result;
        }
        migrateOperations(content, protocol);
        renameSources(content);
        String specificationType = ProtocolExtractionService.mapSpecificationType(protocol);
        if (specificationType != null) {
            content.set(SPECIFICATION_TYPE, TextNode.valueOf(specificationType));
        }
        return result;
    }

    private OperationProtocol readProtocol(ObjectNode node) {
        JsonNode value = node.hasNonNull(MIGRATION_PROTOCOL)
                ? node.get(MIGRATION_PROTOCOL)
                : node.path(CONTENT).get(MIGRATION_PROTOCOL);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return OperationProtocol.valueOf(value.asText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void removeScratchProtocol(ObjectNode node) {
        node.remove(MIGRATION_PROTOCOL);
        if (node.path(CONTENT) instanceof ObjectNode content) {
            content.remove(MIGRATION_PROTOCOL);
        }
    }

    private void migrateOperations(ObjectNode content, OperationProtocol protocol) {
        JsonNode operations = content.path(OPERATIONS);
        if (!operations.isArray()) {
            return;
        }
        ArrayNode migrated = MAPPER.createArrayNode();
        for (JsonNode operationNode : operations) {
            migrated.add(operationNode instanceof ObjectNode node ? toApiOperation(node, protocol) : operationNode);
        }
        content.set(OPERATIONS, migrated);
    }

    // Rebuilds the operation through the export mapper: the api shape carries only ApiOperationDto's fields, so
    // requestSchema/responseSchemas are dropped and the specification (MaaS classifier) survives.
    private JsonNode toApiOperation(ObjectNode operationNode, OperationProtocol protocol) {
        JsonNode specification = operationNode.get(SPECIFICATION);
        Operation operation = Operation.builder()
                .id(text(operationNode, ID))
                .name(text(operationNode, NAME))
                .description(text(operationNode, DESCRIPTION))
                .method(text(operationNode, METHOD))
                .path(text(operationNode, PATH))
                .specification(specification)
                .build();
        TypedOperation typed = BACKFILL.backfillTyped(operation, specification, protocol);
        if (typed == null && protocol == OperationProtocol.SOAP) {
            // Defensive fallback for a SOAP operation with no typed: carry the canonical protocol, leave binding null.
            typed = new WsdlOperation("SOAP", null);
        }
        if (typed != null) {
            operation.setTyped(typed);
        }
        ApiOperationDto dto = apiOperationDtoMapper.toDto(operation);
        return MAPPER.valueToTree(dto);
    }

    private void renameSources(ObjectNode content) {
        JsonNode sources = content.get(SPECIFICATION_SOURCES);
        if (sources == null || !sources.isArray()) {
            return;
        }
        ArrayNode renamed = MAPPER.createArrayNode();
        for (JsonNode source : sources) {
            if (source instanceof ObjectNode node) {
                ObjectNode copy = node.deepCopy();
                renameField(copy, FILE_NAME, FILE_PATH);
                renameField(copy, MAIN_SOURCE, IS_ROOT);
                renamed.add(copy);
            } else {
                renamed.add(source);
            }
        }
        content.remove(SPECIFICATION_SOURCES);
        content.set(SPECIFICATIONS, renamed);
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
