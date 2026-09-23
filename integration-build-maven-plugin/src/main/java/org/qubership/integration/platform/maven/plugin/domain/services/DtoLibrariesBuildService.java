package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.function.Failable;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTaskParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class DtoLibrariesBuildService {
    private final IntegrationServiceLoadService integrationServiceLoadService;
    private final IntegrationServiceCatalog integrationServiceCatalog;
    private final DtoLibraryCompilationService dtoLibraryCompilationService;

    @Autowired
    public DtoLibrariesBuildService(
        IntegrationServiceLoadService integrationServiceLoadService,
        IntegrationServiceCatalog integrationServiceCatalog,
        DtoLibraryCompilationService dtoLibraryCompilationService
    ) {
        this.integrationServiceLoadService = integrationServiceLoadService;
        this.integrationServiceCatalog = integrationServiceCatalog;
        this.dtoLibraryCompilationService = dtoLibraryCompilationService;
    }

    public void buildLibraries(TaskContext<BuildLibsTaskParameters> taskContext) throws IOException {
        BuildLibsTaskParameters parameters = taskContext.getTaskParameters();
        integrationServiceLoadService.loadServices(parameters.getSourceRoots(), parameters.getOutputDirectory());
        Failable.stream(integrationServiceCatalog.findAll()).forEach(integrationService ->
            Failable.stream(integrationService.getSpecificationGroups()).forEach(specificationGroup ->
                Failable.stream(specificationGroup.getSpecifications()).forEach(specification ->
                    buildJar(integrationService, specificationGroup, specification, taskContext))));
    }

    private void buildJar(
        IntegrationService integrationService,
        SpecificationGroup specificationGroup,
        ServiceSpecification serviceSpecification,
        TaskContext<BuildLibsTaskParameters> taskContext
    ) throws Exception {
        BuildLibsTaskParameters parameters = taskContext.getTaskParameters();
        byte[] data = dtoLibraryCompilationService.generateJar(integrationService, specificationGroup, serviceSpecification);
        if (isNull(data)) {
            return;
        }
        Path outputDirectory = Paths.get(parameters.getOutputDirectory()).toAbsolutePath();
        if (!outputDirectory.toFile().exists() && !outputDirectory.toFile().mkdirs()) {
            throw new IOException("Failed to create output directory");
        }
        Path filePath = outputDirectory.resolve(buildJarFileName(serviceSpecification));
        Files.write(filePath, data);
        taskContext.getProjectHelper().attachArtifact(taskContext.getProject(), "jar", serviceSpecification.getId(), filePath.toFile());
        log.info("Built DTO library for service '{}' ({}) specification '{}' ({}): {}",
            integrationService.getName(), integrationService.getId(),
            serviceSpecification.getName(), serviceSpecification.getId(), filePath);
    }

    private String buildJarFileName(ServiceSpecification serviceSpecification) {
        return serviceSpecification.getId() + ".jar";
    }
}
