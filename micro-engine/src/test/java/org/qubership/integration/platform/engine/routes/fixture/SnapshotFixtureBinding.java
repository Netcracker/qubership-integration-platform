package org.qubership.integration.platform.engine.routes.fixture;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public record SnapshotFixtureBinding(
        SnapshotFixtureDefinition definition,
        Map<String, SnapshotFixtureInteraction> interactionsByInvocationId
) {
    public SnapshotFixtureBinding {
        requireNonNull(definition, "definition");
        requireNonNull(interactionsByInvocationId, "interactionsByInvocationId");
        interactionsByInvocationId.forEach((invocationId, interaction) -> {
            requireNonNull(invocationId, "invocationId");
            requireNonNull(interaction, "interaction");
        });
        interactionsByInvocationId = Collections.unmodifiableMap(
                new LinkedHashMap<>(interactionsByInvocationId)
        );
    }

    public SnapshotFixtureInteraction interaction(String invocationId) {
        return interactionsByInvocationId.get(invocationId);
    }

    public SnapshotFixtureInteraction interaction() {
        if (interactionsByInvocationId.size() != 1) {
            throw new IllegalStateException(
                    "Snapshot fixture binding '" + definition.getId()
                            + "' contains multiple invocation interactions."
            );
        }
        return interactionsByInvocationId.values().iterator().next();
    }
}
