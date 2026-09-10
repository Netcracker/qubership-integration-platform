package org.qubership.integration.platform.engine.routes.tests;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionPlan;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;

import java.util.List;

final class SnapshotScenarioSelector {
    static final String TARGET_PROPERTY = "snapshot.target";
    static final String SCENARIO_PROPERTY = "snapshot.scenario";

    private SnapshotScenarioSelector() {
    }

    static List<Selection> select(SnapshotExecutionPlan plan, String targetId, String scenarioId) {
        String targetFilter = nonBlank(targetId);
        String scenarioFilter = nonBlank(scenarioId);
        List<Selection> selections = plan.getTargets().stream()
                .filter(target -> targetFilter == null || targetFilter.equals(target.getId()))
                .flatMap(target -> target.getScenarios().stream()
                        .filter(scenario -> scenarioFilter == null || scenarioFilter.equals(scenario.getId()))
                        .map(scenario -> new Selection(target, scenario)))
                .toList();
        if (selections.isEmpty()) {
            throw new IllegalArgumentException(
                    "No snapshot scenarios match " + TARGET_PROPERTY + "='" + filterDescription(targetFilter)
                            + "' and " + SCENARIO_PROPERTY + "='" + filterDescription(scenarioFilter)
                            + "'. Use exact IDs from the test specification."
            );
        }
        return selections;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String filterDescription(String value) {
        return value == null ? "<all>" : value;
    }

    record Selection(SnapshotExecutionTarget target, SnapshotExecutionScenario scenario) {
    }
}
