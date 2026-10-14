package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;
import org.qubership.integration.platform.runtime.catalog.service.filter.SystemFilterSpecificationBuilder;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemServiceChainUsageTest {

    @Mock
    SystemRepository systemRepository;

    @Mock
    ActionsLogService actionsLogger;

    @Mock
    IntegrationSystemLabelsRepository systemLabelsRepository;

    @Mock
    SystemModelService systemModelService;

    @Mock
    SystemFilterSpecificationBuilder systemFilterSpecificationBuilder;

    @Mock
    ElementHelperService elementHelperService;

    @InjectMocks
    SystemService systemService;

    private static IntegrationSystem system(String id) {
        IntegrationSystem system = new IntegrationSystem();
        system.setId(id);
        return system;
    }

    private static Chain chain(String id) {
        Chain chain = new Chain();
        chain.setId(id);
        return chain;
    }

    @Test
    @DisplayName("Chain usage is resolved for the whole list in a single lookup")
    void attachesChainUsageToEachSystem() {
        Chain chain = chain("chain-1");
        List<IntegrationSystem> systems = List.of(system("system-1"), system("system-2"));
        when(elementHelperService.findChainsGroupedBySystemId())
                .thenReturn(Map.of("system-1", List.of(chain)));

        List<IntegrationSystem> result = systemService.enrichWithChainUsage(systems);

        assertThat(result, sameInstance(systems));
        assertThat(systems.get(0).getChains(), contains(chain));
        verify(elementHelperService, times(1)).findChainsGroupedBySystemId();
    }

    @Test
    @DisplayName("A service no chain uses gets an empty list, not null")
    void attachesEmptyListToUnusedSystem() {
        List<IntegrationSystem> systems = List.of(system("system-1"));
        when(elementHelperService.findChainsGroupedBySystemId()).thenReturn(Map.of());

        systemService.enrichWithChainUsage(systems);

        assertThat(systems.get(0).getChains(), notNullValue());
        assertThat(systems.get(0).getChains(), empty());
    }

    @Test
    @DisplayName("An empty service list still costs a single lookup and no failure")
    void handlesEmptySystemList() {
        when(elementHelperService.findChainsGroupedBySystemId()).thenReturn(Map.of());

        assertThat(systemService.enrichWithChainUsage(List.of()), empty());
    }
}
