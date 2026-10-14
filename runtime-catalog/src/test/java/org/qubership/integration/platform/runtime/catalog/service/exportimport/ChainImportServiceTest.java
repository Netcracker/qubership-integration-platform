package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.io.model.exportimport.MetaInfoExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalContentEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainExternalEntity;
import org.qubership.integration.platform.io.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.io.readers.chain.ChainModelMapper;
import org.qubership.integration.platform.io.readers.chain.ChainReader;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainExternalMapperEntity;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ChainImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportChainPreviewDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.engine.ImportDomainDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequest;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.DependencyService;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.FolderService;
import org.qubership.integration.platform.runtime.catalog.service.MaskedFieldsService;
import org.qubership.integration.platform.runtime.catalog.service.SnapshotService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainExternalEntityMapper;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainImportServiceTest {

    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private FolderService folderService;
    @Mock
    private ChainExternalEntityMapper chainExternalEntityMapper;
    @Mock
    private ChainModelMapper chainModelMapper;
    @Mock
    private ChainReader chainReader;
    @Mock
    private ChainService chainService;
    @Mock
    private DependencyService dependencyService;
    @Mock
    private ElementService elementService;
    @Mock
    private MaskedFieldsService maskedFieldsService;
    @Mock
    private ActionsLogService actionsLogService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private ImportSessionService importSessionService;
    @Mock
    private BulkDeploymentService bulkDeploymentService;

    @InjectMocks
    private ChainImportService service;

    /** Snapshot IDs per domain, in the order the import passed them to {@code deploySnapshots}. */
    private final Map<String, List<String>> deployedSnapshotIds = new HashMap<>();

    private static ChainExternalEntity externalChain(String group) {
        return ChainExternalEntity.builder()
                .id("c1")
                .name("Chain 1")
                .metaInfo(group == null ? null : MetaInfoExternalEntity.builder().group(group).build())
                .content(ChainExternalContentEntity.builder().build())
                .build();
    }

    @DisplayName("resolveRootFolderName should return the first group segment")
    @Test
    void shouldResolveFirstSegmentAsRootName() {
        assertEquals("A", ChainImportService.resolveRootFolderName(externalChain("A/B/C")));
    }

    @DisplayName("resolveRootFolderName should be null when metaInfo is absent")
    @Test
    void shouldResolveNullRootNameWhenNoMetaInfo() {
        assertNull(ChainImportService.resolveRootFolderName(externalChain(null)));
    }

    @DisplayName("resolveRootFolderName should be null when the group is blank")
    @Test
    void shouldResolveNullRootNameWhenGroupBlank() {
        assertNull(ChainImportService.resolveRootFolderName(externalChain("   ")));
    }

    @DisplayName("resolveOrCreateRootFolder should reuse an existing root folder")
    @Test
    void shouldReuseExistingRootFolder() {
        Folder existing = Folder.builder().name("A").build();
        when(folderService.findFirstByName("A", null)).thenReturn(existing);

        Folder result = service.resolveOrCreateRootFolder(externalChain("A/B"));

        assertSame(existing, result);
        verify(folderService, never()).save(any(Folder.class), nullable(String.class));
    }

    @DisplayName("resolveOrCreateRootFolder should create the root folder when it is missing")
    @Test
    void shouldCreateMissingRootFolder() {
        Folder saved = Folder.builder().name("A").build();
        when(folderService.findFirstByName("A", null)).thenReturn(null);
        when(folderService.save(any(Folder.class), nullable(String.class))).thenReturn(saved);

        Folder result = service.resolveOrCreateRootFolder(externalChain("A"));

        assertSame(saved, result);
    }

    @DisplayName("resolveOrCreateRootFolder should return null and touch nothing when there is no group")
    @Test
    void shouldReturnNullRootFolderWhenNoGroup() {
        assertNull(service.resolveOrCreateRootFolder(externalChain(null)));
        verifyNoInteractions(folderService);
    }

    @DisplayName("setActualChainState should persist the folder tree before the chain")
    @Test
    void shouldPersistFolderBeforeChain() {
        Folder folder = Folder.builder().name("A").build();
        Chain imported = Chain.builder().id("c1").name("n").build();
        imported.setParentFolder(folder);
        when(chainFinderService.tryFindById("c1")).thenReturn(Optional.empty());
        when(folderService.setActualizedFolderState(folder)).thenReturn(folder);

        service.saveImportedChainBackward(imported);

        InOrder order = inOrder(folderService, chainService);
        order.verify(folderService).setActualizedFolderState(folder);
        order.verify(chainService).setActualizedChainState(null, imported);
    }

    @DisplayName("setActualChainState should skip folder persistence when the chain has no parent")
    @Test
    void shouldSkipFolderPersistWhenNoParent() {
        Chain imported = Chain.builder().id("c1").name("n").build();
        when(chainFinderService.tryFindById("c1")).thenReturn(Optional.empty());

        service.saveImportedChainBackward(imported);

        verify(folderService, never()).setActualizedFolderState(any());
        verify(chainService).setActualizedChainState(null, imported);
    }

    @DisplayName("saveImportedChain should reuse an existing root folder and report UPDATED for an existing chain")
    @Test
    void shouldImportIntoExistingFolderAsUpdate() {
        Chain existingChain = Chain.builder().id("c1").name("Chain 1").build();
        Folder existingFolder = Folder.builder().name("A").build();
        Chain mapped = Chain.builder().id("c1").name("Chain 1").build();
        when(chainFinderService.tryFindById("c1")).thenReturn(Optional.of(existingChain));
        when(folderService.findFirstByName("A", null)).thenReturn(existingFolder);
        ArgumentCaptor<ChainExternalMapperEntity> captor =
                ArgumentCaptor.forClass(ChainExternalMapperEntity.class);
        when(chainExternalEntityMapper.toInternalEntity(captor.capture())).thenReturn(mapped);

        ImportChainResult result =
                service.saveImportedChain(externalChain("A/B"), null, Collections.emptySet());

        assertEquals(ImportEntityStatus.UPDATED, result.getStatus());
        assertSame(existingFolder, captor.getValue().getExistingFolder());
        verify(folderService, never()).save(any(Folder.class), nullable(String.class));
    }

    // makeDeployActions builds a snapshot for every imported chain whose commit request asks for one,
    // decides which domains the snapshot goes to, and deploys the snapshots domain by domain.

    private static ImportChainResult importedChain(String id, String... archiveDomains) {
        return ImportChainResult.builder()
                .id(id)
                .name(id + "-name")
                .status(ImportEntityStatus.CREATED)
                .deployments(Arrays.stream(archiveDomains)
                        .<DeploymentExternalEntity>map(domain -> DeploymentExternalEntity.builder().domain(domain).build())
                        .toList())
                .build();
    }

    private static ChainCommitRequest commitRequest(String id, ChainCommitRequestAction action, String... domains) {
        return ChainCommitRequest.builder()
                .id(id)
                .deployAction(action)
                .domains(Arrays.stream(domains)
                        .<ImportDomainDTO>map(domain -> ImportDomainDTO.builder().name(domain).build())
                        .toList())
                .build();
    }

    private Snapshot snapshotIsBuilt(String chainId, String snapshotId) {
        Snapshot snapshot = Snapshot.builder()
                .id(snapshotId)
                .chain(Chain.builder().id(chainId).name(chainId + "-name").build())
                .build();
        when(snapshotService.build(eq(chainId), any())).thenReturn(snapshot);
        return snapshot;
    }

    private static BulkDeploymentResponse response(
            Snapshot snapshot,
            String domain,
            BulkDeploymentStatus status,
            String errorMessage
    ) {
        return BulkDeploymentResponse.builder()
                .chainId(snapshot.getChain().getId())
                .status(status)
                .errorMessage(errorMessage)
                .domain(EngineDomain.builder().name(domain).build())
                .build();
    }

    /** Records each deploy in {@link #deployedSnapshotIds} and reports every snapshot as created. */
    private void deploysSucceed() {
        doAnswer(invocation -> {
            Collection<Snapshot> snapshots = invocation.getArgument(0);
            String domain = invocation.<Collection<String>>getArgument(1).iterator().next();
            Consumer<BulkDeploymentResponse> resultConsumer = invocation.getArgument(4);
            snapshots.forEach(snapshot -> {
                deployedSnapshotIds.computeIfAbsent(domain, key -> new ArrayList<>()).add(snapshot.getId());
                resultConsumer.accept(response(snapshot, domain, BulkDeploymentStatus.CREATED, null));
            });
            return null;
        }).when(bulkDeploymentService).deploySnapshots(any(), any(), any(), any(), any());
    }

    private void makeDeployActions(List<ImportChainResult> chains, List<ChainCommitRequest> commitRequests) {
        service.makeDeployActions(chains, commitRequests, "import-1", Set.of());
    }

    @Test
    void deployWithDomainsDeploysToTheDomainsPickedInTheRequest() {
        snapshotIsBuilt("c1", "s1");
        deploysSucceed();

        makeDeployActions(
                List.of(importedChain("c1", "archive-domain")),
                List.of(commitRequest("c1", ChainCommitRequestAction.DEPLOY, "picked-domain")));

        assertEquals(Map.of("picked-domain", List.of("s1")), deployedSnapshotIds);
    }

    @Test
    void deployWithoutDomainsDeploysToTheDomainsTheArchiveLists() {
        snapshotIsBuilt("c1", "s1");
        deploysSucceed();

        makeDeployActions(
                List.of(importedChain("c1", "archive-domain")),
                List.of(commitRequest("c1", ChainCommitRequestAction.DEPLOY)));

        assertEquals(Map.of("archive-domain", List.of("s1")), deployedSnapshotIds);
    }

    @Test
    void requestWithoutActionDeploysToTheDomainsTheArchiveLists() {
        snapshotIsBuilt("c1", "s1");
        deploysSucceed();

        makeDeployActions(
                List.of(importedChain("c1", "archive-domain")),
                List.of(commitRequest("c1", null, "ignored-domain")));

        assertEquals(Map.of("archive-domain", List.of("s1")), deployedSnapshotIds);
    }

    @Test
    void importWithoutCommitRequestsDeploysToTheDomainsTheArchiveLists() {
        snapshotIsBuilt("c1", "s1");
        deploysSucceed();

        makeDeployActions(List.of(importedChain("c1", "archive-domain")), List.of());

        assertEquals(Map.of("archive-domain", List.of("s1")), deployedSnapshotIds);
    }

    @Test
    void snapshotActionBuildsTheSnapshotWithoutDeployingIt() {
        snapshotIsBuilt("c1", "s1");

        makeDeployActions(
                List.of(importedChain("c1", "archive-domain")),
                List.of(commitRequest("c1", ChainCommitRequestAction.SNAPSHOT, "picked-domain")));

        verify(snapshotService).build(eq("c1"), any());
        verify(bulkDeploymentService, never()).deploySnapshots(any(), any(), any(), any(), any());
    }

    @Test
    void noneActionAndAChainWithoutARequestGetNoSnapshot() {
        makeDeployActions(
                List.of(importedChain("c1", "archive-domain"), importedChain("c2", "archive-domain")),
                List.of(commitRequest("c1", ChainCommitRequestAction.NONE)));

        verifyNoInteractions(snapshotService);
        verify(bulkDeploymentService, never()).deploySnapshots(any(), any(), any(), any(), any());
    }

    @Test
    void chainsThatFailedOrWereSkippedOrIgnoredGetNoSnapshot() {
        List<ImportChainResult> chains = List.of(
                importedChain("c1", "archive-domain"),
                importedChain("c2", "archive-domain"),
                importedChain("c3", "archive-domain"));
        chains.get(0).setStatus(ImportEntityStatus.ERROR);
        chains.get(1).setStatus(ImportEntityStatus.SKIPPED);
        chains.get(2).setStatus(ImportEntityStatus.IGNORED);

        makeDeployActions(chains, List.of());

        verifyNoInteractions(snapshotService);
    }

    @Test
    void chainWithoutDeploymentsListGetsASnapshotAndKeepsItsStatus() {
        snapshotIsBuilt("c1", "s1");
        ImportChainResult chain = importedChain("c1");
        chain.setDeployments(null);

        makeDeployActions(List.of(chain), List.of());

        assertEquals(ImportEntityStatus.CREATED, chain.getStatus());
        verify(bulkDeploymentService, never()).deploySnapshots(any(), any(), any(), any(), any());
    }

    @Test
    void failedSnapshotBuildMarksTheChainAsFailedAndSkipsItsDeploy() {
        when(snapshotService.build(eq("c1"), any())).thenThrow(new IllegalStateException("broken chain"));
        ImportChainResult chain = importedChain("c1", "archive-domain");

        makeDeployActions(List.of(chain), List.of());

        assertEquals(ImportEntityStatus.ERROR, chain.getStatus());
        assertEquals("Chain is saved but without snapshot: broken chain", chain.getErrorMessage());
        verify(bulkDeploymentService, never()).deploySnapshots(any(), any(), any(), any(), any());
    }

    @Test
    void chainsGoingToTheSameDomainAreDeployedTogetherInAppendMode() {
        snapshotIsBuilt("c1", "s1");
        snapshotIsBuilt("c2", "s2");
        deploysSucceed();

        makeDeployActions(List.of(importedChain("c1", "orders"), importedChain("c2", "orders")), List.of());

        assertEquals(Map.of("orders", List.of("s1", "s2")), deployedSnapshotIds);
        verify(bulkDeploymentService).deploySnapshots(any(), eq(List.of("orders")), eq(DeployMode.APPEND), any(), any());
    }

    @Test
    void failedDeployMarksTheChainAsFailedWithTheDomainAndTheReason() {
        Snapshot snapshot = snapshotIsBuilt("c1", "s1");
        doAnswer(invocation -> {
            invocation.<Consumer<BulkDeploymentResponse>>getArgument(4)
                    .accept(response(snapshot, "orders", BulkDeploymentStatus.FAILED_DEPLOY, "trigger conflict"));
            return null;
        }).when(bulkDeploymentService).deploySnapshots(any(), any(), any(), any(), any());
        ImportChainResult chain = importedChain("c1", "orders");

        makeDeployActions(List.of(chain), List.of());

        assertEquals(ImportEntityStatus.ERROR, chain.getStatus());
        assertEquals("Chain is saved but not deployed: domain orders: trigger conflict", chain.getErrorMessage());
    }

    @Test
    void exceptionForOneDomainFailsOnlyTheChainsSentToThatDomain() {
        Snapshot microSnapshot = snapshotIsBuilt("c1", "s1");
        snapshotIsBuilt("c2", "s2");
        deploysSucceed();
        DomainTypeDisabledException disabled = new DomainTypeDisabledException(DomainType.MICRO);
        doThrow(disabled).when(bulkDeploymentService)
                .deploySnapshots(any(), eq(List.of("micro-domain")), any(), any(), any());
        when(bulkDeploymentService.buildResponseForSnapshots(List.of(microSnapshot), "micro-domain", DomainType.MICRO,
                BulkDeploymentStatus.FAILED_DEPLOY, disabled.getMessage()))
                .thenReturn(List.of(response(microSnapshot, "micro-domain", BulkDeploymentStatus.FAILED_DEPLOY,
                        disabled.getMessage())));
        ImportChainResult microChain = importedChain("c1", "micro-domain");
        ImportChainResult classicChain = importedChain("c2", "classic-domain");

        makeDeployActions(List.of(microChain, classicChain), List.of());

        assertEquals(ImportEntityStatus.ERROR, microChain.getStatus());
        assertEquals("Chain is saved but not deployed: domain micro-domain: Domain type MICRO is disabled",
                microChain.getErrorMessage());
        assertEquals(ImportEntityStatus.CREATED, classicChain.getStatus());
        assertEquals(Map.of("classic-domain", List.of("s2")), deployedSnapshotIds);
    }

    @Test
    void resultWithoutAnIdDoesNotStopTheOtherChainsFromDeploying() {
        snapshotIsBuilt("c1", "s1");
        deploysSucceed();
        ImportChainResult unreadable = ImportChainResult.builder()
                .status(ImportEntityStatus.ERROR)
                .errorMessage("Exception while chain import: bad YAML")
                .build();

        makeDeployActions(List.of(unreadable, importedChain("c1", "orders")), List.of());

        assertEquals(Map.of("orders", List.of("s1")), deployedSnapshotIds);
    }

    @Test
    void replacedDeploymentsAreReadOnceAndPassedToEveryDomain() {
        snapshotIsBuilt("c1", "s1");
        snapshotIsBuilt("c2", "s2");
        deploysSucceed();
        List<Deployment> replaced = List.of(new Deployment());
        when(bulkDeploymentService.findReplacedDeployments(any())).thenReturn(replaced);

        makeDeployActions(List.of(importedChain("c1", "domain-a"), importedChain("c2", "domain-b")), List.of());

        verify(bulkDeploymentService, times(1)).findReplacedDeployments(any());
        verify(bulkDeploymentService).deploySnapshots(any(), eq(List.of("domain-a")), any(), same(replaced), any());
        verify(bulkDeploymentService).deploySnapshots(any(), eq(List.of("domain-b")), any(), same(replaced), any());
    }

    @Test
    void progressAdvancesAfterEveryDeployResponse() {
        snapshotIsBuilt("c1", "s1");
        snapshotIsBuilt("c2", "s2");
        deploysSucceed();

        makeDeployActions(List.of(importedChain("c1", "orders"), importedChain("c2", "orders")), List.of());

        InOrder order = inOrder(importSessionService);
        order.verify(importSessionService).calculateImportStatus("import-1", 2, 0,
                ImportSessionService.SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD, 100);
        order.verify(importSessionService).calculateImportStatus("import-1", 2, 1,
                ImportSessionService.SNAPSHOT_BUILD_PERCENTAGE_THRESHOLD, 100);
    }

    @DisplayName("getChainsImportPreview reads a chain file named with either qip or cip")
    @Test
    void previewReadsChainFilesNamedWithQipOrCip(@TempDir Path importDirectory) throws Exception {
        writeChainFile(importDirectory, "qip-chain", "qip-chain.chain.qip.yaml");
        writeChainFile(importDirectory, "cip-chain", "cip-chain.chain.cip.yaml");
        writeChainFile(importDirectory, "other-chain", "other-chain.chain.other.yaml");
        FileMigrationService fileMigrationService = mock(FileMigrationService.class);
        when(fileMigrationService.migrate(anyString(), anyCollection()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ChainImportService previewService = new ChainImportService(
                new MapperAutoConfiguration().yamlExportImportMapper(), null, chainService, chainFinderService,
                folderService, snapshotService, chainExternalEntityMapper, importSessionService, actionsLogService,
                dependencyService, elementService, maskedFieldsService, null, null, fileMigrationService,
                List.of(), chainModelMapper, chainReader, bulkDeploymentService);

        List<ImportChainPreviewDTO> previews = previewService.getChainsImportPreview(
                importDirectory.toFile(), ChainImportInstructionsConfig.builder().build());

        Map<String, ImportChainPreviewDTO> byId = new HashMap<>();
        previews.stream().filter(preview -> preview.getId() != null).forEach(preview -> byId.put(preview.getId(), preview));
        assertEquals(Set.of("qip-chain", "cip-chain"), byId.keySet());
        assertNull(byId.get("qip-chain").getErrorMessage());
        assertNull(byId.get("cip-chain").getErrorMessage());
        assertEquals(3, previews.size());
        assertNotNull(previews.stream().filter(preview -> preview.getId() == null).findFirst()
                .orElseThrow().getErrorMessage());
    }

    private static void writeChainFile(Path importDirectory, String chainId, String fileName) throws Exception {
        Path chainDirectory = importDirectory.resolve("chains").resolve(chainId);
        Files.createDirectories(chainDirectory);
        Files.writeString(chainDirectory.resolve(fileName), """
                id: %s
                name: %s
                content:
                  elements: []
                """.formatted(chainId, chainId));
    }
}
