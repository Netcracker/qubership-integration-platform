package org.qubership.integration.platform.runtime.catalog.service.qcp.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
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

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTENT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SPECIFICATION_FILE_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SPECIFICATION_GROUP_FILE_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.YAML_FILE_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;

@Slf4j
@Component
public class ServiceConfigurationsToFilesConverter {

    private static final String SPECIFICATION_FILE_NAME_FIELD_KEY = "fileName";

    private final ObjectMapper objectMapper;
    private final String appPrefix;
    private final List<ServiceImportFileMigration> serviceImportFileMigrations;

    public ServiceConfigurationsToFilesConverter(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            @Value("${app.prefix:qip}") String appPrefix,
            List<ServiceImportFileMigration> serviceImportFileMigrations
    ) {
        this.objectMapper = objectMapper;
        this.appPrefix = appPrefix;
        this.serviceImportFileMigrations = serviceImportFileMigrations;
    }

    public Map<Path, byte[]> convert(
            Map<String, JsonNode> serviceConfigs,
            Map<String, JsonNode> specificationConfigs,
            Map<String, JsonNode> specGroupConfigs,
            Map<String, JsonNode> contextServiceConfigs,
            Map<String, byte[]> resources
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
            Map<String, JsonNode> serviceConfigs,
            String serviceTypePostfix
    ) throws JsonProcessingException {
        for (Map.Entry<String, JsonNode> serviceConfig : serviceConfigs.entrySet()) {
            ObjectNode serviceNode = (ObjectNode) serviceConfig.getValue();
            JsonNode contentNode = serviceNode.get(CONTENT);
            if (contentNode instanceof ObjectNode serviceContent) {
                serviceContent.putIfAbsent(
                        IMPORT_MIGRATIONS_FIELD,
                        TextNode.valueOf(MigrationUtil.formatVersions(serviceImportFileMigrations))
                );
            }

            String serviceId = serviceConfig.getKey();
            Path serviceDirectory = Path.of(serviceId);
            String serviceFileName = serviceId + serviceTypePostfix + appPrefix + YAML_FILE_NAME_POSTFIX;
            putYaml(files, serviceDirectory.resolve(serviceFileName), serviceNode);
        }
    }

    private void convertSpecGroups(
            Map<Path, byte[]> files,
            Map<String, JsonNode> serviceConfigs,
            Map<String, JsonNode> specGroupConfigs
    ) throws JsonProcessingException {
        for (Map.Entry<String, JsonNode> specGroupConfig : specGroupConfigs.entrySet()) {
            String specGroupId = specGroupConfig.getKey();
            JsonNode specGroupNode = specGroupConfig.getValue();
            String serviceId = getParentId(specGroupNode);

            if (serviceId == null) {
                log.error("SpecGroup {} is missing /content/parentId", specGroupId);
                continue;
            }
            if (!serviceConfigs.containsKey(serviceId)) {
                log.error("SpecGroup {} refers to non-existing service {}", specGroupId, serviceId);
                continue;
            }

            Path serviceDirectory = Path.of(serviceId);
            String specGroupFileName = specGroupId + SPECIFICATION_GROUP_FILE_POSTFIX + appPrefix + YAML_FILE_NAME_POSTFIX;
            putYaml(files, serviceDirectory.resolve(specGroupFileName), specGroupNode);
        }
    }

    private void convertSpecifications(
            Map<Path, byte[]> files,
            Map<String, JsonNode> serviceConfigs,
            Map<String, JsonNode> specGroupConfigs,
            Map<String, JsonNode> specificationConfigs,
            Map<String, byte[]> resources
    ) throws JsonProcessingException {
        for (Map.Entry<String, JsonNode> specificationConfig : specificationConfigs.entrySet()) {
            String specificationId = specificationConfig.getKey();
            JsonNode specificationNode = specificationConfig.getValue();
            String specGroupId = getParentId(specificationNode);

            if (specGroupId == null) {
                log.error("Specification {} is missing /content/parentId", specificationId);
                continue;
            }

            JsonNode specGroupNode = specGroupConfigs.get(specGroupId);
            if (specGroupNode == null) {
                log.error("Specification {} refers to non-existing specGroup {}", specificationId, specGroupId);
                continue;
            }

            String serviceId = getParentId(specGroupNode);
            if (serviceId == null) {
                log.error("SpecGroup {} (from specification {}) is missing /content/parentId", specGroupId, specificationId);
                continue;
            }

            if (!serviceConfigs.containsKey(serviceId)) {
                log.error("Specification {} refers to non-existing service {}", specificationId, serviceId);
                continue;
            }

            Path serviceDirectory = Path.of(serviceId);
            String specificationFileName = specificationId + SPECIFICATION_FILE_POSTFIX + appPrefix + YAML_FILE_NAME_POSTFIX;
            putYaml(files, serviceDirectory.resolve(specificationFileName), specificationNode);

            List<Path> specPaths = specificationNode.findValuesAsText(SPECIFICATION_FILE_NAME_FIELD_KEY)
                    .stream()
                    .map(Paths::get)
                    .toList();
            for (Path specPath : specPaths) {
                String specFileName = specPath.getFileName().toString();
                if (resources.containsKey(specFileName)) {
                    files.put(serviceDirectory.resolve(specPath), resources.get(specFileName));
                } else {
                    log.error("Specification file name {} does not exist in package resources", specFileName);
                }
            }
        }
    }

    private static String getParentId(JsonNode node) {
        JsonNode parentIdNode = node.at("/content/parentId");
        return (parentIdNode.isMissingNode() || parentIdNode.isNull()) ? null : parentIdNode.asText();
    }

    private void putYaml(Map<Path, byte[]> files, Path path, JsonNode node) throws JsonProcessingException {
        files.put(path, objectMapper.writeValueAsBytes(node));
    }
}
