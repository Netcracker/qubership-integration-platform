package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.Synchronization;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class SplitTimeoutSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "split-timeout";
    private static final long WAIT_TIMEOUT_SECONDS = 10;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supportsExpectedExchanges() {
        return true;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        SnapshotFixtureBinding binding = SnapshotFixtureValidation.requireSingleBinding(bindings, "Split timeout");
        String fixtureLabel = "Split timeout fixture '" + binding.definition().getId() + "'";
        if (binding.interactionsByInvocationId().size() != 1) {
            throw new IllegalArgumentException(fixtureLabel + " supports exactly one invocation per scenario.");
        }
        SnapshotFixtureInteraction interaction = binding.interaction();
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        if (interaction.getResponse() != null || expectation == null || !interaction.hasExpectedExchanges()) {
            throw new IllegalArgumentException(
                    fixtureLabel + " requires an expected request and expected exchanges, without a response."
            );
        }
        if (expectation.getMethod() != null
                || expectation.getPath() != null
                || expectation.getQuery() != null
                || expectation.getDestination() != null
                || expectation.getKey() != null) {
            throw new IllegalArgumentException(
                    fixtureLabel + " does not support request method, path, query, destination, or key expectations."
            );
        }
        if (expectation.getCount() > 1
                || interaction.getExpectedExchanges().size() != expectation.getCount()
                || interaction.getExpectedExchanges().stream().anyMatch(exchange -> exchange.getCount() != 1)) {
            throw new IllegalArgumentException(
                    fixtureLabel + " requires a request count of 0 or 1 and one expected exchange per request."
            );
        }
        if (interaction.getExpectedExchanges().stream().anyMatch(exchange -> exchange.getExpectedFailure() != null)) {
            throw new IllegalArgumentException(
                    fixtureLabel + " does not support expectedFailure in expected exchanges."
            );
        }
        return new SplitTimeoutSnapshotFixture(deploymentId, binding);
    }

    private static final class SplitTimeoutSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final SnapshotFixtureBinding binding;
        private final String fixtureLabel;
        private final CountDownLatch branchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseBranch = new CountDownLatch(1);
        private final CountDownLatch gateExited = new CountDownLatch(1);
        private final CountDownLatch branchCompleted;
        private final List<RecordedExchange> requests = new CopyOnWriteArrayList<>();
        private final List<RecordedExchange> completedExchanges = new CopyOnWriteArrayList<>();

        private SplitTimeoutSnapshotFixture(String deploymentId, SnapshotFixtureBinding binding) {
            this.deploymentId = deploymentId;
            this.binding = binding;
            this.fixtureLabel = "Split timeout fixture '" + binding.definition().getId() + "'";
            this.branchCompleted = new CountDownLatch(expectedRequest().getCount());
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            String nodeId = binding.definition().getNodeId();
            RouteDefinition route = SnapshotRouteNodes.requireSingle(
                    SnapshotRouteNodes.findById(routes, nodeId),
                    fixtureLabel + " expected one node '" + nodeId + "'"
            ).route();
            AdviceWith.adviceWith(camelContext, route, false, advice ->
                    advice.weaveById(nodeId).before().process(this::holdBranch));
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) {
            if (invocation.getRepeat() != 1) {
                throw new IllegalArgumentException(fixtureLabel + " requires an invocation repeat of 1.");
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            assertRequests();
            assertBranchHeld();
        }

        @Override
        public void releasePendingWork() {
            try {
                assertRequests();
                assertBranchHeld();
            } finally {
                releaseBranch.countDown();
            }
        }

        @Override
        public void awaitCompletion() throws InterruptedException {
            assertTrue(
                    branchCompleted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    () -> fixtureLabel + " timed out waiting for the secondary branch to complete."
            );
        }

        @Override
        public void verify() {
            assertRequests();
            List<RecordedExchange> exchanges = List.copyOf(completedExchanges);
            assertEquals(
                    expectedRequest().getCount(),
                    exchanges.size(),
                    () -> fixtureLabel + " recorded an unexpected number of completed exchanges."
            );
            for (int index = 0; index < exchanges.size(); index++) {
                RecordedExchange actual = exchanges.get(index);
                assertFalse(actual.failed(), () -> fixtureLabel + " observed a failed secondary branch.");
                assertNull(actual.failure(), () -> fixtureLabel + " observed a secondary branch exception.");
                SnapshotFixtureExchangeExpectation expected = binding.interaction().getExpectedExchanges().get(index);
                if (expected.hasBody()) {
                    assertEquals(expected.getBody(), actual.body(), fixtureLabel + " completed with an unexpected body.");
                }
                SnapshotValueAssertions.assertMapValues(
                        expected.getHeaders(), actual.headers(), fixtureLabel + " completed with an unexpected header"
                );
                SnapshotValueAssertions.assertMapValues(
                        expected.getProperties(), actual.properties(), fixtureLabel + " completed with an unexpected property"
                );
            }
        }

        @Override
        public void beforeContextStop() {
            releaseBranch.countDown();
        }

        @Override
        public void close() {
            releaseBranch.countDown();
        }

        private void holdBranch(Exchange exchange) throws InterruptedException {
            requests.add(RecordedExchange.capture(exchange, false));
            if (requests.size() > expectedRequest().getCount()) {
                throw new IllegalStateException(fixtureLabel + " received more requests than expected.");
            }
            exchange.getUnitOfWork().addSynchronization(new Synchronization() {
                @Override
                public void onComplete(Exchange completedExchange) {
                    recordCompletion(completedExchange, false);
                }

                @Override
                public void onFailure(Exchange failedExchange) {
                    recordCompletion(failedExchange, true);
                }
            });
            branchStarted.countDown();
            try {
                if (!releaseBranch.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(fixtureLabel + " timed out waiting for the secondary branch gate to open.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } finally {
                gateExited.countDown();
            }
        }

        private void recordCompletion(Exchange exchange, boolean failed) {
            completedExchanges.add(RecordedExchange.capture(exchange, failed));
            branchCompleted.countDown();
        }

        private void assertRequests() {
            SnapshotFixtureRequestExpectation expectation = expectedRequest();
            List<RecordedExchange> actualRequests = List.copyOf(requests);
            assertEquals(
                    expectation.getCount(),
                    actualRequests.size(),
                    () -> fixtureLabel + " received an unexpected number of requests."
            );
            for (RecordedExchange request : actualRequests) {
                if (expectation.hasBody()) {
                    assertEquals(expectation.getBody(), request.body(), fixtureLabel + " received an unexpected body.");
                }
                SnapshotValueAssertions.assertMapValues(
                        expectation.getHeaders(), request.headers(), fixtureLabel + " received an unexpected header"
                );
                SnapshotValueAssertions.assertMapValues(
                        expectation.getProperties(), request.properties(), fixtureLabel + " received an unexpected property"
                );
            }
        }

        private void assertBranchHeld() {
            assertEquals(1, releaseBranch.getCount(), fixtureLabel + " opened the gate before verifying the route result.");
            assertEquals(1, gateExited.getCount(), fixtureLabel + " left the gate before verifying the route result.");
            assertTrue(completedExchanges.isEmpty(), fixtureLabel + " completed before verifying the route result.");
            if (expectedRequest().getCount() == 1) {
                assertEquals(0, branchStarted.getCount(), fixtureLabel + " did not observe the secondary branch at the gate.");
            }
        }

        private SnapshotFixtureRequestExpectation expectedRequest() {
            return binding.interaction().getExpectedRequest();
        }
    }

    private record RecordedExchange(
            Object body,
            Map<String, Object> headers,
            Map<String, Object> properties,
            boolean failed,
            Throwable failure
    ) {
        private static RecordedExchange capture(Exchange exchange, boolean failed) {
            Throwable failure = exchange.getException();
            if (failure == null) {
                failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
            }
            return new RecordedExchange(
                    exchange.getMessage().getBody(),
                    immutableMap(exchange.getMessage().getHeaders()),
                    immutableMap(exchange.getProperties()),
                    failed,
                    failure
            );
        }
    }
}
