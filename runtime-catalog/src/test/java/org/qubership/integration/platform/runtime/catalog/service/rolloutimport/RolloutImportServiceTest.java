package org.qubership.integration.platform.runtime.catalog.service.rolloutimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportSnapshotClientResponse;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GeneralImportService;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.STATUS_ROLLOUT_FAILED;
import static org.qubership.integration.platform.runtime.catalog.model.constant.RolloutImportConstants.STATUS_ROLLOUT_SUCCESS;

/**
 * What the rollout caller is told about a catalog import. The status is derived from
 * {@link ImportResult#hasErrors()} alone, so every row an import service adds decides it: one row too many turns a
 * package that landed correctly into a reported rollout failure.
 */
@ExtendWith(MockitoExtension.class)
class RolloutImportServiceTest {

    private static final String SNAPSHOT_ID = "snapshot-1";
    private static final String IMPORT_ID = "import-1";
    private static final String CALLBACK_URL = "http://rollout/callback";
    private static final String CONTEXT_SERVICE_ID = "service-ctx";

    @Mock RolloutImportSnapshotToImportDirectoryService snapshotToImportDirectoryService;
    @Mock GeneralImportService generalImportService;
    @Mock RolloutImportCallbackClient rolloutImportCallbackClient;

    @TempDir Path importDirectory;

    /** The package the reviewed defect was found on: one context service whose id wears the legacy flat prefix. */
    @Test
    @DisplayName("a rollout whose import reports no error row calls back with success")
    void rolloutOfAContextServiceReportsSuccess() throws IOException {
        importSessionWith(ImportResult.builder()
                .contextService(List.of(ImportSystemResult.builder()
                        .id(CONTEXT_SERVICE_ID)
                        .name("Discovered context service")
                        .status(ImportSystemStatus.CREATED)
                        .build()))
                .build());

        service().processAsync(SNAPSHOT_ID, new RolloutImportConfigurationRequest(), CALLBACK_URL);

        RolloutImportSnapshotClientResponse response = callback();
        assertEquals(STATUS_ROLLOUT_SUCCESS, response.getStatus());
        assertNull(response.getErrors());
    }

    @Test
    @DisplayName("a rollout whose import reports an error row calls back with failure")
    void rolloutWithAnErrorRowReportsFailure() throws IOException {
        importSessionWith(ImportResult.builder()
                .systems(List.of(ImportSystemResult.builder()
                        .id("svc-external")
                        .name("Orders service")
                        .status(ImportSystemStatus.ERROR)
                        .message("Service file states no service type")
                        .build()))
                .build());

        service().processAsync(SNAPSHOT_ID, new RolloutImportConfigurationRequest(), CALLBACK_URL);

        RolloutImportSnapshotClientResponse response = callback();
        assertEquals(STATUS_ROLLOUT_FAILED, response.getStatus());
        assertEquals(1, response.getErrors().size());
        assertTrue(response.getErrors().getFirst().getMessage().contains("svc-external"),
                () -> "expected the failing service to be named: " + response.getErrors().getFirst().getMessage());
    }

    private RolloutImportService service() {
        RolloutImportService service = new RolloutImportService(
                snapshotToImportDirectoryService, generalImportService, rolloutImportCallbackClient);
        ReflectionTestUtils.setField(service, "clientId", "qip-runtime-catalog");
        ReflectionTestUtils.setField(service, "namespace", "qip");
        return service;
    }

    private void importSessionWith(ImportResult result) throws IOException {
        when(snapshotToImportDirectoryService.toImportConfig(any(), eq(SNAPSHOT_ID))).thenReturn(oneServicePackage());
        when(snapshotToImportDirectoryService.writeImportDirectory(any())).thenReturn(importDirectory.toFile());
        when(generalImportService.importDirectoryAsync(any(File.class), any(ImportRequest.class), anySet(), anyBoolean()))
                .thenReturn(IMPORT_ID);
        when(generalImportService.getImportSession(IMPORT_ID))
                .thenReturn(ImportSession.builder().id(IMPORT_ID).completion(100).result(result).build());
    }

    private RolloutImportSnapshotClientResponse callback() {
        ArgumentCaptor<RolloutImportSnapshotClientResponse> captor =
                ArgumentCaptor.forClass(RolloutImportSnapshotClientResponse.class);
        verify(rolloutImportCallbackClient).sendCallback(eq(SNAPSHOT_ID), eq(CALLBACK_URL), captor.capture());
        return captor.getValue();
    }

    /** Non-empty, or the rollout fails before the import starts. */
    private static ImportConfig oneServicePackage() {
        return new ImportConfig(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(CONTEXT_SERVICE_ID, new RolloutImportConfigurationItem()), Map.of());
    }
}
