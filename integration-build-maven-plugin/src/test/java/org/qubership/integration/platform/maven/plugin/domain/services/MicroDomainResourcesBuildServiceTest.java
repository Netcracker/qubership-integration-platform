package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.impl.ChainImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.readers.chain.ChainReader;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.maven.plugin.domain.adapters.SnapshotImpl;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MicroDomainResourcesBuildServiceTest {

    private static final String RESOURCE_TEXT = "---\nkind: Deployment\n";
    private static final String DEFAULT_IMAGE = "qip/micro-engine:test";

    private final ChainReader chainReader = mock(ChainReader.class);
    private final IntegrationSystemReader integrationSystemReader = mock(IntegrationSystemReader.class);
    private final IntegrationServiceCatalogImpl catalog = new IntegrationServiceCatalogImpl();
    private final SnapshotBuildService snapshotBuildService = mock(SnapshotBuildService.class);
    private final ResourceBuildService resourceBuildService = mock(ResourceBuildService.class);
    private final ResourceWriteService resourceWriteService = mock(ResourceWriteService.class);
    private final MicroDomainResourceBuildContextFactory buildContextFactory =
        mock(MicroDomainResourceBuildContextFactory.class);

    private final MicroDomainResourcesBuildService buildService = new MicroDomainResourcesBuildService(
        chainReader,
        integrationSystemReader,
        catalog,
        snapshotBuildService,
        resourceBuildService,
        resourceWriteService,
        buildContextFactory,
        new ResourceBuildOptionsFactory(DEFAULT_IMAGE));

    @TempDir
    private Path sourceRoot;

    @BeforeEach
    void setUp() {
        when(snapshotBuildService.build(any())).thenAnswer(invocation -> {
            SnapshotImpl snapshot = new SnapshotImpl();
            snapshot.setName(((ChainImpl) invocation.getArgument(0)).getId());
            return snapshot;
        });
        when(resourceBuildService.buildResources(any())).thenReturn(RESOURCE_TEXT);
        when(buildContextFactory.createResourceBuildContext(any(), any())).thenAnswer(invocation -> {
            BuildInfo buildInfo = BuildInfo.builder().options(invocation.getArgument(1)).build();
            return ResourceBuildContext.create(buildInfo, null).updateTo(invocation.getArgument(0));
        });
    }

    @Test
    void groupsChainsByDeploymentDomainAndFallsBackToTheDefaultDomain() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");
        chainDirectory("billing", "billing.chain.yml", "orders-domain");
        chainDirectory("legacy", "chain-legacy.yaml");

        buildService.buildResources(parameters());

        assertEquals(
            Map.of("orders-domain", Set.of("orders", "billing"), "fallback-domain", Set.of("legacy")),
            capturedChainIdsByDomain());
        verify(resourceWriteService, times(2)).writeResources(outputDirectory().toString(), RESOURCE_TEXT);
    }

    @Test
    void buildsResourcesPerDomainForAChainDeployedToSeveralDomains() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "orders-domain", "backup-domain");

        buildService.buildResources(parameters());

        assertEquals(
            Map.of("orders-domain", Set.of("orders"), "backup-domain", Set.of("orders")),
            capturedChainIdsByDomain());
    }

    @Test
    void ignoresChainAndServiceFilesUnderTheOutputDirectory() throws IOException {
        Path generated = outputDirectory().resolve("generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("chain-generated.yaml"), "");
        Files.writeString(generated.resolve("service-generated.yaml"), "");
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");

        buildService.buildResources(parameters());

        assertEquals(Map.of("orders-domain", Set.of("orders")), capturedChainIdsByDomain());
        verifyNoInteractions(integrationSystemReader);
    }

    @Test
    void registersServiceFilesInTheCatalog() throws IOException {
        Path serviceFile = sourceRoot.resolve("service-payments.yaml");
        Files.writeString(serviceFile, "");
        ImportSystemImpl system = new ImportSystemImpl();
        system.setId("system-1");
        when(integrationSystemReader.read(serviceFile.toFile())).thenReturn(system);

        buildService.buildResources(parameters());

        assertEquals("system-1", catalog.findById("system-1").orElseThrow().getId());
    }

    @Test
    void reportsTheFileThatFailedToRead() throws IOException {
        Path chainDirectory = chainDirectory("orders", "chain-orders.yaml", "orders-domain");
        when(chainReader.read(chainDirectory.toFile())).thenThrow(new IllegalStateException("broken chain"));

        Exception exception = assertThrows(Exception.class, () -> buildService.buildResources(parameters()));

        Throwable cause = exception.getCause();
        assertTrue(cause.getMessage().contains(chainDirectory.toFile().getAbsolutePath()));
        assertTrue(cause.getMessage().contains("broken chain"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> capturedChainIdsByDomain() {
        ArgumentCaptor<List<Snapshot>> snapshots = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ResourceBuildOptions> options = ArgumentCaptor.forClass(ResourceBuildOptions.class);
        verify(buildContextFactory, atLeastOnce())
            .createResourceBuildContext(snapshots.capture(), options.capture());
        Map<String, Set<String>> chainIdsByDomain = new HashMap<>();
        for (int i = 0; i < options.getAllValues().size(); i++) {
            chainIdsByDomain.put(
                options.getAllValues().get(i).getName(),
                snapshots.getAllValues().get(i).stream().map(Snapshot::getName).collect(Collectors.toSet()));
        }
        return chainIdsByDomain;
    }

    private Path chainDirectory(String chainId, String fileName, String... deployments) throws IOException {
        Path directory = sourceRoot.resolve(chainId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), "");
        ChainImpl chain = new ChainImpl();
        chain.setId(chainId);
        chain.setDeployments(List.of(deployments));
        when(chainReader.read(directory.toFile())).thenReturn(chain);
        return directory;
    }

    private Path outputDirectory() {
        return sourceRoot.resolve("target");
    }

    private BuildCRsTaskParameters parameters() {
        return BuildCRsTaskParameters.builder()
            .sourceRoots(List.of(sourceRoot.toString()))
            .outputDirectory(outputDirectory().toString())
            .defaultDomain("fallback-domain")
            .options(new BuildCRsOptions())
            .build();
    }
}
