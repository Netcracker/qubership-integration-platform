package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportPackageContent;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.ChainConfigurationsToFilesConverter;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.ServiceConfigurationsToFilesConverter;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.VariableConfigurationsToFilesConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolloutImportSnapshotToImportDirectoryServiceTest {

    private static final String SNAPSHOT_ID = "snapshot-test-1";

    @Mock
    ImportConfigFactory importConfigFactory;
    @Mock
    ChainConfigurationsToFilesConverter chainsToFilesConverter;
    @Mock
    ServiceConfigurationsToFilesConverter servicesToFilesConverter;
    @Mock
    VariableConfigurationsToFilesConverter variablesToFileConverter;

    RolloutImportSnapshotToImportDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new RolloutImportSnapshotToImportDirectoryService(
                importConfigFactory,
                chainsToFilesConverter,
                servicesToFilesConverter,
                variablesToFileConverter
        );
    }

    // toImportConfig tests

    @Test
    @DisplayName("toImportConfig with null request delegates to importConfigFactory.empty()")
    void toImportConfigNullRequestDelegatesToEmpty() {
        ImportConfig emptyConfig = emptyImportConfig();
        when(importConfigFactory.empty()).thenReturn(emptyConfig);

        ImportConfig result = service.toImportConfig(null, SNAPSHOT_ID);

        verify(importConfigFactory).empty();
        assertThat(result).isSameAs(emptyConfig);
    }

    @Test
    @DisplayName("toImportConfig with null packageContent delegates to importConfigFactory.empty()")
    void toImportConfigNullPackageContentDelegatesToEmpty() {
        RolloutImportConfigurationRequest request = new RolloutImportConfigurationRequest();
        ImportConfig emptyConfig = emptyImportConfig();
        when(importConfigFactory.empty()).thenReturn(emptyConfig);

        ImportConfig result = service.toImportConfig(request, SNAPSHOT_ID);

        verify(importConfigFactory).empty();
        assertThat(result).isSameAs(emptyConfig);
    }

    @Test
    @DisplayName("toImportConfig with valid packageContent calls importConfigFactory.fromPackageContent")
    void toImportConfigValidRequestCallsFromPackageContent() {
        RolloutImportPackageContent packageContent = new RolloutImportPackageContent();
        RolloutImportConfigurationRequest request = new RolloutImportConfigurationRequest();
        request.setPackageContent(packageContent);

        ImportConfig expected = emptyImportConfig();
        when(importConfigFactory.fromPackageContent(packageContent)).thenReturn(expected);

        ImportConfig result = service.toImportConfig(request, SNAPSHOT_ID);

        verify(importConfigFactory).fromPackageContent(packageContent);
        assertThat(result).isSameAs(expected);
    }

    // writeImportDirectory tests

    @Test
    @DisplayName("writeImportDirectory with empty chain and service maps creates root dir with no subdirectories")
    void writeImportDirectoryEmptyMapsCreatesRootDirOnly() throws IOException {
        ImportConfig importConfig = emptyImportConfig();
        when(chainsToFilesConverter.convert(any(), any())).thenReturn(Collections.emptyMap());
        when(servicesToFilesConverter.convert(any(), any(), any(), any(), any())).thenReturn(Collections.emptyMap());

        File rootDir = service.writeImportDirectory(importConfig);

        assertThat(rootDir).exists().isDirectory();
        assertThat(rootDir.listFiles()).isEmpty();

        deleteRecursively(rootDir);
    }

    @Test
    @DisplayName("writeImportDirectory with chain files creates chains/ subdirectory with the file")
    void writeImportDirectoryWithChainFilesCreatesChainSubdir() throws IOException {
        byte[] content = "chain-content".getBytes();
        Map<Path, byte[]> chainFiles = Map.of(Path.of("chain-id/chain-id.chain.qip.yaml"), content);

        ImportConfig importConfig = emptyImportConfig();
        when(chainsToFilesConverter.convert(any(), any())).thenReturn(chainFiles);
        when(servicesToFilesConverter.convert(any(), any(), any(), any(), any())).thenReturn(Collections.emptyMap());

        File rootDir = service.writeImportDirectory(importConfig);

        File chainsDir = new File(rootDir, "chains");
        assertThat(chainsDir).exists().isDirectory();

        File chainFile = new File(chainsDir, "chain-id/chain-id.chain.qip.yaml");
        assertThat(chainFile).exists();
        assertThat(chainFile).hasBinaryContent(content);

        deleteRecursively(rootDir);
    }

    // helpers

    private void deleteRecursively(File dir) throws IOException {
        try (var paths = Files.walk(dir.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private ImportConfig emptyImportConfig() {
        return new ImportConfig(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }
}
