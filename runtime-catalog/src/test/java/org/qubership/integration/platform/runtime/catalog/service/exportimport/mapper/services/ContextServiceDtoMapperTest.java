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
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ContextServiceContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ContextServiceDto;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;

import java.net.URI;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextServiceDtoMapperTest {

    private static final URI SCHEMA_URI = URI.create("http://qubership.org/schemas/product/qip/context-service");
    private ContextServiceDtoMapper mapper;

    @BeforeEach
    void setUp() {
        ServiceImportFileMigration migration = mock(ServiceImportFileMigration.class);
        when(migration.getVersion()).thenReturn(102);
        mapper = new ContextServiceDtoMapper(SCHEMA_URI, List.of(migration));
    }

    @Test
    void testToInternalEntityMapsDtoToContextSystem() {
        Timestamp modifiedWhen = new Timestamp(System.currentTimeMillis());
        ContextServiceContentDto content = ContextServiceContentDto.builder()
                .description("Test description")
                .modifiedWhen(modifiedWhen)
                .build();
        ContextServiceDto dto = ContextServiceDto.builder()
                .id("ctx-1")
                .name("Context Service 1")
                .content(content)
                .build();

        ContextSystem result = mapper.toInternalEntity(dto);

        assertNotNull(result);
        assertEquals("ctx-1", result.getId());
        assertEquals("Context Service 1", result.getName());
        assertEquals(modifiedWhen, result.getModifiedWhen());
        assertEquals("Test description", result.getDescription());
    }

    @Test
    void testToExternalEntityMapsContextSystemToDto() {
        ContextSystem system = ContextSystem.builder()
                .id("ctx-2")
                .name("Context Service 2")
                .description("Desc")
                .build();

        ContextServiceDto result = mapper.toExternalEntity(system);

        assertNotNull(result);
        assertEquals("ctx-2", result.getId());
        assertEquals("Context Service 2", result.getName());
        assertEquals(SCHEMA_URI, result.getSchema());
        assertNotNull(result.getContent());
        assertEquals("Desc", result.getContent().getDescription());
        assertEquals("[102]", result.getContent().getMigrations());
    }
}
