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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.io.factories.SnapshotWriterFactory;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.verification.ElementPropertiesVerificationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the finders and the delete and multi-build helpers of {@link SnapshotService}. The service
 * builds missing snapshots through its own lazy self-reference, so the test injects a mock in that
 * slot to drive {@code buildAll} without running the full build.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotServiceTest {

    @Mock
    private SnapshotRepository snapshotRepository;
    @Mock
    private ElementRepository elementRepository;
    @Mock
    private ChainRepository chainRepository;
    @Mock
    private ElementService elementService;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private DependencyRepository dependencyRepository;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private SnapshotService self;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private ElementPropertiesVerificationService elementPropertiesVerificationService;
    @Mock
    private MaskedFieldsService maskedFieldsService;
    @Mock
    private TransactionHandler transactionHandler;
    @Mock
    private SnapshotWriterFactory snapshotWriterFactory;

    private SnapshotService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotService(
                snapshotRepository, elementRepository, chainRepository, elementService, chainFinderService,
                dependencyRepository, deploymentService, self, actionLogger, elementPropertiesVerificationService,
                maskedFieldsService, transactionHandler, snapshotWriterFactory);
    }

    @Test
    void findByIdReturnsTheSnapshot() {
        Snapshot snapshot = Snapshot.builder().build();
        when(snapshotRepository.findById("snap-1")).thenReturn(Optional.of(snapshot));

        assertThat(service.findById("snap-1")).isSameAs(snapshot);
    }

    @Test
    void findByIdThrowsWhenTheSnapshotIsMissing() {
        when(snapshotRepository.findById("snap-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("snap-x"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Can't find configuration with id snap-x");
    }

    @Test
    void findByChainIdLightDelegatesToTheRepository() {
        List<Snapshot> snapshots = List.of(Snapshot.builder().build());
        when(snapshotRepository.findAllByChainId("chain-1")).thenReturn(snapshots);

        assertThat(service.findByChainIdLight("chain-1")).isSameAs(snapshots);
    }

    @Test
    void buildAllBuildsEachChainAndReportsFailuresThroughTheErrorHandler() {
        Snapshot built = Snapshot.builder().build();
        when(self.build("chain-1")).thenReturn(built);
        when(self.build("chain-2")).thenThrow(new RuntimeException("boom"));
        Map<String, String> errors = new HashMap<>();

        Map<String, Snapshot> result = service.buildAll(List.of("chain-1", "chain-2"), errors::put);

        assertThat(result).containsExactly(Map.entry("chain-1", built));
        assertThat(errors).containsEntry("chain-2", "boom");
    }

    @Test
    void findLastCreatedOrBuildMergesExistingSnapshotsWithFreshlyBuiltOnes() {
        Chain chain1 = Chain.builder().build();
        chain1.setId("chain-1");
        Snapshot existing = Snapshot.builder().chain(chain1).build();
        Snapshot built = Snapshot.builder().build();
        when(snapshotRepository.findAllLastCreated(any())).thenReturn(List.of(existing));
        when(self.build("chain-2")).thenReturn(built);

        Map<String, Snapshot> result =
                service.findLastCreatedOrBuild(List.of("chain-1", "chain-2"), (id, message) -> { });

        assertThat(result)
                .containsEntry("chain-1", existing)
                .containsEntry("chain-2", built);
    }

    @Test
    void deleteByIdClearsTheMatchingCurrentSnapshotAndDeletes() {
        Snapshot snapshot = Snapshot.builder().build();
        snapshot.setId("snap-1");
        Chain chain = Chain.builder().build();
        chain.setId("chain-1");
        chain.setName("Orders");
        chain.setCurrentSnapshot(snapshot);
        snapshot.setChain(chain);
        when(snapshotRepository.findById("snap-1")).thenReturn(Optional.of(snapshot));

        service.deleteById("snap-1");

        assertThat(chain.getCurrentSnapshot()).isNull();
        verify(deploymentService).deleteAllBySnapshotId("snap-1");
        verify(snapshotRepository).deleteById("snap-1");
        verify(actionLogger).logAction(any());
    }

    @Test
    void deleteAllByChainIdRemovesDeploymentsSnapshotsAndClearsTheCurrentSnapshot() {
        Chain chain = Chain.builder().build();
        chain.setId("chain-1");
        chain.setName("Orders");
        Snapshot snapshot = Snapshot.builder().chain(chain).build();
        snapshot.setId("snap-1");
        when(snapshotRepository.findAllByChainId("chain-1")).thenReturn(List.of(snapshot));

        service.deleteAllByChainId("chain-1");

        verify(deploymentService).deleteAllByChainId("chain-1");
        verify(chainRepository).updateCurrentSnapshot("chain-1", null);
        verify(chainRepository).updateUnsavedChanges("chain-1", true);
        verify(snapshotRepository).deleteAllByChainId("chain-1");
        verify(actionLogger).logAction(any());
    }
}
