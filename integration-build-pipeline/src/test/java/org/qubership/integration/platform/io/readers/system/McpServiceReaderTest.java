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
import org.qubership.integration.platform.chain.model.McpService;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.io.model.exportimport.system.User;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpServiceReaderTest {

    @TempDir
    Path serviceDir;

    private McpServiceReader reader;

    @BeforeEach
    void setUp() throws Exception {
        // Migration is exercised by its own tests; here it passes the document through unchanged.
        FileMigrationService fileMigrationService = mock(FileMigrationService.class);
        when(fileMigrationService.migrate(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        reader = new McpServiceReader(new YAMLMapper(), fileMigrationService, List.of());
    }

    @DisplayName("toModel copies the identity, content, and audit-user fields into the library model")
    @Test
    void toModelCopiesDtoFields() {
        Timestamp createdWhen = new Timestamp(1_700_000_000_000L);
        Timestamp modifiedWhen = new Timestamp(1_700_000_001_000L);
        User createdBy = User.builder().id("u-1").username("alice").build();
        User modifiedBy = User.builder().id("u-2").username("bob").build();
        MCPServiceDto dto = MCPServiceDto.builder()
                .id("mcp-1")
                .name("MCP Service 1")
                .content(MCPServiceContentDto.builder()
                        .description("A description")
                        .identifier("mcp-identifier")
                        .instructions("Follow these steps")
                        .createdBy(createdBy)
                        .createdWhen(createdWhen)
                        .modifiedBy(modifiedBy)
                        .modifiedWhen(modifiedWhen)
                        .labels(List.of("prod", "billing"))
                        .build())
                .build();

        McpService result = reader.toModel(dto);

        assertEquals("mcp-1", result.getId());
        assertEquals("MCP Service 1", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("mcp-identifier", result.getIdentifier());
        assertEquals("Follow these steps", result.getInstructions());
        assertEquals(createdBy, result.getCreatedBy());
        assertEquals(createdWhen, result.getCreatedWhen());
        assertEquals(modifiedBy, result.getModifiedBy());
        assertEquals(modifiedWhen, result.getModifiedWhen());
        assertEquals(List.of("prod", "billing"), result.getLabels());
    }

    @DisplayName("toModel leaves content fields null when the export has no content")
    @Test
    void toModelHandlesMissingContent() {
        MCPServiceDto dto = MCPServiceDto.builder()
                .id("mcp-2")
                .name("MCP Service 2")
                .build();

        McpService result = reader.toModel(dto);

        assertEquals("mcp-2", result.getId());
        assertEquals("MCP Service 2", result.getName());
        assertNull(result.getDescription());
        assertNull(result.getIdentifier());
        assertNull(result.getInstructions());
        assertNull(result.getCreatedBy());
        assertNull(result.getModifiedBy());
        assertTrue(result.getLabels().isEmpty());
    }

    @DisplayName("read migrates the file, deserializes it, and maps it to the model")
    @Test
    void readsServiceFileIntoModel() throws IOException {
        String serviceYaml = """
                id: mcp-3
                name: MCP Service 3
                content:
                  description: A description
                  identifier: mcp-identifier
                  instructions: Follow these steps
                  createdBy:
                    id: u-1
                    username: alice
                  labels:
                    - prod
                """;
        Path serviceFile = serviceDir.resolve("mcp-3.mcp-service.qip.yaml");
        Files.writeString(serviceFile, serviceYaml);

        McpService result = reader.read(serviceFile.toFile());

        assertEquals("mcp-3", result.getId());
        assertEquals("MCP Service 3", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("mcp-identifier", result.getIdentifier());
        assertEquals("Follow these steps", result.getInstructions());
        assertEquals("u-1", result.getCreatedBy().getId());
        assertEquals("alice", result.getCreatedBy().getUsername());
        assertEquals(List.of("prod"), result.getLabels());
    }
}
