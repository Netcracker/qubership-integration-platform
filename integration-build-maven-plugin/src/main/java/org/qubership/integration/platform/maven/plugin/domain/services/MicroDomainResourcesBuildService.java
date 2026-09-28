package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.Failable;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.model.ImportChain;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;
import org.qubership.integration.platform.io.readers.chain.ChainFileUtil;
import org.qubership.integration.platform.io.readers.chain.ChainReader;
import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.domain.util.SkippableFailableOperationWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.maven.plugin.domain.util.FileUtil.isInDirectory;
import static org.qubership.integration.platform.maven.plugin.domain.util.FileUtil.processFile;

@Slf4j
@Service
public class MicroDomainResourcesBuildService {
    private static final String CLASSIC_DOMAIN_NAME = "default";
    private static final String YAML_ARTIFACT_TYPE = "yaml";
    public static final String BUILD_CRS_TASK_PARAMETERS = "build-crs-task-parameters";

    private final ChainReader chainReader;
    private final IntegrationServiceLoadService integrationServiceLoadService;
    private final SnapshotBuildService snapshotBuildService;
    private final ResourceBuildService resourceBuildService;
    private final ResourceWriteService resourceWriteService;
    private final MicroDomainResourceBuildContextFactory resourceBuildContextFactory;
    private final ResourceBuildOptionsFactory resourceBuildOptionsFactory;

    @Autowired
    public MicroDomainResourcesBuildService(
        ChainReader chainReader,
        IntegrationServiceLoadService integrationServiceLoadService,
        SnapshotBuildService snapshotBuildService,
        ResourceBuildService resourceBuildService,
        ResourceWriteService resourceWriteService,
        MicroDomainResourceBuildContextFactory resourceBuildContextFactory,
        ResourceBuildOptionsFactory resourceBuildOptionsFactory
    ) {
        this.chainReader = chainReader;
        this.integrationServiceLoadService = integrationServiceLoadService;
        this.snapshotBuildService = snapshotBuildService;
        this.resourceBuildService = resourceBuildService;
        this.resourceWriteService = resourceWriteService;
        this.resourceBuildContextFactory = resourceBuildContextFactory;
        this.resourceBuildOptionsFactory = resourceBuildOptionsFactory;
    }

    public void buildResources(TaskContext<BuildCRsTaskParameters> taskContext) throws IOException {
        processServices(taskContext);
        buildChainResources(taskContext);
    }

    private void processServices(TaskContext<BuildCRsTaskParameters> taskContext) throws IOException {
        BuildCRsTaskParameters parameters = taskContext.getTaskParameters();
        integrationServiceLoadService.loadServices(parameters.getSourceRoots(), parameters.getOutputDirectory(), parameters.isFailFast());
    }

    private void buildChainResources(TaskContext<BuildCRsTaskParameters> taskContext) throws IOException {
        BuildCRsTaskParameters parameters = taskContext.getTaskParameters();
        SkippableFailableOperationWrapper failableOperationWrapper = new SkippableFailableOperationWrapper(parameters.isFailFast());
        Path outputDirectory = Path.of(parameters.getOutputDirectory());
        Predicate<ImportChain> isDeployAllowed = getChainFilter(parameters);
        Collection<ImportChain> chains = readChains(parameters.getSourceRoots(), outputDirectory, failableOperationWrapper)
            .stream()
            .filter(isDeployAllowed)
            .toList();
        Map<String, Collection<ImportChain>> chainsByDomain = groupChainsByDomain(chains, parameters.getDefaultDomain());
        Failable.stream(chainsByDomain.entrySet())
            .forEach(failableOperationWrapper.wrapConsumer(entry -> {
                String domain = entry.getKey();
                Collection<ImportChain> chainsForDomain = entry.getValue();
                buildChainResourcesForDomain(domain, chainsForDomain, taskContext, failableOperationWrapper);
            }));
        if (failableOperationWrapper.getErrorCount() > 0) {
            String message = String.format("Failed to generate K8s resources: %d error(s) occurred",
                failableOperationWrapper.getErrorCount());
            throw new RuntimeException(message);
        }
    }

    public void buildChainResourcesForDomain(
        String domain,
        Collection<ImportChain> chains,
        TaskContext<BuildCRsTaskParameters> taskContext,
        SkippableFailableOperationWrapper failableOperationWrapper
    ) throws IOException {
        BuildCRsTaskParameters parameters = taskContext.getTaskParameters();
        List<Snapshot> snapshots = Failable.stream(chains)
            .map(failableOperationWrapper.wrapFunction(snapshotBuildService::build))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        ResourceBuildOptions resourceBuildOptions =
            resourceBuildOptionsFactory.createResourceBuildOptions(domain, parameters);
        ResourceBuildContext<List<Snapshot>> buildContext =
            resourceBuildContextFactory.createResourceBuildContext(
                snapshots, resourceBuildOptions, parameters.getBuildTimestamp());
        buildContext.getBuildCache().put(BUILD_CRS_TASK_PARAMETERS, parameters);
        String resourceText = resourceBuildService.buildResources(buildContext);
        resourceWriteService.writeResources(parameters.getOutputDirectory(), resourceText, file ->
            taskContext.getProjectHelper()
                .attachArtifact(taskContext.getProject(), YAML_ARTIFACT_TYPE,
                    StringUtils.removeEnd(file.getName(), ".yaml"), file)
        );
    }

    private Collection<ImportChain> readChains(
        Collection<String> sourceRoots,
        Path outputDirectory,
        SkippableFailableOperationWrapper failableOperationWrapper
    ) throws IOException {
        Stream<File> chainDirectories = Failable.stream(sourceRoots)
            .map(File::new)
            .map(rootDir -> listDirectoriesThatContainChainFiles(rootDir, outputDirectory))
            .stream()
            .flatMap(Collection::stream);
        return Failable.stream(chainDirectories)
            .map(failableOperationWrapper.wrapFunction(directory -> processFile(directory, chainReader::read)))
            .filter(Objects::nonNull)
            .stream()
            .toList();
    }

    private Map<String, Collection<ImportChain>> groupChainsByDomain(Collection<ImportChain> chains, String defaultDomain) {
        Map<String, Collection<ImportChain>> chainsByDomain = new HashMap<>();
        chains.forEach(chain -> {
            List<String> domains = chain.getDeployments().stream()
                .filter(domain -> !CLASSIC_DOMAIN_NAME.equals(domain))
                .toList();
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
        if (!directory.isDirectory()) {
            log.warn("Skipping source root '{}': not a directory.", directory);
            return Collections.emptyList();
        }
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

    private Predicate<ImportChain> getChainFilter(BuildCRsTaskParameters parameters) {
        return chain ->
            parameters.isDeployAll()
            || (ChainCommitRequestAction.DEPLOY.equals(chain.getDeployAction())
                && chain.getDeployments().stream()
                    .anyMatch(domain -> !CLASSIC_DOMAIN_NAME.equals(domain)));
    }
}
