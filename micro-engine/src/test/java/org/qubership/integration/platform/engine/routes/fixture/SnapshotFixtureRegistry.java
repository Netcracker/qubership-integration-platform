package org.qubership.integration.platform.engine.routes.fixture;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SnapshotFixtureRegistry {
    private final Map<String, SnapshotFixtureProvider> providersById;

    public SnapshotFixtureRegistry(List<SnapshotFixtureProvider> providers) {
        Map<String, SnapshotFixtureProvider> providersById = new LinkedHashMap<>();
        for (SnapshotFixtureProvider provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException("Snapshot fixture provider cannot be null.");
            }
            String providerId = provider.getId();
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("Snapshot fixture provider id is missing.");
            }
            if (providersById.putIfAbsent(providerId, provider) != null) {
                throw new IllegalArgumentException(
                        "Snapshot fixture registry contains duplicate provider id '" + providerId + "'."
                );
            }
        }
        this.providersById = Collections.unmodifiableMap(providersById);
    }

    public static SnapshotFixtureRegistry withDefaultProviders() {
        return new SnapshotFixtureRegistry(List.of(
                new CamelEndpointSnapshotFixtureProvider(),
                new HttpServiceCallSnapshotFixtureProvider(),
                new GraphqlHttpSnapshotFixtureProvider(),
                new CircuitBreakerSnapshotFixtureProvider(),
                new CheckpointSnapshotFixtureProvider(),
                new FileOutputSnapshotFixtureProvider(),
                new LogCaptureSnapshotFixtureProvider(),
                new AsyncFlowSnapshotFixtureProvider(),
                new ParallelBarrierSnapshotFixtureProvider(),
                new KafkaContainerSnapshotFixtureProvider(),
                new RabbitMqContainerSnapshotFixtureProvider(),
                new ArtemisJmsContainerSnapshotFixtureProvider(),
                new PubSubGrpcSnapshotFixtureProvider()
        ));
    }

    public List<SnapshotFixture> createFixtures(
            SnapshotExecutionTarget target,
            SnapshotExecutionScenario scenario
    ) {
        Map<String, SnapshotFixtureDefinition> definitionsById = new LinkedHashMap<>();
        target.getFixtures().forEach(definition -> definitionsById.put(definition.getId(), definition));

        Map<String, Map<String, SnapshotFixtureInteraction>> interactionsByFixtureId = new LinkedHashMap<>();
        for (SnapshotScenarioInvocation invocation : scenario.getInvocations()) {
            for (SnapshotFixtureInteraction interaction : invocation.getInteractions()) {
                if (!definitionsById.containsKey(interaction.getFixtureId())) {
                    throw new IllegalArgumentException(
                            "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                                    + "' references unknown fixture '" + interaction.getFixtureId() + "'."
                    );
                }
                Map<String, SnapshotFixtureInteraction> interactionsByInvocationId =
                        interactionsByFixtureId.computeIfAbsent(
                                interaction.getFixtureId(),
                                ignored -> new LinkedHashMap<>()
                        );
                if (interactionsByInvocationId.putIfAbsent(invocation.getId(), interaction) != null) {
                    throw new IllegalArgumentException(
                            "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                                    + "' contains duplicate interaction for fixture '"
                                    + interaction.getFixtureId() + "'."
                    );
                }
            }
        }

        Map<ProviderDeployment, List<SnapshotFixtureBinding>> bindingsByProviderDeployment = new LinkedHashMap<>();
        for (SnapshotFixtureDefinition definition : target.getFixtures()) {
            SnapshotFixtureProvider provider = providersById.get(definition.getProvider());
            if (provider == null) {
                throw new IllegalArgumentException(
                        "Snapshot fixture '" + definition.getId() + "' uses unknown provider '"
                                + definition.getProvider() + "'."
                );
            }
            Map<String, SnapshotFixtureInteraction> interactions = interactionsByFixtureId.get(definition.getId());
            if (interactions == null || interactions.size() != scenario.getInvocations().size()) {
                throw new IllegalArgumentException(
                        "Snapshot scenario '" + scenario.getId()
                                + "' must define an interaction for fixture '" + definition.getId()
                                + "' in every invocation."
                );
            }
            interactions.values().forEach(interaction ->
                    validateBinding(definition, interaction, provider));

            ProviderDeployment providerDeployment = new ProviderDeployment(
                    definition.getProvider(),
                    definition.getDeploymentId()
            );
            bindingsByProviderDeployment.computeIfAbsent(providerDeployment, ignored -> new ArrayList<>())
                    .add(new SnapshotFixtureBinding(
                            definition,
                            interactions
                    ));
        }

        List<SnapshotFixture> fixtures = new ArrayList<>(bindingsByProviderDeployment.size());
        bindingsByProviderDeployment.forEach((providerDeployment, bindings) -> fixtures.add(
                providersById.get(providerDeployment.providerId()).create(
                        providerDeployment.deploymentId(),
                        List.copyOf(bindings)
                )
        ));
        return List.copyOf(fixtures);
    }

    private static void validateBinding(
            SnapshotFixtureDefinition definition,
            SnapshotFixtureInteraction interaction,
            SnapshotFixtureProvider provider
    ) {
        if (definition.getNodeId() == null && provider.requiresNodeId()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture '" + definition.getId() + "' using provider '" + provider.getId()
                            + "' must define a nodeId."
            );
        }
        if (interaction.hasExpectedLogs() && !provider.supportsExpectedLogs()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture '" + definition.getId() + "' using provider '" + provider.getId()
                            + "' does not support expected logs."
            );
        }
        if (interaction.hasExpectedExchanges() && !provider.supportsExpectedExchanges()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture '" + definition.getId() + "' using provider '" + provider.getId()
                            + "' does not support expected exchanges."
            );
        }
        if (interaction.getTransitionToState() != null && !provider.supportsTransitionToState()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture '" + definition.getId() + "' using provider '" + provider.getId()
                            + "' does not support transitionToState."
            );
        }
        if (interaction.getExpectedState() != null && !provider.supportsExpectedState()) {
            throw new IllegalArgumentException(
                    "Snapshot fixture '" + definition.getId() + "' using provider '" + provider.getId()
                            + "' does not support expectedState."
            );
        }
    }

    private record ProviderDeployment(String providerId, String deploymentId) {
    }
}
