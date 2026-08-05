package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemBaseService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemExportImportServiceTest {

    private static final String SYSTEM_ID = "system-1";
    private static final String SYSTEM_NAME = "Test service";

    @Mock TransactionTemplate transactionTemplate;
    @Mock SystemService systemService;
    @Mock EnvironmentService environmentService;
    @Mock SystemModelService systemModelService;
    @Mock ActionsLogService actionLogger;
    @Mock AuditingHandler auditingHandler;
    @Mock ServiceSerializer serviceSerializer;
    @Mock ServiceDeserializer serviceDeserializer;
    @Mock ArchiveWriter archiveWriter;
    @Mock ImportSessionService importProgressService;
    @Mock ImportInstructionsService importInstructionsService;
    @Mock ElementHelperService elementHelperService;
    @Mock ChainService chainService;
    @Mock ApiGroupService apiGroupService;

    @TempDir Path tempDir;

    private SystemExportImportService service;
    private File serviceFile;

    @BeforeEach
    void setUp() throws IOException {
        service = new SystemExportImportService(
                transactionTemplate,
                systemService,
                environmentService,
                systemModelService,
                new YAMLMapper(),
                actionLogger,
                auditingHandler,
                serviceSerializer,
                serviceDeserializer,
                archiveWriter,
                importProgressService,
                importInstructionsService,
                elementHelperService,
                chainService,
                apiGroupService);

        serviceFile = tempDir.resolve(SYSTEM_ID + ".service.qip.yaml").toFile();
        Files.writeString(serviceFile.toPath(),
                "id: " + SYSTEM_ID + "\nname: " + SYSTEM_NAME + "\n", StandardCharsets.UTF_8);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        // Run the real limit rule rather than a stub, so the import path is tested against the rule it shares.
        SystemBaseService validator = new SystemBaseService(null, null, null);
        lenient().doAnswer(invocation -> {
            validator.validateEnvironmentCount(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(systemService).validateEnvironmentCount(any(), anyInt());
    }

    @Test
    @DisplayName("a second environment is rejected when creating an internal service on import")
    void secondEnvironmentIsRejectedOnCreateForInternalService() {
        importing(systemWith(IntegrationSystemType.INTERNAL, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("internal"));
        assertThat(result.getMessage(), containsString(SYSTEM_ID));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("a second environment is rejected when creating an implemented service on import")
    void secondEnvironmentIsRejectedOnCreateForImplementedService() {
        importing(systemWith(IntegrationSystemType.IMPLEMENTED, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("implemented"));
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("a second environment is accepted when creating an external service on import")
    void secondEnvironmentIsAcceptedOnCreateForExternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.EXTERNAL, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.CREATED));
        verify(systemService).create(system, true);
    }

    @Test
    @DisplayName("a single environment is accepted when creating an internal service on import")
    void singleEnvironmentIsAcceptedOnCreateForInternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.INTERNAL, 1);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.CREATED));
        verify(systemService).create(system, true);
    }

    @Test
    @DisplayName("a service row with no type does not crash the import")
    void typelessServiceDoesNotCrashTheImport() {
        IntegrationSystem system = systemWith(null, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.CREATED));
    }

    @Test
    @DisplayName("a second environment is rejected when updating an internal service on import")
    void secondEnvironmentIsRejectedOnUpdateForInternalService() {
        importing(systemWith(IntegrationSystemType.INTERNAL, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("internal"));
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("a second environment is rejected when updating an implemented service on import")
    void secondEnvironmentIsRejectedOnUpdateForImplementedService() {
        importing(systemWith(IntegrationSystemType.IMPLEMENTED, 2));
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.IMPLEMENTED, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.ERROR));
        assertThat(result.getMessage(), containsString("implemented"));
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("a second environment is accepted when updating an external service on import")
    void secondEnvironmentIsAcceptedOnUpdateForExternalService() {
        IntegrationSystem system = systemWith(IntegrationSystemType.EXTERNAL, 2);
        importing(system);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.EXTERNAL, 1));

        ImportSystemResult result = service.importOneSystemInTransaction(serviceFile, null, null, null);

        assertThat(result.getStatus(), equalTo(ImportSystemStatus.UPDATED));
        verify(systemService).update(system);
    }

    private void importing(IntegrationSystem system) {
        when(serviceDeserializer.deserializeSystem(serviceFile)).thenReturn(system);
    }

    private static IntegrationSystem systemWith(IntegrationSystemType type, int environmentCount) {
        List<Environment> environments = new LinkedList<>();
        for (int i = 0; i < environmentCount; i++) {
            Environment environment = new Environment();
            environment.setId("environment-" + (i + 1));
            environment.setLabels(new ArrayList<>());
            environments.add(environment);
        }
        return IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .integrationSystemType(type)
                .environments(environments)
                .build();
    }
}
