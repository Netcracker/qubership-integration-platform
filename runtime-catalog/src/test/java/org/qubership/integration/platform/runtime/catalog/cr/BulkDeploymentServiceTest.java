package org.qubership.integration.platform.runtime.catalog.cr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployMode;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.DeployWithSnapshotCreationRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.qubership.integration.platform.runtime.catalog.cr.services.ResourceBuildOptionsProvider;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentSnapshotAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers {@link BulkDeploymentService}. Bulk deployment groups the requested domains by type,
 * rejects a request that names a disabled type before any snapshot or deployment is created, sends
 * classic domains to {@link DeploymentService} and micro-domains to a Camel-K resource deploy, and
 * reports one response per chain and domain. A resource deploy that loses an optimistic-concurrency
 * race rebuilds against current cluster state and retries up to a fixed budget before the conflict
 * propagates.
 */
@ExtendWith(MockitoExtension.class)
class BulkDeploymentServiceTest {

    @Mock
    private MicroDomainResourceBuildService microDomainResourceBuildService;
    @Mock
    private MicroDomainService microDomainService;
    @Mock
    private ResourceBuildOptionsProvider resourceBuildOptionsProvider;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ChainRepository chainRepository;
    @Mock
    private EngineService engineService;
    @Mock
    private DomainProperties domainProperties;
    @Mock
    private DomainProperties.DeployMethodConfiguration microConfiguration;
    @Mock
    private DomainProperties.DeployMethodConfiguration classicConfiguration;

    private BulkDeploymentService service;

    @BeforeEach
    void setUp() {
        service = new BulkDeploymentService(
                microDomainResourceBuildService,
                microDomainService,
                resourceBuildOptionsProvider,
                deploymentService,
                chainRepository,
                engineService,
                domainProperties);
    }

    private void microDomainEnabled(boolean enabled) {
        when(domainProperties.getMicro()).thenReturn(microConfiguration);
        when(microConfiguration.isEnabled()).thenReturn(enabled);
    }

    private void classicDomainEnabled(boolean enabled) {
        when(domainProperties.getClassic()).thenReturn(classicConfiguration);
        when(classicConfiguration.isEnabled()).thenReturn(enabled);
    }

    private void domainsExist(EngineDomain... domains) {
        when(engineService.getDomains()).thenReturn(List.of(domains));
    }

    private static EngineDomain engineDomain(String name, DomainType type) {
        return EngineDomain.builder().name(name).type(type).build();
    }

    private static Chain chain(String id) {
        return Chain.builder().id(id).name(id + "-name").build();
    }

    private static Snapshot snapshot(String id, Chain chain) {
        return Snapshot.builder().id(id).chain(chain).build();
    }

    private static ResourceDeployRequest deployRequest(String name) {
        return ResourceDeployRequest.builder()
                .name(name)
                .snapshotIds(List.of("s1"))
                .build();
    }

    private static DeployWithSnapshotCreationRequest bulkDeployRequest(List<String> chainIds, String... domains) {
        return DeployWithSnapshotCreationRequest.builder()
                .domains(List.of(domains))
                .chainIds(chainIds)
                .build();
    }

    private static Deployment deployment(String domain) {
        Deployment deployment = new Deployment();
        deployment.setDomain(domain);
        return deployment;
    }

    private List<BulkDeploymentResponse> deployChains(DeployWithSnapshotCreationRequest request) {
        List<BulkDeploymentResponse> result = new ArrayList<>();
        service.deployChains(request, result::add);
        return result;
    }

    private List<BulkDeploymentResponse> deploySnapshots(List<Snapshot> snapshots, List<String> domains, DeployMode mode) {
        List<BulkDeploymentResponse> result = new ArrayList<>();
        service.deploySnapshots(snapshots, domains, mode, List.of(), result::add);
        return result;
    }

    private void resourceBuildSucceeds() {
        when(resourceBuildOptionsProvider.getOptions(any())).thenReturn(ResourceBuildOptions.builder().build());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
    }

