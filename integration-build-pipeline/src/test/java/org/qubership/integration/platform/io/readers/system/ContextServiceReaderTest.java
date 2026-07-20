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

package org.qubership.integration.platform.io.readers.system;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.chain.model.ContextService;
import org.qubership.integration.platform.io.model.exportimport.system.ContextServiceContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.ContextServiceDto;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextServiceReaderTest {

    @TempDir
    Path serviceDir;

    private ContextServiceReader reader;

    @BeforeEach
    void setUp() throws Exception {
        // Migration is exercised by its own tests; here it passes the document through unchanged.
        FileMigrationService fileMigrationService = mock(FileMigrationService.class);
        when(fileMigrationService.migrate(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        reader = new ContextServiceReader(new YAMLMapper(), fileMigrationService, List.of());
    }

    @DisplayName("toModel copies the identity and content fields into the library model")
    @Test
    void toModelCopiesDtoFields() {
        Timestamp modifiedWhen = new Timestamp(1_700_000_000_000L);
        ContextServiceDto dto = ContextServiceDto.builder()
                .id("ctx-1")
                .name("Context Service 1")
                .content(ContextServiceContentDto.builder()
                        .description("A description")
                        .internalServiceName("internal-service")
                        .modifiedWhen(modifiedWhen)
                        .build())
                .build();

        ContextService result = reader.toModel(dto);

        assertEquals("ctx-1", result.getId());
        assertEquals("Context Service 1", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("internal-service", result.getInternalServiceName());
        assertEquals(modifiedWhen, result.getModifiedWhen());
    }

    @DisplayName("toModel leaves content fields null when the export has no content")
    @Test
    void toModelHandlesMissingContent() {
        ContextServiceDto dto = ContextServiceDto.builder()
                .id("ctx-2")
                .name("Context Service 2")
                .build();

        ContextService result = reader.toModel(dto);

        assertEquals("ctx-2", result.getId());
        assertEquals("Context Service 2", result.getName());
        assertNull(result.getDescription());
        assertNull(result.getInternalServiceName());
        assertNull(result.getModifiedWhen());
    }

    @DisplayName("read migrates the file, deserializes it, and maps it to the model")
    @Test
    void readsServiceFileIntoModel() throws IOException {
        String serviceYaml = """
                id: ctx-3
                name: Context Service 3
                content:
                  description: A description
                  internalServiceName: internal-service
                """;
        Path serviceFile = serviceDir.resolve("ctx-3.context-service.qip.yaml");
        Files.writeString(serviceFile, serviceYaml);

        ContextService result = reader.read(serviceFile.toFile());

        assertEquals("ctx-3", result.getId());
        assertEquals("Context Service 3", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("internal-service", result.getInternalServiceName());
    }
}
