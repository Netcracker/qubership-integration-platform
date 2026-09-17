package org.qubership.integration.platform.maven.plugin.domain.services;

import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.BuildInfoFactory;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MicroDomainResourceBuildContextFactory {
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
        ResourceBuildOptions options
    ) {
        // FIXME createdBy
        BuildInfo buildInfo = buildInfoFactory.createBuildInfo(options, null);
        return ResourceBuildContext.create(buildInfo, integrationServiceCatalog)
            .updateTo(snapshots);
    }
}
