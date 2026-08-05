package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.API_FILE_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.API_GROUP_FILE_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.YAML_FILE_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;

@Slf4j
@Component
public class ServiceConfigurationsToFilesConverter {

    private static final String SPECIFICATION_FILE_PATH_FIELD_KEY = "filePath";
    // Older packages still carry the pre-api field name; read both so their sources are not silently dropped.
    private static final String LEGACY_SPECIFICATION_FILE_NAME_FIELD_KEY = "fileName";
    private static final String INTEGRATION_SYSTEM_TYPE_FIELD_KEY = "integrationSystemType";

    private final ObjectMapper objectMapper;
    private final String appPrefix;
    private final List<ServiceImportFileMigration> serviceImportFileMigrations;
    private final ServiceTypeFiles serviceTypeFiles;

    public ServiceConfigurationsToFilesConverter(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            @Value("${app.prefix:qip}") String appPrefix,
            List<ServiceImportFileMigration> serviceImportFileMigrations,
            ServiceTypeFiles serviceTypeFiles
    ) {
        this.objectMapper = objectMapper;
        this.appPrefix = appPrefix;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
        this.serviceTypeFiles = serviceTypeFiles;
    }

    public Map<Path, byte[]> convert(
            Map<String, RolloutImportConfigurationItem> serviceConfigs,
            Map<String, RolloutImportConfigurationItem> specificationConfigs,
            Map<String, RolloutImportConfigurationItem> specGroupConfigs,
            Map<String, RolloutImportConfigurationItem> contextServiceConfigs,
            Map<String, String> resources
    ) throws JsonProcessingException {
        Map<Path, byte[]> files = new HashMap<>();
        convertServices(files, serviceConfigs, SERVICE_YAML_NAME_POSTFIX);
        convertServices(files, contextServiceConfigs, CONTEXT_SERVICE_YAML_NAME_POSTFIX);
        convertSpecGroups(files, serviceConfigs, specGroupConfigs);
        convertSpecifications(files, serviceConfigs, specGroupConfigs, specificationConfigs, resources);
        return files;
    }

    private void convertServices(
            Map<Path, byte[]> files,
            Map<String, RolloutImportConfigurationItem> serviceConfigs,
            String serviceTypePostfix
    ) throws JsonProcessingException {
        for (Map.Entry<String, RolloutImportConfigurationItem> serviceConfig : serviceConfigs.entrySet()) {
            JsonNode contentNode = serviceConfig.getValue().getContent();
            if (contentNode instanceof ObjectNode serviceContent) {
                // A package carries no version data, so the converter has to claim one. It claims only the versions
                // whose migration is unsafe to re-run, leaving the rest to run on import.
                serviceContent.putIfAbsent(
                        IMPORT_MIGRATIONS_FIELD,
                        TextNode.valueOf(MigrationUtil.formatAppliedVersions(serviceImportFileMigrations))
                );
            }

            String serviceId = serviceConfig.getKey();
            Path serviceDirectory = Path.of(serviceId);
            String postfix = SERVICE_YAML_NAME_POSTFIX.equals(serviceTypePostfix)
                    ? plainServicePostfix(serviceConfig.getValue())
                    : serviceTypePostfix;
            String serviceFileName = serviceId + postfix + appPrefix + YAML_FILE_NAME_POSTFIX;
            putYaml(files, serviceDirectory.resolve(serviceFileName), serviceConfig.getValue());
        }
    }

    /**
     * The per-type name when the package states a type, so the written file is self-describing. Both sources have to
     * be read: a package built before #553 states the type in {@code content.integrationSystemType} only, and one
     * built after states it in {@code $schema} only, since the per-type schemas forbid the field. A file that states
     * the type nowhere is refused by {@code ServiceDeserializer.resolveServiceType}, so the plain {@code .service.}
     * name is a last resort.
     *
     * <p>The content field is read first. Import resolves the file name before the field and refuses a document where
     * the two disagree, so a name derived from the field can never manufacture that disagreement.
     */
    private String plainServicePostfix(RolloutImportConfigurationItem configurationItem) {
        return typeStatedByContent(configurationItem.getContent())
                .or(() -> serviceTypeFiles.typeFromSchemaUri(configurationItem.getSchema()))
                .map(ServiceTypeFiles::postfix)
                .orElse(SERVICE_YAML_NAME_POSTFIX);
    }

