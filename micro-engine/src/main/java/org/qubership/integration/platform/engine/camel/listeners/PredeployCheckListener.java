package org.qubership.integration.platform.engine.camel.listeners;

import jakarta.enterprise.inject.spi.CDI;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.k.Runtime;
import org.apache.camel.k.listener.AbstractPhaseListener;
import org.qubership.integration.platform.engine.camel.predeploy.PredeployCheckService;

// Runs after the routes are loaded and before any of them starts. A failed check stops the engine
// from starting, and Kubernetes restarts the pod, which runs the check again.
@Slf4j
public class PredeployCheckListener extends AbstractPhaseListener {
    public PredeployCheckListener() {
        super(Runtime.Phase.Starting);
    }

    @Override
    protected void accept(Runtime runtime) {
        try {
            CDI.current().select(PredeployCheckService.class).get().checkRoutes(runtime.getCamelContext());
        } catch (Exception exception) {
            log.error("Predeploy check failed. The engine will not start.", exception);
            throw new RuntimeException(exception);
        }
    }
}
