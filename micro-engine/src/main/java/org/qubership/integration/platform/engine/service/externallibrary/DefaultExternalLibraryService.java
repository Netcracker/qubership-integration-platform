package org.qubership.integration.platform.engine.service.externallibrary;

import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;
import org.qubership.integration.platform.engine.catalog.RuntimeCatalogService;
import org.qubership.integration.platform.engine.events.ExternalLibrariesUpdatedEvent;
import org.qubership.integration.platform.engine.events.UpdateEvent;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

@Slf4j
@ApplicationScoped
@DefaultBean
public class DefaultExternalLibraryService implements ExternalLibraryService {
    private static final String RUNTIME_CATALOG_DOWNLOAD_DTO_JAR_ENDPOINT_TEMPLATE = "/v1/models/{id}/dto/jar";
    private static final String LIBRARIES_PATH = "/tmp/cip-engine-libraries";

    @RestClient
    @Inject
    RuntimeCatalogService runtimeCatalogService;

    @Inject
    EventBus eventBus;

    @Inject
    @All
    List<Supplier<Collection<Path>>> librarySuppliers;

    private volatile ClassLoader shellClassLoader;
    private volatile Collection<CompiledLibraryUpdate> systemModelLibraries;

    public synchronized ClassLoader getShellClassLoader() {
        return isNull(shellClassLoader) ? getClass().getClassLoader() : shellClassLoader;
    }

    @Override
    public synchronized void updateSystemModelLibraries(List<CompiledLibraryUpdate> compiledLibraryUpdates) {
        Set<CompiledLibraryUpdate> presentLibraries = new HashSet<>(
                isNull(systemModelLibraries) ? Collections.emptySet() : systemModelLibraries);

        Set<CompiledLibraryUpdate> librariesToAdd = new HashSet<>(compiledLibraryUpdates);
        librariesToAdd.removeAll(presentLibraries);

        Set<CompiledLibraryUpdate> librariesToRemove = new HashSet<>(
                isNull(systemModelLibraries) ? Collections.emptySet() : systemModelLibraries);
        compiledLibraryUpdates.forEach(librariesToRemove::remove);

        librariesToRemove.stream().parallel()
                .map(CompiledLibraryUpdate::getModelId)
                .map(this::getSystemModelLibraryPath)
                .forEach(this::removeLibrary);
        librariesToAdd.stream().parallel()
                .forEach(model -> loadSystemModelLibrary(model, getSystemModelLibraryPath(model.getModelId())));
        boolean initialUpdate = isNull(systemModelLibraries);
        boolean hasUpdates = !librariesToAdd.isEmpty() || !librariesToRemove.isEmpty();
        systemModelLibraries = compiledLibraryUpdates;
        if (hasUpdates) {
            updateShellClassLoader();
        }
        if (hasUpdates || initialUpdate) {
            eventBus.publish(UpdateEvent.EVENT_ADDRESS, new ExternalLibrariesUpdatedEvent(this, initialUpdate));
        }
    }

    @Override
    public synchronized ClassLoader getClassLoaderForSystemModels(
            Collection<String> systemModelIds,
            ClassLoader parentClassLoader
    ) {
        Set<String> ids = new HashSet<>(systemModelIds);
        List<URL> urls = systemModelLibraries.stream()
                .map(CompiledLibraryUpdate::getModelId)
                .filter(ids::contains)
                .map(this::getSystemModelLibraryPath)
                .map(this::toUrl)
                .filter(Objects::nonNull)
                .toList();
        return urls.isEmpty() ? parentClassLoader : new URLClassLoader(urls.toArray(new URL[0]), parentClassLoader);
    }

    private void removeLibrary(Path path) {
        try {
            if (Files.deleteIfExists(path)) {
                log.debug("Removed library: {}", path);
            }
        } catch (IOException exception) {
            log.warn("Failed to remove library {}: {}", path, exception.getMessage());
        }
    }

    private Path getSystemModelLibraryPath(String modelId) {
        return buildLibraryPath(modelId + ".jar");
    }

    private void loadSystemModelLibrary(CompiledLibraryUpdate model, Path path) {
        try {
            log.debug("Requesting DTO classes library for system model {}", model.getModelId());
            RestResponse<InputStream> response = runtimeCatalogService.getDtoJar(model.getModelId());
            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                saveLibrary(response.getEntity(), path);
            } else if (response.getStatus() == Response.Status.NO_CONTENT.getStatusCode()) {
                log.debug("System model doesn't have DTO classes library: {}", model.getModelId());
            } else {
                log.error("Failed to load DTO classes library for system model {}: Response status code {}",
                        model.getModelId(), response.getStatus());
            }
        } catch (Exception exception) {
            log.error("Failed to load DTO classes library for system model {}: {}", model.getModelId(), exception.getMessage());
        }
    }

    private void updateShellClassLoader() {
        List<Path> systemModelLibraryPaths = Collections.emptyList();
        if (systemModelLibraries != null) {
            systemModelLibraryPaths = systemModelLibraries.stream()
                    .map(CompiledLibraryUpdate::getModelId)
                    .map(this::getSystemModelLibraryPath)
                    .collect(Collectors.toList());
        }
        List<URL> libraryUrls = Stream.concat(
                librarySuppliers.stream().map(Supplier::get),
                        Stream.of(systemModelLibraryPaths))
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(this::toUrl)
                .filter(Objects::nonNull)
                .toList();
        ClassLoader classLoader = getClass().getClassLoader();
        shellClassLoader = libraryUrls.isEmpty()
                ? classLoader
                : new URLClassLoader(libraryUrls.toArray(new URL[0]), classLoader);
    }

    private Path buildLibraryPath(String filePath) {
        Path root = Paths.get(LIBRARIES_PATH);
        String fileName = FilenameUtils.getName(filePath);
        if (StringUtils.isBlank(fileName)) {
            fileName = generateRandomFileName();
        }
        return root.resolve(fileName);
    }

    private boolean saveLibrary(InputStream libraryData, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.copy(libraryData, path, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Saved library: {}", path);
            return true;
        } catch (IOException exception) {
            log.error("Failed to save library {}: {}", path, exception.getMessage());
            return false;
        }
    }

    private String generateRandomFileName() {
        return UUID.randomUUID().toString();
    }

    private URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException exception) {
            log.error("Failed to get URL for library {}: {}", path, exception.getMessage());
            return null;
        }
    }
}
