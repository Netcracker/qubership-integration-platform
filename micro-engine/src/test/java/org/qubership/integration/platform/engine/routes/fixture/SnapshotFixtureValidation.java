package org.qubership.integration.platform.engine.routes.fixture;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;

import java.util.List;

final class SnapshotFixtureValidation {
    private SnapshotFixtureValidation() {
    }

    static SnapshotFixtureBinding requireSingleBinding(
            List<SnapshotFixtureBinding> bindings,
            String providerName
    ) {
        if (bindings.size() != 1) {
            throw new IllegalArgumentException(
                    providerName + " provider requires exactly one fixture per deployment."
            );
        }
        return bindings.getFirst();
    }

    static void requireNoNodeId(SnapshotFixtureDefinition definition, String providerName) {
        if (definition.getNodeId() != null) {
            throw new IllegalArgumentException(
                    providerName + " fixture '" + definition.getId() + "' cannot define a nodeId."
            );
        }
    }

    static void requireNoRequestOrResponse(
            SnapshotFixtureDefinition definition,
            SnapshotFixtureInteraction interaction,
            String providerName
    ) {
        if (interaction.getResponse() != null || interaction.getExpectedRequest() != null) {
            throw new IllegalArgumentException(
                    providerName + " fixture '" + definition.getId()
                            + "' cannot define a response or an expected request."
            );
        }
    }

    static SnapshotFixtureRequestExpectation requireMessageExpectation(
            SnapshotFixtureBinding binding,
            String providerName
    ) {
        String fixtureLabel = providerName + " fixture '" + binding.definition().getId() + "'";
        SnapshotFixtureInteraction interaction = binding.interaction();
        if (interaction.getResponse() != null) {
            throw new IllegalArgumentException(fixtureLabel + " cannot define a response.");
        }
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        if (expectation == null) {
            throw new IllegalArgumentException(fixtureLabel + " must define an expected request.");
        }
        if (expectation.getDestination() == null) {
            throw new IllegalArgumentException(fixtureLabel + " must define an expected request destination.");
        }
        return expectation;
    }
}
