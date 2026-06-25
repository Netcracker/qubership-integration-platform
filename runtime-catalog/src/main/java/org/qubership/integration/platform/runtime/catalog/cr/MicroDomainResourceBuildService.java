package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.ResourceBuildError;
import org.qubership.integration.platform.camelk.model.ResourceBuilder;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class MicroDomainResourceBuildService {
    private final ResourceBuildService resourceBuildService;
    private final MicroDomainResourceBuildContextFactory buildContextFactory;

    @Autowired
    public MicroDomainResourceBuildService(
            ResourceBuildService resourceBuildService,
            MicroDomainResourceBuildContextFactory buildContextFactory
    ) {
        this.resourceBuildService = resourceBuildService;
        this.buildContextFactory = buildContextFactory;
    }

    public String buildResources(ResourceBuildRequest request, boolean appendToExisting) {
        ResourceBuildContext<List<Snapshot>> buildContext =
                buildContextFactory.createResourceBuildContext(request, appendToExisting);
        return resourceBuildService.buildResources(buildContext);
    }
}
