package org.qubership.integration.platform.engine.routes.fixture;

import java.util.List;

public interface SnapshotFixtureProvider {
    String getId();

    default boolean requiresNodeId() {
        return true;
    }

    default boolean supportsExpectedLogs() {
        return false;
    }

    default boolean supportsExpectedExchanges() {
        return false;
    }

    default boolean supportsTransitionToState() {
        return false;
    }

    default boolean supportsExpectedState() {
        return false;
    }

    SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings);
}
