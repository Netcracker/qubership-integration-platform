package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.io.readers.migrations.chain.ChainImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.FILE_NAME_PROPERTY;
import static org.qubership.integration.platform.io.readers.migrations.ImportFileMigration.IMPORT_MIGRATIONS_FIELD;
import static org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils.generateChainFileExportName;

@Slf4j
@Component
public class ChainConfigurationsToFilesConverter {

    private static final String RESOURCES_FOLDER_PREFIX = "resources" + File.separator;

    private final ObjectMapper objectMapper;
    private final List<ChainImportFileMigration> chainImportFileMigrations;

    public ChainConfigurationsToFilesConverter(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            List<ChainImportFileMigration> chainImportFileMigrations
    ) {
        this.objectMapper = objectMapper;
        this.chainImportFileMigrations = chainImportFileMigrations;
    }

    public Map<Path, byte[]> convert(Map<String, RolloutImportConfigurationItem> chainConfigs, Map<String, String> resources)
            throws JacksonException {
        if (chainConfigs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Path, byte[]> files = new HashMap<>();
        for (Map.Entry<String, RolloutImportConfigurationItem> chainConfig : chainConfigs.entrySet()) {
            JsonNode contentNode = chainConfig.getValue().getContent();
            if (contentNode instanceof ObjectNode chainContent) {
                chainContent.putIfAbsent(
                        IMPORT_MIGRATIONS_FIELD,
                        StringNode.valueOf(MigrationUtil.formatVersions(chainImportFileMigrations))
                );
            }

            String chainId = chainConfig.getKey();
            Path chainDirectory = Path.of(chainId);
            String chainFileName = generateChainFileExportName(chainId, false);
            files.put(chainDirectory.resolve(chainFileName), objectMapper.writeValueAsBytes(chainConfig.getValue()));

            List<String> propertyFileNames = contentNode.findValuesAsString(FILE_NAME_PROPERTY);
            for (String propertyFileName : propertyFileNames) {
                String resourceName = propertyFileName.startsWith(RESOURCES_FOLDER_PREFIX)
                        ? propertyFileName.substring(RESOURCES_FOLDER_PREFIX.length())
                        : propertyFileName;

                if (resources.containsKey(resourceName)) {
                    files.put(chainDirectory.resolve(propertyFileName), resources.get(resourceName).getBytes());
                } else {
                    log.warn("Chain {} refers to missing resource file {}", chainId, propertyFileName);
                }
            }
        }

        return files;
    }
}