    @Test
    void groupDomainsByTypeTreatsADomainNoSourceListsAsMicro() {
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC));

        Map<DomainType, List<String>> grouped = service.groupDomainsByType(List.of("classic-domain", "new-domain"));

        assertThat(grouped).containsOnly(
                Map.entry(DomainType.CLASSIC, List.of("classic-domain")),
                Map.entry(DomainType.MICRO, List.of("new-domain")));
    }

    @Test
    void deployChainsIsRejectedWhenMicroDomainDisabled() {
        classicDomainEnabled(true);
        microDomainEnabled(false);
        domainsExist(engineDomain("micro-domain", DomainType.MICRO));

        assertThatThrownBy(() -> deployChains(bulkDeployRequest(List.of("chain-1"), "micro-domain")))
                .isInstanceOf(DomainTypeDisabledException.class)
                .hasMessageContaining(DomainType.MICRO.name());
        verifyNoInteractions(chainRepository, deploymentService, microDomainService);
    }

    @Test
    void deployChainsIsRejectedWhenClassicDomainDisabled() {
        classicDomainEnabled(false);
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC));

        assertThatThrownBy(() -> deployChains(bulkDeployRequest(List.of("chain-1"), "classic-domain")))
                .isInstanceOf(DomainTypeDisabledException.class)
                .hasMessageContaining(DomainType.CLASSIC.name());
        verifyNoInteractions(chainRepository, deploymentService, microDomainService);
    }

    @Test
    void deployChainsProceedsWhenNoDomainOfTheDisabledTypeIsRequested() {
        classicDomainEnabled(true);
        microDomainEnabled(false);
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC));

        deployChains(bulkDeployRequest(List.of("chain-1"), "classic-domain"));

        verifyNoInteractions(microDomainService, microDomainResourceBuildService);
    }

    @Test
    void deployChainsReportsAnOverriddenChainAsIgnoredAndDeploysTheRest() {
        classicDomainEnabled(true);
        microDomainEnabled(true);
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC));
        Chain deployed = chain("chain-1");
        Chain overridden = Chain.builder().id("chain-2").name("chain-2-name").overriddenByChainId("chain-3").build();
        when(chainRepository.findAllById(List.of("chain-1", "chain-2"))).thenReturn(List.of(deployed, overridden));
        Snapshot snapshot = snapshot("s1", deployed);
        when(deploymentService.provideSnapshots(eq(List.of("chain-1")), eq(BulkDeploymentSnapshotAction.CREATE_NEW), any()))
                .thenReturn(Map.of("chain-1", snapshot));
        BulkDeploymentResponse created = BulkDeploymentResponse.builder()
                .chainId("chain-1")
                .status(BulkDeploymentStatus.CREATED)
                .build();
        when(deploymentService.deploySnapshot(snapshot, List.of("classic-domain"), List.of())).thenReturn(List.of(created));

        List<BulkDeploymentResponse> result =
                deployChains(bulkDeployRequest(List.of("chain-1", "chain-2"), "classic-domain"));

        assertThat(result)
                .extracting(BulkDeploymentResponse::getChainId, BulkDeploymentResponse::getStatus)
                .containsExactly(
                        tuple("chain-2", BulkDeploymentStatus.IGNORED),
                        tuple("chain-1", BulkDeploymentStatus.CREATED));
    }

    @Test
    void deployChainsReportsASnapshotFailureWithTheChainName() {
        classicDomainEnabled(true);
        microDomainEnabled(true);
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC));
        when(chainRepository.findAllById(List.of("chain-1"))).thenReturn(List.of(chain("chain-1")));
        doAnswer(invocation -> {
            BiConsumer<String, String> errorHandler = invocation.getArgument(2);
            errorHandler.accept("chain-1", "build failed");
            return Map.of();
        }).when(deploymentService).provideSnapshots(any(), any(), any());

        List<BulkDeploymentResponse> result =
                deployChains(bulkDeployRequest(List.of("chain-1"), "classic-domain"));

        assertThat(result)
                .extracting(
                        BulkDeploymentResponse::getChainId,
                        BulkDeploymentResponse::getChainName,
                        BulkDeploymentResponse::getStatus,
                        BulkDeploymentResponse::getErrorMessage)
                .containsExactly(tuple("chain-1", "chain-1-name", BulkDeploymentStatus.FAILED_SNAPSHOT, "build failed"));
    }

    @Test
    void deploySnapshotsSendsClassicDomainsToDeploymentServiceAndMicroDomainsToOneResourceDeploy() {
        classicDomainEnabled(true);
        microDomainEnabled(true);
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC), engineDomain("micro-domain", DomainType.MICRO));
        resourceBuildSucceeds();
        Snapshot first = snapshot("s1", chain("chain-1"));
        Snapshot second = snapshot("s2", chain("chain-2"));

        List<BulkDeploymentResponse> result = deploySnapshots(
                List.of(first, second), List.of("classic-domain", "micro-domain"), DeployMode.REWRITE);

        verify(deploymentService).deploySnapshot(first, List.of("classic-domain"), List.of());
        verify(deploymentService).deploySnapshot(second, List.of("classic-domain"), List.of());
        ArgumentCaptor<ResourceBuildRequest> captor = ArgumentCaptor.forClass(ResourceBuildRequest.class);
        verify(microDomainResourceBuildService).buildResources(captor.capture(), eq(false));
        assertThat(captor.getValue().getSnapshotIds()).containsExactly("s1", "s2");
        verify(microDomainService).deploy(any());
        assertThat(result)
                .extracting(r -> r.getChainId(), r -> r.getStatus(), r -> r.getDomain().getName(), r -> r.getDomain().getType())
                .containsExactly(
                        tuple("chain-1", BulkDeploymentStatus.CREATED, "micro-domain", DomainType.MICRO),
                        tuple("chain-2", BulkDeploymentStatus.CREATED, "micro-domain", DomainType.MICRO));
    }

    @Test
    void deploySnapshotsReportsAFailedMicroDomainForEverySnapshotAndDeploysTheNextDomain() {
        classicDomainEnabled(true);
        microDomainEnabled(true);
        domainsExist();
        resourceBuildSucceeds();
        doThrow(new MicroDomainDeployError("boom", null))
                .doNothing()
                .when(microDomainService).deploy(any());
        Snapshot first = snapshot("s1", chain("chain-1"));
        Snapshot second = snapshot("s2", chain("chain-2"));

        List<BulkDeploymentResponse> result = deploySnapshots(
                List.of(first, second), List.of("failing-domain", "healthy-domain"), DeployMode.REWRITE);

        assertThat(result)
                .extracting(r -> r.getChainId(), r -> r.getStatus(), r -> r.getErrorMessage(), r -> r.getDomain().getName())
                .containsExactly(
                        tuple("chain-1", BulkDeploymentStatus.FAILED_DEPLOY, "boom", "failing-domain"),
                        tuple("chain-2", BulkDeploymentStatus.FAILED_DEPLOY, "boom", "failing-domain"),
                        tuple("chain-1", BulkDeploymentStatus.CREATED, null, "healthy-domain"),
                        tuple("chain-2", BulkDeploymentStatus.CREATED, null, "healthy-domain"));
    }

    // A chain's deployment on a domain the batch doesn't deploy it to stays live, so the trigger check
    // must still compare against it.
    @Test
    void findReplacedDeploymentsReturnsOnlyTheDeploymentOfEachChainOnTheDomainsItGoesTo() {
        Snapshot first = snapshot("s1", chain("chain-1"));
        Snapshot second = snapshot("s2", chain("chain-2"));
        Deployment firstOnA = deployment("domain-a");
        Deployment firstOnB = deployment("domain-b");
        Deployment secondOnB = deployment("domain-b");
        Deployment secondOnC = deployment("domain-c");
        when(deploymentService.findAllByChainId("chain-1")).thenReturn(List.of(firstOnA, firstOnB));
        when(deploymentService.findAllByChainId("chain-2")).thenReturn(List.of(secondOnB, secondOnC));

        List<Deployment> replaced = service.findReplacedDeployments(
                Map.of("domain-a", List.of(first), "domain-b", List.of(second)));

        assertThat(replaced).containsExactlyInAnyOrder(firstOnA, secondOnB);
    }

    @Test
    void deployChainsSkipsTheOldDeploymentsOfTheRequestedChainsOnTheTargetDomains() {
        classicDomainEnabled(true);
        microDomainEnabled(true);
        domainsExist(engineDomain("classic-domain", DomainType.CLASSIC));
        Chain firstChain = chain("chain-1");
        Chain secondChain = chain("chain-2");
        when(chainRepository.findAllById(List.of("chain-1", "chain-2"))).thenReturn(List.of(firstChain, secondChain));
        Snapshot first = snapshot("s1", firstChain);
        Snapshot second = snapshot("s2", secondChain);
        when(deploymentService.provideSnapshots(any(), any(), any()))
                .thenReturn(Map.of("chain-1", first, "chain-2", second));
        Deployment firstOnTarget = deployment("classic-domain");
        Deployment firstElsewhere = deployment("other-domain");
        Deployment secondOnTarget = deployment("classic-domain");
        when(deploymentService.findAllByChainId("chain-1")).thenReturn(List.of(firstOnTarget, firstElsewhere));
        when(deploymentService.findAllByChainId("chain-2")).thenReturn(List.of(secondOnTarget));

        deployChains(bulkDeployRequest(List.of("chain-1", "chain-2"), "classic-domain"));

        ArgumentCaptor<List<Deployment>> captor = ArgumentCaptor.forClass(List.class);
        verify(deploymentService).deploySnapshot(eq(first), eq(List.of("classic-domain")), captor.capture());
        verify(deploymentService).deploySnapshot(eq(second), eq(List.of("classic-domain")), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(excluded ->
                assertThat(excluded).containsExactlyInAnyOrder(firstOnTarget, secondOnTarget));
        // Read once, before the first deploy: a later read would also return deployments the batch created.
        verify(deploymentService, times(1)).findAllByChainId("chain-1");
        verify(deploymentService, times(1)).findAllByChainId("chain-2");
    }

    @Test
    void deployResourceBuildsWithTheProvidedOptionsAndDeploysTheResult() {
        ResourceDeployRequest request = ResourceDeployRequest.builder()
                .name("orders")
                .mode(DeployMode.APPEND)
                .snapshotIds(List.of("s1"))
                .build();
        ResourceBuildOptions options = ResourceBuildOptions.builder().build();
        when(resourceBuildOptionsProvider.getOptions(request)).thenReturn(options);
        BuiltResources built = new BuiltResources("resource-yaml", Map.of());
        when(microDomainResourceBuildService.buildResources(any(ResourceBuildRequest.class), eq(true)))
                .thenReturn(built);

        service.deployResource(request);

        verify(microDomainService).deploy(built);
    }

    @DisplayName("Rebuilds and retries when a deploy loses an optimistic-concurrency race")
    @Test
    void deployRebuildsAndRetriesOnConflict() {
        when(resourceBuildOptionsProvider.getOptions(any())).thenReturn(ResourceBuildOptions.builder().build());
        BuiltResources first = new BuiltResources("first", Map.of());
        BuiltResources second = new BuiltResources("second", Map.of());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(first)
                .thenReturn(second);
        doThrow(new KubeApiConflictException("conflict", null))
                .doNothing()
                .when(microDomainService).deploy(any());

        service.deployResource(deployRequest("payments"));

        // Rebuilt, not re-sent: the second attempt must carry a freshly built document.
        verify(microDomainResourceBuildService, times(2)).buildResources(any(), anyBoolean());
        ArgumentCaptor<BuiltResources> captor = ArgumentCaptor.forClass(BuiltResources.class);
        verify(microDomainService, times(2)).deploy(captor.capture());
        assertEquals(List.of("first", "second"),
                captor.getAllValues().stream().map(BuiltResources::yaml).toList());
    }

    @DisplayName("Builds each retry attempt from options the previous attempt could not have touched")
    @Test
    void deployBuildsAFreshRequestForEveryAttempt() {
        // The build mutates options.mount in place, so a request hoisted out of the retry loop would
        // feed the previous attempt's merged mount set back into the next build: the set could only
        // grow, and a mount the conflicting writer removed would come back.
        when(resourceBuildOptionsProvider.getOptions(any()))
                .thenReturn(ResourceBuildOptions.builder().build())
                .thenReturn(ResourceBuildOptions.builder().build());
        when(microDomainResourceBuildService.buildResources(any(), anyBoolean()))
                .thenReturn(new BuiltResources("yaml", Map.of()));
        doThrow(new KubeApiConflictException("conflict", null))
                .doNothing()
                .when(microDomainService).deploy(any());

        service.deployResource(deployRequest("payments"));

        verify(resourceBuildOptionsProvider, times(2)).getOptions(any());
        ArgumentCaptor<ResourceBuildRequest> captor = ArgumentCaptor.forClass(ResourceBuildRequest.class);
        verify(microDomainResourceBuildService, times(2)).buildResources(captor.capture(), anyBoolean());
        assertThat(captor.getAllValues().get(0).getOptions())
                .as("the second attempt must build from its own options, not the ones attempt 1 mutated")
                .isNotSameAs(captor.getAllValues().get(1).getOptions());
    }

    @DisplayName("Gives up after the retry budget and surfaces the last conflict")
    @Test
    void deployStopsRetryingAfterTheBudgetIsExhausted() {
        resourceBuildSucceeds();
        doThrow(new KubeApiConflictException("conflict", null)).when(microDomainService).deploy(any());
        ResourceDeployRequest request = deployRequest("payments");

        assertThrows(KubeApiConflictException.class, () -> service.deployResource(request));

        verify(microDomainService, times(3)).deploy(any());
    }

    @DisplayName("Does not retry a failure that is not a conflict")
    @Test
    void deployDoesNotRetryANonConflictFailure() {
        resourceBuildSucceeds();
        doThrow(new MicroDomainDeployError("boom", null)).when(microDomainService).deploy(any());
        ResourceDeployRequest request = deployRequest("payments");

        assertThrows(MicroDomainDeployError.class, () -> service.deployResource(request));

        verify(microDomainService, times(1)).deploy(any());
    }
}
