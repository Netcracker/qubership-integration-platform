package org.qubership.integration.platform.maven.plugin.domain.tasks;

import org.qubership.integration.platform.maven.plugin.domain.services.DtoLibrariesBuildService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.function.BiConsumer;

public class BuildLibsTask implements BiConsumer<ApplicationContext, BuildLibsTaskParameters> {
    @Override
    public void accept(ApplicationContext context, BuildLibsTaskParameters parameters) {
        DtoLibrariesBuildService librariesBuildService
            = context.getBean(DtoLibrariesBuildService.class);
        try {
            librariesBuildService.buildLibraries(parameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
