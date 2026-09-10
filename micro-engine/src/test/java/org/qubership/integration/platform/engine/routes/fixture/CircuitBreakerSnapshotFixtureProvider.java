package org.qubership.integration.platform.engine.routes.fixture;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.camel.CamelContext;
import org.apache.camel.Navigate;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.component.resilience4j.ResilienceProcessor;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CircuitBreakerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "circuit-breaker";
    private static final String DEPLOYMENT_INFO_BEAN_PREFIX = "DeploymentInfo-";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean requiresNodeId() {
        return false;
    }

    @Override
    public boolean supportsTransitionToState() {
        return true;
    }

    @Override
    public boolean supportsExpectedState() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        SnapshotFixtureBinding binding = SnapshotFixtureValidation.requireSingleBinding(bindings, "Circuit breaker");
        SnapshotFixtureValidation.requireNoNodeId(binding.definition(), "Circuit breaker");
        binding.interactionsByInvocationId().values().forEach(interaction ->
                validateInteraction(binding.definition().getId(), interaction));
        return new CircuitBreakerSnapshotFixture(deploymentId, binding);
    }

    private static void validateInteraction(
            String fixtureId,
            SnapshotFixtureInteraction interaction
    ) {
        if (interaction.getResponse() != null || interaction.getExpectedRequest() != null) {
            throw new IllegalArgumentException(
                    "Circuit breaker fixture '" + fixtureId
                            + "' supports only transitionToState and expectedState."
            );
        }
    }

    private static final class CircuitBreakerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final String fixtureId;

        private CamelContext camelContext;
        private List<RouteDefinition> routeDefinitions = List.of();
        private ResilienceProcessor resilienceProcessor;

        private CircuitBreakerSnapshotFixture(
                String deploymentId,
                SnapshotFixtureBinding binding
        ) {
            this.deploymentId = deploymentId;
            this.binding = binding;
            this.fixtureId = binding.definition().getId();
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void configure(CamelContext camelContext) {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) {
            this.camelContext = camelContext;
            routeDefinitions = List.copyOf(routes);
            Set<String> routeGroups = new LinkedHashSet<>();
            for (RouteDefinition routeDefinition : routeDefinitions) {
                if (!containsCircuitBreaker(routeDefinition)) {
                    continue;
                }
                String routeGroup = routeDefinition.getGroup();
                if (routeGroup == null || routeGroup.isBlank()) {
                    throw new IllegalStateException(
                            "Circuit breaker fixture '" + fixtureId + "' requires a generated route group for route '"
                                    + routeDefinition.getId() + "'."
                    );
                }
                routeGroups.add(routeGroup);
            }

            for (String routeGroup : routeGroups) {
                String beanName = DEPLOYMENT_INFO_BEAN_PREFIX + routeGroup;
                if (camelContext.getRegistry().lookupByNameAndType(beanName, DeploymentInfo.class) == null) {
                    throw new IllegalStateException(
                            "Circuit breaker fixture '" + fixtureId
                                    + "' requires generated deployment metadata '" + beanName + "'."
                    );
                }
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
            ResilienceProcessor processor = requireResilienceProcessor();
            String transitionToState = interaction.getTransitionToState();
            if (transitionToState == null) {
                return;
            }

            switch (normalizedState(transitionToState)) {
                case "CLOSED" -> processor.transitionToCloseState();
                case "OPEN" -> processor.transitionToOpenState();
                case "HALF_OPEN" -> processor.transitionToHalfOpenState();
                case "FORCED_OPEN" -> processor.transitionToForcedOpenState();
                default -> throw new IllegalArgumentException(
                        "Circuit breaker fixture '" + fixtureId + "' invocation '" + invocation.getId()
                                + "' defines unsupported transition state '" + transitionToState + "'."
                );
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
            String expectedState = interaction.getExpectedState();
            if (expectedState == null) {
                return;
            }

            CircuitBreaker.State parsedExpectedState;
            try {
                parsedExpectedState = CircuitBreaker.State.valueOf(normalizedState(expectedState));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Circuit breaker fixture '" + fixtureId + "' invocation '" + invocation.getId()
                                + "' defines unsupported expected state '" + expectedState + "'.",
                        exception
                );
            }
            assertEquals(
                    parsedExpectedState,
                    requireResilienceProcessor().getCircuitBreaker().getState(),
                    () -> "Circuit breaker fixture '" + fixtureId + "' invocation '" + invocation.getId()
                            + "' has an unexpected state."
            );
        }

        @Override
        public void verify() {
        }

        @Override
        public void beforeContextStop() {
            if (resilienceProcessor != null
                    && resilienceProcessor.getCircuitBreaker().getState() != CircuitBreaker.State.CLOSED) {
                resilienceProcessor.transitionToCloseState();
            }
        }

        private ResilienceProcessor requireResilienceProcessor() {
            if (resilienceProcessor != null) {
                return resilienceProcessor;
            }
            List<ResilienceProcessor> processors = findResilienceProcessors(camelContext, routeDefinitions);
            if (processors.size() != 1) {
                throw new IllegalStateException(
                        "Circuit breaker fixture '" + fixtureId + "' expected one runtime circuit breaker in "
                                + "deployment '" + deploymentId + "', but found " + processors.size() + "."
                );
            }
            resilienceProcessor = processors.getFirst();
            return resilienceProcessor;
        }

        private static String normalizedState(String value) {
            return value.strip().toUpperCase(Locale.ROOT);
        }
    }

    private static boolean containsCircuitBreaker(ProcessorDefinition<?> definition) {
        return definition instanceof CircuitBreakerDefinition
                || definition.getOutputs().stream().anyMatch(CircuitBreakerSnapshotFixtureProvider::containsCircuitBreaker);
    }

    private static List<ResilienceProcessor> findResilienceProcessors(
            CamelContext camelContext,
            List<RouteDefinition> routeDefinitions
    ) {
        List<String> routeIds = routeDefinitions.stream().map(RouteDefinition::getId).toList();
        List<ResilienceProcessor> processors = new ArrayList<>();
        Set<Processor> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Route route : camelContext.getRoutes()) {
            if (routeIds.contains(route.getId())) {
                collectResilienceProcessors(route.getProcessor(), visited, processors);
            }
        }
        return List.copyOf(processors);
    }

    private static void collectResilienceProcessors(
            Processor processor,
            Set<Processor> visited,
            List<ResilienceProcessor> resilienceProcessors
    ) {
        if (processor == null || !visited.add(processor)) {
            return;
        }
        if (processor instanceof ResilienceProcessor resilienceProcessor) {
            resilienceProcessors.add(resilienceProcessor);
        }
        if (processor instanceof Navigate<?> navigate && navigate.hasNext()) {
            for (Object next : navigate.next()) {
                if (next instanceof Processor nextProcessor) {
                    collectResilienceProcessors(nextProcessor, visited, resilienceProcessors);
                }
            }
        }
    }
}
