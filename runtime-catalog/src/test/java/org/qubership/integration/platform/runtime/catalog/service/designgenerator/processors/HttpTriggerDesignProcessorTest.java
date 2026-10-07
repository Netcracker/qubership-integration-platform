package org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class HttpTriggerDesignProcessorTest {

    private final HttpTriggerDesignProcessor processor = new HttpTriggerDesignProcessor(mock(SystemRepository.class));

    // The design generator used to answer 500 with ClassCastException on these strings.
    @Test
    void readsRouteFlagsStoredAsStrings() {
        ChainElement trigger = ChainElement.builder().id("t").type("http-trigger")
                .properties(Map.of("externalRoute", "false", "privateRoute", "false"))
                .build();

        assertEquals("Unknown internal service", processor.getExternalParticipantName(trigger));
    }
}
