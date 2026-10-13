package org.qubership.integration.platform.engine.routes.tests;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionPlan;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioScope;

import java.util.List;
import java.util.stream.IntStream;

final class SnapshotScenarioSelector {
    static final String TARGET_PROPERTY = "snapshot.target";
    static final String SCENARIO_PROPERTY = "snapshot.scenario";
    static final String SCOPE_PROPERTY = "snapshot.scope";
    static final String SHARD_COUNT_PROPERTY = "snapshot.shard.count";
    static final String SHARD_INDEX_PROPERTY = "snapshot.shard.index";

    private SnapshotScenarioSelector() {
    }

    static List<Selection> select(SnapshotExecutionPlan plan, String targetId, String scenarioId) {
        return select(plan, targetId, scenarioId, null);
    }

    private static List<Selection> select(
            SnapshotExecutionPlan plan, String targetId, String scenarioId, SnapshotScenarioScope scope
    ) {
        String targetFilter = nonBlank(targetId);
        String scenarioFilter = nonBlank(scenarioId);
        List<Selection> selections = plan.getTargets().stream()
                .filter(target -> targetFilter == null || targetFilter.equals(target.getId()))
                .flatMap(target -> target.getScenarios().stream()
                        .filter(scenario -> scenarioFilter == null || scenarioFilter.equals(scenario.getId()))
                        .filter(scenario -> scope == null || scenario.getScope() == scope)
                        .map(scenario -> new Selection(target, scenario)))
                .toList();
        if (selections.isEmpty()) {
            throw new IllegalArgumentException(
                    "No snapshot scenarios match " + TARGET_PROPERTY + "='" + filterDescription(targetFilter)
                            + "' and " + SCENARIO_PROPERTY + "='" + filterDescription(scenarioFilter)
                            + "' and " + SCOPE_PROPERTY + "='" + (scope == null ? "<all>" : scope.getValue())
                            + "'. Use exact IDs and scope values from the test specification."
            );
        }
        return selections;
    }

    static List<Selection> select(
            SnapshotExecutionPlan plan,
            String targetId,
            String scenarioId,
            String shardCountProperty,
            String shardIndexProperty
    ) {
        return select(plan, targetId, scenarioId, null, shardCountProperty, shardIndexProperty);
    }

    static List<Selection> select(
            SnapshotExecutionPlan plan,
            String targetId,
            String scenarioId,
            String scopeProperty,
            String shardCountProperty,
            String shardIndexProperty
    ) {
        SnapshotScenarioScope scope = scopeProperty(scopeProperty);
        int shardCount = shardProperty(SHARD_COUNT_PROPERTY, shardCountProperty, 1);
        int shardIndex = shardProperty(SHARD_INDEX_PROPERTY, shardIndexProperty, 0);
        if (shardCount <= 0) {
            throw new IllegalArgumentException(SHARD_COUNT_PROPERTY + " must be greater than zero.");
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    SHARD_INDEX_PROPERTY + " must be between 0 and " + (shardCount - 1) + "."
            );
        }
        List<Selection> selections = select(plan, targetId, scenarioId, scope);
        return IntStream.range(0, selections.size())
                .filter(index -> index % shardCount == shardIndex)
                .mapToObj(selections::get)
                .toList();
    }

    private static SnapshotScenarioScope scopeProperty(String value) {
        if (nonBlank(value) == null) {
            return null;
        }
        try {
            return SnapshotScenarioScope.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(SCOPE_PROPERTY + " must be Long or Short: '" + value + "'.", exception);
        }
    }

    private static int shardProperty(String name, String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer: '" + value + "'.", exception);
        }
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
