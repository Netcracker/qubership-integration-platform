package org.qubership.integration.platform.engine.routes.tests;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFailureExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class SnapshotExpectedBehavior {
    private SnapshotExpectedBehavior() {
    }

    static String describe(SnapshotExecutionScenario scenario, SnapshotScenarioInvocation invocation) {
        if (invocation != null && invocation.getExpectedBehavior() != null) {
            return invocation.getExpectedBehavior();
        }
        if (scenario.getExpectedBehavior() != null) {
            return scenario.getExpectedBehavior();
        }
        if (invocation == null) {
            if (scenario.getExpectedStartupFailure() != null) {
                return "Startup failure: " + describeFailure(scenario.getExpectedStartupFailure());
            }
            if (scenario.getInvocations().size() != 1) {
                return "Complete all " + scenario.getInvocations().size()
                        + " invocations and satisfy their declared result and fixture expectations.";
            }
            invocation = scenario.getInvocations().getFirst();
            if (invocation.getExpectedBehavior() != null) {
                return invocation.getExpectedBehavior();
            }
        }
        List<String> expectations = new ArrayList<>();
        expectations.add(invocation.getExpectedFailure() == null ? "No execution exception."
                : "Execution failure: " + describeFailure(invocation.getExpectedFailure()));
        if (invocation.hasExpectedBody()) {
            expectations.add("Body: " + value(invocation.getExpectedBody()));
        }
        addMap(expectations, "Headers", invocation.getExpectedHeaders());
        addAbsent(expectations, "Absent headers", invocation.getExpectedAbsentHeaders());
        addMap(expectations, "Exchange headers", invocation.getExpectedExchangeHeaders());
        addAbsent(expectations, "Absent exchange headers", invocation.getExpectedAbsentExchangeHeaders());
        addMap(expectations, "Properties", invocation.getExpectedProperties());
        for (SnapshotFixtureInteraction interaction : invocation.getInteractions()) {
            addFixture(expectations, interaction);
        }
        return String.join("\n", expectations);
    }

    private static String describeFailure(SnapshotFailureExpectation failure) {
        if (!failure.getAlternatives().isEmpty()) {
            return failure.getAlternatives().stream().map(SnapshotExpectedBehavior::describeFailure)
                    .collect(Collectors.joining(" OR ", "(", ")"));
        }
        String description = failure.getType();
        if (!failure.isMessageIgnored()) {
            description += " with message " + value(failure.getMessage());
        }
        return failure.getCause() == null ? description + " without a cause"
                : description + "; caused by " + describeFailure(failure.getCause());
    }

    private static void addFixture(List<String> expectations, SnapshotFixtureInteraction interaction) {
        String prefix = "Fixture '" + interaction.getFixtureId() + "': ";
        SnapshotFixtureRequestExpectation request = interaction.getExpectedRequest();
        if (request != null) {
            expectations.add(prefix + "request count = " + request.getCount());
            addValue(expectations, prefix + "request method", request.getMethod());
            addValue(expectations, prefix + "request path", request.getPath());
            addValue(expectations, prefix + "request query", request.getQuery());
            addValue(expectations, prefix + "destination", request.getDestination());
            addValue(expectations, prefix + "request key", request.getKey());
            if (request.hasBody()) {
                expectations.add(prefix + "request body: " + value(request.getBody()));
            }
            addMap(expectations, prefix + "request headers", request.getHeaders());
            addMap(expectations, prefix + "request properties", request.getProperties());
        }
        if (interaction.hasExpectedLogs()) {
            expectations.add(prefix + interaction.getExpectedLogs().size() + " log expectation(s)");
        }
        if (interaction.hasExpectedExchanges()) {
            expectations.add(prefix + interaction.getExpectedExchanges().size() + " exchange expectation(s)");
        }
        addValue(expectations, prefix + "state", interaction.getExpectedState());
    }

    private static void addMap(List<String> expectations, String label, Map<String, Object> values) {
        if (!values.isEmpty()) {
            expectations.add(label + ": " + value(values));
        }
    }

    private static void addAbsent(List<String> expectations, String label, List<String> values) {
        if (!values.isEmpty()) {
            expectations.add(label + ": " + value(values));
        }
    }

    private static void addValue(List<String> expectations, String label, Object expected) {
        if (expected != null) {
            expectations.add(label + ": " + value(expected));
        }
    }

    private static String value(Object expected) {
        return ObjectMappers.getObjectMapper().valueToTree(expected).toString();
    }
}
