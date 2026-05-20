package org.qubership.integration.platform.engine.camel.listeners;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.event.AbstractContextEvent;
import org.apache.camel.impl.event.CamelContextStartedEvent;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.SimpleEventNotifierSupport;
import org.qubership.integration.platform.engine.model.engine.EngineState;
import org.qubership.integration.platform.engine.state.EngineStateBuilder;
import org.qubership.integration.platform.engine.state.EngineStateReporter;

@Slf4j
@ApplicationScoped
@Unremovable
public class EngineStateUpdatingListener extends SimpleEventNotifierSupport {
    @Inject
    EngineStateBuilder engineStateBuilder;

    @Inject
    EngineStateReporter engineStateReporter;

    @Override
    public void notify(CamelEvent event) throws Exception {
        switch (event) {
            case CamelContextStartedEvent ev -> updateEngineStateOnContextStarted(ev);
            default -> {
                // do nothing
            }
        }
    }

    private void updateEngineStateOnContextStarted(AbstractContextEvent event) {
        log.debug("Updating engine state on context started");
        CamelContext camelContext = event.getContext();
        EngineState engineState = engineStateBuilder.build(camelContext);
        engineStateReporter.addStateToQueue(engineState);
    }
}
