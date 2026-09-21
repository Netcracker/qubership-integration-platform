package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntegrationServiceLoadServiceTest {

    private final IntegrationSystemReader integrationSystemReader = mock(IntegrationSystemReader.class);
    private final IntegrationServiceCatalogImpl catalog = new IntegrationServiceCatalogImpl();
    private final IntegrationServiceLoadService loadService =
        new IntegrationServiceLoadService(integrationSystemReader, catalog);

    @TempDir
    private Path sourceRoot;

    @TempDir
    private Path secondSourceRoot;

    @ParameterizedTest
    @ValueSource(strings = {
        "service-payments.yaml",
        "service-payments.yml",
        "payments.service.yaml",
        "payments.service.yml"})
    void addsAServiceFileOfEitherNamingFormToTheCatalog(String fileName) throws IOException {
        serviceFile(sourceRoot, fileName, "system-1");

        loadService.loadServices(List.of(sourceRoot.toString()), outputDirectory().toString());

        assertEquals("system-1", catalog.findById("system-1").orElseThrow().getId());
    }

    @Test
    void findsServiceFilesBelowTheSourceRoot() throws IOException {
        serviceFile(sourceRoot.resolve("payments/export"), "service-payments.yaml", "system-1");

        loadService.loadServices(List.of(sourceRoot.toString()), outputDirectory().toString());

        assertTrue(catalog.findById("system-1").isPresent());
    }

    @Test
    void loadsFromEverySourceRoot() throws IOException {
        serviceFile(sourceRoot, "service-payments.yaml", "system-1");
        serviceFile(secondSourceRoot, "service-orders.yaml", "system-2");

        loadService.loadServices(
            List.of(sourceRoot.toString(), secondSourceRoot.toString()), outputDirectory().toString());

        assertEquals(2, catalog.findAllByIds(List.of("system-1", "system-2")).size());
    }

    @Test
    void ignoresFilesThatAreNotServiceExports() throws IOException {
        Files.writeString(sourceRoot.resolve("chain-payments.yaml"), "");
        Files.writeString(sourceRoot.resolve("service-payments.txt"), "");
        Files.writeString(sourceRoot.resolve("services.yaml"), "");
        Files.writeString(sourceRoot.resolve("README.md"), "");

        loadService.loadServices(List.of(sourceRoot.toString()), outputDirectory().toString());

        verifyNoInteractions(integrationSystemReader);
    }

    /** A rebuild reads its own output back in otherwise, because the output directory sits under the source root. */
    @Test
    void ignoresServiceFilesUnderTheOutputDirectory() throws IOException {
        serviceFile(outputDirectory().resolve("generated"), "service-generated.yaml", "system-1");

        loadService.loadServices(List.of(sourceRoot.toString()), outputDirectory().toString());

        verifyNoInteractions(integrationSystemReader);
    }

    @Test
    void reportsTheFileThatFailedToRead() throws IOException {
        Path serviceFile = serviceFile(sourceRoot, "service-payments.yaml", "system-1");
        when(integrationSystemReader.read(serviceFile.toFile()))
            .thenThrow(new IllegalArgumentException("broken service"));

        Exception exception = assertThrows(Exception.class,
            () -> loadService.loadServices(List.of(sourceRoot.toString()), outputDirectory().toString()));

        // Failable rethrows the checked wrapper undeclared, so the named file sits one level down.
        Throwable cause = exception.getCause();
        assertTrue(cause.getMessage().contains(serviceFile.toFile().getAbsolutePath()));
        assertTrue(cause.getMessage().contains("broken service"));
    }

    private Path serviceFile(Path directory, String fileName, String systemId) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(fileName);
        Files.writeString(file, "");
        ImportSystemImpl system = new ImportSystemImpl();
        system.setId(systemId);
        when(integrationSystemReader.read(file.toFile())).thenReturn(system);
        return file;
    }

    private Path outputDirectory() {
        return sourceRoot.resolve("target");
    }
}
