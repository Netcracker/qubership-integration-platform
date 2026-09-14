package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class ParallelBarrierSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "parallel-barrier";
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(5);

    private final Duration waitTimeout;

    ParallelBarrierSnapshotFixtureProvider() {
        this(DEFAULT_WAIT_TIMEOUT);
    }

    ParallelBarrierSnapshotFixtureProvider(Duration waitTimeout) {
        if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("Parallel barrier wait timeout must be positive.");
        }
        this.waitTimeout = waitTimeout;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        if (bindings.size() < 2) {
            throw new IllegalArgumentException(
                    "Parallel barrier provider requires at least two fixtures per deployment."
            );
        }
        bindings.forEach(binding -> binding.interactionsByInvocationId().values()
                .forEach(interaction -> validateInteraction(binding.definition().getId(), interaction)));
        return new ParallelBarrierSnapshotFixture(deploymentId, bindings, waitTimeout);
    }

    private static void validateInteraction(
            String fixtureId,
            SnapshotFixtureInteraction interaction
    ) {
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        if (interaction.getResponse() != null || expectation == null) {
            throw new IllegalArgumentException(
                    "Parallel barrier fixture '" + fixtureId + "' supports only an expected request."
            );
        }
        if (expectation.getMethod() != null
                || expectation.getPath() != null
                || expectation.getQuery() != null
                || expectation.getDestination() != null
                || expectation.getKey() != null) {
            throw new IllegalArgumentException(
                    "Parallel barrier fixture '" + fixtureId
                            + "' does not support request method, path, query, destination, or key expectations."
            );
        }
    }

    private static final class ParallelBarrierSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Duration waitTimeout;
        private final AtomicReference<InvocationState> activeInvocation = new AtomicReference<>();

        private ParallelBarrierSnapshotFixture(
                String deploymentId,
                List<SnapshotFixtureBinding> bindings,
                Duration waitTimeout
        ) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            this.waitTimeout = waitTimeout;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            ModelCamelContext modelCamelContext = (ModelCamelContext) camelContext;
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                RouteDefinition route = findRoute(
                        modelCamelContext.getRouteDefinitions(),
                        binding.definition().getId(),
                        binding.definition().getNodeId()
                );
                bindingsByRoute.computeIfAbsent(route, ignored -> new ArrayList<>()).add(binding);
            }

            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId())
                                .before()
                                .process(exchange -> awaitParallelBranches(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) {
            for (SnapshotFixtureBinding binding : bindings) {
                int expectedCount = binding.interaction(invocation.getId())
                        .getExpectedRequest()
                        .getCount();
                if (expectedCount != invocation.getRepeat()) {
                    throw new IllegalArgumentException(
                            "Parallel barrier fixture '" + binding.definition().getId()
                                    + "' invocation '" + invocation.getId() + "' expects "
                                    + expectedCount + " requests, but invocation repeat is "
                                    + invocation.getRepeat() + "."
                    );
                }
            }

            InvocationState state = new InvocationState(
                    invocation.getId(),
                    invocation.getRepeat(),
                    bindings
            );
            if (!activeInvocation.compareAndSet(null, state)) {
                throw new IllegalStateException(
                        "Parallel barrier deployment '" + deploymentId
                                + "' started invocation '" + invocation.getId()
                                + "' before the previous invocation was verified."
                );
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            InvocationState state = activeInvocation.get();
            if (state == null || !state.invocationId().equals(invocation.getId())) {
                throw new IllegalStateException(
                        "Parallel barrier deployment '" + deploymentId
                                + "' does not have active invocation '" + invocation.getId() + "'."
                );
            }

            try {
                for (int index = 0; index < state.barriers().size(); index++) {
                    int repetition = index + 1;
                    CountDownLatch barrier = state.barriers().get(index);
                    assertEquals(
                            0,
                            barrier.getCount(),
                            () -> "Parallel barrier deployment '" + deploymentId
                                    + "' did not observe every branch in repetition " + repetition + "."
                    );
                    assertEquals(
                            bindings.size(),
                            state.threadIdsByRepetition().get(index).size(),
                            () -> "Parallel barrier deployment '" + deploymentId
                                    + "' did not execute each branch on a distinct thread in repetition "
                                    + repetition + "."
                    );
                }
                bindings.forEach(binding -> verifyRequests(binding, state));
            } finally {
                activeInvocation.compareAndSet(state, null);
            }
        }

        @Override
        public void verify() {
            assertNull(
                    activeInvocation.get(),
                    () -> "Parallel barrier deployment '" + deploymentId
                            + "' still has an active invocation."
            );
        }

        @Override
        public void beforeContextStop() {
            releaseBarriers();
        }

        @Override
        public void close() {
            releaseBarriers();
            activeInvocation.set(null);
        }

        private void awaitParallelBranches(
                SnapshotFixtureBinding binding,
                Exchange exchange
        ) throws InterruptedException {
            InvocationState state = activeInvocation.get();
            if (state == null) {
                throw new IllegalStateException(
                        "Parallel barrier fixture '" + binding.definition().getId()
                                + "' received an exchange outside an invocation."
                );
            }

            String fixtureId = binding.definition().getId();
            List<RecordedRequest> requests = state.requestsByFixtureId().get(fixtureId);
            int repetitionIndex = requests.size();
            requests.add(new RecordedRequest(
                    exchange.getMessage().getBody(),
                    immutableMap(exchange.getMessage().getHeaders()),
                    immutableMap(exchange.getProperties())
            ));
            if (repetitionIndex >= state.barriers().size()) {
                throw new IllegalStateException(
                        "Parallel barrier fixture '" + fixtureId
                                + "' received more exchanges than expected for invocation '"
                                + state.invocationId() + "'."
                );
            }

            state.threadIdsByRepetition().get(repetitionIndex).add(Thread.currentThread().threadId());
            CountDownLatch barrier = state.barriers().get(repetitionIndex);
            barrier.countDown();
            if (!barrier.await(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException(
                        "Parallel barrier fixture '" + fixtureId
                                + "' timed out waiting for all branches in invocation '"
                                + state.invocationId() + "' repetition " + (repetitionIndex + 1) + "."
                );
            }
        }

        private void verifyRequests(
                SnapshotFixtureBinding binding,
                InvocationState state
        ) {
            String fixtureId = binding.definition().getId();
            SnapshotFixtureRequestExpectation expectation = binding.interaction(state.invocationId())
                    .getExpectedRequest();
            List<RecordedRequest> requests = state.requestsByFixtureId().get(fixtureId);
            assertEquals(
                    expectation.getCount(),
                    requests.size(),
                    () -> "Parallel barrier fixture '" + fixtureId
                            + "' received an unexpected number of requests."
            );
            for (int index = 0; index < requests.size(); index++) {
                int requestNumber = index + 1;
                RecordedRequest request = requests.get(index);
                if (expectation.hasBody()) {
                    assertEquals(
                            expectation.getBody(),
                            request.body(),
                            () -> "Parallel barrier fixture '" + fixtureId + "' request "
                                    + requestNumber + " has an unexpected body."
                    );
                }
                SnapshotValueAssertions.assertMapValues(
                        expectation.getHeaders(),
                        request.headers(),
                        "Parallel barrier fixture '" + fixtureId + "' request " + requestNumber
                                + " has an unexpected header"
                );
                SnapshotValueAssertions.assertMapValues(
                        expectation.getProperties(),
                        request.properties(),
                        "Parallel barrier fixture '" + fixtureId + "' request " + requestNumber
                                + " has an unexpected property"
                );
            }
        }

        private void releaseBarriers() {
            InvocationState state = activeInvocation.get();
            if (state == null) {
                return;
            }
            state.barriers().forEach(barrier -> {
                while (barrier.getCount() > 0) {
                    barrier.countDown();
                }
            });
        }

        private static RouteDefinition findRoute(
                List<RouteDefinition> routes,
                String fixtureId,
                String nodeId
        ) {
            return SnapshotRouteNodes.requireSingle(
                    SnapshotRouteNodes.findById(routes, nodeId),
                    "Parallel barrier fixture '" + fixtureId + "' expected one node '" + nodeId + "'"
            ).route();
        }
    }

    private record InvocationState(
            String invocationId,
            List<CountDownLatch> barriers,
            List<Set<Long>> threadIdsByRepetition,
            Map<String, List<RecordedRequest>> requestsByFixtureId
    ) {
        private InvocationState(
                String invocationId,
                int repeat,
                List<SnapshotFixtureBinding> bindings
        ) {
            this(
                    invocationId,
                    barriers(repeat, bindings.size()),
                    threadIds(repeat),
                    requestLists(bindings)
            );
        }

        private static List<CountDownLatch> barriers(int repeat, int participantCount) {
            List<CountDownLatch> barriers = new ArrayList<>(repeat);
            for (int index = 0; index < repeat; index++) {
                barriers.add(new CountDownLatch(participantCount));
            }
            return List.copyOf(barriers);
        }

        private static List<Set<Long>> threadIds(int repeat) {
            List<Set<Long>> threadIds = new ArrayList<>(repeat);
            for (int index = 0; index < repeat; index++) {
                threadIds.add(ConcurrentHashMap.newKeySet());
            }
            return List.copyOf(threadIds);
        }

        private static Map<String, List<RecordedRequest>> requestLists(
                List<SnapshotFixtureBinding> bindings
        ) {
            Map<String, List<RecordedRequest>> requests = new LinkedHashMap<>();
            bindings.forEach(binding -> requests.put(
                    binding.definition().getId(),
                    new CopyOnWriteArrayList<>()
            ));
            return Collections.unmodifiableMap(requests);
        }
    }

    private record RecordedRequest(
            Object body,
            Map<String, Object> headers,
            Map<String, Object> properties
    ) {
    }
}
