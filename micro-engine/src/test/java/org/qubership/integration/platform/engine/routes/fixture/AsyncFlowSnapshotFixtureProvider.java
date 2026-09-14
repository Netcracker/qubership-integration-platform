package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinitionHelper;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.camel.processors.SplitAsyncProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ActiveThreadCounterIncrementer;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncFlowSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "async-flow";
    private static final String ACTIVE_THREAD_COUNTER_BEAN_NAME = "activeThreadCounterIncrementer";
    private static final String SPLIT_ASYNC_PROCESSOR_BEAN_NAME = "splitAsyncProcessor";
    private static final String CHAIN_FINISH_PROCESSOR_BEAN_NAME = "chainFinishProcessor";
    private static final long WAIT_TIMEOUT_SECONDS = 5;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean requiresNodeId() {
        return false;
    }

    @Override
    public boolean supportsExpectedExchanges() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        SnapshotFixtureBinding binding = SnapshotFixtureValidation.requireSingleBinding(bindings, "Async flow");
        SnapshotFixtureDefinition definition = binding.definition();
        SnapshotFixtureInteraction interaction = binding.interaction();
        SnapshotFixtureValidation.requireNoNodeId(definition, "Async flow");
        SnapshotFixtureValidation.requireNoRequestOrResponse(definition, interaction, "Async flow");
        if (!interaction.hasExpectedExchanges()) {
            throw new IllegalArgumentException(
                    "Async flow fixture '" + definition.getId() + "' must define expected exchanges."
            );
        }

        return new AsyncFlowSnapshotFixture(
                deploymentId,
                definition.getId(),
                interaction.getExpectedExchanges()
        );
    }

    private static final class AsyncFlowSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final String fixtureId;
        private final List<SnapshotFixtureExchangeExpectation> expectedExchanges;
        private final int expectedCompletionCount;
        private final CountDownLatch branchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseBranches = new CountDownLatch(1);
        private final CountDownLatch completionsRecorded;
        private final AtomicInteger counterInvocationCount = new AtomicInteger();
        private final AtomicReference<AtomicInteger> activeThreadCounter = new AtomicReference<>();
        private final List<CapturedExchange> completedExchanges = new CopyOnWriteArrayList<>();
        private final ActiveThreadCounterIncrementer counterDelegate = new ActiveThreadCounterIncrementer();
        private final SplitAsyncProcessor splitAsyncDelegate = new SplitAsyncProcessor();
        private boolean dispatchedBeforeRelease;
        private boolean branchStartedBeforeRelease;
        private boolean noCompletionBeforeRelease;
        private boolean allCompletionsRecorded;

        private AsyncFlowSnapshotFixture(
                String deploymentId,
                String fixtureId,
                List<SnapshotFixtureExchangeExpectation> expectedExchanges
        ) {
            this.deploymentId = deploymentId;
            this.fixtureId = fixtureId;
            this.expectedExchanges = List.copyOf(expectedExchanges);
            this.expectedCompletionCount = expectedExchanges.stream()
                    .mapToInt(SnapshotFixtureExchangeExpectation::getCount)
                    .reduce(0, Math::addExact);
            if (expectedCompletionCount == 0) {
                throw new IllegalArgumentException(
                        "Async flow fixture '" + fixtureId + "' must expect at least one exchange."
                );
            }
            this.completionsRecorded = new CountDownLatch(expectedCompletionCount);
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
            List<RouteDefinition> completionRoutes = routes.stream()
                    .filter(route -> ProcessorDefinitionHelper.filterTypeInOutputs(
                            route.getOutputs(), ProcessDefinition.class
                    ).stream().anyMatch(process -> SPLIT_ASYNC_PROCESSOR_BEAN_NAME.equals(process.getRef())))
                    .toList();
            SnapshotFixtureRouteScope.bind(camelContext, routes, deploymentId + ':' + fixtureId, Map.of(
                    ACTIVE_THREAD_COUNTER_BEAN_NAME, (Processor) this::incrementActiveThreadCounter,
                    SPLIT_ASYNC_PROCESSOR_BEAN_NAME, (Processor) this::processAsyncBranch
            ));
            SnapshotFixtureRouteScope.bind(camelContext, completionRoutes, deploymentId + ':' + fixtureId, Map.of(
                    CHAIN_FINISH_PROCESSOR_BEAN_NAME, (Processor) this::captureCompletion
            ));
        }

        @Override
        public void releasePendingWork() {
            dispatchedBeforeRelease = counterInvocationCount.get() > 0;
            try {
                // A nested deployment can dispatch only after its parent's gate opens.
                if (dispatchedBeforeRelease) {
                    branchStartedBeforeRelease = await(branchStarted, "an asynchronous branch to start");
                }
                noCompletionBeforeRelease = completedExchanges.isEmpty();
            } finally {
                releaseBranches.countDown();
            }
        }

        @Override
        public void awaitCompletion() {
            allCompletionsRecorded = await(
                    completionsRecorded,
                    expectedCompletionCount + " asynchronous branch completions"
            );
        }

        @Override
        public void verify() {
            assertTrue(
                    !dispatchedBeforeRelease || branchStartedBeforeRelease,
                    () -> "Async flow fixture '" + fixtureId
                            + "' did not observe an asynchronous branch before releasing the gate."
            );
            assertTrue(
                    noCompletionBeforeRelease,
                    () -> "Async flow fixture '" + fixtureId
                            + "' observed a completed branch while the asynchronous gate was closed."
            );
            assertTrue(
                    allCompletionsRecorded,
                    () -> "Async flow fixture '" + fixtureId + "' timed out waiting for "
                            + expectedCompletionCount + " asynchronous branch completions; recorded "
                            + completedExchanges.size() + "."
            );

            List<CapturedExchange> actualExchanges = List.copyOf(completedExchanges);
            assertEquals(
                    expectedCompletionCount,
                    counterInvocationCount.get(),
                    () -> "Async flow fixture '" + fixtureId
                            + "' invoked the active thread counter an unexpected number of times."
            );
            assertEquals(
                    expectedCompletionCount,
                    actualExchanges.size(),
                    () -> "Async flow fixture '" + fixtureId
                            + "' recorded an unexpected number of completed exchanges."
            );

            AtomicInteger counter = activeThreadCounter.get();
            assertNotNull(
                    counter,
                    () -> "Async flow fixture '" + fixtureId + "' did not initialize the active thread counter."
            );
            assertEquals(
                    0,
                    counter.get(),
                    () -> "Async flow fixture '" + fixtureId
                            + "' left an unexpected number of active asynchronous branches."
            );

            for (int index = 0; index < actualExchanges.size(); index++) {
                int exchangeNumber = index + 1;
                CapturedExchange actualExchange = actualExchanges.get(index);
                assertNull(
                        actualExchange.failure(),
                        () -> "Async flow fixture '" + fixtureId + "' exchange " + exchangeNumber
                                + " failed: " + failureDescription(actualExchange.failure()) + "."
                );
            }
            assertExpectedExchanges(actualExchanges);
        }

        @Override
        public void beforeContextStop() {
            releaseBranches.countDown();
        }

        @Override
        public void close() {
            releaseBranches.countDown();
            completedExchanges.clear();
        }

        private void incrementActiveThreadCounter(Exchange exchange) throws Exception {
            AtomicInteger exchangeCounter = exchange.getProperty(
                    Properties.SESSION_ACTIVE_THREAD_COUNTER,
                    AtomicInteger.class
            );
            if (exchangeCounter == null) {
                exchangeCounter = new AtomicInteger();
                exchange.setProperty(Properties.SESSION_ACTIVE_THREAD_COUNTER, exchangeCounter);
            }

            AtomicInteger counter = exchangeCounter;
            AtomicInteger fixtureCounter = activeThreadCounter.updateAndGet(
                    existingCounter -> existingCounter == null ? counter : existingCounter
            );
            if (fixtureCounter.get() != counter.get()) {
                throw new IllegalStateException(
                        "Async flow fixture '" + fixtureId
                                + "' received an exchange with a different active thread counter."
                );
            }

            counterDelegate.process(exchange);
            counterInvocationCount.incrementAndGet();
        }

        private void processAsyncBranch(Exchange exchange) throws Exception {
            splitAsyncDelegate.process(exchange);
            branchStarted.countDown();
            try {
                if (!releaseBranches.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Async flow fixture '" + fixtureId
                                    + "' timed out waiting for the asynchronous branch gate to open."
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
        }

        private void captureCompletion(Exchange exchange) {
            AtomicInteger counter = exchange.getProperty(
                    Properties.SESSION_ACTIVE_THREAD_COUNTER,
                    AtomicInteger.class
            );
            try {
                Throwable failure = exchangeFailure(exchange);
                if (counter == null && failure == null) {
                    failure = new IllegalStateException(
                            "The asynchronous exchange does not contain an active thread counter."
                    );
                }
                completedExchanges.add(new CapturedExchange(
                        immutableValue(exchange.getMessage().getBody()),
                        immutableMap(exchange.getMessage().getHeaders()),
                        immutableMap(exchange.getProperties()),
                        failure
                ));
            } finally {
                if (counter != null) {
                    counter.decrementAndGet();
                }
                completionsRecorded.countDown();
            }
        }

        private void assertExpectedExchanges(List<CapturedExchange> actualExchanges) {
            for (SnapshotFixtureExchangeExpectation expectation : expectedExchanges) {
                if (expectation.getCount() == 0) {
                    long matchingCount = actualExchanges.stream()
                            .filter(actualExchange -> matches(expectation, actualExchange))
                            .count();
                    assertEquals(
                            0,
                            matchingCount,
                            () -> "Async flow fixture '" + fixtureId
                                    + "' recorded an exchange that must be absent: "
                                    + expectationDescription(expectation) + "."
                    );
                }
            }

            List<SnapshotFixtureExchangeExpectation> expandedExpectations = new ArrayList<>();
            expectedExchanges.forEach(expectation -> {
                for (int index = 0; index < expectation.getCount(); index++) {
                    expandedExpectations.add(expectation);
                }
            });

            int[] expectationByActualExchange = new int[actualExchanges.size()];
            Arrays.fill(expectationByActualExchange, -1);
            for (int expectationIndex = 0; expectationIndex < expandedExpectations.size(); expectationIndex++) {
                boolean[] visitedActualExchanges = new boolean[actualExchanges.size()];
                int currentExpectationIndex = expectationIndex;
                assertTrue(
                        assignExpectation(
                                currentExpectationIndex,
                                expandedExpectations,
                                actualExchanges,
                                expectationByActualExchange,
                                visitedActualExchanges
                        ),
                        () -> "Async flow fixture '" + fixtureId
                                + "' did not record an exchange matching "
                                + expectationDescription(expandedExpectations.get(currentExpectationIndex))
                                + ". Actual exchanges: " + actualExchanges + "."
                );
            }
        }

        private static boolean assignExpectation(
                int expectationIndex,
                List<SnapshotFixtureExchangeExpectation> expectations,
                List<CapturedExchange> actualExchanges,
                int[] expectationByActualExchange,
                boolean[] visitedActualExchanges
        ) {
            SnapshotFixtureExchangeExpectation expectation = expectations.get(expectationIndex);
            for (int actualIndex = 0; actualIndex < actualExchanges.size(); actualIndex++) {
                if (visitedActualExchanges[actualIndex]
                        || !matches(expectation, actualExchanges.get(actualIndex))) {
                    continue;
                }
                visitedActualExchanges[actualIndex] = true;
                int previousExpectationIndex = expectationByActualExchange[actualIndex];
                if (previousExpectationIndex == -1
                        || assignExpectation(
                        previousExpectationIndex,
                        expectations,
                        actualExchanges,
                        expectationByActualExchange,
                        visitedActualExchanges
                )) {
                    expectationByActualExchange[actualIndex] = expectationIndex;
                    return true;
                }
            }
            return false;
        }

        private boolean await(CountDownLatch latch, String eventDescription) {
            try {
                return latch.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Async flow fixture '" + fixtureId + "' was interrupted while waiting for "
                                + eventDescription + ".",
                        exception
                );
            }
        }
    }

    private static boolean matches(
            SnapshotFixtureExchangeExpectation expectation,
            CapturedExchange actualExchange
    ) {
        return matchesBody(expectation, actualExchange.body())
                && containsExpectedValues(expectation.getHeaders(), actualExchange.headers())
                && containsExpectedValues(expectation.getProperties(), actualExchange.properties());
    }

    static boolean matchesBody(
            SnapshotFixtureExchangeExpectation expectation,
            Object actualBody
    ) {
        return !expectation.hasBody() || Objects.equals(expectation.getBody(), actualBody);
    }

    private static boolean containsExpectedValues(
            Map<String, Object> expectedValues,
            Map<String, Object> actualValues
    ) {
        return expectedValues.entrySet().stream().allMatch(entry ->
                Objects.equals(entry.getValue(), actualValues.get(entry.getKey())));
    }

    private static Throwable exchangeFailure(Exchange exchange) {
        Throwable failure = exchange.getException();
        if (failure == null) {
            failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        }
        return failure;
    }

    private static String failureDescription(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static String expectationDescription(SnapshotFixtureExchangeExpectation expectation) {
        return "{body=" + expectation.getBody()
                + ", headers=" + expectation.getHeaders()
                + ", properties=" + expectation.getProperties() + "}";
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((name, value) -> result.put(name, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof AtomicInteger atomicInteger) {
            return atomicInteger.get();
        }
        return value;
    }

    private record CapturedExchange(
            Object body,
            Map<String, Object> headers,
            Map<String, Object> properties,
            Throwable failure
    ) {
    }
}
