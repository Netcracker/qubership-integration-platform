package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.STATUS_ROLLOUT_FAILED;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.STATUS_ROLLOUT_SUCCESS;

@ExtendWith(MockitoExtension.class)
class RolloutImportServiceTest {

    private static final String SNAPSHOT_ID = "snapshot-id";
    private static final String CALLBACK_URL = "http://callback";
    private static final String IMPORT_ID = "import-id";

    @Mock
    private RolloutImportSnapshotToImportDirectoryService snapshotToImportDirectoryService;
    @Mock
    private GeneralImportService generalImportService;
    @Mock
    private RolloutImportCallbackClient rolloutImportCallbackClient;

    @Captor
    private ArgumentCaptor<RolloutImportSnapshotClientResponse> responseCaptor;

    private ImportConfig configWith(Map<String, ?> specificationGroups, Map<String, ?> chains) {
        return new ImportConfig(
                castMap(chains), Map.of(), castMap(specificationGroups),
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private <T> Map<String, T> castMap(Map<String, ?> map) {
        return (Map<String, T>) map;
    }

    private RolloutImportSnapshotClientResponse runRollout(File importDirectory, ImportConfig config, ImportSession session) throws Exception {
        when(snapshotToImportDirectoryService.toImportConfig(any(), anyString())).thenReturn(config);
        when(snapshotToImportDirectoryService.writeImportDirectory(config)).thenReturn(importDirectory);
        when(generalImportService.importDirectoryAsync(eq(importDirectory), any(), eq(Set.of()), anyBoolean()))
                .thenReturn(IMPORT_ID);
        when(generalImportService.getImportSession(IMPORT_ID)).thenReturn(session);

        new RolloutImportService(snapshotToImportDirectoryService, generalImportService, rolloutImportCallbackClient)
                .processAsync(SNAPSHOT_ID, mock(RolloutImportConfigurationRequest.class), CALLBACK_URL);

        verify(rolloutImportCallbackClient).sendCallback(eq(SNAPSHOT_ID), eq(CALLBACK_URL), responseCaptor.capture());
        return responseCaptor.getValue();
    }

    private ImportSession finishedSession() {
        ImportSession session = new ImportSession();
        session.setId(IMPORT_ID);
        session.setCompletion(100);
        return session;
    }

    @DisplayName("A rollout whose import produced no result reports the import error")
    @Test
    void rolloutReportsImportErrorWhenResultIsMissing(@TempDir Path tmp) throws Exception {
        File dir = tmp.resolve("rollout").toFile();
        dir.mkdirs();
        ImportSession session = finishedSession();
        session.setError("Nothing to import: the archive contains no chains, services, variables, or import instructions.");

        RolloutImportSnapshotClientResponse response =
                runRollout(dir, configWith(Map.of("group", mock(Object.class)), Map.of()), session);

        assertThat(response.getStatus()).isEqualTo(STATUS_ROLLOUT_FAILED);
        assertThat(response.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getMessage()).contains("Nothing to import"));
    }

    @DisplayName("A rollout whose import produced a clean result reports success")
    @Test
    void rolloutReportsSuccessWhenResultIsClean(@TempDir Path tmp) throws Exception {
        File dir = tmp.resolve("rollout-ok").toFile();
        dir.mkdirs();
        ImportSession session = finishedSession();
        session.setResult(new ImportResult());

        RolloutImportSnapshotClientResponse response =
                runRollout(dir, configWith(Map.of(), Map.of("chain", mock(Object.class))), session);

        assertThat(response.getStatus()).isEqualTo(STATUS_ROLLOUT_SUCCESS);
    }
}
