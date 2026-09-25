package org.qubership.integration.platform.maven.plugin.domain.services;

import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.impl.ChainImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;
import org.qubership.integration.platform.io.readers.chain.ChainReader;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.adapters.SnapshotImpl;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
    private final MavenProject project = mock(MavenProject.class);
    private final MavenProjectHelper projectHelper = mock(MavenProjectHelper.class);

    // Real, not a mock: these tests assert on the services the load leaves in the catalog.
    private final IntegrationServiceLoadService integrationServiceLoadService =
        new IntegrationServiceLoadService(integrationSystemReader, catalog);

    private final MicroDomainResourcesBuildService buildService = new MicroDomainResourcesBuildService(
        chainReader,
        integrationServiceLoadService,
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
        when(buildContextFactory.createResourceBuildContext(any(), any(), any())).thenAnswer(invocation -> {
            BuildInfo buildInfo = BuildInfo.builder().options(invocation.getArgument(1)).build();
            return ResourceBuildContext.create(buildInfo, IntegrationServiceCatalog.EMPTY).updateTo(invocation.getArgument(0));
        });
    }

    @Test
    void groupsChainsByDeploymentDomain() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");
        chainDirectory("billing", "billing.chain.yml", "orders-domain");
        chainDirectory("payments", "chain-payments.yaml", "payments-domain");

        buildService.buildResources(taskContext());

        assertEquals(
            Map.of("orders-domain", Set.of("orders", "billing"), "payments-domain", Set.of("payments")),
            capturedChainIdsByDomain());
        verify(resourceWriteService, times(2))
            .writeResources(eq(outputDirectory().toString()), eq(RESOURCE_TEXT), any());
    }

    @Test
    void attachesEachWrittenResourceClassifiedByItsFileName() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");

        buildService.buildResources(taskContext());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<File>> onWrite = ArgumentCaptor.forClass(Consumer.class);
        verify(resourceWriteService).writeResources(any(), any(), onWrite.capture());
        File written = outputDirectory().resolve("Service-qip-engine-orders-domain.yaml").toFile();
        onWrite.getValue().accept(written);
        verify(projectHelper).attachArtifact(project, "yaml", "Service-qip-engine-orders-domain", written);
    }

    @Test
    void buildsResourcesPerDomainForAChainDeployedToSeveralDomains() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "orders-domain", "backup-domain");

        buildService.buildResources(taskContext());

        assertEquals(
            Map.of("orders-domain", Set.of("orders"), "backup-domain", Set.of("orders")),
            capturedChainIdsByDomain());
    }

    @Test
    void skipsChainsThatAreNotRequestedForDeployment() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", ChainCommitRequestAction.NONE, "orders-domain");
        chainDirectory("billing", "billing.chain.yml", ChainCommitRequestAction.SNAPSHOT, "orders-domain");
        chainDirectory("payments", "chain-payments.yaml", (ChainCommitRequestAction) null, "payments-domain");

        buildService.buildResources(taskContext());

        verifyNoInteractions(buildContextFactory, resourceWriteService);
    }

    @Test
    void skipsAChainThatNamesNoDeploymentDomain() throws IOException {
        chainDirectory("legacy", "chain-legacy.yaml");

        buildService.buildResources(taskContext());

        verifyNoInteractions(buildContextFactory, resourceWriteService);
    }

    @Test
    void skipsAChainDeployedToTheClassicDomainAlone() throws IOException {
        chainDirectory("legacy", "chain-legacy.yaml", "default");

        buildService.buildResources(taskContext());

        verifyNoInteractions(buildContextFactory, resourceWriteService);
    }

    @Test
    void buildsAChainDeployedToTheClassicDomainAlongsideAnotherDomain() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "default", "orders-domain");

        buildService.buildResources(taskContext());

        assertEquals(
            Map.of("orders-domain", Set.of("orders")),
            capturedChainIdsByDomain());
    }

    @Test
    void buildsEveryChainWhenDeployAllIsSet() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", ChainCommitRequestAction.NONE, "orders-domain");
        chainDirectory("billing", "billing.chain.yml", "default");
        chainDirectory("legacy", "chain-legacy.yaml");

        buildService.buildResources(taskContext(true, true));

        assertEquals(
            Map.of(
                "orders-domain", Set.of("orders"),
                "fallback-domain", Set.of("billing", "legacy")),
            capturedChainIdsByDomain());
    }

    @Test
    void ignoresChainAndServiceFilesUnderTheOutputDirectory() throws IOException {
        Path generated = outputDirectory().resolve("generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("chain-generated.yaml"), "");
        Files.writeString(generated.resolve("service-generated.yaml"), "");
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");

        buildService.buildResources(taskContext());

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

        buildService.buildResources(taskContext());

        assertEquals("system-1", catalog.findById("system-1").orElseThrow().getId());
    }

    @Test
    void reportsTheFileThatFailedToRead() throws IOException {
        Path chainDirectory = chainDirectory("orders", "chain-orders.yaml", "orders-domain");
        when(chainReader.read(chainDirectory.toFile())).thenThrow(new IllegalStateException("broken chain"));

        Exception exception = assertThrows(Exception.class, () -> buildService.buildResources(taskContext()));

        Throwable cause = exception.getCause();
        assertTrue(cause.getMessage().contains(chainDirectory.toFile().getAbsolutePath()));
        assertTrue(cause.getMessage().contains("broken chain"));
    }

    @Test
    void buildsTheOtherDomainsWhenAChainFailsToReadAndNotFailingFast() throws IOException {
        Path broken = chainDirectory("orders", "chain-orders.yaml", "orders-domain");
        when(chainReader.read(broken.toFile())).thenThrow(new IllegalStateException("broken chain"));
        chainDirectory("payments", "chain-payments.yaml", "payments-domain");

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> buildService.buildResources(taskContext(false, false)));

        assertEquals("Failed to generate K8s resources: 1 error(s) occurred", exception.getMessage());
        assertEquals(Map.of("payments-domain", Set.of("payments")), capturedChainIdsByDomain());
        verify(resourceWriteService).writeResources(any(), any(), any());
    }

    /** A chain that fails verification drops out of its domain; the rest of the domain is still built. */
    @Test
    void buildsTheRestOfTheDomainWhenAChainFailsToBuildAndNotFailingFast() throws IOException {
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");
        chainDirectory("billing", "chain-billing.yaml", "orders-domain");
        chainDirectory("payments", "chain-payments.yaml", "payments-domain");
        doAnswer(invocation -> {
            ChainImpl chain = invocation.getArgument(0);
            if ("orders".equals(chain.getId())) {
                throw new IllegalStateException("invalid chain");
            }
            SnapshotImpl snapshot = new SnapshotImpl();
            snapshot.setName(chain.getId());
            return snapshot;
        }).when(snapshotBuildService).build(any());

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> buildService.buildResources(taskContext(false, false)));

        assertEquals("Failed to generate K8s resources: 1 error(s) occurred", exception.getMessage());
        assertEquals(
            Map.of("orders-domain", Set.of("billing"), "payments-domain", Set.of("payments")),
            capturedChainIdsByDomain());
        verify(resourceWriteService, times(2)).writeResources(any(), any(), any());
    }

    /** Chains resolve their services from the catalog, so a service that failed to load stops the build. */
    @Test
    void stopsBeforeReadingChainsWhenAServiceFailsToLoad() throws IOException {
        Path serviceFile = sourceRoot.resolve("service-payments.yaml");
        Files.writeString(serviceFile, "");
        when(integrationSystemReader.read(serviceFile.toFile())).thenThrow(new IllegalArgumentException("broken service"));
        chainDirectory("orders", "chain-orders.yaml", "orders-domain");

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> buildService.buildResources(taskContext(false, false)));

        assertEquals("Failed to load services: 1 error(s) occurred", exception.getMessage());
        verifyNoInteractions(buildContextFactory, resourceWriteService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> capturedChainIdsByDomain() {
        ArgumentCaptor<List<Snapshot>> snapshots = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ResourceBuildOptions> options = ArgumentCaptor.forClass(ResourceBuildOptions.class);
        verify(buildContextFactory, atLeastOnce())
            .createResourceBuildContext(snapshots.capture(), options.capture(), any());
        Map<String, Set<String>> chainIdsByDomain = new HashMap<>();
        for (int i = 0; i < options.getAllValues().size(); i++) {
            chainIdsByDomain.put(
                options.getAllValues().get(i).getName(),
                snapshots.getAllValues().get(i).stream().map(Snapshot::getName).collect(Collectors.toSet()));
        }
        return chainIdsByDomain;
    }

    private Path chainDirectory(String chainId, String fileName, String... deployments) throws IOException {
        return chainDirectory(chainId, fileName, ChainCommitRequestAction.DEPLOY, deployments);
    }

    private Path chainDirectory(
        String chainId,
        String fileName,
        ChainCommitRequestAction deployAction,
        String... deployments
    ) throws IOException {
        Path directory = sourceRoot.resolve(chainId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), "");
        ChainImpl chain = new ChainImpl();
        chain.setId(chainId);
        chain.setDeployAction(deployAction);
        chain.setDeployments(List.of(deployments));
        when(chainReader.read(directory.toFile())).thenReturn(chain);
        return directory;
    }

    private Path outputDirectory() {
        return sourceRoot.resolve("target");
    }

    private TaskContext<BuildCRsTaskParameters> taskContext() {
        return taskContext(false, true);
    }

    private TaskContext<BuildCRsTaskParameters> taskContext(boolean deployAll, boolean failFast) {
        BuildCRsTaskParameters parameters = BuildCRsTaskParameters.builder()
            .sourceRoots(List.of(sourceRoot.toString()))
            .outputDirectory(outputDirectory().toString())
            .defaultDomain("fallback-domain")
            .deployAll(deployAll)
            .failFast(failFast)
            .options(new BuildCRsOptions())
            .build();
        return TaskContext.<BuildCRsTaskParameters>builder()
            .project(project)
            .projectHelper(projectHelper)
            .taskParameters(parameters)
            .build();
    }
}
