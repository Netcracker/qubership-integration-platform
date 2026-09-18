package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.function.Failable;
import org.apache.commons.lang3.function.FailableFunction;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.model.ImportChain;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.readers.chain.ChainFileUtil;
import org.qubership.integration.platform.io.readers.chain.ChainReader;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.io.readers.system.ServiceFileUtil;
import org.qubership.integration.platform.maven.plugin.domain.adapters.ImportSystemAdapter;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class MicroDomainResourcesBuildService {
    public static final String BUILD_CRS_TASK_PARAMETERS = "build-crs-task-parameters";

    private final ChainReader chainReader;
    private final IntegrationSystemReader integrationSystemReader;
    private final IntegrationServiceCatalogImpl integrationServiceCatalog;
    private final SnapshotBuildService snapshotBuildService;
    private final ResourceBuildService resourceBuildService;
    private final ResourceWriteService resourceWriteService;
    private final MicroDomainResourceBuildContextFactory resourceBuildContextFactory;
    private final ResourceBuildOptionsFactory resourceBuildOptionsFactory;

    @Autowired
    public MicroDomainResourcesBuildService(
        ChainReader chainReader,
        IntegrationSystemReader integrationSystemReader,
        IntegrationServiceCatalogImpl integrationServiceCatalog,
        SnapshotBuildService snapshotBuildService,
        ResourceBuildService resourceBuildService,
        ResourceWriteService resourceWriteService,
        MicroDomainResourceBuildContextFactory resourceBuildContextFactory,
        ResourceBuildOptionsFactory resourceBuildOptionsFactory
    ) {
        this.chainReader = chainReader;
        this.integrationSystemReader = integrationSystemReader;
        this.integrationServiceCatalog = integrationServiceCatalog;
        this.snapshotBuildService = snapshotBuildService;
        this.resourceBuildService = resourceBuildService;
        this.resourceWriteService = resourceWriteService;
        this.resourceBuildContextFactory = resourceBuildContextFactory;
        this.resourceBuildOptionsFactory = resourceBuildOptionsFactory;
    }

    public void buildResources(BuildCRsTaskParameters parameters) throws IOException {
        processServices(parameters);
        buildChainResources(parameters);
    }

    private void processServices(BuildCRsTaskParameters parameters) throws IOException {
        Path outputDirectory = Path.of(parameters.getOutputDirectory());
        Stream<File> serviceFiles = Failable.stream(parameters.getSourceRoots())
            .map(File::new)
            .map(sourceRoot -> listServiceFiles(sourceRoot, outputDirectory))
            .stream()
            .flatMap(Collection::stream);
        Failable.stream(serviceFiles)
            .map(file -> processFile(file, integrationSystemReader::read))
            .map(ImportSystemAdapter::new)
            .stream()
            .forEach(integrationServiceCatalog::addService);
    }

    private void buildChainResources(BuildCRsTaskParameters parameters) throws IOException {
        Path outputDirectory = Path.of(parameters.getOutputDirectory());
        Collection<ImportChain> chains = readChains(parameters.getSourceRoots(), outputDirectory);
        Map<String, Collection<ImportChain>> chainsByDomain = groupChainsByDomain(chains, parameters.getDefaultDomain());
        Failable.stream(chainsByDomain.entrySet()).forEach(entry -> {
            String domain = entry.getKey();
            Collection<ImportChain> chainsForDomain = entry.getValue();
            buildChainResourcesForDomain(domain, chainsForDomain, parameters);
        });
    }

    public void buildChainResourcesForDomain(
        String domain,
        Collection<ImportChain> chains,
        BuildCRsTaskParameters parameters
    ) throws IOException {
        List<Snapshot> snapshots = Failable.stream(chains)
            .map(snapshotBuildService::build)
            .collect(Collectors.toList());
        ResourceBuildOptions resourceBuildOptions =
            resourceBuildOptionsFactory.createResourceBuildOptions(domain, parameters);
        ResourceBuildContext<List<Snapshot>> buildContext =
            resourceBuildContextFactory.createResourceBuildContext(snapshots, resourceBuildOptions);
        buildContext.getBuildCache().put(BUILD_CRS_TASK_PARAMETERS, parameters);
        String resourceText = resourceBuildService.buildResources(buildContext);
        resourceWriteService.writeResources(parameters.getOutputDirectory(), resourceText);
    }

    private Collection<ImportChain> readChains(Collection<String> sourceRoots, Path outputDirectory) throws IOException {
        Stream<File> chainDirectories = Failable.stream(sourceRoots)
            .map(File::new)
            .map(rootDir -> listDirectoriesThatContainChainFiles(rootDir, outputDirectory))
            .stream()
            .flatMap(Collection::stream);
        return Failable.stream(chainDirectories)
            .map(directory -> processFile(directory, chainReader::read))
            .stream()
            .toList();
    }

    private Map<String, Collection<ImportChain>> groupChainsByDomain(Collection<ImportChain> chains, String defaultDomain) {
        Map<String, Collection<ImportChain>> chainsByDomain = new HashMap<>();
        chains.forEach(chain -> {
            List<String> domains = chain.getDeployments();
            if (domains.isEmpty()) {
                domains = Collections.singletonList(defaultDomain);
            }
            domains.forEach(name ->
                chainsByDomain.compute(name, (k, v) -> {
                    if (isNull(v)) {
                        Collection<ImportChain> result = new ArrayList<>();
                        result.add(chain);
                        return result;
                    } else {
                        v.add(chain);
                        return v;
                    }
                })
            );
        });
        return chainsByDomain;
    }

    private Collection<File> listDirectoriesThatContainChainFiles(File directory, Path outputDirectory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            return paths
                .filter(path -> !isInDirectory(path, outputDirectory))
                .filter(Files::isRegularFile)
                .filter(file -> ChainFileUtil.isChainFile(file.getFileName().toString()))
                .map(Path::getParent)
                .map(Path::toFile)
                .collect(Collectors.toSet());
        }
    }

    private Collection<File> listServiceFiles(File directory, Path outputDirectory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            return paths
                .filter(path -> !isInDirectory(path, outputDirectory))
                .filter(Files::isRegularFile)
                .filter(file -> ServiceFileUtil.isServiceFile(file.getFileName().toString()))
                .map(Path::toFile)
                .toList();
        }
    }

    private boolean isInDirectory(Path path, Path directory) {
        Path p = path.normalize().toAbsolutePath();
        Path d = directory.normalize().toAbsolutePath();
        return p.startsWith(d);
    }

    private <R, E extends Throwable> R processFile(File file, FailableFunction<File, R, E> processor) throws Exception {
        try {
            return processor.apply(file);
        } catch (Throwable error) {
            String message = String.format("%s: %s", file.getAbsolutePath(), error.getMessage());
            throw new Exception(message, error);
        }
    }
}
