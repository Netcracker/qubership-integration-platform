package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;
import org.qubership.integration.platform.io.readers.chain.ChainModelMapper;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {

    private static final String CHAIN_YAML = """
            id: c1
            name: c1
            content:
              elements: []
            """;

    @Mock
    private ChainExternalEntityMapper chainExternalEntityMapper;
    @Mock
    private ChainModelMapper chainModelMapper;
    @Mock
    private ActionsLogService actionsLogService;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private EngineService engineService;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private ChainRepository chainRepository;
    @Mock
    private ImportSessionService importSessionService;
    @Mock
    private ChainImportService chainImportService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private FileMigrationService fileMigrationService;

    private ImportService service;

    @BeforeEach
    void setUp() {
        MapperAutoConfiguration mappers = new MapperAutoConfiguration();
        service = new ImportService(chainExternalEntityMapper, chainModelMapper, mappers.yamlExportImportMapper(true),
                mappers.primaryObjectMapper(), actionsLogService, deploymentService, snapshotService, engineService,
                chainFinderService, chainRepository, importSessionService, chainImportService, transactionTemplate,
                fileMigrationService, List.of());
    }

    private static MockMultipartFile archive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("chains/c1/c1.chain.cip.yaml"));
            zip.write(CHAIN_YAML.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("file", "export.zip", "application/zip", bytes.toByteArray());
    }

    private static ImportChainResult restored(String id) {
        return ImportChainResult.builder()
                .id(id)
                .status(ImportEntityStatus.CREATED)
                .deployAction(ChainCommitRequestAction.SNAPSHOT)
                .build();
    }

    private void archiveRestores(List<ChainCommitRequest> commitRequests, String... chainIds) {
        when(chainImportService.restoreChainsFromDirBackward(any(), eq(commitRequests), any(), any()))
                .thenReturn(Arrays.stream(chainIds).map(ImportServiceTest::restored).toList());
    }

    @Test
    void nullListSnapshotsEveryRestoredChain() throws Exception {
        archiveRestores(null, "c1", "c2");

        service.importFile(archive(), null, Set.of());

        verify(snapshotService).build(eq("c1"), any());
        verify(snapshotService).build(eq("c2"), any());
    }

    @Test
    void emptyListSnapshotsNoChain() throws Exception {
        archiveRestores(List.of(), "c1");

        service.importFile(archive(), List.of(), Set.of());

        verifyNoInteractions(snapshotService);
    }

    @Test
    void listSnapshotsOnlyTheChainsItNames() throws Exception {
        List<ChainCommitRequest> requests = List.of(
                ChainCommitRequest.builder().id("c1").deployAction(ChainCommitRequestAction.SNAPSHOT).build());
        archiveRestores(requests, "c1", "c2");

        service.importFile(archive(), requests, Set.of());

        verify(snapshotService).build(eq("c1"), any());
        verify(snapshotService, never()).build(eq("c2"), any());
    }

    @Test
    void emptyListStillSnapshotsAChainImportedFromAYamlFile() throws Exception {
        when(fileMigrationService.migrate(anyString(), anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chainExternalEntityMapper.toInternalEntity(any())).thenReturn(Chain.builder().id("c1").name("c1").build());
        MockMultipartFile yaml = new MockMultipartFile(
                "file", "c1.yaml", "application/yaml", CHAIN_YAML.getBytes(StandardCharsets.UTF_8));

        service.importFile(yaml, List.of(), Set.of());

        verify(snapshotService).build(eq("c1"), any());
    }

    @Test
    void emptyListSavesNoChainFromYaml() throws Exception {
        when(fileMigrationService.migrate(anyString(), anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));

        assertNull(service.restoreChainFromYaml(CHAIN_YAML, null, List.of(), Set.of()));

        verify(chainImportService, never()).saveImportedChainBackward(any());
    }
}
