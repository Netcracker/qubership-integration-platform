package org.qubership.integration.platform.runtime.catalog.service.helpers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

@ContextConfiguration(classes = ElementHelperService.class)
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class ElementHelperServiceTest {

    @MockitoBean
    ElementRepository elementRepository;

    @Autowired
    ElementHelperService elementHelperService;

    private static ChainElement element(String systemId, Chain chain) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CamelOptions.SYSTEM_ID, systemId);
        ChainElement element = new ChainElement();
        element.setProperties(properties);
        element.setChain(chain);
        return element;
    }

    private static Chain chain(String id) {
        Chain chain = new Chain();
        chain.setId(id);
        return chain;
    }

    @Test
    @DisplayName("Chains are grouped by the service they use, in a single repository call")
    void groupsChainsBySystemId() {
        Chain first = chain("chain-1");
        Chain second = chain("chain-2");
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of(
                        element("system-1", first),
                        element("system-1", second),
                        element("system-2", first)));

        Map<String, List<Chain>> result = elementHelperService.findChainsGroupedBySystemId();

        assertThat(result.keySet(), containsInAnyOrder("system-1", "system-2"));
        assertThat(result.get("system-1"), containsInAnyOrder(first, second));
        assertThat(result.get("system-2"), contains(first));
        verify(elementRepository, times(1)).findAll(ArgumentMatchers.<Specification<ChainElement>>any());
    }

    @Test
    @DisplayName("The same chain is reported once per service when several elements reference it")
    void deduplicatesChainsPerSystem() {
        Chain chain = chain("chain-1");
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of(element("system-1", chain), element("system-1", chain)));

        Map<String, List<Chain>> result = elementHelperService.findChainsGroupedBySystemId();

        assertThat(result.get("system-1"), contains(chain));
    }

    @Test
    @DisplayName("A service used by no chain is absent from the result")
    void returnsNoEntryForUnusedSystem() {
        when(elementRepository.findAll(ArgumentMatchers.<Specification<ChainElement>>any()))
                .thenReturn(List.of());

        assertThat(elementHelperService.findChainsGroupedBySystemId().entrySet(), empty());
    }
}
