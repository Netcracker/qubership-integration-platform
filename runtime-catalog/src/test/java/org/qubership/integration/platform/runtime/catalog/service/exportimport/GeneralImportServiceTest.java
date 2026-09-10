package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportContextServiceAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportSystemsAndInstructionsResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariablesResult;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.variables.CommonVariablesService;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.ARCH_PARENT_DIR;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CHAINS_ARCH_PARENT_DIR;

@ExtendWith(MockitoExtension.class)
class GeneralImportServiceTest {

    @Mock
    private CommonVariablesService commonVariablesService;
    @Mock
    private SystemExportImportService systemExportImportService;
    @Mock
    private ContextExportImportService contextExportImportService;
    @Mock
    private MCPSystemImportExportService mcpSystemImportExportService;
    @Mock
    private ChainImportService chainImportService;
    @Mock
    private ImportSessionService importSessionService;
    @Mock
    private ActionsLogService actionsLogService;
    @Mock
    private ImportInstructionsService importInstructionsService;
    @Mock
    private GeneralInstructionsMapper generalInstructionsMapper;

    @Captor
    private ArgumentCaptor<ActionLog> actionLogCaptor;
    @Captor
    private ArgumentCaptor<ImportSession> importSessionCaptor;

    private GeneralImportService createService() {
        return new GeneralImportService(
                commonVariablesService,
                systemExportImportService,
                contextExportImportService,
                mcpSystemImportExportService,
                chainImportService,
                importSessionService,
                actionsLogService,
                importInstructionsService,
                generalInstructionsMapper
        );
    }

    private void stubAsyncDependenciesForAnyImportId() {
        stubAsyncDependenciesWithMcpResults(List.of());
    }

    private void stubAsyncDependenciesWithMcpResults(List<ImportSystemResult> mcpResults) {
        when(commonVariablesService.importVariables(any(File.class), any()))
                .thenReturn(ImportVariablesResult.builder().variables(List.of()).instructions(List.of()).build());
        when(systemExportImportService.importSystems(any(File.class), any(), anyString(), any()))
                .thenReturn(new ImportSystemsAndInstructionsResult(List.of(), List.of()));
        when(contextExportImportService.importContextService(any(File.class), any(), anyString()))
                .thenReturn(new ImportContextServiceAndInstructionsResult(List.of(), List.of()));
        when(mcpSystemImportExportService.importSystems(any(File.class), any(), anyString()))
                .thenReturn(new ImportSystemsAndInstructionsResult(mcpResults, List.of()));
        when(chainImportService.importChains(any(File.class), any(), anyString(), any(), anyBoolean()))
                .thenReturn(new ImportChainsAndInstructionsResult(List.of(), List.of()));
    }

    private File importDirectoryWithOneChain(Path parent, String name) {
        File dir = parent.resolve(name).toFile();
        new File(dir, CHAINS_ARCH_PARENT_DIR + File.separator + "chain-id").mkdirs();
        return dir;
    }

    private CountDownLatch latchForSave() {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(importSessionService).saveImportSession(any());
        return latch;
    }

    private void awaitLatch(CountDownLatch latch) throws InterruptedException {
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }

    @DisplayName("importFileAsync logs ActionLog with entityId equal to returned importId")
    @Test
    void importFileAsyncLogsEntityIdEqualToReturnedImportId(@TempDir Path tempDir) {
        lenient().when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        File unpackedDir = tempDir.resolve("unpacked").toFile();
        unpackedDir.mkdirs();
        MockMultipartFile file = new MockMultipartFile("file", "archive.zip", "application/zip", new byte[]{1, 2, 3});
        ImportRequest req = mock(ImportRequest.class);
        GeneralImportService service = createService();

        try (MockedStatic<ExportImportUtils> mocked = mockStatic(ExportImportUtils.class)) {
            mocked.when(() -> ExportImportUtils.extractDirectoriesFromZip(any(InputStream.class), anyString()))
                    .thenReturn(unpackedDir);
            mocked.when(() -> ExportImportUtils.deleteFile(any(File.class))).thenAnswer(inv -> null);

            String importId = service.importFileAsync(file, req, Set.of("tech"), false);

            verify(actionsLogService).logAction(actionLogCaptor.capture());
            ActionLog logged = actionLogCaptor.getValue();
            assertThat(logged.getEntityType()).isEqualTo(EntityType.CHAINS);
            assertThat(logged.getOperation()).isEqualTo(LogOperation.IMPORT);
            assertThat(logged.getEntityName()).isEqualTo("archive.zip");
            assertThat(logged.getEntityId()).isEqualTo(importId);
            assertThat(logged.getEntityId()).isNotNull();
            verify(importSessionService).setImportProgressPercentage(importId, 0);
            verify(importSessionService).deleteObsoleteImportSessionStatuses();
        }
    }

