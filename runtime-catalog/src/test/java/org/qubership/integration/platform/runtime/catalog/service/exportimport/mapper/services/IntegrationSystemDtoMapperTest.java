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
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;

import java.net.URI;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationSystemDtoMapperTest {

    private static final URI SCHEMA_URI = URI.create("http://qubership.org/schemas/product/qip/service");
    private IntegrationSystemDtoMapper mapper;

    @BeforeEach
    void setUp() {
        ServiceImportFileMigration migration = mock(ServiceImportFileMigration.class);
        when(migration.getVersion()).thenReturn(102);
        mapper = new IntegrationSystemDtoMapper(SCHEMA_URI, List.of(migration));
    }

    @Test
    void testToInternalEntityMapsEveryContentField() {
        Timestamp createdWhen = new Timestamp(1_000L);
        Timestamp modifiedWhen = new Timestamp(2_000L);
        IntegrationSystemContentDto content = IntegrationSystemContentDto.builder()
                .description("A description")
                .activeEnvironmentId("env-1")
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .internalServiceName("internal-service")
                .protocol(OperationProtocol.HTTP)
                .createdWhen(createdWhen)
                .modifiedWhen(modifiedWhen)
                .labels(List.of("prod", "billing"))
                .build();
        IntegrationSystemDto dto = IntegrationSystemDto.builder()
                .id("sys-1")
                .name("Payment System")
                .content(content)
                .build();

        IntegrationSystem result = mapper.toInternalEntity(dto);

        assertNotNull(result);
        assertEquals("sys-1", result.getId());
        assertEquals("Payment System", result.getName());
        assertEquals("A description", result.getDescription());
        assertEquals("env-1", result.getActiveEnvironmentId());
        assertEquals(IntegrationSystemType.EXTERNAL, result.getIntegrationSystemType());
        assertEquals("internal-service", result.getInternalServiceName());
        assertEquals(OperationProtocol.HTTP, result.getProtocol());
        assertEquals(createdWhen, result.getCreatedWhen());
        assertEquals(modifiedWhen, result.getModifiedWhen());
    }

    @Test
    void testToInternalEntityWiresEnvironmentsBackToSystem() {
        Environment environment = Environment.builder()
                .id("env-1")
                .name("Prod")
                .address("http://example.org")
                .build();
        IntegrationSystemContentDto content = IntegrationSystemContentDto.builder()
                .environments(List.of(environment))
                .build();
        IntegrationSystemDto dto = IntegrationSystemDto.builder()
                .id("sys-1")
                .name("Payment System")
                .content(content)
                .build();

        IntegrationSystem result = mapper.toInternalEntity(dto);

        assertEquals(1, result.getEnvironments().size());
        Environment resultEnvironment = result.getEnvironments().get(0);
        assertEquals("env-1", resultEnvironment.getId());
        assertSame(result, resultEnvironment.getSystem(),
                "Each imported environment must point back to the resulting system");
    }

    @Test
    void testToInternalEntityMapsLabelsBoundToSystemAndNonTechnical() {
        IntegrationSystemContentDto content = IntegrationSystemContentDto.builder()
                .labels(List.of("prod", "billing"))
                .build();
        IntegrationSystemDto dto = IntegrationSystemDto.builder()
                .id("sys-1")
                .name("Payment System")
                .content(content)
                .build();

        IntegrationSystem result = mapper.toInternalEntity(dto);

        Set<String> labelNames = result.getLabels().stream()
                .map(IntegrationSystemLabel::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("prod", "billing"), labelNames);
        assertTrue(result.getLabels().stream().noneMatch(IntegrationSystemLabel::isTechnical),
                "Imported labels must not be marked technical");
        assertTrue(result.getLabels().stream().allMatch(label -> label.getSystem() == result),
                "Every imported label must reference the resulting system");
    }

    @Test
    void testToExternalEntityMapsSystemToDto() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id("sys-2")
                .name("Order System")
                .description("Order desc")
                .activeEnvironmentId("env-9")
                .integrationSystemType(IntegrationSystemType.INTERNAL)
                .internalServiceName("orders")
                .protocol(OperationProtocol.KAFKA)
                .build();
        system.setLabels(Set.of(new IntegrationSystemLabel("prod", system)));

        IntegrationSystemDto result = mapper.toExternalEntity(system);

        assertNotNull(result);
        assertEquals("sys-2", result.getId());
        assertEquals("Order System", result.getName());
        assertEquals(SCHEMA_URI, result.getSchema());
        assertNotNull(result.getContent());
        assertEquals("Order desc", result.getContent().getDescription());
        assertEquals("env-9", result.getContent().getActiveEnvironmentId());
        assertEquals(IntegrationSystemType.INTERNAL, result.getContent().getIntegrationSystemType());
        assertEquals("orders", result.getContent().getInternalServiceName());
        assertEquals(OperationProtocol.KAFKA, result.getContent().getProtocol());
        assertEquals(List.of("prod"), result.getContent().getLabels());
        assertEquals("[102]", result.getContent().getMigrations());
    }
}
