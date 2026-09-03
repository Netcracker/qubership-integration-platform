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

package org.qubership.integration.platform.runtime.catalog.cr.rest.v1.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.cr.services.ResourceBuildOptionsProvider;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the micro-domain gate and the single-resource endpoints of {@link CustomResourceController}.
 * Each endpoint runs only when the micro domain is enabled and otherwise raises
 * {@link DomainTypeDisabledException}; the deploy endpoint wires the options provider, the build
 * service, and the deploy call together.
 */
@ExtendWith(MockitoExtension.class)
class CustomResourceControllerTest {

    @Mock
    private MicroDomainResourceBuildService microDomainResourceBuildService;
    @Mock
    private MicroDomainService microDomainService;
    @Mock
    private ResourceBuildOptionsProvider resourceBuildOptionsProvider;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ChainRepository chainRepository;
    @Mock
    private EngineService engineService;
    @Mock
    private DomainProperties domainProperties;
    @Mock
    private DomainProperties.DeployMethodConfiguration microConfiguration;

    private CustomResourceController controller;

    @BeforeEach
    void setUp() {
        controller = new CustomResourceController(
                microDomainResourceBuildService,
                microDomainService,
                resourceBuildOptionsProvider,
                deploymentService,
                chainRepository,
                engineService,
                domainProperties);
    }

    private void microDomainEnabled(boolean enabled) {
        when(domainProperties.getMicro()).thenReturn(microConfiguration);
        when(microConfiguration.isEnabled()).thenReturn(enabled);
    }

    @Test
    void buildResourceReturnsTheBuiltResourceWhenMicroDomainEnabled() {
        microDomainEnabled(true);
        ResourceBuildRequest request = ResourceBuildRequest.builder()
                .options(ResourceBuildOptions.builder().build())
                .build();
        when(microDomainResourceBuildService.buildResources(request, false)).thenReturn("resource-yaml");

        assertThat(controller.buildResource(request)).isEqualTo("resource-yaml");
    }

    @Test
    void buildResourceIsRejectedWhenMicroDomainDisabled() {
        microDomainEnabled(false);

        ResourceBuildRequest request = ResourceBuildRequest.builder()
                .options(ResourceBuildOptions.builder().build())
                .build();
        assertThatThrownBy(() -> controller.buildResource(request))
                .isInstanceOf(DomainTypeDisabledException.class);
    }

    @Test
    void deployResourceBuildsWithTheProvidedOptionsAndDeploysTheResult() {
        microDomainEnabled(true);
        ResourceDeployRequest request = ResourceDeployRequest.builder()
                .name("orders")
                .mode(DeployMode.APPEND)
                .snapshotIds(List.of("s1"))
                .build();
        ResourceBuildOptions options = ResourceBuildOptions.builder().build();
        when(resourceBuildOptionsProvider.getOptions(request)).thenReturn(options);
        when(microDomainResourceBuildService.buildResources(any(ResourceBuildRequest.class), eq(true)))
                .thenReturn("resource-yaml");

        ResponseEntity<Void> response = controller.deployResource(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(microDomainService).deploy("resource-yaml");
    }

    @Test
    void deleteResourceDeletesTheNamedResource() {
        microDomainEnabled(true);

        ResponseEntity<Void> response = controller.deleteResource("orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(microDomainService).delete("orders");
    }

    @Test
    void deleteSnapshotFromResourceDeletesTheChainSnapshot() {
        microDomainEnabled(true);

        ResponseEntity<Void> response = controller.deleteSnapshotFromResource("orders", "s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(microDomainService).deleteChainSnapshot("orders", "s1");
    }
}
