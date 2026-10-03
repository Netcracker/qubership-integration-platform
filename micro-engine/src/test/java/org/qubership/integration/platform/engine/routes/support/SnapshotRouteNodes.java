package org.qubership.integration.platform.engine.routes.support;

import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SnapshotRouteNodes {
    private SnapshotRouteNodes() {
    }

    public static List<NodeMatch> findById(List<RouteDefinition> routes, String nodeId) {
        List<NodeMatch> matches = new ArrayList<>();
        for (RouteDefinition route : routes) {
            visitOutputs(route, node -> {
                if (nodeId.equals(node.getId())) {
                    matches.add(new NodeMatch(route, node));
                }
            });
        }
        return matches;
    }

    public static List<ToDynamicDefinition> findDynamicEndpoints(ProcessorDefinition<?> parent, String uriPrefix) {
        List<ToDynamicDefinition> endpoints = new ArrayList<>();
        visitOutputs(parent, node -> {
            if (node instanceof ToDynamicDefinition endpoint
                    && endpoint.getUri() != null
                    && endpoint.getUri().startsWith(uriPrefix)) {
                endpoints.add(endpoint);
            }
        });
        return endpoints;
    }

    public static <T> T requireSingle(List<T> nodes, String expectation) {
        if (nodes.size() != 1) {
            throw new IllegalArgumentException(expectation + ", but found " + nodes.size() + ".");
        }
        return nodes.getFirst();
    }

    private static void visitOutputs(ProcessorDefinition<?> parent, Consumer<ProcessorDefinition<?>> visitor) {
        for (ProcessorDefinition<?> node : parent.getOutputs()) {
            visitor.accept(node);
            visitOutputs(node, visitor);
        }
    }

    public record NodeMatch(RouteDefinition route, ProcessorDefinition<?> definition) {
    }
}
