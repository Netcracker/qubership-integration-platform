package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineRoutesPublicNamingStrategyTest {

    @Test
    void proposesNameWithDefaultSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        EngineRoutesPublicNamingStrategy strategy = new EngineRoutesPublicNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-public-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-public-routes", strategy.getName(context));
    }

    @Test
    void proposesNameWithOverriddenSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        EngineRoutesPublicNamingStrategy strategy = new EngineRoutesPublicNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-public");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-public", strategy.getName(context));
    }

    @Test
    void reservesHeadroomForSuffixSoLongBaseNameDoesNotCollideWithSiblingTier() {
        String longBase = "a".repeat(80);
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> longBase;
        EngineRoutesPublicNamingStrategy publicStrategy = new EngineRoutesPublicNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy, "-public-routes");
        EngineRoutesInternalNamingStrategy internalStrategy = new EngineRoutesInternalNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy, "-internal-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        String publicName = publicStrategy.getName(context);
        String internalName = internalStrategy.getName(context);

        assertEquals(63, publicName.length());
        assertEquals(63, internalName.length());
        org.junit.jupiter.api.Assertions.assertNotEquals(publicName, internalName);
        org.junit.jupiter.api.Assertions.assertTrue(publicName.endsWith("-public-routes"));
        org.junit.jupiter.api.Assertions.assertTrue(internalName.endsWith("-internal-routes"));
    }
}
