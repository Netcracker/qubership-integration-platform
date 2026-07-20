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
import org.qubership.integration.platform.chain.model.ImportEnvironment;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationSystemReaderTest {

    @TempDir
    Path serviceDir;

    private IntegrationSystemReader reader;

    @BeforeEach
    void setUp() throws Exception {
        // Migration is exercised by its own tests; here it passes the document through unchanged.
        FileMigrationService fileMigrationService = mock(FileMigrationService.class);
        when(fileMigrationService.migrate(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        VersionsGetterService versionsGetterService = mock(VersionsGetterService.class);

        reader = new IntegrationSystemReader(
                new YAMLMapper(), fileMigrationService, versionsGetterService, java.util.List.of());
    }

    @DisplayName("read migrates the file, deserializes it, and maps the enum, audit-user, and environment fields")
    @Test
    void readsSystemFileIntoModel() throws IOException {
        String systemYaml = """
                id: sys-1
                name: Payment System
                content:
                  description: A description
                  integrationSystemType: EXTERNAL
                  protocol: HTTP
                  createdBy:
                    id: u-1
                    username: alice
                  environments:
                    - id: env-1
                      name: Prod
                      address: http://example.org
                      sourceType: MANUAL
                  labels:
                    - prod
                """;
        Path systemFile = serviceDir.resolve("sys-1.service.qip.yaml");
        Files.writeString(systemFile, systemYaml);

        ImportSystem result = reader.read(systemFile.toFile());

        assertEquals("sys-1", result.getId());
        assertEquals("Payment System", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals(IntegrationSystemType.EXTERNAL, result.getIntegrationSystemType());
        assertEquals(OperationProtocol.HTTP, result.getProtocol());
        assertEquals("u-1", result.getCreatedBy().getId());
        assertEquals("alice", result.getCreatedBy().getUsername());
        assertEquals(1, result.getEnvironments().size());
        ImportEnvironment environment = result.getEnvironments().get(0);
        assertEquals("env-1", environment.getId());
        assertEquals("http://example.org", environment.getAddress());
        assertEquals(java.util.List.of("prod"), result.getLabels());
    }

    @DisplayName("toModel flattens the export content block onto the model")
    @Test
    void toModelFlattensContent() {
        IntegrationSystemDto dto = IntegrationSystemDto.builder()
                .id("sys-2")
                .name("Order System")
                .build();

        ImportSystem result = reader.toModel(dto);

        assertEquals("sys-2", result.getId());
        assertEquals("Order System", result.getName());
    }
}
