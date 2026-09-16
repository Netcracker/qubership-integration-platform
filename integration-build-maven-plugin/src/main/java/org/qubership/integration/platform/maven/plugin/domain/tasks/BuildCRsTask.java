package org.qubership.integration.platform.maven.plugin.domain.tasks;

import org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.function.BiConsumer;

public class BuildCRsTask implements BiConsumer<ApplicationContext, BuildCRsTaskParameters> {
    @Override
    public void accept(
        ApplicationContext context,
        BuildCRsTaskParameters parameters
    ) {
        MicroDomainResourcesBuildService microDomainResourcesBuildService
            = context.getBean(MicroDomainResourcesBuildService.class);
        try {
            microDomainResourcesBuildService.buildResources(parameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
