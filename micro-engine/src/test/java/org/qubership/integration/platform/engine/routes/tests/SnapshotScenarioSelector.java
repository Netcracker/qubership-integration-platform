package org.qubership.integration.platform.engine.routes.tests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionPlan;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

final class SnapshotScenarioSelector {
    static final String TARGET_PROPERTY = "snapshot.target";
    static final String SCENARIO_PROPERTY = "snapshot.scenario";
    static final String SCOPE_PROPERTY = "snapshot.scope";
    static final String ORDER_PROPERTY = "snapshot.scenario.order";
    static final String REPEAT_PROPERTY = "snapshot.scenario.repeat";
    static final String SHARD_COUNT_PROPERTY = "snapshot.shard.count";
    static final String SHARD_INDEX_PROPERTY = "snapshot.shard.index";
    static final String STRATEGY_PROPERTY = "snapshot.shard.strategy";
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotScenarioSelector.class);

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
        List<Selection> assigned = assign(selections, shardCount, shardIndex).stream()
                .sorted(Comparator.comparing((Selection selection) -> !selection.scenario().isReuseBroker())
                        .thenComparing(selection -> selection.scenario().isReuseBroker() ? selection.target().getId() : ""))
                .toList();
        String order = System.getProperty(ORDER_PROPERTY, "normal");
        if (!"normal".equals(order) && !"reverse".equals(order)) {
            throw new IllegalArgumentException(ORDER_PROPERTY + " must be normal or reverse.");
        }
        if ("reverse".equals(order)) {
            assigned = assigned.reversed();
        }
        int repeat = shardProperty(REPEAT_PROPERTY, System.getProperty(REPEAT_PROPERTY), 1);
        if (repeat <= 0) {
            throw new IllegalArgumentException(REPEAT_PROPERTY + " must be greater than zero.");
        }
        return assigned.stream().flatMap(selection -> IntStream.range(0, repeat).mapToObj(index -> selection)).toList();
    }

    private static List<Selection> assign(List<Selection> selections, int shardCount, int shardIndex) {
        String strategy = System.getProperty(STRATEGY_PROPERTY, "duration");
        if ("round-robin".equals(strategy)) {
            return IntStream.range(0, selections.size())
                    .filter(index -> index % shardCount == shardIndex).mapToObj(selections::get).toList();
        }
        if (!"duration".equals(strategy)) {
            throw new IllegalArgumentException(STRATEGY_PROPERTY + " must be duration or round-robin.");
        }
        Durations durations = readDurations();
        double[] estimates = selections.stream().mapToDouble(durations::estimate).toArray();
        List<Group> groups = compatibleGroups(selections, estimates, shardCount);
        List<Bucket> shards = buckets(shardCount);
        for (Group group : groups) {
            Bucket shard = shards.get(leastLoaded(shards, false));
            shard.indices.addAll(group.indices());
            shard.seconds += group.seconds();
            if (group.reusable()) {
                shard.compatibleGroups++;
            }
        }
        Bucket shard = shards.get(shardIndex);
        LOG.info("Snapshot shard {}/{}: {} scenarios, {} compatible groups, {} predicted seconds.",
                shardIndex, shardCount, shard.indices.size(), shard.compatibleGroups, Math.round(shard.seconds * 1000) / 1000.0);
        Set<Integer> indices = Set.copyOf(shard.indices);
        return IntStream.range(0, selections.size()).filter(indices::contains).mapToObj(selections::get).toList();
    }

    private static List<Group> compatibleGroups(List<Selection> selections, double[] estimates, int shardCount) {
        List<Group> groups = new ArrayList<>();
        Map<String, List<Integer>> reusable = new LinkedHashMap<>();
        for (int index = 0; index < selections.size(); index++) {
            Selection selection = selections.get(index);
            if (selection.scenario().isReuseBroker()) {
                reusable.computeIfAbsent(selection.target().getId(), ignored -> new ArrayList<>()).add(index);
            } else {
                groups.add(new Group(List.of(index), estimates[index], identity(selection), false));
            }
        }
        for (List<Integer> members : reusable.values()) {
            // Pair reusable scenarios so balancing retains a warm broker lease within each group.
            List<Bucket> cohorts = buckets(Math.min(shardCount, Math.max(1, members.size() / 2)));
            List<Integer> sorted = members.stream().sorted(Comparator.<Integer>comparingDouble(index -> estimates[index])
                    .reversed().thenComparing(index -> identity(selections.get(index)))).toList();
            for (int index : sorted) {
                boolean fillPairs = cohorts.stream().anyMatch(cohort -> cohort.indices.size() < 2);
                Bucket cohort = cohorts.get(leastLoaded(cohorts, fillPairs));
                cohort.indices.add(index);
                cohort.seconds += estimates[index];
            }
            for (Bucket cohort : cohorts) {
                String first = cohort.indices.stream().map(index -> identity(selections.get(index))).min(String::compareTo).orElseThrow();
                groups.add(new Group(List.copyOf(cohort.indices), cohort.seconds, first, true));
            }
        }
        return groups.stream().sorted(Comparator.comparingDouble(Group::seconds).reversed().thenComparing(Group::first)).toList();
    }

    private static List<Bucket> buckets(int count) {
        return IntStream.range(0, count).mapToObj(index -> new Bucket()).toList();
    }

    private static int leastLoaded(List<Bucket> buckets, boolean fillPairs) {
        return IntStream.range(0, buckets.size())
                .filter(index -> !fillPairs || buckets.get(index).indices.size() < 2)
                .boxed().min(Comparator.<Integer>comparingDouble(index -> buckets.get(index).seconds)
                        .thenComparingInt(index -> buckets.get(index).indices.size()).thenComparingInt(Integer::intValue))
                .orElseThrow();
    }

    private static String identity(Selection selection) {
        return selection.target().getId() + '/' + selection.scenario().getId();
    }

    private static Durations readDurations() {
        try (InputStream input = SnapshotScenarioSelector.class.getResourceAsStream("/snapshot-scenario-durations.json")) {
            Map<String, Map<String, Double>> measured = new ObjectMapper().readValue(input, new TypeReference<>() { });
            Map<String, Double> medians = new LinkedHashMap<>();
            measured.forEach((target, values) -> medians.put(target, median(new ArrayList<>(values.values()))));
            double fallback = median(measured.values().stream().flatMap(values -> values.values().stream()).toList());
            return new Durations(measured, medians, fallback);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read snapshot scenario durations.", exception);
        }
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2 : sorted.get(middle);
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

    private record Durations(Map<String, Map<String, Double>> measured, Map<String, Double> medians, double fallback) {
        double estimate(Selection selection) {
            String target = selection.target().getId();
            return measured.getOrDefault(target, Map.of()).getOrDefault(
                    selection.scenario().getId(), medians.getOrDefault(target, fallback));
        }
    }

    private record Group(List<Integer> indices, double seconds, String first, boolean reusable) {
    }

    private static final class Bucket {
        private final List<Integer> indices = new ArrayList<>();
        private double seconds;
        private int compatibleGroups;
    }
}
