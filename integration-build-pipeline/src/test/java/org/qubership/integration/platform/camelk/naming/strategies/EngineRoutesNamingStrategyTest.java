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
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRoutesNamingStrategyTest {

    @Test
    void proposesNameWithDefaultSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        EngineRoutesNamingStrategy strategy = new EngineRoutesNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-routes", strategy.getName(context));
    }

    @Test
    void proposesNameWithOverriddenSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        EngineRoutesNamingStrategy strategy = new EngineRoutesNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-engine-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-engine-routes", strategy.getName(context));
    }

    @Test
    void truncatesBaseSoNameNeverExceedsTheLengthLimit() {
        String longBase = "a".repeat(80);
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> longBase;
        EngineRoutesNamingStrategy strategy = new EngineRoutesNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy, "-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        String name = strategy.getName(context);

        assertEquals(63, name.length());
        assertTrue(name.endsWith("-routes"));
    }
}
