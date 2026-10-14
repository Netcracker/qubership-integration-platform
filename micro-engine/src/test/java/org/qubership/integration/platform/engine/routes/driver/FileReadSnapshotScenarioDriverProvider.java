package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.PollEnrichDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

class FileReadSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final String PROVIDER_ID = "file-read";
    private static final String ENDPOINT_URI_PARAMETER = "endpointUri";
    private static final String NODE_ID_PARAMETER = "nodeId";
    private static final String DEFAULT_POLL_TIMEOUT_MILLIS = "1000";

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
        return new FileReadSnapshotScenarioDriver(
                scenario.getId(),
                requiredParameter(definition, ENDPOINT_URI_PARAMETER),
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
                    "File read driver parameter '" + parameterName + "' is missing."
            );
        }
        return string.strip();
    }

    private static final class FileReadSnapshotScenarioDriver implements SnapshotScenarioDriver {
        private final String scenarioId;
        private final String endpointUri;
        private final String nodeId;

        private FileReadSnapshotScenarioDriver(
                String scenarioId,
                String endpointUri,
                String nodeId
        ) {
            this.scenarioId = scenarioId;
            this.endpointUri = endpointUri;
            this.nodeId = nodeId;
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            NodeMatch match = findPollEnrichRoute(
                    routes,
                    scenarioId,
                    nodeId
            );
            PollEnrichDefinition pollEnrich = (PollEnrichDefinition) match.definition();
            if (requiresFiniteTimeout(pollEnrich.getTimeout())) {
                pollEnrich.setTimeout(DEFAULT_POLL_TIMEOUT_MILLIS);
            }
            AdviceWith.adviceWith(camelContext, match.route(), false, advice ->
                    advice.weaveById(nodeId)
                            .after()
                            .convertBodyTo(String.class));
        }

        @Override
        public Exchange execute(
                ProducerTemplate producerTemplate,
                SnapshotScenarioInvocation invocation
        ) {
            return producerTemplate.request(endpointUri, request -> {
                request.getMessage().setBody(invocation.getBody());
                invocation.getHeaders().forEach(request.getMessage()::setHeader);
                invocation.getProperties().forEach(request::setProperty);
            });
        }

        private static NodeMatch findPollEnrichRoute(
                List<RouteDefinition> routes,
                String scenarioId,
                String nodeId
        ) {
            List<NodeMatch> matches = new ArrayList<>();
            for (RouteDefinition route : routes) {
                collectMatches(route, route.getOutputs(), nodeId, matches);
            }
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "File read scenario '" + scenarioId + "' expected one node '" + nodeId
                                + "', but found " + matches.size() + "."
                );
            }

            NodeMatch match = matches.getFirst();
            if (!(match.definition() instanceof PollEnrichDefinition)) {
                throw new IllegalArgumentException(
                        "File read scenario '" + scenarioId + "' node '" + nodeId
                                + "' is not a pollEnrich definition."
                );
            }
            return match;
        }

        private static boolean requiresFiniteTimeout(String timeout) {
            if (timeout == null || timeout.isBlank()) {
                return true;
            }
            try {
                return new BigInteger(timeout.strip()).signum() < 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private static void collectMatches(
                RouteDefinition route,
                List<ProcessorDefinition<?>> definitions,
                String nodeId,
                List<NodeMatch> matches
        ) {
            for (ProcessorDefinition<?> definition : definitions) {
                if (nodeId.equals(definition.getId())) {
                    matches.add(new NodeMatch(route, definition));
                }
                collectMatches(route, definition.getOutputs(), nodeId, matches);
            }
        }

        private record NodeMatch(
                RouteDefinition route,
                ProcessorDefinition<?> definition
        ) {
        }
    }
}
