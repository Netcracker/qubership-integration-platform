package org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpSenderDesignProcessorTest {

    private final HttpSenderDesignProcessor processor = new HttpSenderDesignProcessor();

    // The design generator used to answer 500 with ClassCastException on this string.
    @Test
    void readsIsExternalCallStoredAsString() {
        ChainElement sender = ChainElement.builder().id("s").type("http-sender")
                .properties(Map.of("uri", "http://internal:8080/a", "isExternalCall", "false"))
                .build();

        assertEquals("Internal service: http://internal:8080", processor.getExternalParticipantName(sender));
    }
}
