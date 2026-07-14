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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.mcp.MCPServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.MCPServiceContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.MCPServiceDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystemLabel;

import java.net.URI;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MCPServiceDtoMapperTest {

    private static final URI SCHEMA_URI = URI.create("http://qubership.org/schemas/product/qip/mcp-service");
    private MCPServiceDtoMapper mapper;

    @BeforeEach
    void setUp() {
        MCPServiceImportFileMigration migration = mock(MCPServiceImportFileMigration.class);
        when(migration.getVersion()).thenReturn(1);
        mapper = new MCPServiceDtoMapper(SCHEMA_URI, List.of(migration));
    }

    @Test
    void testToInternalEntityMapsEveryContentField() {
        Timestamp createdWhen = new Timestamp(1_000L);
        Timestamp modifiedWhen = new Timestamp(2_000L);
        MCPServiceContentDto content = MCPServiceContentDto.builder()
                .description("A description")
                .identifier("mcp-identifier")
                .instructions("Follow these steps")
                .createdWhen(createdWhen)
                .modifiedWhen(modifiedWhen)
                .labels(List.of("prod", "billing"))
                .build();
        MCPServiceDto dto = MCPServiceDto.builder()
                .id("mcp-1")
                .name("MCP Service")
                .content(content)
                .build();

        MCPSystem result = mapper.toInternalEntity(dto);

        assertNotNull(result);
        assertEquals("mcp-1", result.getId());
        assertEquals("MCP Service", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("mcp-identifier", result.getIdentifier());
        assertEquals("Follow these steps", result.getInstructions());
        assertEquals(createdWhen, result.getCreatedWhen());
        assertEquals(modifiedWhen, result.getModifiedWhen());
    }

    @Test
    void testToInternalEntityMapsLabelsBoundToSystemAndNonTechnical() {
        MCPServiceContentDto content = MCPServiceContentDto.builder()
                .labels(List.of("prod", "billing"))
                .build();
        MCPServiceDto dto = MCPServiceDto.builder()
                .id("mcp-1")
                .name("MCP Service")
                .content(content)
                .build();

        MCPSystem result = mapper.toInternalEntity(dto);

        Set<String> labelNames = result.getLabels().stream()
                .map(MCPSystemLabel::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("prod", "billing"), labelNames);
        assertTrue(result.getLabels().stream().noneMatch(MCPSystemLabel::isTechnical),
                "Imported labels must not be marked technical");
        assertTrue(result.getLabels().stream().allMatch(label -> label.getSystem() == result),
                "Every imported label must reference the resulting system");
    }

    @Test
    void testToExternalEntityMapsSystemToDto() {
        MCPSystem system = MCPSystem.builder()
                .id("mcp-2")
                .name("MCP Service 2")
                .description("Desc")
                .identifier("id-2")
                .instructions("Do this")
                .build();
        system.setLabels(Set.of(new MCPSystemLabel("prod", system)));

        MCPServiceDto result = mapper.toExternalEntity(system);

        assertNotNull(result);
        assertEquals("mcp-2", result.getId());
        assertEquals("MCP Service 2", result.getName());
        assertEquals(SCHEMA_URI, result.getSchema());
        assertNotNull(result.getContent());
        assertEquals("Desc", result.getContent().getDescription());
        assertEquals("id-2", result.getContent().getIdentifier());
        assertEquals("Do this", result.getContent().getInstructions());
        assertEquals(List.of("prod"), result.getContent().getLabels());
        assertEquals("[1]", result.getContent().getMigrations());
    }
}
