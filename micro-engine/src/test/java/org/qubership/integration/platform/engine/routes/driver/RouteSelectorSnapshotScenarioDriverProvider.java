package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;

import java.util.List;

class RouteSelectorSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final String PROVIDER_ID = "routeSelector";
    private static final String NODE_ID_PARAMETER = "nodeId";
    private static final String DIRECT_ENDPOINT_PREFIX = "direct:";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotScenarioDriver create(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioDriverDefinition definition,
            List<SnapshotScenarioInvocation> invocations
    ) {
        return new RouteSelectorSnapshotScenarioDriver(
                scenario.getId(),
                requiredParameter(definition, NODE_ID_PARAMETER)
        );
    }

    private static String requiredParameter(
            SnapshotScenarioDriverDefinition definition,
            String parameterName
    ) {
        Object value = definition.getParameters().get(parameterName);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(
                    "Route selector driver parameter '" + parameterName + "' is missing."
            );
        }
        return string.strip();
    }

    private static final class RouteSelectorSnapshotScenarioDriver implements SnapshotScenarioDriver {
        private final String scenarioId;
        private final String nodeId;
        private String endpointUri;

        private RouteSelectorSnapshotScenarioDriver(String scenarioId, String nodeId) {
            this.scenarioId = scenarioId;
            this.nodeId = nodeId;
        }

        @Override
        public void configure(CamelContext camelContext) {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) {
            RouteDefinition route = findRoute(
                    routes,
                    scenarioId,
                    nodeId
            );
            String resolvedEndpointUri = route.getInput() == null
                    ? null : route.getInput().getEndpointUri();
            if (resolvedEndpointUri == null || !resolvedEndpointUri.startsWith(DIRECT_ENDPOINT_PREFIX)) {
                throw new IllegalArgumentException(
                        "Route selector scenario '" + scenarioId + "' node '" + nodeId
                                + "' belongs to a route whose input is not a direct endpoint."
                );
            }
            endpointUri = resolvedEndpointUri;
        }

        @Override
        public Exchange execute(
                ProducerTemplate producerTemplate,
                SnapshotScenarioInvocation invocation
        ) {
            if (endpointUri == null) {
                throw new IllegalStateException(
                        "Route selector scenario '" + scenarioId + "' is not configured."
                );
            }
            return producerTemplate.request(endpointUri, request -> {
                request.getMessage().setBody(invocation.getBody());
                invocation.getHeaders().forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
            });
        }

        private static RouteDefinition findRoute(
                List<RouteDefinition> routes,
                String scenarioId,
                String nodeId
        ) {
            return SnapshotRouteNodes.requireSingle(
                    SnapshotRouteNodes.findById(routes, nodeId),
                    "Route selector scenario '" + scenarioId + "' expected one node '" + nodeId + "'"
            ).route();
        }
    }
}
