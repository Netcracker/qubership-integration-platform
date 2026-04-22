package org.qubership.integration.platform.engine.camel.listeners;

import org.apache.camel.spi.CamelEvent;

public interface EventProcessingAction<T extends CamelEvent> {
    void process(T event) throws Exception;
}
