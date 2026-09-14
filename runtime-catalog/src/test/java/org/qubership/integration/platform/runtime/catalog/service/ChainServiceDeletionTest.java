package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainServiceDeletionTest {
    @Mock
    ChainRepository chainRepository;
    @Mock
    ChainFinderService chainFinderService;
    @Mock
    DeploymentService deploymentService;
    @Mock
    ActionsLogService actionsLogService;
    @Mock
    ChainRuntimePropertiesService chainRuntimePropertiesService;
    @InjectMocks
    ChainService chainService;

    @Test
    void deletingChainDeletesItsCustomRuntimeProperties() {
        Chain chain = Chain.builder().id("chain-1").name("chain").build();
        when(chainFinderService.tryFindById("chain-1")).thenReturn(Optional.of(chain));

        chainService.deleteByIdIfExists("chain-1");

        verify(chainRuntimePropertiesService).deleteCustomRuntimePropertiesAfterCommit(List.of("chain-1"));
    }
}
