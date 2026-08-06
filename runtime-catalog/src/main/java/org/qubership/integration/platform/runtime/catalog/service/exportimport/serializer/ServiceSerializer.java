/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ApiGroupDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.ExtractedSchemas;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor.OperationKey;
import org.qubership.integration.platform.runtime.catalog.util.EnvironmentLimitUtils;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ServiceSerializer {

    private static final String CONTENT = "content";
    private static final String OPERATIONS = "operations";
    private static final String METHOD = "method";
    private static final String PATH = "path";
    private static final String SPECIFICATION = "specification";
    private static final String REQUEST_SCHEMA = "requestSchema";
    private static final String RESPONSE_SCHEMAS = "responseSchemas";

    private final YAMLMapper yamlMapper;
    private final IntegrationSystemDtoMapper integrationSystemDtoMapper;
    private final ApiGroupDtoMapper apiGroupDtoMapper;
    private final SystemModelDtoMapper systemModelDtoMapper;
    private final FileMigrationService fileMigrationService;
    private final OperationSchemaExtractor operationSchemaExtractor;

    @Autowired
    public ServiceSerializer(
            YAMLMapper yamlExportImportMapper,
            IntegrationSystemDtoMapper integrationSystemDtoMapper,
            ApiGroupDtoMapper apiGroupDtoMapper,
            SystemModelDtoMapper systemModelDtoMapper,
            FileMigrationService fileMigrationService,
            OperationSchemaExtractor operationSchemaExtractor
    ) {
        this.yamlMapper = yamlExportImportMapper;
        this.integrationSystemDtoMapper = integrationSystemDtoMapper;
        this.apiGroupDtoMapper = apiGroupDtoMapper;
        this.systemModelDtoMapper = systemModelDtoMapper;
        this.fileMigrationService = fileMigrationService;
        this.operationSchemaExtractor = operationSchemaExtractor;
    }

    public ExportedSystemObject serialize(IntegrationSystem system) {
        warnOnEnvironmentLimit(system);
        // Refused here rather than where the name is written, which is inside the loop over every service of the
        // archive: the export service catches this per row and leaves the rest of the archive intact.
        ExportImportUtils.requireExportableServiceId(system.getId(), fileMigrationService.isLegacyExport());
        IntegrationSystemDto integrationSystemDto = integrationSystemDtoMapper.toExternalEntity(system);
        ObjectNode systemNode = fileMigrationService.revertMigrationIfNeeded(yamlMapper.valueToTree(integrationSystemDto));

        List<ExportedApiGroup> exportedApiGroups = system.getApiGroups()
                .stream()
                .map(this::serialize)
                .toList();

        return new ExportedIntegrationSystem(
                system.getId(), systemNode, exportedApiGroups, system.getIntegrationSystemType());
    }

    public ExportedApiGroup serialize(ApiGroup apiGroup) {
        ApiGroupDto dto = apiGroupDtoMapper.toExternalEntity(apiGroup);
        ObjectNode node = fileMigrationService.revertMigrationIfNeeded(yamlMapper.valueToTree(dto));

        List<ExportedSpecification> exportedSpecifications = apiGroup.getSystemModels()
                .stream()
                .map(this::serialize)
                .toList();
        return new ExportedApiGroup(apiGroup.getId(), node, exportedSpecifications);
    }

    public ExportedSpecification serialize(SystemModel specification) {
        SystemModelDto dto = systemModelDtoMapper.toExternalEntity(specification);
        ObjectNode node = fileMigrationService.revertMigrationIfNeeded(yamlMapper.valueToTree(dto));
        if (fileMigrationService.isLegacyExport()) {
            restoreLegacyOperationSchemas(node, specification);
        } else {
            stripOperationSpecifications(node, specification);
        }

        List<ExportedSpecificationSource> exportedSpecificationSources = specification.getSpecificationSources()
                .stream()
                .map(source -> new ExportedSpecificationSource(
                        source.getId(),
                        source.getSource(),
                        ExportImportUtils.getFullSpecificationFileName(source)))
                .toList();

        return new ExportedSpecification(specification.getId(), node, exportedSpecificationSources);
    }

    // The archive ships the whole specification source, so the per-operation slice is redundant payload here; import
    // re-derives it from that source. Only the legacy format still carries the field, because nothing re-derives it
    // for an older QIP. Outside legacy mode no revert runs, so the document keeps the api shape: operations sit under
    // content.
    private void stripOperationSpecifications(ObjectNode node, SystemModel specification) {
        // A protocol whose slice import cannot rebuild keeps it: the strip and the rebuild answer one question.
        if (!OperationSchemaExtractor.canExtractSchemas(protocolOf(specification))) {
            return;
        }
        // revertMigrationIfNeeded answers null for a null document, so the node is nullable by contract.
        if (node == null || !(node.path(CONTENT).path(OPERATIONS) instanceof ArrayNode operations)) {
            return;
        }
        for (JsonNode operationNode : operations) {
            if (operationNode instanceof ObjectNode operation) {
                operation.remove(SPECIFICATION);
            }
        }
    }

    // The legacy file carried the schemas on every operation, and an older QIP importing this archive still reads them
    // from there. They are no longer stored, so re-derive them from the raw source; this is the only place holding both
    // the source and the reverted document. The revert already flattened content onto the root, so operations sit there.
    private void restoreLegacyOperationSchemas(ObjectNode node, SystemModel specification) {
        if (node == null || !(node.get(OPERATIONS) instanceof ArrayNode operations) || operations.isEmpty()) {
            return;
        }
        Map<OperationKey, ExtractedSchemas> schemasByOperation = extractOperationSchemas(specification);
        if (schemasByOperation.isEmpty()) {
            // An empty result is expected for a protocol that carries no schemas; for any other it means the source
            // yielded nothing, which the per-operation report below would otherwise never surface.
            if (OperationSchemaExtractor.canExtractSchemas(protocolOf(specification))) {
                log.warn("Legacy export of specification {}: the source yielded no schemas, so all {} operations"
                        + " export without them", specification.getId(), operations.size());
            }
            return;
        }
        List<OperationKey> unmatched = new ArrayList<>();
        int total = 0;
        for (JsonNode operationNode : operations) {
            if (!(operationNode instanceof ObjectNode operation)) {
                continue;
            }
            total++;
            OperationKey key = OperationKey.of(textOrNull(operation, PATH), textOrNull(operation, METHOD));
            ExtractedSchemas schemas = schemasByOperation.get(key);
            if (schemas == null) {
                unmatched.add(key);
                continue;
            }
            setIfNotEmpty(operation, REQUEST_SCHEMA, schemas.requestSchema());
            setIfNotEmpty(operation, RESPONSE_SCHEMAS, schemas.responseSchemas());
        }
        if (!unmatched.isEmpty()) {
            log.warn("Legacy export of specification {}: {} of {} operations did not match the parsed source"
                            + " and carry no request or response schemas. Unmatched operations: {}",
                    specification.getId(), unmatched.size(), total, OperationSchemaExtractor.describeKeys(unmatched));
        }
    }

    private Map<OperationKey, ExtractedSchemas> extractOperationSchemas(SystemModel specification) {
        OperationProtocol protocol = protocolOf(specification);
        List<SpecificationSource> sources = specification.getSpecificationSources();
        if (protocol == null || sources == null || sources.isEmpty()) {
            return Map.of();
        }
        try {
            // Every source goes in, not just the main one: a .proto resolves message types across files.
            return operationSchemaExtractor.extractAll(sources, protocol, true);
        } catch (RuntimeException exception) {
            // Any parser failure costs the schemas of one model, never the export. The parsers wrap everything as
            // SpecificationImportException with one fixed message, so only the cause says what actually broke.
            log.warn("Cannot derive operation schemas for the legacy export of specification {}",
                    specification.getId(), exception);
            return Map.of();
        }
    }

    private static OperationProtocol protocolOf(SystemModel specification) {
        ApiGroup group = specification.getApiGroup();
        if (group == null || group.getSystem() == null) {
            return null;
        }
        return group.getSystem().getProtocol();
    }

    // Absent, not null or {}: an operation with nothing to derive carried no such field in the legacy file either.
    private void setIfNotEmpty(ObjectNode operation, String field, Map<String, JsonNode> schemas) {
        if (schemas != null && !schemas.isEmpty()) {
            operation.set(field, yamlMapper.valueToTree(schemas));
        }
    }

    private static String textOrNull(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * A row holding more environments than its type allows still exports, because refusing would leave no way to
     * extract it at all. Rows in that shape predate the rule: IMPLEMENTED was never checked, and INTERNAL was
     * unchecked on import-create. The warning is what tells the operator the archive does not import as it stands.
     */
    private static void warnOnEnvironmentLimit(IntegrationSystem system) {
        EnvironmentLimitUtils.violation(system, system.getEnvironments().size()).ifPresent(reason ->
                log.warn("{} The archive is produced anyway, but re-importing this service fails until the extra"
                        + " environments are removed.", reason));
    }
}
