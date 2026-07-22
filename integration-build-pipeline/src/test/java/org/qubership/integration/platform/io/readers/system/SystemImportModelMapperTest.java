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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.ImportEnvironment;
import org.qubership.integration.platform.chain.model.ImportOperation;
import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.EnvironmentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemDto;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType;
import org.qubership.integration.platform.io.model.exportimport.system.OperationDto;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationSourceDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelContentDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelSource;
import org.qubership.integration.platform.io.model.exportimport.system.User;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemImportModelMapperTest {

    @DisplayName("system DTO maps to a model that carries every field the catalog reads")
    @Test
    void mapsSystemContentAndChildren() {
        Timestamp createdWhen = new Timestamp(1_000L);
        Timestamp modifiedWhen = new Timestamp(2_000L);
        EnvironmentDto environment = EnvironmentDto.builder()
                .id("env-1")
                .name("Prod")
                .address("http://example.org")
                .build();
        IntegrationSystemContentDto content = IntegrationSystemContentDto.builder()
                .description("A description")
                .createdBy(User.builder().id("u-1").username("alice").build())
                .createdWhen(createdWhen)
                .modifiedWhen(modifiedWhen)
                .activeEnvironmentId("env-1")
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .internalServiceName("internal-service")
                .protocol(OperationProtocol.HTTP)
                .environments(List.of(environment))
                .labels(List.of("prod", "billing"))
                .build();
        IntegrationSystemDto dto = IntegrationSystemDto.builder()
                .id("sys-1")
                .name("Payment System")
                .content(content)
                .build();

        ImportSystem model = SystemImportModelMapper.toModel(dto);

        assertEquals("sys-1", model.getId());
        assertEquals("Payment System", model.getName());
        assertEquals("A description", model.getDescription());
        assertEquals("u-1", model.getCreatedBy().getId());
        assertEquals(createdWhen, model.getCreatedWhen());
        assertEquals(modifiedWhen, model.getModifiedWhen());
        assertEquals("env-1", model.getActiveEnvironmentId());
        assertEquals(IntegrationSystemType.EXTERNAL, model.getIntegrationSystemType());
        assertEquals("internal-service", model.getInternalServiceName());
        assertEquals(OperationProtocol.HTTP, model.getProtocol());
        assertEquals(List.of("prod", "billing"), model.getLabels());
        assertEquals(1, model.getEnvironments().size());
        ImportEnvironment mappedEnvironment = model.getEnvironments().get(0);
        assertEquals("env-1", mappedEnvironment.getId());
        assertEquals("http://example.org", mappedEnvironment.getAddress());
    }

    @DisplayName("specification-group DTO flattens its content block onto the model")
    @Test
    void mapsSpecificationGroup() {
        SpecificationGroupContentDto content = SpecificationGroupContentDto.builder()
                .description("Group desc")
                .url("http://example.org/spec")
                .synchronization(true)
                .parentId("sys-1")
                .labels(List.of("prod"))
                .build();
        SpecificationGroupDto dto = SpecificationGroupDto.builder()
                .id("group-1")
                .name("Group")
                .content(content)
                .build();

        ImportSpecificationGroup model = SystemImportModelMapper.toModel(dto);

        assertEquals("group-1", model.getId());
        assertEquals("Group desc", model.getDescription());
        assertEquals("http://example.org/spec", model.getUrl());
        assertTrue(model.isSynchronization());
        assertEquals("sys-1", model.getParentId());
        assertEquals(List.of("prod"), model.getLabels());
    }

    @DisplayName("system-model DTO maps its operations and leaves specification-source text empty")
    @Test
    void mapsSystemModelAndSources() {
        OperationDto operation = OperationDto.builder()
                .id("op-1")
                .name("getThing")
                .method("GET")
                .path("/things")
                .build();
        SpecificationSourceDto source = SpecificationSourceDto.builder()
                .id("src-1")
                .name("openapi.json")
                .fileName("openapi.json")
                .sourceHash("hash")
                .mainSource(true)
                .build();
        SystemModelContentDto content = SystemModelContentDto.builder()
                .description("Model desc")
                .deprecated(true)
                .version("1.0.0")
                .source(SystemModelSource.DISCOVERED)
                .parentId("group-1")
                .operations(List.of(operation))
                .specificationSources(List.of(source))
                .labels(List.of("prod"))
                .build();
        SystemModelDto dto = SystemModelDto.builder()
                .id("model-1")
                .name("Model")
                .content(content)
                .build();

        ImportSystemModel model = SystemImportModelMapper.toModel(dto);

        assertEquals("model-1", model.getId());
        assertEquals("Model desc", model.getDescription());
        assertTrue(model.isDeprecated());
        assertEquals("1.0.0", model.getVersion());
        assertEquals(SystemModelSource.DISCOVERED, model.getSource());
        assertEquals("group-1", model.getParentId());
        assertEquals(List.of("prod"), model.getLabels());

        assertEquals(1, model.getOperations().size());
        ImportOperation mappedOperation = model.getOperations().get(0);
        assertEquals("op-1", mappedOperation.getId());
        assertEquals("GET", mappedOperation.getMethod());
        assertEquals("/things", mappedOperation.getPath());

        assertEquals(1, model.getSpecificationSources().size());
        ImportSpecificationSource mappedSource = model.getSpecificationSources().get(0);
        assertEquals("src-1", mappedSource.getId());
        assertEquals("openapi.json", mappedSource.getFileName());
        assertTrue(mappedSource.isMainSource());
    }
}