    @DisplayName("importFileAsync handles null originalFilename and still sets entityId")
    @Test
    void importFileAsyncNullFilenameStillSetsEntityId(@TempDir Path tempDir) {
        lenient().when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        File unpackedDir = tempDir.resolve("unpacked2").toFile();
        unpackedDir.mkdirs();
        MockMultipartFile file = new MockMultipartFile("file", null, "application/zip", new byte[]{1, 2, 3});
        ImportRequest req = mock(ImportRequest.class);
        GeneralImportService service = createService();

        try (MockedStatic<ExportImportUtils> mocked = mockStatic(ExportImportUtils.class)) {
            mocked.when(() -> ExportImportUtils.extractDirectoriesFromZip(any(InputStream.class), anyString()))
                    .thenReturn(unpackedDir);
            mocked.when(() -> ExportImportUtils.deleteFile(any(File.class))).thenAnswer(inv -> null);

            String importId = service.importFileAsync(file, req, Set.of(), false);

            verify(actionsLogService).logAction(actionLogCaptor.capture());
            assertThat(actionLogCaptor.getValue().getEntityId()).isEqualTo(importId);
            assertThat(actionLogCaptor.getValue().getEntityName()).isIn(null, "");
            assertThat(actionLogCaptor.getValue().getEntityType()).isEqualTo(EntityType.CHAINS);
            assertThat(actionLogCaptor.getValue().getOperation()).isEqualTo(LogOperation.IMPORT);
        }
    }

    @DisplayName("importFileAsync propagates same importId to directory import")
    @Test
    void importFileAsyncPropagatesSameImportIdToDirectoryImport(@TempDir Path tempDir) throws Exception {
        File unpackedDir = importDirectoryWithOneChain(tempDir, "unpacked3");
        MockMultipartFile file = new MockMultipartFile("file", "my.zip", "application/zip", new byte[]{5, 6});
        ImportRequest req = mock(ImportRequest.class);
        when(req.getVariablesCommitRequest()).thenReturn(null);
        when(req.getSystemsCommitRequest()).thenReturn(null);
        when(req.getChainCommitRequests()).thenReturn(null);
        when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.json");
        GeneralImportService service = createService();

        try (MockedStatic<ExportImportUtils> mocked = mockStatic(ExportImportUtils.class)) {
            mocked.when(() -> ExportImportUtils.extractDirectoriesFromZip(any(InputStream.class), anyString()))
                    .thenReturn(unpackedDir);
            mocked.when(() -> ExportImportUtils.deleteFile(any(File.class))).thenAnswer(inv -> null);
            stubAsyncDependenciesForAnyImportId();
            CountDownLatch latch = latchForSave();

            String importId = service.importFileAsync(file, req, Set.of("tech-label"), true);

            verify(actionsLogService).logAction(actionLogCaptor.capture());
            assertThat(actionLogCaptor.getValue().getEntityId()).isEqualTo(importId);

            awaitLatch(latch);
            verify(systemExportImportService).importSystems(eq(unpackedDir), any(), eq(importId), eq(Set.of("tech-label")));
            verify(chainImportService).importChains(eq(unpackedDir), any(), eq(importId), eq(Set.of("tech-label")), eq(true));
        }
    }

