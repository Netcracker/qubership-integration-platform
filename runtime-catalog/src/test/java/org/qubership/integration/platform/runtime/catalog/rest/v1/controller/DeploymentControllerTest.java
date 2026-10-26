package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentControllerTest {

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
    private DomainProperties.DeployMethodConfiguration microConfiguration;
    @Mock
    private DomainProperties.DeployMethodConfiguration classicConfiguration;

    private DeploymentController controller;

    @BeforeEach
    void setUp() {
        controller = new DeploymentController(
                deploymentService,
                deploymentMapper,
                runtimeDeploymentService,
                domainProperties,
                bulkDeploymentService,
                snapshotService);
    }

    private void microDomainEnabled(boolean enabled) {
        when(domainProperties.getMicro()).thenReturn(microConfiguration);
        when(microConfiguration.isEnabled()).thenReturn(enabled);
    }

    private void classicDomainEnabled(boolean enabled) {
        when(domainProperties.getClassic()).thenReturn(classicConfiguration);
        when(classicConfiguration.isEnabled()).thenReturn(enabled);
    }

    private static DeploymentRequest microDeployRequest() {
        DeploymentRequest request = new DeploymentRequest();
        request.setDomain("orders");
        request.setSnapshotId("s1");
        request.setMode(DeployMode.APPEND);
        return request;
    }

    @Test
    void createOnMicroDomainDeploysThroughTheBulkDeploymentService() {
        Snapshot snapshot = Snapshot.builder().id("s1").build();
        DeploymentResponse expected = mock(DeploymentResponse.class);
        microDomainEnabled(true);
        when(bulkDeploymentService.resolveDomainType("orders")).thenReturn(DomainType.MICRO);
        when(snapshotService.findById("s1")).thenReturn(snapshot);
        when(deploymentMapper.asMicroResponse("chain-1", "orders", snapshot)).thenReturn(expected);

        ResponseEntity<DeploymentResponse> response = controller.create("chain-1", microDeployRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        ArgumentCaptor<ResourceDeployRequest> captor = ArgumentCaptor.forClass(ResourceDeployRequest.class);
        verify(bulkDeploymentService).deployResource(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("orders");
        assertThat(captor.getValue().getSnapshotIds()).containsExactly("s1");
        assertThat(captor.getValue().getMode()).isEqualTo(DeployMode.APPEND);
    }

    @Test
    void createOnMicroDomainIsRejectedWhenMicroDomainDisabled() {
        microDomainEnabled(false);
        when(bulkDeploymentService.resolveDomainType("orders")).thenReturn(DomainType.MICRO);

        assertThatThrownBy(() -> controller.create("chain-1", microDeployRequest()))
                .isInstanceOf(DomainTypeDisabledException.class);
        verify(bulkDeploymentService, never()).deployResource(any());
    }

    @Test
    void deleteByIdRemovesTheSnapshotFromTheMicroDomainResource() {
        classicDomainEnabled(false);
        microDomainEnabled(true);
        when(snapshotService.findByChainIdLight("chain-1"))
                .thenReturn(List.of(Snapshot.builder().id("s1").build()));

        ResponseEntity<Void> response = controller.deleteById("chain-1", "orders-s1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(bulkDeploymentService).deleteChainSnapshot("orders", "s1");
    }
}
