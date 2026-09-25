package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.BuildInfoFactory;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class MicroDomainResourceBuildContextFactory {
    /** A Maven build has no authenticated user, so the resources name the producer instead. */
    private static final String CREATED_BY = "maven-plugin";

    private final BuildInfoFactory buildInfoFactory;
    private final IntegrationServiceCatalog integrationServiceCatalog;

    @Autowired
    public MicroDomainResourceBuildContextFactory(
        BuildInfoFactory buildInfoFactory,
        IntegrationServiceCatalog integrationServiceCatalog
    ) {
        this.buildInfoFactory = buildInfoFactory;
        this.integrationServiceCatalog = integrationServiceCatalog;
    }

    public ResourceBuildContext<List<Snapshot>> createResourceBuildContext(
        List<Snapshot> snapshots,
        ResourceBuildOptions options,
        Instant buildTimestamp
    ) {
        BuildInfo buildInfo = buildInfoFactory.createBuildInfo(options, CREATED_BY, buildTimestamp);
        return ResourceBuildContext.create(buildInfo, integrationServiceCatalog)
            .updateTo(snapshots);
    }
}
