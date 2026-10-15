package org.qubership.integration.platform.engine.camel.predeploy;

import io.quarkus.arc.All;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.model.EndpointRequiredDefinition;
import org.apache.camel.model.Model;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.util.StringHelper;

import java.util.List;

import static org.apache.camel.model.ProcessorDefinitionHelper.filterTypeInOutputs;

/**
 * Runs every {@link PredeployCheck} over the endpoints the route definitions name.
 *
 * <p>It reads definitions rather than started routes because a {@code toD} creates its endpoint only
 * when the first exchange arrives. A {@code toD} URI that contains {@code ${...}} is resolved per
 * exchange, so it is not checked.
 */
@ApplicationScoped
@Unremovable
public class PredeployCheckService {
    private final List<PredeployCheck> checks;

    @Inject
    public PredeployCheckService(@All List<PredeployCheck> checks) {
        this.checks = checks;
    }

    public void checkRoutes(CamelContext context) throws Exception {
        Model model = context.getCamelContextExtension().getContextPlugin(Model.class);
        for (RouteDefinition route : model.getRouteDefinitions()) {
            check(context, route.getInput().getEndpointUri(), true);

            List<ProcessorDefinition<?>> outputs = route.getOutputs();
            for (EndpointRequiredDefinition to : filterTypeInOutputs(outputs, EndpointRequiredDefinition.class)) {
                check(context, to.getEndpointUri(), false);
            }
            for (ToDynamicDefinition toD : filterTypeInOutputs(outputs, ToDynamicDefinition.class)) {
                if (!toD.getUri().contains("${")) {
                    check(context, toD.getUri(), false);
                }
            }
        }
    }

    private void check(CamelContext context, String uri, boolean consumer) throws Exception {
        String scheme = StringHelper.before(uri, ":");
        for (PredeployCheck check : checks) {
            if (check.applicableTo(scheme)) {
                check.check(context.getEndpoint(uri), consumer);
            }
        }
    }
}
