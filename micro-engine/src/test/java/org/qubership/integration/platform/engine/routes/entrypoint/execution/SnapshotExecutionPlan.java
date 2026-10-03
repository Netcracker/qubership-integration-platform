package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class SnapshotExecutionPlan {
    private final List<SnapshotExecutionTarget> targets;

    public SnapshotExecutionPlan(List<SnapshotExecutionTarget> targets) {
        this.targets = List.copyOf(requireNonNull(targets, "targets"));
    }

    public List<SnapshotExecutionTarget> getTargets() {
        return targets;
    }
}
