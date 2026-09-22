package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTaskParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DtoLibrariesBuildServiceTest {

    private static final byte[] JAR_DATA = new byte[] {1, 2, 3};

    private final IntegrationServiceLoadService integrationServiceLoadService =
        mock(IntegrationServiceLoadService.class);
    private final DtoLibraryCompilationService dtoLibraryCompilationService =
        mock(DtoLibraryCompilationService.class);

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

        buildService.buildLibraries(parameters());

        assertArrayEquals(JAR_DATA, Files.readAllBytes(outputDirectory().resolve("orders.jar")));
        assertArrayEquals(JAR_DATA, Files.readAllBytes(outputDirectory().resolve("invoices.jar")));
    }

    @Test
    void loadsTheServicesBeforeBuildingTheirLibraries() throws Exception {
        registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);

        buildService.buildLibraries(parameters());

        var order = inOrder(integrationServiceLoadService, dtoLibraryCompilationService);
        order.verify(integrationServiceLoadService)
            .loadServices(List.of(sourceRoot.toString()), outputDirectory().toString());
        order.verify(dtoLibraryCompilationService).generateJar(any(), any(), any());
    }

    @Test
    void writesNoJarForASpecificationWithoutAGeneratedLibrary() throws Exception {
        registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(null);

        buildService.buildLibraries(parameters());

        assertFalse(Files.exists(outputDirectory().resolve("orders.jar")));
    }

    @Test
    void reportsTheSpecificationThatFailedToBuild() throws Exception {
        registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any()))
            .thenThrow(new IllegalStateException("broken specification"));

        Exception exception = assertThrows(IllegalStateException.class, () -> buildService.buildLibraries(parameters()));

        assertEquals("broken specification", exception.getMessage());
    }

    @Test
    void passesTheOwningServiceAndGroupToTheLibraryBuild() throws Exception {
        IntegrationService service = registerService("payments", specification("orders"));
        when(dtoLibraryCompilationService.generateJar(any(), any(), any())).thenReturn(JAR_DATA);

        buildService.buildLibraries(parameters());

        SpecificationGroup group = service.getSpecificationGroups().iterator().next();
        verify(dtoLibraryCompilationService)
            .generateJar(service, group, group.getSpecifications().iterator().next());
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

    private BuildLibsTaskParameters parameters() {
        return BuildLibsTaskParameters.builder()
            .sourceRoots(List.of(sourceRoot.toString()))
            .outputDirectory(outputDirectory().toString())
            .build();
    }
}
