package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.BuildInfoFactory;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.maven.plugin.domain.adapters.SnapshotImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MicroDomainResourceBuildContextFactoryTest {

    private final IntegrationServiceCatalogImpl catalog = new IntegrationServiceCatalogImpl();
    private final MicroDomainResourceBuildContextFactory factory =
        new MicroDomainResourceBuildContextFactory(new BuildInfoFactory(context -> "build-name"), catalog);

    @Test
    void attributesTheBuildToTheMavenPluginAndCarriesTheOptionsAndCatalog() {
        List<Snapshot> snapshots = List.of(new SnapshotImpl());
        ResourceBuildOptions options = ResourceBuildOptions.builder().name("orders").build();

        ResourceBuildContext<List<Snapshot>> context = factory.createResourceBuildContext(snapshots, options);

        assertEquals("maven-plugin", context.getBuildInfo().getCreatedBy());
        assertEquals("build-name", context.getBuildInfo().getName());
        assertSame(options, context.getBuildInfo().getOptions());
        assertSame(catalog, context.getServiceCatalog());
        assertEquals(snapshots, context.getData());
    }
}
