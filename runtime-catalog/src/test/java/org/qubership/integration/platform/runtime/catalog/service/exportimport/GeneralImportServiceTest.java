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
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariablesResult;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.GeneralInstructionsMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private void stubAsyncDependenciesForImportId(String importId) {
        when(commonVariablesService.importVariables(any(File.class), any()))
                .thenReturn(ImportVariablesResult.builder().variables(List.of()).instructions(List.of()).build());
        when(systemExportImportService.importSystems(any(File.class), any(), eq(importId), any()))
                .thenReturn(new ImportSystemsAndInstructionsResult(List.of(), List.of()));
        when(contextExportImportService.importContextService(any(File.class), any(), eq(importId)))
                .thenReturn(new ImportContextServiceAndInstructionsResult(List.of(), List.of()));
        when(mcpSystemImportExportService.importSystems(any(File.class), any(), eq(importId)))
                .thenReturn(new ImportSystemsAndInstructionsResult(List.of(), List.of()));
        when(chainImportService.importChains(any(File.class), any(), eq(importId), any(), anyBoolean()))
                .thenReturn(new ImportChainsAndInstructionsResult(List.of(), List.of()));
    }

    private void stubAsyncDependenciesForAnyImportId() {
        when(commonVariablesService.importVariables(any(File.class), any()))
                .thenReturn(ImportVariablesResult.builder().variables(List.of()).instructions(List.of()).build());
        when(systemExportImportService.importSystems(any(File.class), any(), anyString(), any()))
                .thenReturn(new ImportSystemsAndInstructionsResult(List.of(), List.of()));
        when(contextExportImportService.importContextService(any(File.class), any(), anyString()))
                .thenReturn(new ImportContextServiceAndInstructionsResult(List.of(), List.of()));
        when(mcpSystemImportExportService.importSystems(any(File.class), any(), anyString()))
                .thenReturn(new ImportSystemsAndInstructionsResult(List.of(), List.of()));
        when(chainImportService.importChains(any(File.class), any(), anyString(), any(), anyBoolean()))
                .thenReturn(new ImportChainsAndInstructionsResult(List.of(), List.of()));
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
        File unpackedDir = tempDir.resolve("unpacked3").toFile();
        unpackedDir.mkdirs();
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
        File dir = tmp.resolve("dir1").toFile();
        dir.mkdirs();
        ImportRequest req = mock(ImportRequest.class);
        when(req.getVariablesCommitRequest()).thenReturn(null);
        when(req.getSystemsCommitRequest()).thenReturn(null);
        when(req.getChainCommitRequests()).thenReturn(null);
        when(importInstructionsService.getInstructionsFileName()).thenReturn("import-instructions.json");
        GeneralImportService service = createService();
        String fixed = "correlation-id-xyz";
        stubAsyncDependenciesForImportId(fixed);
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
}
