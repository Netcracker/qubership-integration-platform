package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.model.RouteDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SnapshotScenarioDriverRegistry {
    private final Map<String, SnapshotScenarioDriverProvider> providersById;

    public SnapshotScenarioDriverRegistry(List<SnapshotScenarioDriverProvider> providers) {
        Map<String, SnapshotScenarioDriverProvider> providersById = new LinkedHashMap<>();
        for (SnapshotScenarioDriverProvider provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException("Snapshot scenario driver provider cannot be null.");
            }
            String providerId = provider.getId();
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("Snapshot scenario driver provider id is missing.");
            }
            if (providersById.putIfAbsent(providerId, provider) != null) {
                throw new IllegalArgumentException(
                        "Snapshot scenario driver registry contains duplicate provider id '" + providerId + "'."
                );
            }
        }
        this.providersById = Collections.unmodifiableMap(providersById);
    }

    public static SnapshotScenarioDriverRegistry withDefaultProviders() {
        return new SnapshotScenarioDriverRegistry(List.of(
                new HttpTriggerSnapshotScenarioDriverProvider(),
                new FileReadSnapshotScenarioDriverProvider(),
                new RouteSelectorSnapshotScenarioDriverProvider()
        ));
    }

    public SnapshotScenarioDriver createDriver(
            SnapshotExecutionTarget executionTarget,
            SnapshotExecutionScenario scenario
    ) {
        Map<DriverKey, ResolvedTarget> targetsByKey = new LinkedHashMap<>();
        Map<DriverKey, List<SnapshotScenarioInvocation>> invocationsByTarget = new LinkedHashMap<>();
        Map<String, DriverKey> targetKeysByInvocationId = new LinkedHashMap<>();

        for (SnapshotScenarioInvocation invocation : scenario.getInvocations()) {
            ResolvedTarget target = resolveTarget(executionTarget, scenario, invocation);
            SnapshotScenarioDriverProvider provider = target.driver() == null
                    ? null : providersById.get(target.driver().getProvider());
            DriverKey key = DriverKey.from(target, provider);
            targetsByKey.putIfAbsent(key, target);
            invocationsByTarget.computeIfAbsent(key, ignored -> new ArrayList<>()).add(invocation);
            targetKeysByInvocationId.put(invocation.getId(), key);
        }

        List<SnapshotScenarioDriver> drivers = new ArrayList<>(targetsByKey.size());
        Map<DriverKey, SnapshotScenarioDriver> driversByKey = new LinkedHashMap<>();
        targetsByKey.forEach((key, target) -> {
            SnapshotScenarioDriver driver = createDriver(
                    scenario,
                    target,
                    List.copyOf(invocationsByTarget.get(key))
            );
            drivers.add(driver);
            driversByKey.put(key, driver);
        });

        Map<String, SnapshotScenarioDriver> driversByInvocationId = new LinkedHashMap<>();
        targetKeysByInvocationId.forEach((invocationId, key) ->
                driversByInvocationId.put(invocationId, driversByKey.get(key)));
        return new DispatchingSnapshotScenarioDriver(
                List.copyOf(drivers),
                Collections.unmodifiableMap(driversByInvocationId)
        );
    }

    private SnapshotScenarioDriver createDriver(
            SnapshotExecutionScenario scenario,
            ResolvedTarget target,
            List<SnapshotScenarioInvocation> invocations
    ) {
        if (target.driver() == null) {
            return new CamelEndpointSnapshotScenarioDriver(target.endpointUri());
        }

        SnapshotScenarioDriverProvider provider = providersById.get(target.driver().getProvider());
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Snapshot scenario '" + scenario.getId() + "' uses unknown driver provider '"
                            + target.driver().getProvider() + "'."
            );
        }
        return provider.create(scenario, target.driver(), invocations);
    }

    private static ResolvedTarget resolveTarget(
            SnapshotExecutionTarget executionTarget,
            SnapshotExecutionScenario scenario,
            SnapshotScenarioInvocation invocation
    ) {
        if (invocation.getEndpointUri() != null || invocation.getDriver() != null) {
            return new ResolvedTarget(
                    invocation.getEndpointUri(),
                    resolveDriver(executionTarget, invocation.getDriver())
            );
        }
        return new ResolvedTarget(
                scenario.getEndpointUri(),
                resolveDriver(executionTarget, scenario.getDriver())
        );
    }

    private static SnapshotScenarioDriverDefinition resolveDriver(
            SnapshotExecutionTarget executionTarget,
            SnapshotScenarioDriverDefinition driver
    ) {
        if (driver == null) {
            return null;
        }
        return driver.resolveNodeId(executionTarget.getSubjectDeployment());
    }

    private record ResolvedTarget(
            String endpointUri,
            SnapshotScenarioDriverDefinition driver
    ) {
    }

    private record DriverKey(
            String endpointUri,
            String provider,
            Map<String, Object> parameters
    ) {
        private static DriverKey from(ResolvedTarget target, SnapshotScenarioDriverProvider provider) {
            if (target.driver() == null) {
                return new DriverKey(target.endpointUri(), null, Map.of());
            }
            return new DriverKey(
                    null,
                    target.driver().getProvider(),
                    provider == null ? target.driver().getParameters()
                            : provider.configurationParameters(target.driver())
            );
        }
    }

    private static final class DispatchingSnapshotScenarioDriver implements SnapshotScenarioDriver {
        private final List<SnapshotScenarioDriver> drivers;
        private final Map<String, SnapshotScenarioDriver> driversByInvocationId;

        private DispatchingSnapshotScenarioDriver(
                List<SnapshotScenarioDriver> drivers,
                Map<String, SnapshotScenarioDriver> driversByInvocationId
        ) {
            this.drivers = drivers;
            this.driversByInvocationId = driversByInvocationId;
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            for (SnapshotScenarioDriver driver : drivers) {
                driver.configure(camelContext);
            }
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            for (SnapshotScenarioDriver driver : drivers) {
                driver.configure(camelContext, routes);
            }
        }

        @Override
        public Exchange execute(
                ProducerTemplate producerTemplate,
                SnapshotScenarioInvocation invocation
        ) throws Exception {
            SnapshotScenarioDriver driver = driversByInvocationId.get(invocation.getId());
            if (driver == null) {
                throw new IllegalStateException(
                        "Snapshot scenario invocation '" + invocation.getId() + "' does not have a driver."
                );
            }
            return driver.execute(producerTemplate, invocation);
        }

        @Override
        public void beforeContextStop() throws Exception {
            Exception cleanupFailure = null;
            for (int index = drivers.size() - 1; index >= 0; index--) {
                try {
                    drivers.get(index).beforeContextStop();
                } catch (Exception exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        @Override
        public void close() throws Exception {
            Exception cleanupFailure = null;
            for (int index = drivers.size() - 1; index >= 0; index--) {
                try {
                    drivers.get(index).close();
                } catch (Exception exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private static final class CamelEndpointSnapshotScenarioDriver implements SnapshotScenarioDriver {
        private final String endpointUri;

        private CamelEndpointSnapshotScenarioDriver(String endpointUri) {
            this.endpointUri = endpointUri;
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
    }
}
