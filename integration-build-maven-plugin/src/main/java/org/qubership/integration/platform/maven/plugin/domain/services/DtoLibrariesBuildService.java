package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTaskParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
public class DtoLibrariesBuildService {
    private final IntegrationServiceLoadService integrationServiceLoadService;
    private final DtoLibraryCompilationService dtoLibraryCompilationService;

    @Autowired
    public DtoLibrariesBuildService(
        IntegrationServiceLoadService integrationServiceLoadService,
        DtoLibraryCompilationService dtoLibraryCompilationService
    ) {
        this.integrationServiceLoadService = integrationServiceLoadService;
        this.dtoLibraryCompilationService = dtoLibraryCompilationService;
    }

    public void buildLibraries(BuildLibsTaskParameters parameters) throws IOException {
        integrationServiceLoadService.loadServices(parameters.getSourceRoots(), parameters.getOutputDirectory());

        // TODO
    }
}
