package org.qubership.integration.platform.engine.state;

import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.model.engine.EngineState;

import static org.qubership.integration.platform.engine.consul.ConsulSessionService.CREATE_SESSION_EVENT;

@Slf4j
@ApplicationScoped
public class NewConsulSessionMessageConsumer {
    @Inject
    CamelContext camelContext;

    @Inject
    EngineStateBuilder engineStateBuilder;

    @Inject
    EngineStateReporter engineStateReporter;

    @ConsumeEvent(CREATE_SESSION_EVENT)
    public void onCreateSessionEvent(String sessionId) {
        log.debug("Updating engine state on new consul session created");
        updateEngineState(camelContext);
    }

    private void updateEngineState(CamelContext context) {
        EngineState engineState = engineStateBuilder.build(context);
        engineStateReporter.addStateToQueue(engineState);
    }
}
