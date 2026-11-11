package org.qubership.integration.platform.engine.camel.listeners;

import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.k.Runtime;
import org.apache.camel.k.listener.AbstractPhaseListener;
import org.qubership.integration.platform.engine.scheduler.TasksScheduler;

// Reads the chain runtime properties before any route starts, so the first calls are logged with them.
// The scheduled task skips runs until the application has started, and its next run repeats a failed read.
public class ChainRuntimePropertiesListener extends AbstractPhaseListener {
    public ChainRuntimePropertiesListener() {
        super(Runtime.Phase.Starting);
    }

    @Override
    protected void accept(Runtime runtime) {
        CDI.current().select(TasksScheduler.class).get().checkRuntimeDeploymentProperties();
    }
}
