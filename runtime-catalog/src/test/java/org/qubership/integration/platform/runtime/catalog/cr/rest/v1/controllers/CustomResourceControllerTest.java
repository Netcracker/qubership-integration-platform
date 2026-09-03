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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainDeployError;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployWithSnapshotCreationRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.cr.services.ResourceBuildOptionsProvider;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the micro-domain gate and the single-resource endpoints of {@link CustomResourceController}.
 * Each endpoint runs only when the micro domain is enabled and otherwise raises
 * {@link DomainTypeDisabledException}; the deploy endpoint wires the options provider, the build
 * service, and the deploy call together. A deploy that loses an optimistic-concurrency race
 * rebuilds against current cluster state and retries up to a fixed budget before the conflict
 * propagates. Bulk deployment checks both domain types up front, so a
 * request naming a disabled type is rejected before a snapshot or a deployment is created.
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
    @Mock
    private DomainProperties.DeployMethodConfiguration classicConfiguration;

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

    private ResourceDeployRequest deployRequest(String name) {
        return ResourceDeployRequest.builder()
                .name(name)
                .snapshotIds(List.of("s1"))
                .build();
    }

    private void classicDomainEnabled(boolean enabled) {
        when(domainProperties.getClassic()).thenReturn(classicConfiguration);
        when(classicConfiguration.isEnabled()).thenReturn(enabled);
    }

    private static EngineDomain engineDomain(String name, DomainType type) {
        return EngineDomain.builder().name(name).type(type).build();
    }

    private static DeployWithSnapshotCreationRequest deployRequest(String... domains) {
        return DeployWithSnapshotCreationRequest.builder()
                .domains(List.of(domains))
                .chainIds(List.of("chain-1"))
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
    void deployChainsIsRejectedWhenMicroDomainDisabled() {
        classicDomainEnabled(true);
        microDomainEnabled(false);
        when(engineService.getDomains()).thenReturn(List.of(engineDomain("micro-domain", DomainType.MICRO)));

        assertThatThrownBy(() -> controller.deployChains(deployRequest("micro-domain")))
                .isInstanceOf(DomainTypeDisabledException.class)
                .hasMessageContaining(DomainType.MICRO.name());
        verifyNoInteractions(chainRepository, deploymentService, microDomainService);
    }

    @Test
    void deployChainsIsRejectedWhenClassicDomainDisabled() {
        classicDomainEnabled(false);
        when(engineService.getDomains()).thenReturn(List.of(engineDomain("classic-domain", DomainType.CLASSIC)));

        assertThatThrownBy(() -> controller.deployChains(deployRequest("classic-domain")))
                .isInstanceOf(DomainTypeDisabledException.class)
                .hasMessageContaining(DomainType.CLASSIC.name());
        verifyNoInteractions(chainRepository, deploymentService, microDomainService);
    }

    @Test
    void deployChainsProceedsWhenNoDomainOfTheDisabledTypeIsRequested() {
        classicDomainEnabled(true);
        microDomainEnabled(false);
        when(engineService.getDomains()).thenReturn(List.of(engineDomain("classic-domain", DomainType.CLASSIC)));

        ResponseEntity<List<BulkDeploymentResponse>> response =
                controller.deployChains(deployRequest("classic-domain"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(microDomainService, microDomainResourceBuildService);
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
        BuiltResources built = new BuiltResources("resource-yaml", Map.of());
        when(microDomainResourceBuildService.buildResources(any(ResourceBuildRequest.class), eq(true)))
                .thenReturn(built);

        ResponseEntity<Void> response = controller.deployResource(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(microDomainService).deploy(built);
    }

    @DisplayName("Rebuilds and retries when a deploy loses an optimistic-concurrency race")
    @Test
    void deployRebuildsAndRetriesOnConflict() {
        microDomainEnabled(true);
        when(resourceBuildOptionsProvider.getOptions(any())).thenReturn(ResourceBuildOptions.builder().build());
        BuiltResources first = new BuiltResources("first", Map.of());
        BuiltResources second = new BuiltResources("second", Map.of());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(first)
                .thenReturn(second);
        doThrow(new KubeApiConflictException("conflict", null))
                .doNothing()
                .when(microDomainService).deploy(any());

        controller.deployResource(deployRequest("payments"));

        // Rebuilt, not re-sent: the second attempt must carry a freshly built document.
        verify(microDomainResourceBuildService, times(2)).buildResources(any(), anyBoolean());
        ArgumentCaptor<BuiltResources> captor = ArgumentCaptor.forClass(BuiltResources.class);
        verify(microDomainService, times(2)).deploy(captor.capture());
        assertEquals(List.of("first", "second"),
                captor.getAllValues().stream().map(BuiltResources::yaml).toList());
    }

    @DisplayName("Builds each retry attempt from options the previous attempt could not have touched")
    @Test
    void deployBuildsAFreshRequestForEveryAttempt() {
        microDomainEnabled(true);
        // The build mutates options.mount in place, so a request hoisted out of the retry loop would
        // feed the previous attempt's merged mount set back into the next build: the set could only
        // grow, and a mount the conflicting writer removed would come back.
        when(resourceBuildOptionsProvider.getOptions(any()))
                .thenReturn(ResourceBuildOptions.builder().build())
                .thenReturn(ResourceBuildOptions.builder().build());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
        doThrow(new KubeApiConflictException("conflict", null))
                .doNothing()
                .when(microDomainService).deploy(any());

        controller.deployResource(deployRequest("payments"));

        verify(resourceBuildOptionsProvider, times(2)).getOptions(any());
        ArgumentCaptor<ResourceBuildRequest> captor = ArgumentCaptor.forClass(ResourceBuildRequest.class);
        verify(microDomainResourceBuildService, times(2)).buildResources(captor.capture(), anyBoolean());
        assertThat(captor.getAllValues().get(0).getOptions())
                .as("the second attempt must build from its own options, not the ones attempt 1 mutated")
                .isNotSameAs(captor.getAllValues().get(1).getOptions());
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

    @DisplayName("Gives up after the retry budget and surfaces the last conflict")
    @Test
    void deployStopsRetryingAfterTheBudgetIsExhausted() {
        microDomainEnabled(true);
        when(resourceBuildOptionsProvider.getOptions(any())).thenReturn(ResourceBuildOptions.builder().build());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
        doThrow(new KubeApiConflictException("conflict", null)).when(microDomainService).deploy(any());
        ResourceDeployRequest request = deployRequest("payments");

        assertThrows(KubeApiConflictException.class, () -> controller.deployResource(request));

        verify(microDomainService, times(3)).deploy(any());
    }

    @DisplayName("Does not retry a failure that is not a conflict")
    @Test
    void deployDoesNotRetryANonConflictFailure() {
        microDomainEnabled(true);
        when(resourceBuildOptionsProvider.getOptions(any())).thenReturn(ResourceBuildOptions.builder().build());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
        doThrow(new MicroDomainDeployError("boom", null)).when(microDomainService).deploy(any());
        ResourceDeployRequest request = deployRequest("payments");

        assertThrows(MicroDomainDeployError.class, () -> controller.deployResource(request));

        verify(microDomainService, times(1)).deploy(any());
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
