package org.qubership.integration.platform.maven.plugin.domain.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.function.Failable;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.model.ImportChain;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.readers.chain.ChainFileUtil;
import org.qubership.integration.platform.io.readers.chain.ChainReader;
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
    private final ChainReader chainReader;
    private final SnapshotBuildService snapshotBuildService;
    private final ResourceBuildService resourceBuildService;
    private final ResourceWriteService resourceWriteService;
    private final MicroDomainResourceBuildContextFactory resourceBuildContextFactory;
    private final ResourceBuildOptionsFactory resourceBuildOptionsFactory;

    @Autowired
    public MicroDomainResourcesBuildService(
        ChainReader chainReader,
        SnapshotBuildService snapshotBuildService,
        ResourceBuildService resourceBuildService,
        ResourceWriteService resourceWriteService,
        MicroDomainResourceBuildContextFactory resourceBuildContextFactory,
        ResourceBuildOptionsFactory resourceBuildOptionsFactory
        ) {
        this.chainReader = chainReader;
        this.snapshotBuildService = snapshotBuildService;
        this.resourceBuildService = resourceBuildService;
        this.resourceWriteService = resourceWriteService;
        this.resourceBuildContextFactory = resourceBuildContextFactory;
        this.resourceBuildOptionsFactory = resourceBuildOptionsFactory;
    }

    public void buildResources(BuildCRsTaskParameters parameters) throws IOException {
        Collection<ImportChain> chains = readChains(parameters.getSourceRoots());
        Map<String, Collection<ImportChain>> chainsByDomain = groupChainsByDomain(chains, parameters.getDefaultDomain());
        Failable.stream(chainsByDomain.entrySet()).forEach(entry -> {
            String domain = entry.getKey();
            Collection<ImportChain> chainsForDomain = entry.getValue();
            buildResourcesForDomain(domain, chainsForDomain, parameters);
        });
    }

    public void buildResourcesForDomain(
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
        String resourceText = resourceBuildService.buildResources(buildContext);
        resourceWriteService.writeResources(parameters.getOutputDirectory(), resourceText);
    }

    private Collection<ImportChain> readChains(Collection<String> sourceRoots) {
        Stream<File> chainDirectories = Failable.stream(sourceRoots)
            .map(rootDir -> listDirectoriesThatContainChainFiles(new File(rootDir)))
            .stream()
            .flatMap(Collection::stream);
        return Failable.stream(chainDirectories).map(chainReader::read).stream().toList();
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

    private Collection<File> listDirectoriesThatContainChainFiles(File directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            return paths
                .filter(Files::isRegularFile)
                .filter(file -> ChainFileUtil.isChainFile(file.getFileName().toString()))
                .map(Path::getParent)
                .map(Path::toFile)
                .collect(Collectors.toSet());
        }
    }
}
