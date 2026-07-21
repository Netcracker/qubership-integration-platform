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

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentsUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DeploymentRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentSnapshotAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.service.deployment.DeploymentBuilderService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the finders, the delegating snapshot and bulk-deploy helpers, and the single-domain deploy
 * path of {@link DeploymentService}, plus its static full-deployments cache. Trigger checking is off
 * (the {@code @Value} flag defaults to {@code false}), so these tests stay on the persistence and
 * assembly branches rather than the trigger-collision machinery.
 */
@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private ElementRepository elementRepository;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private DeploymentBuilderService deploymentBuilderService;
    @Mock
    private TransactionHandler transactionHandler;
    @Mock
    private RoutesGetterService routesGetterService;
    @Mock
    private IntegrationServiceCatalog integrationServiceCatalog;

    private DeploymentService service;

    @BeforeEach
    void setUp() {
        DeploymentService.clearDeploymentsUpdateCache(0L);
        service = new DeploymentService(
                deploymentRepository,
                elementRepository,
                chainFinderService,
                snapshotService,
                actionLogger,
                deploymentBuilderService,
                transactionHandler,
                routesGetterService,
                integrationServiceCatalog);
    }

    @AfterEach
    void tearDown() {
        DeploymentService.clearDeploymentsUpdateCache(0L);
    }

    private void runTransactionsImmediately() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(transactionHandler).runInNewTransaction(any());
    }

    @Test
    void findByIdReturnsTheDeployment() {
        Deployment deployment = new Deployment();
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));

        assertThat(service.findById("dep-1")).isSameAs(deployment);
    }

    @Test
    void findByIdThrowsWhenTheDeploymentIsMissing() {
        when(deploymentRepository.findById("dep-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("dep-x"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Can't find deployment with id: dep-x");
    }

    @Test
    void findAllByChainIdDelegatesToTheRepository() {
        List<Deployment> deployments = List.of(new Deployment());
        when(deploymentRepository.findAllByChainId("chain-1")).thenReturn(deployments);

        assertThat(service.findAllByChainId("chain-1")).isSameAs(deployments);
    }

    @Test
    void getDeploymentsCountByDomainDelegatesToTheRepository() {
        when(deploymentRepository.countByDomain("domainA")).thenReturn(7L);

        assertThat(service.getDeploymentsCountByDomain("domainA")).isEqualTo(7L);
    }

    @Test
    void createAllReturnsEmptyListWhenGivenNoDeployments() {
        assertThat(service.createAll(List.of(), "chain-1")).isEmpty();
    }

    @Test
    void provideSnapshotsBuildsNewSnapshotsForTheCreateNewAction() {
        BiConsumer<String, String> errorHandler = (chainId, message) -> { };
        Map<String, Snapshot> built = Map.of("chain-1", new Snapshot());
        when(snapshotService.buildAll(List.of("chain-1"), errorHandler)).thenReturn(built);

        Map<String, Snapshot> result = service.provideSnapshots(
                List.of("chain-1"), BulkDeploymentSnapshotAction.CREATE_NEW, errorHandler);

        assertThat(result).isSameAs(built);
    }

    @Test
    void provideSnapshotsReusesLastCreatedSnapshotsForTheLastCreatedAction() {
        BiConsumer<String, String> errorHandler = (chainId, message) -> { };
        Map<String, Snapshot> lastCreated = Map.of("chain-1", new Snapshot());
        when(snapshotService.findLastCreatedOrBuild(List.of("chain-1"), errorHandler)).thenReturn(lastCreated);

        Map<String, Snapshot> result = service.provideSnapshots(
                List.of("chain-1"), BulkDeploymentSnapshotAction.LAST_CREATED, errorHandler);

        assertThat(result).isSameAs(lastCreated);
    }

    @Test
    void deploySnapshotToDomainReportsCreatedOnSuccess() {
        Snapshot snapshot = snapshotWithChain();
        when(chainFinderService.findById("chain-1")).thenReturn(snapshot.getChain());
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of());
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        runTransactionsImmediately();

        var response = service.deploySnapshotToDomain(snapshot, "domainA");

        assertThat(response.getStatus()).isEqualTo(BulkDeploymentStatus.CREATED);
        assertThat(response.getChainId()).isEqualTo("chain-1");
        assertThat(response.getDomain().getName()).isEqualTo("domainA");
    }

    @Test
    void deploySnapshotToDomainReportsFailedDeployWhenCreationThrows() {
        Snapshot snapshot = snapshotWithChain();
        when(chainFinderService.findById("chain-1")).thenReturn(snapshot.getChain());
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of());
        when(deploymentRepository.save(any())).thenThrow(new RuntimeException("db down"));
        runTransactionsImmediately();

        var response = service.deploySnapshotToDomain(snapshot, "domainA");

        assertThat(response.getStatus()).isEqualTo(BulkDeploymentStatus.FAILED_DEPLOY);
        assertThat(response.getErrorMessage()).isEqualTo("db down");
    }

    @Test
    void deleteByIdThrowsWhenTheDeploymentIsMissing() {
        runTransactionsImmediately();
        when(deploymentRepository.findById("dep-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteById("dep-x"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Can't find deployment with id: dep-x");
    }

    @Test
    void deleteByIdRemovesAndLogsTheDeployment() {
        Deployment deployment = new Deployment();
        deployment.setId("dep-1");
        deployment.setChain(chain());
        runTransactionsImmediately();
        when(deploymentRepository.findById("dep-1")).thenReturn(Optional.of(deployment));

        service.deleteById("dep-1");

        verify(deploymentRepository).deleteById("dep-1");
        verify(actionLogger).logAction(any());
    }

    @Test
    void getDeploymentsForDomainCachesTheFullDeploymentsResponse() {
        EngineDeploymentsDTO fullRequest = EngineDeploymentsDTO.builder().excludeDeployments(List.of()).build();
        List<Deployment> deployments = List.of(new Deployment());
        List<DeploymentUpdate> updates = List.of(DeploymentUpdate.builder().build());
        when(deploymentRepository.findAllByDomain("domainA")).thenReturn(deployments);
        when(deploymentBuilderService.buildDeploymentsUpdate(deployments)).thenReturn(updates);

        DeploymentsUpdate first = service.getDeploymentsForDomain("domainA", fullRequest);
        DeploymentsUpdate second = service.getDeploymentsForDomain("domainA", fullRequest);

        assertThat(first.getUpdate()).isEqualTo(updates);
        assertThat(second).isSameAs(first);
        verify(deploymentBuilderService, times(1)).buildDeploymentsUpdate(deployments);
    }

    @Test
    void clearDeploymentsUpdateCacheForcesTheNextFullRequestToRebuild() {
        EngineDeploymentsDTO fullRequest = EngineDeploymentsDTO.builder().excludeDeployments(List.of()).build();
        List<Deployment> deployments = List.of(new Deployment());
        when(deploymentRepository.findAllByDomain("domainA")).thenReturn(deployments);
        when(deploymentBuilderService.buildDeploymentsUpdate(deployments))
                .thenReturn(List.of(DeploymentUpdate.builder().build()));

        service.getDeploymentsForDomain("domainA", fullRequest);
        DeploymentService.clearDeploymentsUpdateCache(1L);
        service.getDeploymentsForDomain("domainA", fullRequest);

        verify(deploymentBuilderService, times(2)).buildDeploymentsUpdate(deployments);
    }

    private static Snapshot snapshotWithChain() {
        Snapshot snapshot = Snapshot.builder().chain(chain()).build();
        snapshot.setId("snap-1");
        snapshot.setName("snap-name");
        return snapshot;
    }

    private static Chain chain() {
        Chain chain = Chain.builder().build();
        chain.setId("chain-1");
        chain.setName("chain-name");
        return chain;
    }
}
