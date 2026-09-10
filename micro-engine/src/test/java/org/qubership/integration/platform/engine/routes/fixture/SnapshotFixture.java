package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.List;

public interface SnapshotFixture extends AutoCloseable {
    String getDeploymentId();

    default void start() throws Exception {
    }

    default void beforeRouteLoad(CamelContext camelContext) throws Exception {
    }

    void configure(CamelContext camelContext) throws Exception;

    default void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
        configure(camelContext);
    }

    default void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
    }

    default void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
    }

    default void releasePendingWork() throws Exception {
    }

    default void awaitCompletion() throws Exception {
    }

    void verify();

    default void beforeContextStop() throws Exception {
    }

    @Override
    default void close() throws Exception {
    }
}
