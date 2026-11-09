package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.support.DefaultExchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChainConcurrentSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    @Override
    public String getId() {
        return "chain-concurrent";
    }

    @Override
    public SnapshotScenarioDriver create(SnapshotExecutionScenario scenario, SnapshotScenarioDriverDefinition definition,
            List<SnapshotScenarioInvocation> invocations) {
        Map<String, Object> parameters = definition.getParameters();
        if (definition.getSourceElementId() == null
                || !Set.of("nodeId", "requests", "failureIndex").containsAll(parameters.keySet())
                || !(parameters.get("requests") instanceof Number requests)
                || requests.intValue() < 2 || requests.doubleValue() != requests.intValue()) {
            throw new IllegalArgumentException("Concurrent Chain calls require a sourceElementId and an integer requests >= 2.");
        }
        int count = requests.intValue();
        int failureIndex = -1;
        if (parameters.containsKey("failureIndex")) {
            if (!(parameters.get("failureIndex") instanceof Number index) || index.intValue() < 0
                    || index.intValue() >= count || index.doubleValue() != index.intValue()) {
                throw new IllegalArgumentException("Chain failureIndex must identify one of the concurrent requests.");
            }
            failureIndex = index.intValue();
        }
        return new Driver("cip-chain:" + definition.getSourceElementId(), count, failureIndex);
    }

    private record Driver(String uri, int requests, int failureIndex) implements SnapshotScenarioDriver {
        @Override
        public void configure(CamelContext context, List<RouteDefinition> routes) {
            assertTrue(routes.stream().anyMatch(route -> uri.equals(route.getInput().getUri().split("\\?", 2)[0])),
                    "Concurrent Chain calls must enter a generated trigger.");
        }

        @Override
        public Exchange execute(ProducerTemplate producer, SnapshotScenarioInvocation invocation) throws Exception {
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Map<String, Object>>> calls = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int index = 0; index < requests; index++) {
                    int requestIndex = index;
                    calls.add(executor.submit(() -> {
                        ClassLoader before = Thread.currentThread().getContextClassLoader();
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS), "Concurrent Chain callers must start together.");
                        String requestId = invocation.getId() + '-' + requestIndex;
                        Exchange response = producer.request(uri, exchange -> {
                            exchange.getMessage().setBody(invocation.getBody() + "-" + requestIndex);
                            exchange.getMessage().setHeaders(new LinkedHashMap<>(invocation.getHeaders()));
                            invocation.getProperties().forEach(exchange::setProperty);
                            exchange.getMessage().setHeader("X-Chain-Request", requestId);
                            exchange.setProperty("chainRequestId", requestId);
                            exchange.setProperty("failChild", requestIndex == failureIndex);
                            exchange.setProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION, requestIndex % 2 == 0);
                        });
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("requestId", requestId);
                        result.put("body", response.getMessage().getBody());
                        result.put("headerRequestId", response.getMessage().getHeader("X-Chain-Request"));
                        result.put("propertyRequestId", response.getProperty("chainRequestId"));
                        result.put("sessionFlag", response.getProperty(CamelConstants.Properties.IS_CHAIN_CALL_TRIGGERED_SESSION));
                        result.put("classLoaderRestored", before == Thread.currentThread().getContextClassLoader());
                        result.put("afterCallReached", response.getProperty("afterCallReached"));
                        result.put("testProperty", response.getProperty("testProperty"));
                        Throwable failure = response.getException();
                        Map<String, Object> reportedFailure = null;
                        if (failure != null) {
                            reportedFailure = new LinkedHashMap<>();
                            reportedFailure.put("type", failure.getClass().getName());
                            reportedFailure.put("message", failure.getMessage());
                            reportedFailure.put("cause", failure.getCause() == null ? null : failure.getCause().getClass().getName());
                        }
                        result.put("failure", reportedFailure);
                        return result;
                    }));
                }
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS), "All Chain callers must reach the publication barrier.");
                } finally {
                    start.countDown();
                }
                List<Map<String, Object>> responses = new ArrayList<>();
                for (Future<Map<String, Object>> call : calls) {
                    responses.add(call.get(10, TimeUnit.SECONDS));
                }
                assertEquals(requests, responses.size(), "Every Chain call must complete independently.");
                Exchange result = new DefaultExchange(producer.getCamelContext());
                result.getMessage().setBody(responses);
                return result;
            } finally {
                start.countDown();
                calls.forEach(call -> call.cancel(true));
            }
        }
    }
}
