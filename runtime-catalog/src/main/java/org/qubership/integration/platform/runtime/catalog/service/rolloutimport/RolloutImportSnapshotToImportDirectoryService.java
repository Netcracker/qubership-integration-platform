package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportPackageContent;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.ChainConfigurationsToFilesConverter;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.ServiceConfigurationsToFilesConverter;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.VariableConfigurationsToFilesConverter;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.CHAINS_DIR_NAME;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.SERVICES_DIR_NAME;

@Slf4j
@Service
public class RolloutImportSnapshotToImportDirectoryService {

    private final ImportConfigFactory importConfigFactory;
    private final ChainConfigurationsToFilesConverter chainsToFilesConverter;
    private final ServiceConfigurationsToFilesConverter servicesToFilesConverter;
    private final VariableConfigurationsToFilesConverter variablesToFileConverter;

    public RolloutImportSnapshotToImportDirectoryService(
            ImportConfigFactory importConfigFactory,
            ChainConfigurationsToFilesConverter chainsToFilesConverter,
            ServiceConfigurationsToFilesConverter servicesToFilesConverter,
            VariableConfigurationsToFilesConverter variablesToFileConverter
    ) {
        this.importConfigFactory = importConfigFactory;
        this.chainsToFilesConverter = chainsToFilesConverter;
        this.servicesToFilesConverter = servicesToFilesConverter;
        this.variablesToFileConverter = variablesToFileConverter;
    }

    public ImportConfig toImportConfig(RolloutImportConfigurationRequest request, String snapshotId) {
        if (request == null) {
            log.warn("Request body is null for snapshotId={}", snapshotId);
            return importConfigFactory.empty();
        }
        if (request.getPackageContent() == null) {
            log.warn("packageContent is null for snapshot={}", snapshotId);
            return importConfigFactory.empty();
        }

        RolloutImportPackageContent packageContent = request.getPackageContent();
        return importConfigFactory.fromPackageContent(packageContent);
    }

    public java.io.File writeImportDirectory(ImportConfig importConfig) throws IOException {
        java.io.File rootDir = Files.createTempDirectory("rollout-import-" + UUID.randomUUID()).toFile();

        try {
            Map<Path, byte[]> chainFiles = chainsToFilesConverter.convert(importConfig.getChains(), importConfig.getResources());
            writeSection(rootDir, CHAINS_DIR_NAME, chainFiles);

            Map<Path, byte[]> serviceFiles = servicesToFilesConverter.convert(
                    importConfig.getServices(),
                    importConfig.getSpecifications(),
                    importConfig.getSpecificationGroups(),
                    importConfig.getContextServices(),
                    importConfig.getResources()
            );
            writeSection(rootDir, SERVICES_DIR_NAME, serviceFiles);

            return rootDir;
        } catch (JsonProcessingException e) {
            log.error("JSON conversion failed for import directory {}", rootDir.getAbsolutePath(), e);
            throw new IOException("Failed to convert rollout import snapshot package to import directory", e);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to write import directory {}, cleaning up", rootDir.getAbsolutePath(), e);
            deleteQuietly(rootDir);
            throw e;
        }
    }

    private void writeSection(java.io.File rootDir, String sectionName, Map<Path, byte[]> files) throws IOException {
        if (files.isEmpty()) {
            return;
        }
        java.io.File sectionDir = new java.io.File(rootDir, sectionName);
        Files.createDirectories(sectionDir.toPath());
        for (Map.Entry<Path, byte[]> entry : files.entrySet()) {
            Path targetPath = sectionDir.toPath().resolve(entry.getKey());
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, entry.getValue());
        }
    }

    private void deleteQuietly(java.io.File directory) {
        if (directory == null) {
            return;
        }
        try {
            ExportImportUtils.deleteFile(directory);
            log.info("Deleted temp directory {}", directory.getAbsolutePath());
        } catch (Exception ex) {
            log.warn("Failed to delete temporary rollout import directory {}", directory, ex);
        }
    }
}
