package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression: the {@code chainId} field used to be populated with the chain's display name, so a client
 * looking the failing chain up by {@code details.chainId} would 404. It must carry the chain id.
 */
class SnapshotCreationExceptionTest {

    @Test
    void chainIdIsTheChainIdNotTheChainName() {
        Chain chain = Chain.builder().id("chain-uuid-1").name("Human Readable Chain Name").build();
        ChainElement element = ChainElement.builder().id("element-1").name("Element 1").chain(chain).build();

        SnapshotCreationException exception = new SnapshotCreationException("boom", element);

        assertEquals("chain-uuid-1", exception.getChainId());
    }
}
