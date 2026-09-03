package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildContextFactory.BuildContextWithObservations;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public BuiltResources buildResources(ResourceBuildRequest request, boolean appendToExisting) {
        BuildContextWithObservations built =
                buildContextFactory.createResourceBuildContext(request, appendToExisting);
        return new BuiltResources(
                resourceBuildService.buildResources(built.context()),
                built.observations());
    }
}
