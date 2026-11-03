package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentControllerTest {

    private static final String CHAIN_ID = "chain-1";
    private static final String DOMAIN = "orders";
    private static final String SNAPSHOT_ID = "3f0c7f4e-2b1a-4c9d-8e5f-6a7b8c9d0e1f";

    @Mock
    private DeploymentService deploymentService;
    @Mock
    private DeploymentMapper deploymentMapper;
    @Mock
    private RuntimeDeploymentService runtimeDeploymentService;
    @Mock
    private DomainProperties domainProperties;
    @Mock
    private BulkDeploymentService bulkDeploymentService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private MicroDomainService microDomainService;
    @Mock
    private DomainProperties.DeployMethodConfiguration classicConfiguration;
    @Mock
    private DomainProperties.DeployMethodConfiguration microConfiguration;

    private DeploymentController controller;

    @BeforeEach
    void setUp() {
        controller = new DeploymentController(
                deploymentService,
                deploymentMapper,
                runtimeDeploymentService,
                domainProperties,
                bulkDeploymentService,
                snapshotService,
                chainFinderService,
                microDomainService);
    }

    private void classicDomainEnabled(boolean enabled) {
        when(domainProperties.getClassic()).thenReturn(classicConfiguration);
        when(classicConfiguration.isEnabled()).thenReturn(enabled);
    }

    private void microDomainEnabled(boolean enabled) {
        when(microDomainService.verifyMicroDomainEnabled(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            if (enabled) {
                return supplier.get();
            }
            throw new DomainTypeDisabledException(DomainType.MICRO);
        });
    }

    private Snapshot snapshotInChain() {
        Chain chain = new Chain();
        chain.setId(CHAIN_ID);
        Snapshot snapshot = new Snapshot();
        snapshot.setId(SNAPSHOT_ID);
        snapshot.setChain(chain);
        when(chainFinderService.findById(CHAIN_ID)).thenReturn(chain);
        when(snapshotService.findById(SNAPSHOT_ID)).thenReturn(snapshot);
        return snapshot;
    }

    @Test
    void createDeploysToTheMicroDomainForADeclaredTypeWithoutDiscovery() {
        microDomainEnabled(true);
        when(bulkDeploymentService.discoverDomainType(DOMAIN)).thenReturn(Optional.empty());
        Snapshot snapshot = snapshotInChain();
        DeploymentResponse microResponse = DeploymentResponse.builder().id(DOMAIN + "-" + SNAPSHOT_ID).build();
        when(deploymentMapper.asMicroResponse(CHAIN_ID, DOMAIN, snapshot)).thenReturn(microResponse);

        ResponseEntity<DeploymentResponse> response = controller.create(CHAIN_ID,
                DeploymentRequest.builder().domain(DOMAIN).snapshotId(SNAPSHOT_ID)
                        .domainType(DomainType.MICRO).mode(DeployMode.APPEND).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(microResponse);
        ArgumentCaptor<ResourceDeployRequest> sent = ArgumentCaptor.forClass(ResourceDeployRequest.class);
        verify(bulkDeploymentService).deployResource(sent.capture());
        assertThat(sent.getValue().getName()).isEqualTo(DOMAIN);
        assertThat(sent.getValue().getSnapshotIds()).containsExactly(SNAPSHOT_ID);
        assertThat(sent.getValue().getMode()).isEqualTo(DeployMode.APPEND);
        verifyNoInteractions(deploymentService);
    }

    @Test
    void createDeploysToTheMicroDomainWhenTheDomainIsDiscoveredAsMicroWithoutADeclaration() {
        microDomainEnabled(true);
        when(bulkDeploymentService.discoverDomainType(DOMAIN)).thenReturn(Optional.of(DomainType.MICRO));
        Snapshot snapshot = snapshotInChain();
        when(deploymentMapper.asMicroResponse(CHAIN_ID, DOMAIN, snapshot))
                .thenReturn(DeploymentResponse.builder().id(DOMAIN + "-" + SNAPSHOT_ID).build());

        controller.create(CHAIN_ID, DeploymentRequest.builder().domain(DOMAIN).snapshotId(SNAPSHOT_ID).build());

        ArgumentCaptor<ResourceDeployRequest> sent = ArgumentCaptor.forClass(ResourceDeployRequest.class);
        verify(bulkDeploymentService).deployResource(sent.capture());
        assertThat(sent.getValue().getName()).isEqualTo(DOMAIN);
        assertThat(sent.getValue().getMode()).isEqualTo(DeployMode.REWRITE);
    }

    @Test
    void createRejectsARequestWhoseDeclaredTypeDisagreesWithDiscovery() {
        when(bulkDeploymentService.discoverDomainType(DOMAIN)).thenReturn(Optional.of(DomainType.MICRO));

        assertThatThrownBy(() -> controller.create(CHAIN_ID,
                DeploymentRequest.builder().domain(DOMAIN).snapshotId(SNAPSHOT_ID)
                        .domainType(DomainType.CLASSIC).build()))
                .isInstanceOf(DeploymentProcessingException.class)
                .hasMessageContaining("is discovered as MICRO");

        verify(bulkDeploymentService, never()).deployResource(any());
        verifyNoInteractions(deploymentService, snapshotService, chainFinderService);
    }

    @Test
    void createStillCreatesAClassicDeploymentWhenTheDomainIsDiscoveredAsClassic() {
        classicDomainEnabled(true);
        when(bulkDeploymentService.discoverDomainType(DOMAIN)).thenReturn(Optional.of(DomainType.CLASSIC));
        DeploymentRequest request = DeploymentRequest.builder().domain(DOMAIN).snapshotId(SNAPSHOT_ID)
                .domainType(DomainType.CLASSIC).build();
        Deployment deployment = new Deployment();
        DeploymentResponse classicResponse = DeploymentResponse.builder().id("classic-1").build();
        when(deploymentMapper.asEntity(request)).thenReturn(deployment);
        when(deploymentService.create(deployment, CHAIN_ID, SNAPSHOT_ID)).thenReturn(deployment);
        when(deploymentMapper.asResponse(deployment)).thenReturn(classicResponse);

        ResponseEntity<DeploymentResponse> response = controller.create(CHAIN_ID, request);

        assertThat(response.getBody()).isSameAs(classicResponse);
        verify(bulkDeploymentService, never()).deployResource(any());
        verifyNoInteractions(microDomainService, snapshotService, chainFinderService);
    }

    @Test
    void createStillCreatesAClassicDeploymentWhenTheDomainIsNotDiscovered() {
        classicDomainEnabled(true);
        when(bulkDeploymentService.discoverDomainType(DOMAIN)).thenReturn(Optional.empty());
        DeploymentRequest request = DeploymentRequest.builder().domain(DOMAIN).snapshotId(SNAPSHOT_ID).build();
        Deployment deployment = new Deployment();
        when(deploymentMapper.asEntity(request)).thenReturn(deployment);
        when(deploymentService.create(deployment, CHAIN_ID, SNAPSHOT_ID)).thenReturn(deployment);
        when(deploymentMapper.asResponse(deployment)).thenReturn(DeploymentResponse.builder().id("classic-1").build());

        controller.create(CHAIN_ID, request);

        verify(bulkDeploymentService, never()).deployResource(any());
        verifyNoInteractions(microDomainService);
    }

    @Test
    void deleteByIdDeletesAClassicDeployment() {
        classicDomainEnabled(true);

        ResponseEntity<Void> response = controller.deleteById("chain-1", SNAPSHOT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deploymentService).deleteById(SNAPSHOT_ID);
        verifyNoInteractions(microDomainService);
    }

    @Test
    void deleteByIdIsRejectedWhenClassicDomainDisabled() {
        classicDomainEnabled(false);

        assertThatThrownBy(() -> controller.deleteById("chain-1", SNAPSHOT_ID))
                .isInstanceOf(DomainTypeDisabledException.class);
        verifyNoInteractions(microDomainService);
    }

    @Test
    void deleteByIdRemovesTheSnapshotFromTheMicroDomainResource() {
        microDomainEnabled(true);

        ResponseEntity<Void> response = controller.deleteById("chain-1", "orders-" + SNAPSHOT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(bulkDeploymentService).deleteChainSnapshot("orders", SNAPSHOT_ID);
        verifyNoInteractions(deploymentService);
    }

    @Test
    void deleteByIdKeepsTheWholeHyphenatedDomainOfACompositeId() {
        microDomainEnabled(true);

        controller.deleteById("chain-1", "micro-domain-1-" + SNAPSHOT_ID);

        verify(bulkDeploymentService).deleteChainSnapshot("micro-domain-1", SNAPSHOT_ID);
    }

    @Test
    void deleteByIdRejectsAnIdThatIsNotACompositeMicroDeploymentId() {
        assertThatThrownBy(() -> controller.deleteById("chain-1", "orders-s1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid deployment id format: orders-s1");
        verifyNoInteractions(microDomainService);
        verifyNoInteractions(deploymentService);
    }

    @Test
    void deleteByIdIsRejectedWhenMicroDomainDisabled() {
        microDomainEnabled(false);

        assertThatThrownBy(() -> controller.deleteById("chain-1", "orders-" + SNAPSHOT_ID))
                .isInstanceOf(DomainTypeDisabledException.class);
        verify(bulkDeploymentService, never()).deleteChainSnapshot(any(), any());
    }

    @Test
    void createIsRejectedWhenMicroDomainDisabled() {
        microDomainEnabled(false);
        when(bulkDeploymentService.discoverDomainType(DOMAIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.create(CHAIN_ID,
                DeploymentRequest.builder().domain(DOMAIN).snapshotId(SNAPSHOT_ID)
                        .domainType(DomainType.MICRO).build()))
                .isInstanceOf(DomainTypeDisabledException.class);
        verify(bulkDeploymentService, never()).deployResource(any());
        verifyNoInteractions(snapshotService, chainFinderService);
    }
}
