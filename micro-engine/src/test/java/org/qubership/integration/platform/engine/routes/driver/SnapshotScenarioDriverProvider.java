package org.qubership.integration.platform.engine.routes.driver;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.List;
import java.util.Map;

public interface SnapshotScenarioDriverProvider {
    String getId();

    /** Defines which invocations share a configured driver. */
    default Map<String, Object> configurationParameters(SnapshotScenarioDriverDefinition definition) {
        return definition.getParameters();
    }

    SnapshotScenarioDriver create(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioDriverDefinition definition,
            List<SnapshotScenarioInvocation> invocations
    );
}
