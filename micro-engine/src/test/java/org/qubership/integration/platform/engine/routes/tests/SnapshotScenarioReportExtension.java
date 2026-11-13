package org.qubership.integration.platform.engine.routes.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

class SnapshotScenarioReportExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback,
        TestWatcher, AfterAllCallback {
    private static final ExtensionContext.Namespace TIMING_NAMESPACE =
            ExtensionContext.Namespace.create(SnapshotScenarioReportExtension.class);
    private static final String START_TIME_KEY = "startTimeNanos";
    private static final String ELEMENT_COLUMN = "Element";
    private static final String SCENARIO_COLUMN = "Scenario";
    private static final String DURATION_COLUMN = "Duration (s)";
    private final List<ScenarioResult> results = new ArrayList<>();
    private final Map<String, Long> durationsByTestId = new HashMap<>();

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        context.getStore(TIMING_NAMESPACE).put(START_TIME_KEY, System.nanoTime());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        ExtensionContext.Store timing = context.getStore(TIMING_NAMESPACE);
        Long startedAt = timing.remove(START_TIME_KEY, Long.class);
        if (startedAt != null) {
            durationsByTestId.put(context.getUniqueId(), System.nanoTime() - startedAt);
        }
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        recordResult(context, Outcome.PASSED, null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        recordResult(context, Outcome.FAILED, cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        recordResult(context, Outcome.ABORTED, null);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        recordResult(context, Outcome.SKIPPED, null);
    }

    @Override
    public void afterAll(ExtensionContext context) throws IOException {
        SnapshotShard shard = context.getRequiredTestClass().getAnnotation(SnapshotShard.class);
        String reportDirectory = System.getProperty("snapshot.native.reports.directory");
        if (shard != null && reportDirectory != null) {
            writeShardReport(Path.of(reportDirectory), shard.index(), shard.count()).ifPresent(System.out::println);
        } else {
            System.out.println(formatReport(results));
        }
    }

    void writeReport(Path reportFile) throws IOException {
        Files.createDirectories(reportFile.toAbsolutePath().getParent());
        ObjectMappers.getObjectMapper().writeValue(reportFile.toFile(), results);
    }

    Optional<String> writeShardReport(Path directory, int index, int count) throws IOException {
        Files.createDirectories(directory);
        Path temporary = directory.resolve("shard-" + index + ".tmp");
        writeReport(temporary);
        Files.move(temporary, directory.resolve("shard-" + index + ".json"), StandardCopyOption.ATOMIC_MOVE);
        for (int shard = 0; shard < count; shard++) {
            if (!Files.exists(directory.resolve("shard-" + shard + ".json"))) {
                return Optional.empty();
            }
        }
        try {
            Files.createFile(directory.resolve("report.done"));
        } catch (FileAlreadyExistsException exception) {
            return Optional.empty();
        }
        List<ScenarioResult> combined = new ArrayList<>();
        for (int shard = 0; shard < count; shard++) {
            combined.addAll(Arrays.asList(ObjectMappers.getObjectMapper().readValue(
                    directory.resolve("shard-" + shard + ".json").toFile(), ScenarioResult[].class)));
        }
        combined.sort(Comparator.comparing(ScenarioResult::element).thenComparing(ScenarioResult::scenario));
        ObjectMappers.getObjectMapper().writeValue(directory.resolve("summary.json").toFile(), combined);
        return Optional.of(formatReport(combined));
    }

    private void recordResult(ExtensionContext context, Outcome outcome, Throwable cause) {
        String[] names = context.getDisplayName().split("/", 2);
        Long durationNanos = durationsByTestId.remove(context.getUniqueId());
        String duration = durationNanos == null ? "-" : String.format(Locale.ROOT, "%.3f", durationNanos / 1_000_000_000.0);
        SnapshotFailureContext failure = outcome == Outcome.FAILED
                ? context.getTestInstance().filter(SnapshotRouteExecutionTest.class::isInstance)
                        .map(SnapshotRouteExecutionTest.class::cast)
                        .map(SnapshotRouteExecutionTest::getFailureContext).orElse(null)
                : null;
        results.add(new ScenarioResult(names[0], names.length > 1 ? names[1] : "", outcome, duration,
                failure == null ? null : failure.invocation(), failure == null ? null : failure.repetition(),
                failure == null ? null : failure.stage(), failure == null ? null : failure.expectedBehavior(),
                cause == null ? null : formatFailure(cause)));
    }

    private static String formatFailure(Throwable failure) {
        StringBuilder message = new StringBuilder(failure.toString());
        appendFailureDetails(message, failure);
        return message.toString();
    }

    private static void appendFailureDetails(StringBuilder message, Throwable failure) {
        for (Throwable suppressed : failure.getSuppressed()) {
            message.append("\nSuppressed: ").append(suppressed);
            appendFailureDetails(message, suppressed);
        }
        if (failure.getCause() != null) {
            message.append("\nCaused by: ").append(failure.getCause());
            appendFailureDetails(message, failure.getCause());
        }
    }

    private static String formatReport(List<ScenarioResult> results) {
        if (results.isEmpty()) {
            return "\nSnapshot scenario results: no scenario results were recorded."
                    + " Check the Surefire report for setup or discovery errors.";
        }
        int elementWidth = results.stream()
                .mapToInt(result -> result.element().length())
                .max()
                .orElse(ELEMENT_COLUMN.length());
        elementWidth = Math.max(elementWidth, ELEMENT_COLUMN.length());
        int scenarioWidth = results.stream()
                .mapToInt(result -> result.scenario().length())
                .max()
                .orElse(SCENARIO_COLUMN.length());
        scenarioWidth = Math.max(scenarioWidth, SCENARIO_COLUMN.length());
        int durationWidth = Math.max(DURATION_COLUMN.length(), results.stream()
                .mapToInt(result -> result.duration().length())
                .max()
                .orElse(0));
        String border = "+" + "-".repeat(elementWidth + 2)
                + "+" + "-".repeat(scenarioWidth + 2) + "+---------+"
                + "-".repeat(durationWidth + 2) + "+\n";
        String rowFormat = "| %-" + elementWidth + "s | %-" + scenarioWidth + "s | %-7s | %"
                + durationWidth + "s |%n";
        StringBuilder report = new StringBuilder("\nSnapshot scenario results\n");
        report.append(border);
        report.append(rowFormat.formatted(ELEMENT_COLUMN, SCENARIO_COLUMN, "Result", DURATION_COLUMN));
        report.append(border);
        for (ScenarioResult result : results) {
            report.append(rowFormat.formatted(result.element(), result.scenario(), result.outcome(), result.duration()));
        }
        report.append(border);
        report.append("Scenarios: ").append(results.size());
        for (Outcome outcome : Outcome.values()) {
            long count = results.stream().filter(result -> result.outcome() == outcome).count();
            report.append(" | ").append(outcome).append(": ").append(count);
        }
        List<SnapshotFailureTable.Row> failures = results.stream()
                .filter(result -> result.outcome() == Outcome.FAILED)
                .map(result -> new SnapshotFailureTable.Row(result.element(), failureTestName(result),
                        result.expectedBehavior() == null ? "Not specified." : result.expectedBehavior(),
                        (result.stage() == null ? "" : "Stage: " + result.stage() + "\n")
                                + result.error()))
                .toList();
        if (!failures.isEmpty()) {
            report.append("\n\n").append(SnapshotFailureTable.format(failures));
        }
        return report.toString();
    }

    private static String failureTestName(ScenarioResult result) {
        String test = result.scenario();
        if (result.invocation() != null && !result.invocation().equals(result.scenario())) {
            test += " / " + result.invocation();
        }
        if (result.repetition() != null && result.repetition() > 1) {
            test += " (repeat " + result.repetition() + ")";
        }
        return test;
    }

    private enum Outcome {
        PASSED,
        FAILED,
        ABORTED,
        SKIPPED
    }

    private record ScenarioResult(
            String element,
            String scenario,
            Outcome outcome,
            String duration,
            String invocation,
            Integer repetition,
            String stage,
            String expectedBehavior,
            String error
    ) {
    }
}
