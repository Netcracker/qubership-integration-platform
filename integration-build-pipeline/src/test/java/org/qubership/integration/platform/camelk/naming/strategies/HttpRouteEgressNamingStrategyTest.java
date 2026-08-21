package org.qubership.integration.platform.camelk.naming.strategies;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.chain.model.Snapshot;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpRouteEgressNamingStrategyTest {

    @Test
    void proposesNameWithDefaultSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        HttpRouteEgressNamingStrategy strategy = new HttpRouteEgressNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-egress-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-egress-routes", strategy.getName(context));
    }

    @Test
    void proposesNameWithOverriddenSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        HttpRouteEgressNamingStrategy strategy = new HttpRouteEgressNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-egress");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-egress", strategy.getName(context));
    }
}
