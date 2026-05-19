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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.MCPSystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.MCPSystemDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions.ImportInstructionsService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.MCPSystemSerializer;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MCPSystemImportExportServiceTest {

    private static final String SYSTEM_ID = "system-id-1";
    private static final String SYSTEM_NAME = "Test System";
    private static final byte[] ARCHIVE_BYTES = new byte[]{1, 2, 3};

    @Mock TransactionTemplate transactionTemplate;
    @Mock YAMLMapper yamlMapper;
    @Mock MCPSystemService mcpSystemService;
    @Mock ActionsLogService actionLogger;
    @Mock MCPSystemSerializer mcpSystemSerializer;
    @Mock MCPSystemDeserializer mcpSystemDeserializer;
    @Mock ArchiveWriter archiveWriter;
    @Mock ImportInstructionsService importInstructionsService;
    @Mock ImportSessionService importProgressService;

    @Captor ArgumentCaptor<List<ExportedSystemObject>> exportedSystemsCaptor;

    MCPSystemImportExportService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new MCPSystemImportExportService(
                transactionTemplate,
                yamlMapper,
                mcpSystemService,
                actionLogger,
                mcpSystemSerializer,
                mcpSystemDeserializer,
                archiveWriter,
                importInstructionsService,
                importProgressService,
                new URI("http://qubership.org/schemas/product/qip/mcp-service")
        );
    }

    // export tests

    @Test
    @DisplayName("export with null ids calls findAll()")
    void exportWithNullIdsFindAll() throws JsonProcessingException {
        MCPSystem system = buildSystem();
        ExportedSystemObject exported = mock(ExportedSystemObject.class);
        when(mcpSystemService.findAll()).thenReturn(List.of(system));
        when(mcpSystemSerializer.serialize(system)).thenReturn(exported);
        when(archiveWriter.writeArchive(anyList())).thenReturn(ARCHIVE_BYTES);

        service.export(null);

        verify(mcpSystemService).findAll();
        verify(mcpSystemService, never()).findAllById(any());
    }

    @Test
    @DisplayName("export with ids list calls findAllById()")
    void exportWithIdsCallsFindAllById() throws JsonProcessingException {
        List<String> ids = List.of(SYSTEM_ID);
        MCPSystem system = buildSystem();
        ExportedSystemObject exported = mock(ExportedSystemObject.class);
        when(mcpSystemService.findAllById(ids)).thenReturn(List.of(system));
        when(mcpSystemSerializer.serialize(system)).thenReturn(exported);
        when(archiveWriter.writeArchive(anyList())).thenReturn(ARCHIVE_BYTES);

        service.export(ids);

        verify(mcpSystemService).findAllById(ids);
        verify(mcpSystemService, never()).findAll();
    }

    @Test
    @DisplayName("export returns null when no systems found")
    void exportReturnsNullWhenNoSystems() {
        when(mcpSystemService.findAll()).thenReturn(List.of());

        byte[] result = service.export(null);

        assertNull(result);
        verifyNoInteractions(mcpSystemSerializer, archiveWriter);
    }

    @Test
    @DisplayName("export returns archive bytes when systems found")
    void exportReturnsBytesFromArchiveWriter() throws JsonProcessingException {
        MCPSystem system = buildSystem();
        ExportedSystemObject exported = mock(ExportedSystemObject.class);
        when(mcpSystemService.findAll()).thenReturn(List.of(system));
        when(mcpSystemSerializer.serialize(system)).thenReturn(exported);
        when(archiveWriter.writeArchive(anyList())).thenReturn(ARCHIVE_BYTES);

        byte[] result = service.export(null);

        assertThat(result, equalTo(ARCHIVE_BYTES));
    }

    @Test
    @DisplayName("export serializes all systems and passes them to archiveWriter")
    void exportSerializesAllSystems() throws JsonProcessingException {
        MCPSystem s1 = buildSystem("id-1", "sys-1");
        MCPSystem s2 = buildSystem("id-2", "sys-2");
        ExportedSystemObject e1 = mock(ExportedSystemObject.class);
        ExportedSystemObject e2 = mock(ExportedSystemObject.class);
        when(mcpSystemService.findAll()).thenReturn(List.of(s1, s2));
        when(mcpSystemSerializer.serialize(s1)).thenReturn(e1);
        when(mcpSystemSerializer.serialize(s2)).thenReturn(e2);
        when(archiveWriter.writeArchive(exportedSystemsCaptor.capture())).thenReturn(ARCHIVE_BYTES);

        service.export(null);

        List<ExportedSystemObject> passed = exportedSystemsCaptor.getValue();
        assertThat(passed, containsInAnyOrder(e1, e2));
    }

    @Test
    @DisplayName("export logs EXPORT action for each exported system")
    void exportLogsExportForEachSystem() throws JsonProcessingException {
        MCPSystem s1 = buildSystem("id-1", "sys-1");
        MCPSystem s2 = buildSystem("id-2", "sys-2");
        ExportedSystemObject e1 = mock(ExportedSystemObject.class);
        ExportedSystemObject e2 = mock(ExportedSystemObject.class);
        when(mcpSystemService.findAll()).thenReturn(List.of(s1, s2));
        when(mcpSystemSerializer.serialize(s1)).thenReturn(e1);
        when(mcpSystemSerializer.serialize(s2)).thenReturn(e2);
        when(archiveWriter.writeArchive(anyList())).thenReturn(ARCHIVE_BYTES);

        service.export(null);

        verify(actionLogger, times(2)).logAction(any());
    }

    @Test
    @DisplayName("export throws RuntimeException when serialization fails")
    void exportThrowsWhenSerializationFails() throws JsonProcessingException {
        MCPSystem system = buildSystem();
        when(mcpSystemService.findAll()).thenReturn(List.of(system));
        when(mcpSystemSerializer.serialize(system)).thenThrow(mock(JsonProcessingException.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.export(null));

        assertThat(ex.getMessage(), containsString("Failed to export system"));
        assertThat(ex.getMessage(), containsString(SYSTEM_NAME));
        assertThat(ex.getMessage(), containsString(SYSTEM_ID));
    }

    // importSystems(MultipartFile, List<String>) error paths

    @Test
    @DisplayName("importSystems throws RuntimeException for non-zip file extension")
    void importSystemsThrowsForNonZipExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "systems.yaml", "application/octet-stream", new byte[]{1});

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.importSystems(file, null));

        assertThat(ex.getMessage(), containsString("Unsupported file extension"));
        assertThat(ex.getMessage(), containsString("yaml"));
    }

    @Test
    @DisplayName("importSystems throws RuntimeException for file with no extension")
    void importSystemsThrowsForMissingExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "systems", "application/octet-stream", new byte[]{1});

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.importSystems(file, null));

        assertThat(ex.getMessage(), containsString("Unsupported file extension"));
    }

    @Test
    @DisplayName("importSystems accepts zip file (case-insensitive extension)")
    void importSystemsAcceptsZipCaseInsensitive() throws Exception {
        byte[] zipBytes = buildEmptyZip();
        MockMultipartFile file = new MockMultipartFile(
                "file", "systems.ZIP", "application/octet-stream", zipBytes);

        when(importInstructionsService.performServiceIgnoreInstructions(any(), anyBoolean()))
                .thenReturn(new org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.IgnoreResult(
                        java.util.Set.of(), List.of()));

        List<ImportSystemResult> result = service.importSystems(file, null);

        assertThat(result, empty());
    }

    // getImportPreview(MultipartFile) error paths

    @Test
    @DisplayName("getImportPreview throws RuntimeException for non-zip file extension")
    void getImportPreviewThrowsForNonZipExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "systems.yaml", "application/octet-stream", new byte[]{1});

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getImportPreview(file));

        assertThat(ex.getMessage(), containsString("Unsupported file extension"));
    }

    @Test
    @DisplayName("getImportPreview returns empty list for empty zip")
    void getImportPreviewReturnsEmptyListForEmptyZip() throws Exception {
        byte[] zipBytes = buildEmptyZip();
        MockMultipartFile file = new MockMultipartFile(
                "file", "systems.zip", "application/octet-stream", zipBytes);

        when(importInstructionsService.getServiceImportInstructionsConfig(any()))
                .thenReturn(new org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig());

        List<ImportSystemResult> result = service.getImportPreview(file);

        assertThat(result, empty());
    }

    // helpers

    private MCPSystem buildSystem() {
        return buildSystem(SYSTEM_ID, SYSTEM_NAME);
    }

    private MCPSystem buildSystem(String id, String name) {
        return MCPSystem.builder().id(id).name(name).build();
    }

    private byte[] buildEmptyZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("placeholder/"));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