    @DisplayName("importDirectoryAsync 4-arg generates UUID and initiates session")
    @Test
    void importDirectoryAsyncFourArgGeneratesUuid(@TempDir Path tmp) {
        lenient().when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        File dir = tmp.resolve("dir1").toFile();
        dir.mkdirs();
        ImportRequest req = mock(ImportRequest.class);
        GeneralImportService service = createService();

        String importId = service.importDirectoryAsync(dir, req, Set.of(), false);

        assertThat(importId).isNotNull();
        assertThatNoException().isThrownBy(() -> UUID.fromString(importId));
        verify(importSessionService).deleteObsoleteImportSessionStatuses();
        verify(importSessionService).setImportProgressPercentage(importId, 0);
    }

    @DisplayName("importDirectoryAsync 5-arg reuses provided importId")
    @Test
    void importDirectoryAsyncFiveArgReusesProvidedId(@TempDir Path tmp) {
        lenient().when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        File dir = tmp.resolve("dir1").toFile();
        dir.mkdirs();
        ImportRequest req = mock(ImportRequest.class);
        GeneralImportService service = createService();
        String fixed = "fixed-import-id-123";

        String result = service.importDirectoryAsync(dir, req, Set.of(), false, fixed);

        assertThat(result).isEqualTo(fixed);
        verify(importSessionService).setImportProgressPercentage(fixed, 0);
        verify(importSessionService, never()).setImportProgressPercentage(eq("other-id"), any(int.class));
    }

    @DisplayName("importDirectoryAsync 5-arg with null generates new UUID")
    @Test
    void importDirectoryAsyncFiveArgNullGeneratesUuid(@TempDir Path tmp) {
        lenient().when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        File dir1 = tmp.resolve("dir1").toFile();
        File dir2 = tmp.resolve("dir2").toFile();
        dir1.mkdirs();
        dir2.mkdirs();
        ImportRequest req = mock(ImportRequest.class);
        GeneralImportService service = createService();

        String id1 = service.importDirectoryAsync(dir1, req, Set.of(), false, null);
        String id2 = service.importDirectoryAsync(dir2, req, Set.of(), false, null);

        assertThat(id1).isNotNull().isNotEqualTo(id2);
        assertThatNoException().isThrownBy(() -> UUID.fromString(id1));
        assertThatNoException().isThrownBy(() -> UUID.fromString(id2));
    }

    @DisplayName("importDirectoryAsync propagates same importId to all collaborators")
    @Test
    void importDirectoryAsyncPropagatesSameImportIdToAllCollaborators(@TempDir Path tmp) throws Exception {
        File dir = importDirectoryWithOneChain(tmp, "dir1");
        ImportRequest req = mock(ImportRequest.class);
        when(req.getVariablesCommitRequest()).thenReturn(null);
        when(req.getSystemsCommitRequest()).thenReturn(null);
        when(req.getChainCommitRequests()).thenReturn(null);
        when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.json");
        GeneralImportService service = createService();
        String fixed = "correlation-id-xyz";
        stubAsyncDependenciesForAnyImportId();
        CountDownLatch latch = latchForSave();

        String result = service.importDirectoryAsync(dir, req, Set.of("tech-label"), true, fixed);

        assertThat(result).isEqualTo(fixed);
        awaitLatch(latch);
        verify(systemExportImportService).importSystems(eq(dir), any(), eq(fixed), eq(Set.of("tech-label")));
        verify(contextExportImportService).importContextService(eq(dir), any(), eq(fixed));
        verify(mcpSystemImportExportService).importSystems(eq(dir), any(), eq(fixed));
        verify(chainImportService).importChains(eq(dir), any(), eq(fixed), eq(Set.of("tech-label")), eq(true));
    }

