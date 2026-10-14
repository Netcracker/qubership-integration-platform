package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.PolicyDefinition;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SnapshotFixtureRouteScope {
    private SnapshotFixtureRouteScope() {
    }

    public static void bind(
            CamelContext context,
            List<RouteDefinition> routes,
            String scopeId,
            Map<String, ?> collaborators
    ) {
        Map<String, String> references = new LinkedHashMap<>();
        collaborators.forEach((name, collaborator) -> {
            String scopedName = "snapshot-fixture:" + scopeId + ':' + name;
            context.getRegistry().bind(scopedName, collaborator);
            references.put(name, scopedName);
        });
        routes.forEach(route -> replaceReferences(route, references));
    }

    private static void replaceReferences(ProcessorDefinition<?> definition, Map<String, String> references) {
        if (definition instanceof ProcessDefinition process && references.containsKey(process.getRef())) {
            process.setRef(references.get(process.getRef()));
        }
        if (definition instanceof PolicyDefinition policy && references.containsKey(policy.getRef())) {
            policy.setRef(references.get(policy.getRef()));
        }
        definition.getOutputs().forEach(output -> replaceReferences(output, references));
        if (definition instanceof CircuitBreakerDefinition breaker && breaker.getOnFallback() != null) {
            breaker.getOnFallback().getOutputs().forEach(output -> replaceReferences(output, references));
        }
    }
}
