package org.qubership.integration.platform.maven.plugin.domain.tasks;

import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.services.DtoLibrariesBuildService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.function.BiConsumer;

public class BuildLibsTask implements BiConsumer<ApplicationContext, TaskContext<BuildLibsTaskParameters>> {
    @Override
    public void accept(ApplicationContext context, TaskContext<BuildLibsTaskParameters> taskContext) {
        DtoLibrariesBuildService librariesBuildService
            = context.getBean(DtoLibrariesBuildService.class);
        try {
            librariesBuildService.buildLibraries(taskContext);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
