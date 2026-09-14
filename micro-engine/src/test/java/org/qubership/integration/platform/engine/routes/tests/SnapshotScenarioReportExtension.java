package org.qubership.integration.platform.engine.routes.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class SnapshotScenarioReportExtension implements TestWatcher, AfterAllCallback {
    private static final String ELEMENT_COLUMN = "Element";
    private static final String SCENARIO_COLUMN = "Scenario";
    private final List<ScenarioResult> results = new ArrayList<>();

    @Override
    public void testSuccessful(ExtensionContext context) {
        recordResult(context, Outcome.PASSED);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        recordResult(context, Outcome.FAILED);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        recordResult(context, Outcome.ABORTED);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        recordResult(context, Outcome.SKIPPED);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        System.out.println(formatReport());
    }

    private void recordResult(ExtensionContext context, Outcome outcome) {
        String[] names = context.getDisplayName().split("/", 2);
        results.add(new ScenarioResult(names[0], names.length > 1 ? names[1] : "", outcome));
    }

    private String formatReport() {
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
        String border = "+" + "-".repeat(elementWidth + 2)
                + "+" + "-".repeat(scenarioWidth + 2) + "+---------+\n";
        String rowFormat = "| %-" + elementWidth + "s | %-" + scenarioWidth + "s | %-7s |%n";
        StringBuilder report = new StringBuilder("\nSnapshot scenario results\n");
        report.append(border);
        report.append(rowFormat.formatted(ELEMENT_COLUMN, SCENARIO_COLUMN, "Result"));
        report.append(border);
        for (ScenarioResult result : results) {
            report.append(rowFormat.formatted(result.element(), result.scenario(), result.outcome()));
        }
        report.append(border);
        report.append("Scenarios: ").append(results.size());
        for (Outcome outcome : Outcome.values()) {
            long count = results.stream().filter(result -> result.outcome() == outcome).count();
            report.append(" | ").append(outcome).append(": ").append(count);
        }
        return report.toString();
    }

    private enum Outcome {
        PASSED,
        FAILED,
        ABORTED,
        SKIPPED
    }

    private record ScenarioResult(String element, String scenario, Outcome outcome) {
    }
}