    @DisplayName("importDirectoryAsync 4-arg delegates to 5-arg with null importId")
    @Test
    void importDirectoryAsyncFourArgDelegatesWithNull(@TempDir Path tmp) {
        lenient().when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        File dir1 = tmp.resolve("dir1").toFile();
        File dir2 = tmp.resolve("dir2").toFile();
        dir1.mkdirs();
        dir2.mkdirs();
        ImportRequest req = mock(ImportRequest.class);
        GeneralImportService service = createService();

        String id1 = service.importDirectoryAsync(dir1, req, Set.of(), false);
        String id2 = service.importDirectoryAsync(dir2, req, Set.of(), false, null);

        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id1).isNotEqualTo(id2);
        verify(importSessionService, times(2)).deleteObsoleteImportSessionStatuses();
    }

    private ImportSession runImportAndCaptureSession(File dir) throws Exception {
        ImportRequest req = mock(ImportRequest.class);
        when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.yaml");
        GeneralImportService service = createService();
        CountDownLatch latch = latchForSave();

        service.importDirectoryAsync(dir, req, Set.of(), false);

        awaitLatch(latch);
        verify(importSessionService).saveImportSession(importSessionCaptor.capture());
        return importSessionCaptor.getValue();
    }

    @DisplayName("importDirectoryAsync reports an error naming the layout when the archive holds nothing importable")
    @Test
    void importDirectoryAsyncReportsErrorWhenArchiveHoldsNothingImportable(@TempDir Path tmp) throws Exception {
        File dir = tmp.resolve("junk-only").toFile();
        new File(dir, "junk").mkdirs();

        ImportSession session = runImportAndCaptureSession(dir);

        assertThat(session.getError())
                .contains("Nothing to import")
                .contains("chains/<id>/")
                .contains("services/<id>/")
                .contains("variables/common-variables.yaml")
                .contains("import-instructions.yaml");
        assertThat(session.getResult()).isNull();
        verifyNoInteractions(chainImportService, systemExportImportService, contextExportImportService,
                mcpSystemImportExportService, commonVariablesService);
    }

    @DisplayName("importDirectoryAsync accepts an archive that only carries a services directory")
    @Test
    void importDirectoryAsyncAcceptsServicesOnlyArchive(@TempDir Path tmp) throws Exception {
        File dir = mcpServiceArchive(tmp, "services-only");
        stubAsyncDependenciesForAnyImportId();

        assertThat(runImportAndCaptureSession(dir).getError()).isNull();
    }

    @DisplayName("importDirectoryAsync reports the MCP services it imported")
    @Test
    void importDirectoryAsyncReportsImportedMcpServices(@TempDir Path tmp) throws Exception {
        File dir = mcpServiceArchive(tmp, "mcp-only");
        ImportSystemResult mcpResult = new ImportSystemResult();
        mcpResult.setId("mcp-id");
        mcpResult.setName("mcp-name");
        stubAsyncDependenciesWithMcpResults(List.of(mcpResult));

        ImportSession session = runImportAndCaptureSession(dir);

        assertThat(session.getError()).isNull();
        assertThat(session.getResult().getMcpService()).singleElement()
                .satisfies(result -> assertThat(result.getId()).isEqualTo("mcp-id"));
    }

    private File mcpServiceArchive(Path parent, String name) throws Exception {
        File dir = parent.resolve(name).toFile();
        File serviceDir = new File(dir, ARCH_PARENT_DIR + File.separator + "service-id");
        serviceDir.mkdirs();
        Files.writeString(serviceDir.toPath().resolve("service-id.mcp-service.qip.yaml"), "id: service-id\n");
        return dir;
    }

    @DisplayName("importDirectoryAsync accepts an archive that only carries import instructions")
    @Test
    void importDirectoryAsyncAcceptsInstructionsOnlyArchive(@TempDir Path tmp) throws Exception {
        File dir = tmp.resolve("instructions-only").toFile();
        dir.mkdirs();
        Files.writeString(dir.toPath().resolve("import-instructions.yaml"), "chains:\n  ignore: []\n");
        when(importInstructionsService.uploadImportInstructionsConfig(any(File.class), any())).thenReturn(List.of());
        stubAsyncDependenciesForAnyImportId();

        assertThat(runImportAndCaptureSession(dir).getError()).isNull();
    }

    @DisplayName("importDirectoryAsync reports an error when chains holds files instead of chain directories")
    @Test
    void importDirectoryAsyncReportsErrorWhenChainsHoldsNoDirectories(@TempDir Path tmp) throws Exception {
        File dir = tmp.resolve("flattened-chains").toFile();
        File chainsDir = new File(dir, CHAINS_ARCH_PARENT_DIR);
        chainsDir.mkdirs();
        Files.writeString(chainsDir.toPath().resolve("chain-id.chain.qip.yaml"), "id: chain-id\n");

        assertThat(runImportAndCaptureSession(dir).getError()).contains("Nothing to import");
    }
}
