package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.IdempotentConsumerDefinition;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.camel.processors.NoopProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IdempotencySnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final Set<String> OBSERVATIONS = Set.of(
            "insertionAttempts", "key", "keyExpiry", "storedKeys", "updatedKeys", "records");

    @Override
    public String getId() {
        return "idempotency-postgres";
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        bindings.forEach(binding -> binding.interactionsByInvocationId().values().forEach(interaction ->
                validateInteraction(binding.definition().getId(), interaction)));
        return new IdempotencySnapshotFixture(deploymentId, bindings);
    }

    private static void validateInteraction(String fixtureId, SnapshotFixtureInteraction interaction) {
        String label = "Idempotency fixture '" + fixtureId + "'";
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        if (expected == null) {
            throw new IllegalArgumentException(label + " must define expectedRequest.");
        }
        if (expected.hasBody() || !expected.getHeaders().isEmpty() || expected.getMethod() != null
                || expected.getPath() != null || expected.getQuery() != null
                || expected.getDestination() != null || expected.getKey() != null) {
            throw new IllegalArgumentException(label + " supports only expectedRequest.count and properties.");
        }
        if (!OBSERVATIONS.containsAll(expected.getProperties().keySet())) {
            throw new IllegalArgumentException(label + " has unsupported expectedRequest.properties.");
        }
        SnapshotFixtureResponse response = interaction.getResponse();
        if (response != null) {
            if (response.hasExplicitStatus() || response.getBody() != null || !response.getHeaders().isEmpty()
                    || response.getDelayMillis() != null
                    || !Set.of("expireKey").containsAll(response.getProperties().keySet())) {
                throw new IllegalArgumentException(label + " supports only response.properties.expireKey.");
            }
            if (response.getProperties().containsKey("expireKey")
                    && !(response.getProperties().get("expireKey") instanceof String)) {
                throw new IllegalArgumentException(label + " requires a string response.properties.expireKey.");
            }
        }
    }

    private static final class IdempotencySnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final PostgresIdempotencySnapshotStore store = new PostgresIdempotencySnapshotStore();
        private final Map<String, AtomicInteger> downstreamExecutions = new LinkedHashMap<>();
        private Map<String, PostgresIdempotencySnapshotStore.StoredRecord> previousRecords = Map.of();

        private IdempotencySnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            bindings.forEach(binding -> downstreamExecutions.put(binding.definition().getId(), new AtomicInteger()));
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            store.start();
        }

        @Override
        public void configure(CamelContext context) throws Exception {
            configure(context, ((ModelCamelContext) context).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext context, List<RouteDefinition> routes) throws Exception {
            List<IdempotentConsumerDefinition> consumers = new ArrayList<>();
            routes.forEach(route -> findConsumers(route, consumers));
            assertFalse(consumers.isEmpty(), "Idempotency fixture requires a generated idempotent consumer.");
            consumers.forEach(consumer -> consumer.setIdempotentRepository(store.repository()));
            SnapshotFixtureRouteScope.bind(context, routes, deploymentId + ":idempotency",
                    Map.of("noopProcessor", new NoopProcessor()));
            for (SnapshotFixtureBinding binding : bindings) {
                String nodeId = binding.definition().getNodeId();
                RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, nodeId),
                        "Idempotency fixture expected one downstream node '" + nodeId + "'").route();
                AtomicInteger executions = downstreamExecutions.get(binding.definition().getId());
                AdviceWith.adviceWith(context, route, false, advice -> advice.weaveById(nodeId).before()
                        .process(exchange -> executions.incrementAndGet()));
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            downstreamExecutions.values().forEach(executions -> executions.set(0));
            store.resetAttempts();
            for (SnapshotFixtureBinding binding : bindings) {
                SnapshotFixtureResponse response = binding.interaction(invocation.getId()).getResponse();
                if (response != null && response.getProperties().containsKey("expireKey")) {
                    store.expire((String) response.getProperties().get("expireKey"));
                }
            }
            previousRecords = store.readRecords();
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            Map<String, Object> observations = store.observations(previousRecords);
            for (SnapshotFixtureBinding binding : bindings) {
                String label = "Idempotency fixture '" + binding.definition().getId()
                        + "' invocation '" + invocation.getId() + "'";
                SnapshotFixtureRequestExpectation expected = binding.interaction(invocation.getId()).getExpectedRequest();
                assertEquals(expected.getCount(), downstreamExecutions.get(binding.definition().getId()).get(),
                        label + " executed the downstream node an unexpected number of times.");
                SnapshotValueAssertions.assertMapValues(expected.getProperties(), observations,
                        label + " has an unexpected property");
            }
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() {
            store.close();
        }

        private static void findConsumers(ProcessorDefinition<?> node, List<IdempotentConsumerDefinition> result) {
            if (node instanceof IdempotentConsumerDefinition consumer) {
                result.add(consumer);
            }
            node.getOutputs().forEach(child -> findConsumers(child, result));
        }
    }
}
