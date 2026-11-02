package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
