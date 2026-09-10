package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.List;

public interface SnapshotScenarioDriver extends AutoCloseable {
    default void configure(CamelContext camelContext) throws Exception {
    }

    default void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
        configure(camelContext);
    }

    Exchange execute(
            ProducerTemplate producerTemplate,
            SnapshotScenarioInvocation invocation
    ) throws Exception;

    default void beforeContextStop() throws Exception {
    }

    @Override
    default void close() throws Exception {
    }
}
