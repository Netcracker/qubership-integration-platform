package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainRuntimePropertiesServiceTest {
    @Mock
    ConsulService consulService;
    @Mock
    ActionsLogService actionsLogService;
    @Mock
    ChainRepository chainRepository;
    @Mock
    ChainFinderService chainFinderService;
    @InjectMocks
    ChainRuntimePropertiesService chainRuntimePropertiesService;

    @Test
    void savesPropertiesOfExistingChain() {
        DeploymentRuntimeProperties properties = DeploymentRuntimeProperties.getDefaultValues();
        when(chainFinderService.findById("chain-1")).thenReturn(Chain.builder().id("chain-1").build());

        chainRuntimePropertiesService.saveRuntimeProperties("chain-1", properties);

        verify(consulService).updateChainRuntimeConfig("chain-1", properties);
    }

    @Test
    void rejectsPropertiesOfMissingChain() {
        when(chainFinderService.findById("missing")).thenThrow(new EntityNotFoundException("Can't find chain with id: missing"));

        assertThrows(EntityNotFoundException.class, () -> chainRuntimePropertiesService.saveRuntimeProperties(
                "missing", DeploymentRuntimeProperties.getDefaultValues()));

        verifyNoInteractions(consulService);
    }
}
