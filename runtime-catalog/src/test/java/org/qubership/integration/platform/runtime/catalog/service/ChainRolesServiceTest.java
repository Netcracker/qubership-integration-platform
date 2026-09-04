package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.AbacRoleChangeException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainRolesMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainRolesServiceTest {

    private static final String ELEMENT_ID = "element-a";
    private static final String OTHER_ELEMENT_ID = "element-b";
    private static final String CHAIN_ID = "chain-a";
    private static final String OTHER_CHAIN_ID = "chain-b";
    private static final String ACCESS_CONTROL_TYPE = "accessControlType";
    private static final String ROLES = "roles";

    @Mock
    private ElementService elementService;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private RuntimeDeploymentService runtimeDeploymentService;
    @Mock
    private ChainRolesMapper chainRolesMapper;
    @Mock
    private ChainService chainService;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private ElementRepository elementRepository;
    @Mock
    private DeploymentMapper deploymentMapper;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private ActionsLogService actionLogger;

    @InjectMocks
    private ChainRolesService chainRolesService;

    @Test
    @DisplayName("A role update on an element that does not exist fails instead of reporting success")
    void updateRolesPropagatesMissingElement() {
        when(elementService.findById(ELEMENT_ID))
                .thenThrow(new EntityNotFoundException("Can't find chain element with id: " + ELEMENT_ID));

        List<UpdateRolesRequest> batch = List.of(updateRequest(ELEMENT_ID, "reader"));

        assertThatThrownBy(() -> chainRolesService.updateRoles(batch))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(ELEMENT_ID);

        verifyNoInteractions(snapshotService);
    }

    @Test
    @DisplayName("A batch whose second element does not exist leaves the first element untouched")
    void updateRolesWritesNothingWhenAnElementOfTheBatchIsMissing() {
        ChainElement element = httpTrigger(ELEMENT_ID, CHAIN_ID);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);
        when(elementService.findById(OTHER_ELEMENT_ID))
                .thenThrow(new EntityNotFoundException("Can't find chain element with id: " + OTHER_ELEMENT_ID));

        List<UpdateRolesRequest> batch = List.of(
                updateRequest(ELEMENT_ID, "reader"),
                updateRequest(OTHER_ELEMENT_ID, "reader"));

        assertThatThrownBy(() -> chainRolesService.updateRoles(batch))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(OTHER_ELEMENT_ID);

        verify(elementService, never()).save(element);
        assertThat(element.getProperties()).doesNotContainKey(ROLES);
        assertThat(element.getChain().isUnsavedChanges()).isFalse();
        verifyNoInteractions(actionLogger);
    }

    @Test
    @DisplayName("A role update switches an unrestricted endpoint to RBAC and marks the chain unsaved")
    void updateRolesAppliesRolesAndSwitchesAccessControlType() {
        ChainElement element = httpTrigger(ELEMENT_ID, CHAIN_ID);
        element.getProperties().put(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_NONE);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);

        chainRolesService.updateRoles(List.of(updateRequest(ELEMENT_ID, "reader")));

        verify(elementService).save(element);
        assertThat(element.getProperties())
                .containsEntry(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_RBAC)
                .containsEntry(ROLES, List.of("reader"));
        assertThat(element.getChain().isUnsavedChanges()).isTrue();
    }

    @Test
    @DisplayName("A role update does not redeploy the chain")
    void updateRolesLeavesTheDeploymentAlone() {
        ChainElement element = httpTrigger(ELEMENT_ID, CHAIN_ID);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);

        chainRolesService.updateRoles(List.of(updateRequest(ELEMENT_ID, "reader")));

        verifyNoInteractions(snapshotService, deploymentService, chainService);
    }

    @Test
    @DisplayName("A role update on an ABAC endpoint is rejected")
    void updateRolesRejectsAbacElement() {
        ChainElement element = httpTrigger(ELEMENT_ID, CHAIN_ID);
        element.getProperties().put(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_ABAC);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);

        List<UpdateRolesRequest> batch = List.of(updateRequest(ELEMENT_ID, "reader"));

        assertThatThrownBy(() -> chainRolesService.updateRoles(batch))
                .isInstanceOf(AbacRoleChangeException.class)
                .hasMessageContaining(ELEMENT_ID);

        verifyNoInteractions(snapshotService);
    }

    @Test
    @DisplayName("A batch whose second element is ABAC leaves the first element untouched")
    void updateRolesWritesNothingWhenAnElementOfTheBatchIsAbac() {
        ChainElement element = httpTrigger(ELEMENT_ID, CHAIN_ID);
        ChainElement abacElement = httpTrigger(OTHER_ELEMENT_ID, OTHER_CHAIN_ID);
        abacElement.getProperties().put(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_ABAC);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);
        when(elementService.findById(OTHER_ELEMENT_ID)).thenReturn(abacElement);

        List<UpdateRolesRequest> batch = List.of(
                updateRequest(ELEMENT_ID, "reader"),
                updateRequest(OTHER_ELEMENT_ID, "reader"));

        assertThatThrownBy(() -> chainRolesService.updateRoles(batch))
                .isInstanceOf(AbacRoleChangeException.class)
                .hasMessageContaining(OTHER_ELEMENT_ID);

        verify(elementService, never()).save(element);
        assertThat(element.getProperties()).doesNotContainKey(ROLES);
        verifyNoInteractions(actionLogger);
    }

    @Test
    @DisplayName("A redeploy builds a snapshot and clears the unsaved changes of the chain")
    void redeployDeploysTheRequestedChain() {
        Chain chain = chain(CHAIN_ID);
        chain.setUnsavedChanges(true);
        Snapshot snapshot = new Snapshot();
        DeploymentRequest deploymentRequest = new DeploymentRequest();
        List<Deployment> deploymentEntities = List.of(new Deployment());
        when(chainFinderService.findById(CHAIN_ID)).thenReturn(chain);
        when(snapshotService.build(CHAIN_ID)).thenReturn(snapshot);
        when(chainRolesMapper.prepareDeploymentRequest(snapshot)).thenReturn(deploymentRequest);
        when(deploymentMapper.asEntities(List.of(deploymentRequest))).thenReturn(deploymentEntities);

        chainRolesService.redeploy(List.of(CHAIN_ID, CHAIN_ID));

        verify(deploymentService).createAll(deploymentEntities, CHAIN_ID, snapshot);
        verify(chainService).update(chain);
        verify(snapshotService).build(CHAIN_ID);
        assertThat(chain.isUnsavedChanges()).isFalse();
        assertThat(chain.getCurrentSnapshot()).isSameAs(snapshot);
    }

    @Test
    @DisplayName("A batch whose second chain does not exist deploys nothing")
    void redeployDeploysNothingWhenAChainOfTheBatchIsMissing() {
        when(chainFinderService.findById(CHAIN_ID)).thenReturn(chain(CHAIN_ID));
        when(chainFinderService.findById(OTHER_CHAIN_ID))
                .thenThrow(new EntityNotFoundException("Can't find chain with id: " + OTHER_CHAIN_ID));

        List<String> batch = List.of(CHAIN_ID, OTHER_CHAIN_ID);

        assertThatThrownBy(() -> chainRolesService.redeploy(batch))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(OTHER_CHAIN_ID);

        verifyNoInteractions(snapshotService, deploymentService, chainService);
    }

    @Test
    @DisplayName("A chain that fails to deploy does not stop the rest of the batch")
    void redeployKeepsGoingAfterAChainFails() {
        Chain failing = chain(CHAIN_ID);
        Chain healthy = chain(OTHER_CHAIN_ID);
        Snapshot snapshot = new Snapshot();
        DeploymentRequest deploymentRequest = new DeploymentRequest();
        List<Deployment> deploymentEntities = List.of(new Deployment());
        when(chainFinderService.findById(CHAIN_ID)).thenReturn(failing);
        when(chainFinderService.findById(OTHER_CHAIN_ID)).thenReturn(healthy);
        when(snapshotService.build(CHAIN_ID)).thenThrow(new SnapshotCreationException("broken element", CHAIN_ID, null, null));
        when(snapshotService.build(OTHER_CHAIN_ID)).thenReturn(snapshot);
        when(chainRolesMapper.prepareDeploymentRequest(snapshot)).thenReturn(deploymentRequest);
        when(deploymentMapper.asEntities(List.of(deploymentRequest))).thenReturn(deploymentEntities);

        List<String> batch = List.of(CHAIN_ID, OTHER_CHAIN_ID);

        assertThatThrownBy(() -> chainRolesService.redeploy(batch))
                .isInstanceOf(SnapshotCreationException.class)
                .hasMessageContaining(CHAIN_ID);

        verify(deploymentService).createAll(deploymentEntities, OTHER_CHAIN_ID, snapshot);
        assertThat(healthy.isUnsavedChanges()).isFalse();
        assertThat(healthy.getCurrentSnapshot()).isSameAs(snapshot);
    }

    @Test
    @DisplayName("Several failed chains are reported in one error naming each of them")
    void redeployReportsEveryFailedChain() {
        when(chainFinderService.findById(CHAIN_ID)).thenReturn(chain(CHAIN_ID));
        when(chainFinderService.findById(OTHER_CHAIN_ID)).thenReturn(chain(OTHER_CHAIN_ID));
        when(snapshotService.build(CHAIN_ID)).thenThrow(new IllegalStateException("no snapshot"));
        when(snapshotService.build(OTHER_CHAIN_ID)).thenThrow(new IllegalStateException("no snapshot"));

        List<String> batch = List.of(CHAIN_ID, OTHER_CHAIN_ID);

        assertThatThrownBy(() -> chainRolesService.redeploy(batch))
                .isInstanceOf(DeploymentProcessingException.class)
                .hasMessageContaining(CHAIN_ID)
                .hasMessageContaining(OTHER_CHAIN_ID);

        verifyNoInteractions(deploymentService, chainService);
    }

    private Chain chain(String chainId) {
        Chain chain = new Chain();
        chain.setId(chainId);
        chain.setName(chainId);
        return chain;
    }

    private ChainElement httpTrigger(String elementId, String chainId) {
        ChainElement element = new ChainElement();
        element.setId(elementId);
        element.setChain(chain(chainId));
        return element;
    }

    private UpdateRolesRequest updateRequest(String elementId, String role) {
        UpdateRolesRequest request = new UpdateRolesRequest();
        request.setElementId(elementId);
        request.setRoles(Set.of(role));
        return request;
    }
}
