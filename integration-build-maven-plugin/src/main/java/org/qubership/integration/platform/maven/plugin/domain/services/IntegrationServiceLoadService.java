package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.function.Failable;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.io.readers.system.ServiceFileUtil;
import org.qubership.integration.platform.maven.plugin.domain.adapters.ImportSystemAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Stream;

import static org.qubership.integration.platform.maven.plugin.domain.util.FileUtil.isInDirectory;
import static org.qubership.integration.platform.maven.plugin.domain.util.FileUtil.processFile;

@Slf4j
@Service
public class IntegrationServiceLoadService {
    private final IntegrationSystemReader integrationSystemReader;
    private final IntegrationServiceCatalogImpl integrationServiceCatalog;

    @Autowired
    public IntegrationServiceLoadService(
        IntegrationSystemReader integrationSystemReader,
        IntegrationServiceCatalogImpl integrationServiceCatalog
    ) {
        this.integrationSystemReader = integrationSystemReader;
        this.integrationServiceCatalog = integrationServiceCatalog;
    }

    public void loadServices(Collection<String> sourceRoots, String outputDirectory) {
        Path outputDirectoryPath = Path.of(outputDirectory);
        Stream<File> serviceFiles = Failable.stream(sourceRoots)
            .map(File::new)
            .map(sourceRoot -> listServiceFiles(sourceRoot, outputDirectoryPath))
            .stream()
            .flatMap(Collection::stream);
        Failable.stream(serviceFiles)
            .map(file -> processFile(file, integrationSystemReader::read))
            .map(ImportSystemAdapter::new)
            .stream()
            .forEach(integrationServiceCatalog::addService);
    }

    private Collection<File> listServiceFiles(File directory, Path outputDirectory) throws IOException {
        if (!directory.isDirectory()) {
            log.warn("Skipping source root '{}': not a directory.", directory);
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            return paths
                .filter(path -> !isInDirectory(path, outputDirectory))
                .filter(Files::isRegularFile)
                .filter(file -> ServiceFileUtil.isIntegrationSystemFile(file.getFileName().toString()))
                .map(Path::toFile)
                .toList();
        }
    }
}
