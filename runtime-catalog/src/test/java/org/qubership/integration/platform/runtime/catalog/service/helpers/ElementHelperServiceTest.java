package org.qubership.integration.platform.runtime.catalog.service.helpers;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the batch chain lookup behind the operation listing: one query for the whole id set, grouped in
 * memory with the same per-operation semantics {@code findBySystemAndOperationId} has.
 */
class ElementHelperServiceTest {

    private final ElementRepository elementRepository = mock(ElementRepository.class);
    private final ElementHelperService service = new ElementHelperService(elementRepository);

    @Test
    void groupsChainsByOperationIdWithOneQuery() {
        Chain first = Chain.builder().id("chain-1").build();
        Chain second = Chain.builder().id("chain-2").build();
        stubElements(
                element("op-1", first),
                element("op-1", first),
                element("op-1", second),
                element("op-2", second));

        Map<String, List<Chain>> result = service.findChainsGroupedByOperationId(Set.of("op-1", "op-2", "op-3"));

        assertEquals(List.of("chain-1", "chain-2"), chainIds(result.get("op-1")));
        assertEquals(List.of("chain-2"), chainIds(result.get("op-2")));
        assertFalse(result.containsKey("op-3"), "an operation with no usage must not appear in the map");
        verify(elementRepository).findAll(any(Specification.class));
    }

    @Test
    void skipsElementsWithoutOperationIdOrChain() {
        Chain chain = Chain.builder().id("chain-1").build();
        stubElements(element(null, chain), element("op-1", null), element("op-1", chain));

        Map<String, List<Chain>> result = service.findChainsGroupedByOperationId(Set.of("op-1"));

        assertEquals(List.of("chain-1"), chainIds(result.get("op-1")));
    }

    @Test
    void emptyIdSetIssuesNoQuery() {
        assertTrue(service.findChainsGroupedByOperationId(Set.of()).isEmpty());

        verify(elementRepository, never()).findAll(any(Specification.class));
    }

    @SuppressWarnings("unchecked")
    private void stubElements(ChainElement... elements) {
        when(elementRepository.findAll(any(Specification.class))).thenReturn(List.of(elements));
    }

    private static ChainElement element(String operationId, Chain chain) {
        ChainElement element = ChainElement.builder().chain(chain).build();
        if (operationId != null) {
            element.getProperties().put(CamelOptions.OPERATION_ID, operationId);
        }
        return element;
    }

    private static List<String> chainIds(List<Chain> chains) {
        return chains.stream().map(Chain::getId).sorted().toList();
    }
}
