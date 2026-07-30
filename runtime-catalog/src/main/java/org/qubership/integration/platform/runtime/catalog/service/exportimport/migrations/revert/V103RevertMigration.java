package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ApiOperationDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SPECIFICATION_TYPE;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil.removeMigrationVersion;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil.renameField;

/**
 * Reverts the {@code api} shape back to the legacy {@code specification} shape for {@code QIP_EXPORT_LEGACY_FORMAT}:
 * typed operations collapse to {@code method}/{@code path}, {@code specifications} to {@code specificationSources}
 * ({@code filePath}/{@code isRoot} renamed to {@code fileName}/{@code mainSource}), and {@code specificationType} is
 * dropped so a re-import does not treat the file as already current.
 *
 * <p>On the specification-group document it drops {@code content.apis}, a field the legacy format never carried. That
 * field is also what identifies the group, since neither a chain nor a service content has it.
 *
 * <p>On the service document it strips {@code 103} from {@code content.migrations}. Without that strip re-import
 * computes an empty version set, the {@code V103} forward migration never fires, and the legacy-shaped operations
 * deserialize into {@link ApiOperationDto} as nulls.
 *
 * <p>{@code supportsDocument} rejects chain documents. The revert list is one flat list keyed by version, and the
 * chain sequence owns its own {@code 103} ({@code V103ChainImportFileMigration}); an ungated strip would remove a
 * chain's legitimate migration on legacy export. {@link ServiceDocumentMatcher} draws that line, shared with
 * {@link V104RevertMigration} so the two cannot drift.
 *
 * <p>The reverted model file carries specification-shaped content, so its {@code $schema} is restamped to the
 * specification form ({@code qip.json.schemas.specification}); the service and group documents keep their own
 * {@code $schema}. That restamp is currently unobservable in a real export: {@link V101RevertMigration} runs last
 * and rebuilds each node from {@code id}, {@code name}, and the children of {@code content}, so a legacy artifact
 * ends up with no {@code $schema} at all.
 */
@Component
public class V103RevertMigration implements RevertMigration {

    private static final String OPERATIONS = "operations";
    private static final String SPECIFICATIONS = "specifications";
    private static final String SPECIFICATION_SOURCES = "specificationSources";
    private static final String SPECIFICATION_VERSION = "specificationVersion";
    private static final String FILE_PATH = "filePath";
    private static final String FILE_NAME = "fileName";
    private static final String IS_ROOT = "isRoot";
    private static final String MAIN_SOURCE = "mainSource";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String METHOD = "method";
    private static final String PATH = "path";
    private static final String SPECIFICATION = "specification";

    private static final String APIS = "apis";

    private static final String SCHEMA = "$schema";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ApiOperationDtoMapper apiOperationDtoMapper;
    private final URI specificationSchemaUri;
    private final ServiceDocumentMatcher serviceDocumentMatcher;

    @Autowired
    public V103RevertMigration(
            ApiOperationDtoMapper apiOperationDtoMapper,
            @Value("${qip.json.schemas.specification:http://qubership.org/schemas/product/qip/specification.schema.yaml}")
            URI specificationSchemaUri,
            ServiceDocumentMatcher serviceDocumentMatcher) {
        this.apiOperationDtoMapper = apiOperationDtoMapper;
        this.specificationSchemaUri = specificationSchemaUri;
        this.serviceDocumentMatcher = serviceDocumentMatcher;
    }

    @Override
    public int getVersion() {
        return 103;
    }

    @Override
    public boolean supportsDocument(ObjectNode node) {
        if (serviceDocumentMatcher.matches(node)) {
            return true;
        }
        return node.get(CONTENT) instanceof ObjectNode content
                && (isApiModel(content) || isSpecificationGroup(content));
    }

    @Override
    public ObjectNode revert(ObjectNode node) {
        ObjectNode result = node.deepCopy();
        if (!(result.get(CONTENT) instanceof ObjectNode content)) {
            return result;
        }
        boolean apiModel = isApiModel(content);
        revertOperations(content);
        revertSources(content);
        content.remove(SPECIFICATION_TYPE);
        content.remove(SPECIFICATION_VERSION);
        // Group child list; version 103 introduced it, so the legacy export must not carry it.
        content.remove(APIS);
        // No-op on an api-model node, which has no migrations field; the service document is where the strip lands.
        removeMigrationVersion(content, String.valueOf(getVersion()));
        if (apiModel) {
            // The content is now specification-shaped, so the file must carry the specification $schema, not the api
            // one it was exported with. The service document is left with its own $schema.
            result.set(SCHEMA, TextNode.valueOf(specificationSchemaUri.toString()));
        }
        return result;
    }

    private static boolean isApiModel(ObjectNode content) {
        return content.has(SPECIFICATION_TYPE) || content.has(SPECIFICATIONS) || content.has(OPERATIONS);
    }

    private static boolean isSpecificationGroup(ObjectNode content) {
        return content.has(APIS);
    }

    private void revertOperations(ObjectNode content) {
        JsonNode operations = content.get(OPERATIONS);
        if (operations == null || !operations.isArray()) {
            return;
        }
        ArrayNode reverted = MAPPER.createArrayNode();
        for (JsonNode operationNode : operations) {
            reverted.add(operationNode instanceof ObjectNode node ? toLegacyOperation(node) : operationNode);
        }
        content.set(OPERATIONS, reverted);
    }

    // Rebuilds the operation through the export mapper's entity, so method and path come from the same derivation the
    // forward migration reverses. The typed shape is dropped and the specification (MaaS classifier home) survives.
    private JsonNode toLegacyOperation(ObjectNode operationNode) {
        ApiOperationDto dto = MAPPER.convertValue(operationNode, ApiOperationDto.class);
        Operation operation = apiOperationDtoMapper.toEntity(dto);
        ObjectNode legacy = MAPPER.createObjectNode();
        putIfPresent(legacy, ID, operation.getId());
        putIfPresent(legacy, NAME, operation.getName());
        putIfPresent(legacy, DESCRIPTION, operation.getDescription());
        legacy.put(METHOD, operation.getMethod());
        legacy.put(PATH, operation.getPath());
        if (operation.getSpecification() != null) {
            legacy.set(SPECIFICATION, operation.getSpecification());
        }
        return legacy;
    }

    private void revertSources(ObjectNode content) {
        JsonNode sources = content.get(SPECIFICATIONS);
        if (sources == null || !sources.isArray()) {
            return;
        }
        ArrayNode renamed = MAPPER.createArrayNode();
        for (JsonNode source : sources) {
            if (source instanceof ObjectNode node) {
                ObjectNode copy = node.deepCopy();
                renameField(copy, FILE_PATH, FILE_NAME);
                renameField(copy, IS_ROOT, MAIN_SOURCE);
                renamed.add(copy);
            } else {
                renamed.add(source);
            }
        }
        content.remove(SPECIFICATIONS);
        content.set(SPECIFICATION_SOURCES, renamed);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
