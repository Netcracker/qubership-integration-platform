package org.qubership.integration.platform.engine.service;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@ApplicationScoped
@Unremovable
public class ExternalLibraryService {
    private final ConcurrentMap<String, URL> libraryMap = new ConcurrentHashMap<>();

    @ConfigProperty(name = "qip.libraries.path", defaultValue = "/tmp/libraries")
    String librariesPath;

    public void addLibrary(String specificationId, byte[] data) {
        log.info("Adding library jar with ID: {}", specificationId);
        try {
            Path path = buildLibraryPath(specificationId);
            saveLibrary(path, data);
            URL url = path.toUri().toURL();
            libraryMap.put(specificationId, url);
        } catch (IOException e) {
            log.error("Failed to add library with ID: {}", specificationId, e);
            throw new RuntimeException("Failed to add library", e);
        }
    }

    public synchronized ClassLoader getClassLoaderForSpecifications(
            Collection<String> specificationIds,
            ClassLoader parentClassLoader
    ) {
        URL[] urls = libraryMap.entrySet().stream()
                .filter(entry -> specificationIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toArray(URL[]::new);

        return urls.length == 0
                ? parentClassLoader
                : new URLClassLoader(urls, parentClassLoader);
    }

    private Path buildLibraryPath(String id) {
        String fileName = id + ".jar";
        return Paths.get(librariesPath).resolve(fileName);
    }

    private void saveLibrary(Path path, byte[] data) throws IOException {
        try {
            Files.createDirectories(path.getParent());
            try (InputStream inputStream = new ByteArrayInputStream(data)) {
                Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Saved library: {}", path);
        } catch (IOException exception) {
            log.error("Failed to save library {}: {}", path, exception.getMessage());
            throw exception;
        }
    }
}
