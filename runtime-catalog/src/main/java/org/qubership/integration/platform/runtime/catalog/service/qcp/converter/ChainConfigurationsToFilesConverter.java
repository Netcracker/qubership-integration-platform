package org.qubership.integration.platform.runtime.catalog.service.qcp.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.CHAIN_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.FILE_NAME_PROPERTY;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.YAML_FILE_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;

@Slf4j
@Component
public class ChainConfigurationsToFilesConverter {

    private static final String RESOURCES_FOLDER_PREFIX = "resources" + File.separator;

    private final ObjectMapper objectMapper;
    private final String appPrefix;
    private final List<ChainImportFileMigration> chainImportFileMigrations;

    public ChainConfigurationsToFilesConverter(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            @Value("${app.prefix:qip}") String appPrefix,
            List<ChainImportFileMigration> chainImportFileMigrations
    ) {
        this.objectMapper = objectMapper;
        this.appPrefix = appPrefix;
        this.chainImportFileMigrations = chainImportFileMigrations;
    }

    public Map<Path, byte[]> convert(Map<String, JsonNode> chainConfigs, Map<String, byte[]> resources)
            throws JsonProcessingException {
        if (chainConfigs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Path, byte[]> files = new HashMap<>();
        for (Map.Entry<String, JsonNode> chainConfig : chainConfigs.entrySet()) {
            ObjectNode chainNode = (ObjectNode) chainConfig.getValue();
            JsonNode contentNode = chainNode.get("content");
            if (contentNode instanceof ObjectNode chainContent) {
                chainContent.putIfAbsent(
                        IMPORT_MIGRATIONS_FIELD,
                        TextNode.valueOf(MigrationUtil.formatVersions(chainImportFileMigrations))
                );
            }

            String chainId = chainConfig.getKey();
            Path chainDirectory = Path.of(chainId);
            String chainFileName = chainId + CHAIN_YAML_NAME_POSTFIX + appPrefix + YAML_FILE_NAME_POSTFIX;
            files.put(chainDirectory.resolve(chainFileName), objectMapper.writeValueAsBytes(chainNode));

            List<String> propertyFileNames = chainConfig.getValue().findValuesAsText(FILE_NAME_PROPERTY);
            for (String propertyFileName : propertyFileNames) {
                String resourceName = propertyFileName.startsWith(RESOURCES_FOLDER_PREFIX)
                        ? propertyFileName.substring(RESOURCES_FOLDER_PREFIX.length())
                        : propertyFileName;

                if (resources.containsKey(resourceName)) {
                    files.put(chainDirectory.resolve(propertyFileName), resources.get(resourceName));
                } else {
                    log.warn("Chain {} refers to missing resource file {}", chainId, propertyFileName);
                }
            }
        }

        return files;
    }
}