    private static Optional<IntegrationSystemType> typeStatedByContent(JsonNode contentNode) {
        JsonNode type = contentNode == null ? null : contentNode.get(INTEGRATION_SYSTEM_TYPE_FIELD_KEY);
        if (type == null || !type.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(IntegrationSystemType.valueOf(type.asText()));
        } catch (IllegalArgumentException exception) {
            log.warn("Service configuration states an unknown service type {}", type.asText());
            return Optional.empty();
        }
    }

    private void convertSpecGroups(
            Map<Path, byte[]> files,
            Map<String, RolloutImportConfigurationItem> serviceConfigs,
            Map<String, RolloutImportConfigurationItem> specGroupConfigs
    ) throws JsonProcessingException {
        for (Map.Entry<String, RolloutImportConfigurationItem> specGroupConfig : specGroupConfigs.entrySet()) {
            String specGroupId = specGroupConfig.getKey();
            String serviceId = getParentId(specGroupConfig.getValue().getContent());

            if (serviceId == null) {
                log.error("SpecGroup {} is missing /content/parentId", specGroupId);
                continue;
            }
            if (!serviceConfigs.containsKey(serviceId)) {
                log.error("SpecGroup {} refers to non-existing service {}", specGroupId, serviceId);
                continue;
            }

            Path serviceDirectory = Path.of(serviceId);
            String specGroupFileName = specGroupId + API_GROUP_FILE_POSTFIX + appPrefix + YAML_FILE_NAME_POSTFIX;
            putYaml(files, serviceDirectory.resolve(specGroupFileName), specGroupConfig.getValue());
        }
    }

    private void convertSpecifications(
            Map<Path, byte[]> files,
            Map<String, RolloutImportConfigurationItem> serviceConfigs,
            Map<String, RolloutImportConfigurationItem> specGroupConfigs,
            Map<String, RolloutImportConfigurationItem> specificationConfigs,
            Map<String, String> resources
    ) throws JsonProcessingException {
        for (Map.Entry<String, RolloutImportConfigurationItem> specificationConfig : specificationConfigs.entrySet()) {
            String specificationId = specificationConfig.getKey();
            String specGroupId = getParentId(specificationConfig.getValue().getContent());

            if (specGroupId == null) {
                log.error("Specification {} is missing /content/parentId", specificationId);
                continue;
            }

            RolloutImportConfigurationItem specGroupNode = specGroupConfigs.get(specGroupId);
            if (specGroupNode == null) {
                log.error("Specification {} refers to non-existing specGroup {}", specificationId, specGroupId);
                continue;
            }

            String serviceId = getParentId(specGroupNode.getContent());
            if (serviceId == null) {
                log.error("SpecGroup {} (from specification {}) is missing /content/parentId", specGroupId, specificationId);
                continue;
            }

            if (!serviceConfigs.containsKey(serviceId)) {
                log.error("Specification {} refers to non-existing service {}", specificationId, serviceId);
                continue;
            }

            Path serviceDirectory = Path.of(serviceId);
            String specificationFileName = specificationId + API_FILE_POSTFIX + appPrefix + YAML_FILE_NAME_POSTFIX;
            putYaml(files, serviceDirectory.resolve(specificationFileName), specificationConfig.getValue());

            JsonNode specificationContent = specificationConfig.getValue().getContent();
            List<Path> specPaths = Stream.concat(
                            specificationContent.findValuesAsText(SPECIFICATION_FILE_PATH_FIELD_KEY).stream(),
                            specificationContent.findValuesAsText(LEGACY_SPECIFICATION_FILE_NAME_FIELD_KEY).stream())
                    .map(Paths::get)
                    .toList();
            for (Path specPath : specPaths) {
                String specFileName = specPath.getFileName().toString();
                if (resources.containsKey(specFileName)) {
                    files.put(serviceDirectory.resolve(specPath), resources.get(specFileName).getBytes());
                } else {
                    log.error("Specification file name {} does not exist in package resources", specFileName);
                }
            }
        }
    }

    private static String getParentId(JsonNode node) {
        JsonNode parentIdNode = node.at("/parentId");
        return (parentIdNode.isMissingNode() || parentIdNode.isNull()) ? null : parentIdNode.asText();
    }

    private void putYaml(Map<Path, byte[]> files, Path path, RolloutImportConfigurationItem configurationItem) throws JsonProcessingException {
        files.put(path, objectMapper.writeValueAsBytes(configurationItem));
    }
}
