/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SpecificationGroupService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemExportImportServiceTest {

    private static final String SYSTEM_ID = "system-id-1";
    private static final String SYSTEM_NAME = "Test System";
    private static final String ENV_ADDRESS_CHANGE_MESSAGE = "There are changes in environment address. Please redeploy affected chains (if any)";

    @Mock
    TransactionTemplate transactionTemplate;
    @Mock
    YAMLMapper yamlMapper;
    @Mock
    SystemService systemService;
    @Mock
    EnvironmentService environmentService;
    @Mock
    SystemModelService systemModelService;
    @Mock
    ActionsLogService actionLogger;
    @Mock
    AuditingHandler auditingHandler;
    @Mock
    ServiceSerializer serviceSerializer;
    @Mock
    ServiceDeserializer serviceDeserializer;
    @Mock
    ArchiveWriter archiveWriter;
    @Mock
    ImportSessionService importProgressService;
    @Mock
    ImportInstructionsService importInstructionsService;
    @Mock
    ElementHelperService elementHelperService;
    @Mock
    ChainService chainService;
    @Mock
    SpecificationGroupService specificationGroupService;

    @InjectMocks
    SystemExportImportService service;

    @Test
    @DisplayName("importOneSystemInTransaction creates a new system and returns CREATED status")
    void importOneSystemCreatesNewSystem() throws IOException {
        File file = mock(File.class);
        IntegrationSystem deserialized = buildSystem();
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(deserialized);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(SYSTEM_ID, result.getId());
        assertEquals(SYSTEM_NAME, result.getName());
        assertEquals(ImportSystemStatus.CREATED, result.getStatus());
        assertEquals("", result.getMessage());
        verify(systemService).create(deserialized, true);
        verify(systemService, never()).update(any());
    }

    @Test
    @DisplayName("importOneSystemInTransaction updates an existing system and returns UPDATED status")
    void importOneSystemUpdatesExistingSystem() throws IOException {
        File file = mock(File.class);
        IntegrationSystem deserialized = buildSystem(IntegrationSystemType.INTERNAL);
        IntegrationSystem oldSystem = IntegrationSystem.builder().id(SYSTEM_ID).name("Old Name").build();
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(deserialized);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(oldSystem);
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, List.of(SYSTEM_ID), null);

        assertEquals(SYSTEM_ID, result.getId());
        assertEquals(SYSTEM_NAME, result.getName());
        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        assertEquals("", result.getMessage());
        verify(systemService).update(deserialized);
        verify(systemService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("importOneSystemInTransaction skips a system that is not present in the systemIds filter")
    void importOneSystemReturnsNullWhenSystemNotInFilteredList() throws IOException {
        File file = mock(File.class);
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, List.of("other-system-id"), null);

        assertNull(result);
        verify(serviceDeserializer, never()).deserializeSystem(any());
        verifyNoInteractions(systemService);
    }

    @Test
    @DisplayName("importOneSystemInTransaction returns ERROR result when yaml reading fails")
    void importOneSystemReturnsErrorWhenYamlReadFails() throws IOException {
        File file = mock(File.class);
        when(yamlMapper.readTree(file)).thenThrow(new IOException("Failed to read yaml"));

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertEquals("Failed to read yaml", result.getMessage());
        assertNull(result.getId());
        assertEquals("", result.getName());
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    @DisplayName("importOneSystemInTransaction returns ERROR result when the system id is missing in the file")
    void importOneSystemReturnsErrorWhenSystemIdMissing() throws IOException {
        File file = mock(File.class);
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("name", SYSTEM_NAME);
        when(yamlMapper.readTree(file)).thenReturn(node);

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertEquals("Missing id field in system file", result.getMessage());
        assertNull(result.getId());
        assertEquals("", result.getName());
    }

    @Test
    @DisplayName("importOneSystemInTransaction returns ERROR result with base system info when deserialization fails")
    void importOneSystemReturnsErrorWhenDeserializationFails() throws IOException {
        File file = mock(File.class);
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenThrow(new RuntimeException("Deserialization failed"));
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(SYSTEM_ID, result.getId());
        assertEquals(SYSTEM_NAME, result.getName());
        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertEquals("Deserialization failed", result.getMessage());
    }

    @Test
    @DisplayName("importOneSystemInTransaction returns ERROR result with the exception message when saving fails")
    void importOneSystemReturnsErrorWhenSaveFails() throws IOException {
        File file = mock(File.class);
        IntegrationSystem deserialized = buildSystem();
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(deserialized);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);
        when(systemService.create(deserialized, true)).thenThrow(new RuntimeException("Save failed"));
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(SYSTEM_ID, result.getId());
        assertEquals(SYSTEM_NAME, result.getName());
        assertEquals(ImportSystemStatus.ERROR, result.getStatus());
        assertEquals("Save failed", result.getMessage());
    }

    @Test
    @DisplayName("importOneSystemInTransaction reports a warning when an environment address is changed")
    void importOneSystemReportsEnvironmentAddressChange() throws IOException {
        File file = mock(File.class);
        IntegrationSystem newSystem = buildSystem(IntegrationSystemType.INTERNAL);
        Environment newEnvironment = Environment.builder().id("env-new").address("new-address").build();
        newSystem.addEnvironment(newEnvironment);

        IntegrationSystem oldSystem = IntegrationSystem.builder().id(SYSTEM_ID).name(SYSTEM_NAME).build();
        oldSystem.addEnvironment(Environment.builder().id("env-old").address("old-address").build());

        Chain chain = Chain.builder().id("chain-1").build();
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(newSystem);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(oldSystem);
        when(elementHelperService.findChainBySystemId(SYSTEM_ID)).thenReturn(List.of(chain));
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        assertThat(result.getMessage(), containsString(ENV_ADDRESS_CHANGE_MESSAGE));
        verify(chainService).markChainAsUnsaved(chain);
        verify(systemService).update(newSystem);
    }

    @Test
    @DisplayName("importOneSystemInTransaction updates an external system merging missing environments and inheriting the active environment")
    void importOneSystemUpdatesExternalSystemMergesMissingEnvironmentsAndInheritsActiveEnvironmentId()
            throws IOException {
        File file = mock(File.class);
        IntegrationSystem newSystem = buildSystem(IntegrationSystemType.EXTERNAL);
        IntegrationSystem oldSystem = IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .activeEnvironmentId("env-1")
                .build();
        Environment oldEnvironment = Environment.builder().id("env-1").address("addr").labels(new ArrayList<>())
                .build();
        oldSystem.addEnvironment(oldEnvironment);
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(newSystem);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(oldSystem);
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        assertEquals("", result.getMessage());
        assertEquals("env-1", newSystem.getActiveEnvironmentId());
        assertEquals(1, newSystem.getEnvironments().size());
        assertSame(oldEnvironment, newSystem.getEnvironments().get(0));
        verify(systemService).update(newSystem);
    }

    @Test
    @DisplayName("importOneSystemInTransaction marks chains as unsaved when an external system active environment changes")
    void importOneSystemUpdatesExternalSystemMarksChainUnsavedWhenActiveEnvironmentChanged() throws IOException {
        File file = mock(File.class);
        IntegrationSystem newSystem = buildSystem(IntegrationSystemType.EXTERNAL);
        Environment newEnvironment = Environment.builder().id("env-1").address("new-address").labels(new ArrayList<>())
                .build();
        newSystem.addEnvironment(newEnvironment);

        IntegrationSystem oldSystem = IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .activeEnvironmentId("env-1")
                .build();
        oldSystem.addEnvironment(
                Environment.builder().id("env-1").address("old-address").labels(new ArrayList<>()).build());

        Chain chain = Chain.builder().id("chain-1").build();
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(newSystem);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(oldSystem);
        when(elementHelperService.findChainBySystemId(SYSTEM_ID)).thenReturn(List.of(chain));
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, null, null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        assertEquals("env-1", newSystem.getActiveEnvironmentId());
        verify(chainService).markChainAsUnsaved(chain);
        verify(systemService).update(newSystem);
    }

    @Test
    @DisplayName("importOneSystemInTransaction activates an external system environment by deploy label")
    void importOneSystemUpdatesExternalSystemActivatesEnvironmentByDeployLabel() throws IOException {
        File file = mock(File.class);
        IntegrationSystem newSystem = buildSystem(IntegrationSystemType.EXTERNAL);
        Environment environment = Environment.builder().id("env-1").labels(List.of(EnvironmentLabel.PRODUCTION))
                .build();
        newSystem.addEnvironment(environment);

        IntegrationSystem oldSystem = IntegrationSystem.builder().id(SYSTEM_ID).name(SYSTEM_NAME).build();
        when(yamlMapper.readTree(file)).thenReturn(systemNode(SYSTEM_ID, SYSTEM_NAME));
        when(serviceDeserializer.deserializeSystem(file)).thenReturn(newSystem);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(oldSystem);
        executeTransaction();

        ImportSystemResult result = service.importOneSystemInTransaction(file, "PRODUCTION", null, null);

        assertEquals(ImportSystemStatus.UPDATED, result.getStatus());
        assertEquals("env-1", newSystem.getActiveEnvironmentId());
        verify(systemService).update(newSystem);
    }

    private void executeTransaction() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private IntegrationSystem buildSystem() {
        return buildSystem(null);
    }

    private IntegrationSystem buildSystem(IntegrationSystemType type) {
        return IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .integrationSystemType(type)
                .build();
    }

    private ObjectNode systemNode(String id, String name) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("name", name);
        return node;
    }
}
