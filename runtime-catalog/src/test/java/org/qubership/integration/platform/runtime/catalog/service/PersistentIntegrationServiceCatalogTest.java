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

package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.chain.model.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers {@link PersistentIntegrationServiceCatalog}: it looks each system up through
 * {@link SystemService} and wraps it in an adapter, returning an empty optional when a lookup by id
 * finds nothing. The wrapped system exposes its specification groups, specifications, and sources,
 * which is what DTO library generation walks.
 */
@ExtendWith(MockitoExtension.class)
class PersistentIntegrationServiceCatalogTest {

    @Mock
    private SystemService systemService;

    private PersistentIntegrationServiceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new PersistentIntegrationServiceCatalog(systemService);
    }

    @Test
    void findByIdWrapsTheSystemWhenPresent() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").name("Orders").build();
        when(systemService.findById("sys-1")).thenReturn(system);

        Optional<IntegrationService> result = catalog.findById("sys-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("sys-1");
        assertThat(result.get().getName()).isEqualTo("Orders");
    }

    @Test
    void findByIdReturnsEmptyWhenTheSystemIsAbsent() {
        when(systemService.findById("missing")).thenReturn(null);

        assertThat(catalog.findById("missing")).isEmpty();
    }

    @Test
    void findAllByIdsWrapsEverySystemItFinds() {
        IntegrationSystem first = IntegrationSystem.builder().id("sys-a").name("A").build();
        IntegrationSystem second = IntegrationSystem.builder().id("sys-b").name("B").build();
        when(systemService.findAllByIds(List.of("sys-a", "sys-b"))).thenReturn(List.of(first, second));

        Collection<IntegrationService> result = catalog.findAllByIds(List.of("sys-a", "sys-b"));

        assertThat(result).extracting(IntegrationService::getId).containsExactly("sys-a", "sys-b");
    }

    @Test
    void findAllWrapsEverySystem() {
        IntegrationSystem first = IntegrationSystem.builder().id("sys-a").name("A").build();
        IntegrationSystem second = IntegrationSystem.builder().id("sys-b").name("B").build();
        when(systemService.findAll()).thenReturn(List.of(first, second));

        Collection<IntegrationService> result = catalog.findAll();

        assertThat(result).extracting(IntegrationService::getId).containsExactly("sys-a", "sys-b");
        assertThat(result).extracting(IntegrationService::getName).containsExactly("A", "B");
    }

    @Test
    void findAllReturnsEmptyWhenThereAreNoSystems() {
        when(systemService.findAll()).thenReturn(List.of());

        assertThat(catalog.findAll()).isEmpty();
    }

    @Test
    void exposesTheSpecificationGroupsSpecificationsAndSourcesOfASystem() {
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource source =
            org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource.builder()
                .name("orders.proto")
                .isMainSource(true)
                .source("syntax = \"proto3\";")
                .build();
        SystemModel model = SystemModel.builder()
            .id("model-1")
            .name("v1")
            .description("First version")
            .specificationSources(List.of(source))
            .build();
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup group =
            org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup.builder()
                .id("group-1")
                .name("Orders API")
                .description("Orders gRPC API")
                .systemModels(List.of(model))
                .build();
        IntegrationSystem system = IntegrationSystem.builder()
            .id("sys-1")
            .name("Orders")
            .specificationGroups(List.of(group))
            .build();
        when(systemService.findAll()).thenReturn(List.of(system));

        IntegrationService service = catalog.findAll().iterator().next();

        SpecificationGroup specificationGroup = service.getSpecificationGroups().iterator().next();
        assertThat(specificationGroup.getId()).isEqualTo("group-1");
        assertThat(specificationGroup.getName()).isEqualTo("Orders API");
        assertThat(specificationGroup.getDescription()).isEqualTo("Orders gRPC API");

        ServiceSpecification specification = specificationGroup.getSpecifications().iterator().next();
        assertThat(specification.getId()).isEqualTo("model-1");
        assertThat(specification.getName()).isEqualTo("v1");
        assertThat(specification.getDescription()).isEqualTo("First version");

        SpecificationSource specificationSource = specification.getSources().iterator().next();
        assertThat(specificationSource.getName()).isEqualTo("orders.proto");
        assertThat(specificationSource.isMainSource()).isTrue();
        assertThat(specificationSource.getText()).isEqualTo("syntax = \"proto3\";");
    }
}
