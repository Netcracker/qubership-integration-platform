package org.qubership.integration.platform.maven.plugin.domain.services;

import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTaskParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DtoLibrariesBuildServiceTest {

    private static final byte[] JAR_DATA = new byte[] {1, 2, 3};

    private final IntegrationServiceLoadService integrationServiceLoadService =
        mock(IntegrationServiceLoadService.class);
    private final DtoLibraryCompilationService dtoLibraryCompilationService =
        mock(DtoLibraryCompilationService.class);

    private final MavenProject project = mock(MavenProject.class);
    private final MavenProjectHelper projectHelper = mock(MavenProjectHelper.class);

    // Real, not a mock: these tests build on the services the load leaves in the catalog.
    private final IntegrationServiceCatalogImpl catalog = new IntegrationServiceCatalogImpl();

    private final DtoLibrariesBuildService buildService = new DtoLibrariesBuildService(
        integrationServiceLoadService, catalog, dtoLibraryCompilationService);

    @TempDir
    private Path sourceRoot;

    @Test
    void writesOneJarPerSpecificationIntoTheOutputDirectory() throws Exception {
        registerService("payments", specification("orders"), specification("invoices"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);

        buildService.buildLibraries(taskContext());

        assertArrayEquals(JAR_DATA, Files.readAllBytes(outputDirectory().resolve("orders.jar")));
        assertArrayEquals(JAR_DATA, Files.readAllBytes(outputDirectory().resolve("invoices.jar")));
    }

    @Test
    void attachesEachJarClassifiedBySpecificationId() throws Exception {
        registerService("payments", specification("orders"), specification("invoices"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);

        buildService.buildLibraries(taskContext());

        verify(projectHelper).attachArtifact(
            project, "jar", "orders", outputDirectory().resolve("orders.jar").toAbsolutePath().toFile());
        verify(projectHelper).attachArtifact(
            project, "jar", "invoices", outputDirectory().resolve("invoices.jar").toAbsolutePath().toFile());
    }

    @Test
    void loadsTheServicesBeforeBuildingTheirLibraries() throws Exception {
        registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);

        buildService.buildLibraries(taskContext());

        var order = inOrder(integrationServiceLoadService, dtoLibraryCompilationService);
        order.verify(integrationServiceLoadService)
            .loadServices(List.of(sourceRoot.toString()), outputDirectory().toString(), true);
        order.verify(dtoLibraryCompilationService).generateJar(any(), any(), any());
    }

    @Test
    void writesNoJarForASpecificationWithoutAGeneratedLibrary() throws Exception {
        registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(null);

        buildService.buildLibraries(taskContext());

        assertFalse(Files.exists(outputDirectory().resolve("orders.jar")));
        verifyNoInteractions(projectHelper);
    }

    @Test
    void reportsTheSpecificationThatFailedToBuild() throws Exception {
        registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any()))
            .thenThrow(new IllegalStateException("broken specification"));

        Exception exception = assertThrows(IllegalStateException.class, () -> buildService.buildLibraries(taskContext()));

        assertEquals("broken specification", exception.getMessage());
    }

    @Test
    void passesTheOwningServiceAndGroupToTheLibraryBuild() throws Exception {
        IntegrationService service = registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);

        buildService.buildLibraries(taskContext());

        SpecificationGroup group = service.getSpecificationGroups().iterator().next();
        verify(dtoLibraryCompilationService)
            .generateJar(service, group, group.getSpecifications().iterator().next());
    }

    @Test
    void buildsTheOtherServicesWhenOneFailsAndNotFailingFast() throws Exception {
        ServiceSpecification broken = specification("orders");
        registerService("payments", broken);
        registerService("billing", specification("invoices"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);
        when(dtoLibraryCompilationService.generateJar(any(), any(), eq(broken)))
            .thenThrow(new IllegalStateException("broken specification"));

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> buildService.buildLibraries(taskContext(false)));

        assertEquals("Failed to build DTO libraries for services: 1 error(s) occurred", exception.getMessage());
        assertTrue(Files.exists(outputDirectory().resolve("invoices.jar")));
        assertFalse(Files.exists(outputDirectory().resolve("orders.jar")));
    }

    private IntegrationService registerService(String id, ServiceSpecification... specifications) {
        SpecificationGroup group = mock(SpecificationGroup.class);
        when(group.getSpecifications()).thenReturn(List.of(specifications));
        IntegrationService service = mock(IntegrationService.class);
        when(service.getId()).thenReturn(id);
        when(service.getName()).thenReturn(id);
        when(service.getSpecificationGroups()).thenReturn(List.of(group));
        catalog.addService(service);
        return service;
    }

    private static ServiceSpecification specification(String id) {
        ServiceSpecification specification = mock(ServiceSpecification.class);
        when(specification.getId()).thenReturn(id);
        when(specification.getName()).thenReturn(id);
        return specification;
    }

    private Path outputDirectory() {
        return sourceRoot.resolve("target");
    }

    private TaskContext<BuildLibsTaskParameters> taskContext() {
        return taskContext(true);
    }

    private TaskContext<BuildLibsTaskParameters> taskContext(boolean failFast) {
        BuildLibsTaskParameters parameters = BuildLibsTaskParameters.builder()
            .sourceRoots(List.of(sourceRoot.toString()))
            .outputDirectory(outputDirectory().toString())
            .failFast(failFast)
            .build();
        return TaskContext.<BuildLibsTaskParameters>builder()
            .project(project)
            .projectHelper(projectHelper)
            .taskParameters(parameters)
            .build();
    }
}
