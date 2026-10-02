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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainDeployError;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployWithSnapshotCreationRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the micro-domain gate and the endpoints of {@link CustomResourceController}. Each
 * single-resource endpoint runs only when the micro domain is enabled and otherwise throws
 * {@link DomainTypeDisabledException}. The deploy endpoints delegate to {@link BulkDeploymentService},
 * whose behavior {@code BulkDeploymentServiceTest} covers. Removing a snapshot retries a lost
 * optimistic-concurrency race up to a fixed budget before the conflict propagates.
 */
@ExtendWith(MockitoExtension.class)
class CustomResourceControllerTest {

    @Mock
    private BulkDeploymentService bulkDeploymentService;
    @Mock
    private MicroDomainResourceBuildService microDomainResourceBuildService;
    @Mock
    private MicroDomainService microDomainService;
    @Mock
    private DomainProperties domainProperties;
    @Mock
    private DomainProperties.DeployMethodConfiguration microConfiguration;

    private CustomResourceController controller;

    @BeforeEach
    void setUp() {
        controller = new CustomResourceController(
                bulkDeploymentService,
                microDomainResourceBuildService,
                microDomainService,
                domainProperties);
    }

    private void microDomainEnabled(boolean enabled) {
        when(domainProperties.getMicro()).thenReturn(microConfiguration);
        when(microConfiguration.isEnabled()).thenReturn(enabled);
    }

    private static ResourceDeployRequest deployRequest(String name) {
        return ResourceDeployRequest.builder()
                .name(name)
                .snapshotIds(List.of("s1"))
                .build();
    }

    @Test
    void buildResourceReturnsTheBuiltResourceWhenMicroDomainEnabled() {
        microDomainEnabled(true);
        ResourceBuildRequest request = ResourceBuildRequest.builder()
                .options(ResourceBuildOptions.builder().build())
                .build();
        when(microDomainResourceBuildService.buildResources(request, false))
                .thenReturn(new BuiltResources("resource-yaml", Map.of()));

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
    void deployChainsReturnsTheResultOfTheBulkDeployment() {
        DeployWithSnapshotCreationRequest request = DeployWithSnapshotCreationRequest.builder()
                .domains(List.of("orders"))
                .chainIds(List.of("chain-1"))
                .build();
        List<BulkDeploymentResponse> result = List.of(BulkDeploymentResponse.builder()
                .chainId("chain-1")
                .status(BulkDeploymentStatus.CREATED)
                .build());
        when(bulkDeploymentService.deployChains(request)).thenReturn(result);

        ResponseEntity<List<BulkDeploymentResponse>> response = controller.deployChains(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(result);
    }

    @Test
    void deployResourceDeploysThroughTheBulkDeploymentServiceWhenMicroDomainEnabled() {
        microDomainEnabled(true);
        ResourceDeployRequest request = deployRequest("orders");

        ResponseEntity<Void> response = controller.deployResource(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bulkDeploymentService).deployResource(request);
    }

    @Test
    void deployResourceIsRejectedWhenMicroDomainDisabled() {
        microDomainEnabled(false);
        ResourceDeployRequest request = deployRequest("orders");

        assertThatThrownBy(() -> controller.deployResource(request))
                .isInstanceOf(DomainTypeDisabledException.class);
        verifyNoInteractions(bulkDeploymentService);
    }

    // deleteChainSnapshot rewrites the Integration, the integrations-configuration ConfigMap and the
    // shared HTTPRoute tiers, each carrying the resourceVersion it read on entry, so a deploy to the
    // same domain can take any of those writes. It reloads everything through
    // getMainIntegrationResources, so re-calling it recomputes against current state.
    @DisplayName("Retries a snapshot removal that loses a concurrency race")
    @Test
    void deleteSnapshotRetriesOnConflict() {
        microDomainEnabled(true);
        doThrow(new KubeApiConflictException("conflict", null))
                .doNothing()
                .when(microDomainService).deleteChainSnapshot("orders", "s1");

        controller.deleteSnapshotFromResource("orders", "s1");

        verify(microDomainService, times(2)).deleteChainSnapshot("orders", "s1");
    }

    @DisplayName("Gives up on a snapshot removal after the retry budget is exhausted")
    @Test
    void deleteSnapshotStopsRetryingAfterTheBudgetIsExhausted() {
        microDomainEnabled(true);
        doThrow(new KubeApiConflictException("conflict", null))
                .when(microDomainService).deleteChainSnapshot("orders", "s1");

        assertThrows(KubeApiConflictException.class,
                () -> controller.deleteSnapshotFromResource("orders", "s1"));

        verify(microDomainService, times(3)).deleteChainSnapshot("orders", "s1");
    }

    @DisplayName("Does not retry a snapshot removal that failed for a reason other than a conflict")
    @Test
    void deleteSnapshotDoesNotRetryANonConflictFailure() {
        microDomainEnabled(true);
        doThrow(new MicroDomainDeployError("boom", null))
                .when(microDomainService).deleteChainSnapshot("orders", "s1");

        assertThrows(MicroDomainDeployError.class,
                () -> controller.deleteSnapshotFromResource("orders", "s1"));

        verify(microDomainService, times(1)).deleteChainSnapshot("orders", "s1");
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
