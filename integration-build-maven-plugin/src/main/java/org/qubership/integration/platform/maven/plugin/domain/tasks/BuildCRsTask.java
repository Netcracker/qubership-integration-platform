package org.qubership.integration.platform.maven.plugin.domain.tasks;

import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.function.BiConsumer;

public class BuildCRsTask implements BiConsumer<ApplicationContext, TaskContext<BuildCRsTaskParameters>> {
    @Override
    public void accept(
        ApplicationContext context,
        TaskContext<BuildCRsTaskParameters> taskContext
    ) {
        MicroDomainResourcesBuildService microDomainResourcesBuildService
            = context.getBean(MicroDomainResourcesBuildService.class);
        try {
            microDomainResourcesBuildService.buildResources(taskContext);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
