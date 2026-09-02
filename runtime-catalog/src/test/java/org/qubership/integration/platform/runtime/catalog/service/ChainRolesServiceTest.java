package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.AbacRoleChangeException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRedeployRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.DeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainRolesMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainRolesServiceTest {

    private static final String ELEMENT_ID = "element-a";
    private static final String CHAIN_ID = "chain-a";
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

        assertThatThrownBy(() -> chainRolesService.updateRoles(List.of(updateRequest("reader"))))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(ELEMENT_ID);

        verifyNoInteractions(snapshotService);
    }

    @Test
    @DisplayName("A role update switches an unrestricted endpoint to RBAC and marks the chain unsaved")
    void updateRolesAppliesRolesAndSwitchesAccessControlType() {
        ChainElement element = httpTrigger();
        element.getProperties().put(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_NONE);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);

        chainRolesService.updateRoles(List.of(updateRequest("reader")));

        verify(elementService).save(element);
        assertThat(element.getProperties())
                .containsEntry(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_RBAC)
                .containsEntry(ROLES, List.of("reader"));
        assertThat(element.getChain().isUnsavedChanges()).isTrue();
        verifyNoInteractions(snapshotService);
    }

    @Test
    @DisplayName("A role update on an ABAC endpoint is rejected")
    void updateRolesRejectsAbacElement() {
        ChainElement element = httpTrigger();
        element.getProperties().put(ACCESS_CONTROL_TYPE, ChainRolesService.ACCESS_CONTROL_TYPE_ABAC);
        when(elementService.findById(ELEMENT_ID)).thenReturn(element);

        assertThatThrownBy(() -> chainRolesService.updateRoles(List.of(updateRequest("reader"))))
                .isInstanceOf(AbacRoleChangeException.class);

        verifyNoInteractions(snapshotService);
    }

    @Test
    @DisplayName("Redeploy builds a snapshot only for the chains that carry unsaved changes")
    void redeploySkipsChainsWithoutUnsavedChanges() {
        Chain chain = chain();
        Snapshot snapshot = new Snapshot();
        DeploymentRequest deploymentRequest = new DeploymentRequest();
        List<Deployment> deploymentEntities = List.of(new Deployment());
        when(chainFinderService.findById(CHAIN_ID)).thenReturn(chain);
        when(snapshotService.build(CHAIN_ID)).thenReturn(snapshot);
        when(chainRolesMapper.prepareDeploymentRequest(snapshot)).thenReturn(deploymentRequest);
        when(deploymentMapper.asEntities(List.of(deploymentRequest))).thenReturn(deploymentEntities);

        chainRolesService.redeploy(List.of(
                redeployRequest(CHAIN_ID, true),
                redeployRequest("chain-b", false)));

        verify(deploymentService).createAll(deploymentEntities, CHAIN_ID, snapshot);
        verify(chainService).update(chain);
        assertThat(chain.isUnsavedChanges()).isFalse();
        assertThat(chain.getCurrentSnapshot()).isSameAs(snapshot);
    }

    private Chain chain() {
        Chain chain = new Chain();
        chain.setId(CHAIN_ID);
        chain.setName(CHAIN_ID);
        return chain;
    }

    private ChainElement httpTrigger() {
        ChainElement element = new ChainElement();
        element.setId(ELEMENT_ID);
        element.setChain(chain());
        return element;
    }

    private UpdateRolesRequest updateRequest(String role) {
        UpdateRolesRequest request = new UpdateRolesRequest();
        request.setElementId(ELEMENT_ID);
        request.setRoles(Set.of(role));
        request.setIsRedeploy(false);
        return request;
    }

    private ChainRedeployRequest redeployRequest(String chainId, boolean unsavedChanges) {
        return new ChainRedeployRequest(chainId, unsavedChanges);
    }
}
