package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class CamelEndpointSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "camel-endpoint";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        bindings.forEach(binding -> binding.interactionsByInvocationId().values()
                .forEach(interaction -> validateInteraction(binding.definition().getId(), interaction)));
        return new CamelEndpointSnapshotFixture(deploymentId, bindings);
    }

    private static void validateInteraction(
            String fixtureId,
            SnapshotFixtureInteraction interaction
    ) {
        SnapshotFixtureResponse response = interaction.getResponse();
        if (response != null && response.hasExplicitStatus()) {
            throw new IllegalArgumentException(
                    "Camel endpoint fixture '" + fixtureId + "' does not support response status."
            );
        }
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        if (expectation != null
                && (expectation.getMethod() != null
                || expectation.getPath() != null
                || expectation.getQuery() != null
                || expectation.getDestination() != null
                || expectation.getKey() != null)) {
            throw new IllegalArgumentException(
                    "Camel endpoint fixture '" + fixtureId
                            + "' does not support request method, path, query, destination, or key expectations."
            );
        }
    }

    private static final class CamelEndpointSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, List<RecordedRequest>> requestsByFixtureId = new LinkedHashMap<>();

        private CamelEndpointSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            this.bindings.forEach(binding ->
                    requestsByFixtureId.put(binding.definition().getId(), new CopyOnWriteArrayList<>()));
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
                        binding.definition()
                );
                bindingsByRoute.computeIfAbsent(route, ignored -> new ArrayList<>()).add(binding);
            }

            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId())
                                .replace()
                                .process(exchange -> processRequest(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void verify() {
            for (SnapshotFixtureBinding binding : bindings) {
                verifyRequests(binding);
            }
        }

        private void processRequest(SnapshotFixtureBinding binding, Exchange exchange) {
            requestsByFixtureId.get(binding.definition().getId()).add(new RecordedRequest(
                    exchange.getMessage().getBody(),
                    immutableMap(exchange.getMessage().getHeaders()),
                    immutableMap(exchange.getProperties())
            ));

            SnapshotFixtureResponse response = binding.interaction().getResponse();
            if (response == null) {
                return;
            }
            exchange.getMessage().setBody(response.getBody());
            response.getHeaders().forEach(exchange.getMessage()::setHeader);
            response.getProperties().forEach(exchange::setProperty);
        }

        private void verifyRequests(SnapshotFixtureBinding binding) {
            SnapshotFixtureRequestExpectation expectation = binding.interaction().getExpectedRequest();
            if (expectation == null) {
                return;
            }

            String fixtureId = binding.definition().getId();
            List<RecordedRequest> requests = requestsByFixtureId.get(fixtureId);
            assertEquals(
                    expectation.getCount(),
                    requests.size(),
                    () -> "Snapshot fixture '" + fixtureId + "' received an unexpected number of requests."
            );
            for (int index = 0; index < requests.size(); index++) {
                RecordedRequest request = requests.get(index);
                int requestNumber = index + 1;
                if (expectation.hasBody()) {
                    assertEquals(
                            expectation.getBody(),
                            request.body(),
                            () -> "Snapshot fixture '" + fixtureId + "' request " + requestNumber
                                    + " has an unexpected body."
                    );
                }
                SnapshotValueAssertions.assertMapValues(
                        expectation.getHeaders(),
                        request.headers(),
                        "Snapshot fixture '" + fixtureId + "' request " + requestNumber
                                + " has an unexpected header"
                );
                SnapshotValueAssertions.assertMapValues(
                        expectation.getProperties(),
                        request.properties(),
                        "Snapshot fixture '" + fixtureId + "' request " + requestNumber
                                + " has an unexpected property"
                );
            }
        }

        private static RouteDefinition findRoute(
                List<RouteDefinition> routes,
                SnapshotFixtureDefinition definition
        ) {
            return SnapshotRouteNodes.requireSingle(
                    SnapshotRouteNodes.findById(routes, definition.getNodeId()),
                    "Snapshot fixture '" + definition.getId() + "' expected one node '"
                            + definition.getNodeId() + "' in deployment '" + definition.getDeploymentId() + "'"
            ).route();
        }

        private record RecordedRequest(
                Object body,
                Map<String, Object> headers,
                Map<String, Object> properties
        ) {
        }
    }
}
