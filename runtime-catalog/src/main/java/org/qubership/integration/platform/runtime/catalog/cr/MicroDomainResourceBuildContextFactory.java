package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.MountOptions;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.strategies.SourceDslConfigMapNamingStrategy;
import org.qubership.integration.platform.camelk.services.BuildInfoFactory;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.adapters.SnapshotAdapter;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.ResourceKey;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.*;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder.destinationRuleCacheKey;
import static org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder.serviceEntryCacheKey;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.CHAIN_ID_LABEL;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;
import static org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil.getName;

@Component
public class MicroDomainResourceBuildContextFactory {

    private final SnapshotRepository snapshotRepository;
    private final MicroDomainService microDomainService;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final BuildInfoFactory buildInfoFactory;
    private final IntegrationServiceCatalog integrationServiceCatalog;
    private final SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy;
    private final AuditorAware<User> auditor;
    private final boolean hostResourcesEnabled;

    @Autowired
    public MicroDomainResourceBuildContextFactory(
            SnapshotRepository snapshotRepository,
            MicroDomainService microDomainService,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,
            BuildInfoFactory buildInfoFactory,
            IntegrationServiceCatalog integrationServiceCatalog,

            @Qualifier("sourceDslConfigMapNamingStrategy")
            SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy,

            AuditorAware<User> auditor,

            @Value("${cip.istio.host-resources.enabled:true}") boolean hostResourcesEnabled
    ) {
        this.snapshotRepository = snapshotRepository;
        this.microDomainService = microDomainService;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.buildInfoFactory = buildInfoFactory;
        this.integrationServiceCatalog = integrationServiceCatalog;
        this.sourceDslConfigMapNamingStrategy = sourceDslConfigMapNamingStrategy;
        this.auditor = auditor;
        this.hostResourcesEnabled = hostResourcesEnabled;
    }

    /**
     * A built context together with what Phase 1 observed while building it. Returned instead of
     * stashing the observation map on the factory itself: this factory is a singleton Spring bean,
     * so a field would be shared across concurrent builds -- the exact class of bug this record
     * exists to help close.
     */
    public record BuildContextWithObservations(
            ResourceBuildContext<List<Snapshot>> context,
            Map<ResourceKey, V1ObjectMeta> observations
    ) { }

    public BuildContextWithObservations createResourceBuildContext(
            ResourceBuildRequest request,
            boolean appendToExising
    ) {
        List<Snapshot> snapshots = snapshotRepository.findAllByIdIn(request.getSnapshotIds())
            .stream()
            .<Snapshot>map(SnapshotAdapter::new)
            .toList();

        ResourceBuildOptions options = copyOptions(request.getOptions());
        String createdBy = auditor.getCurrentAuditor().map(User::getUsername).orElse(null);
        BuildInfo buildInfo = buildInfoFactory.createBuildInfo(options, createdBy);
        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(buildInfo, integrationServiceCatalog)
                .updateTo(snapshots);

        Map<ResourceKey, V1ObjectMeta> observations = new LinkedHashMap<>();

        if (appendToExising) {
            addAppendConfigurationToContext(context, observations);
        }

        // Unlike the rest of addAppendConfigurationToContext, this runs regardless of
        // appendToExising: ServiceEntry/DestinationRule are shared across every domain that targets
        // a given external host, not scoped to this one, so another domain's existing contribution
        // matters even on this domain's very first build.
        if (hostResourcesEnabled) {
            putHostResourceSpecsToBuildCache(context, observations);
        }

        return new BuildContextWithObservations(context, observations);
    }

    /**
     * Copies {@code source} deeply enough that this factory cannot write back into the caller's
     * options. {@code toBuilder().build()} alone is not enough: Lombok copies field references, so
     * the copy would share the caller's {@code MountOptions} instance and
     * {@link #updateIntegrationResources} and {@link #updateIntegrationEmptyDirs} would union the
     * live Integration's mounts straight into it. Any caller that builds twice from one request
     * would then merge against its own previous result: the mount set could only grow, and a mount
     * another writer removed would come back.
     */
    private static ResourceBuildOptions copyOptions(ResourceBuildOptions source) {
        return source.toBuilder()
                .mount(copyMount(source.getMount()))
                .build();
    }

    /** The one nested options object this factory mutates, so the one that needs a real copy. */
    private static MountOptions copyMount(MountOptions source) {
        if (source == null) {
            return null;
        }
        return MountOptions.builder()
                .emptyDirs(source.getEmptyDirs() == null ? new HashSet<>() : new HashSet<>(source.getEmptyDirs()))
                .resources(source.getResources() == null ? new HashSet<>() : new HashSet<>(source.getResources()))
                .build();
    }

    private void addAppendConfigurationToContext(
            ResourceBuildContext<List<Snapshot>> context,
            Map<ResourceKey, V1ObjectMeta> observations
    ) {
        microDomainService
                .getMainIntegrationResources(context.getBuildInfo().getOptions().getName())
                .ifPresent(resources -> {
                    updateIntegrationResources(context, resources.integration());
                    updateIntegrationEmptyDirs(context, resources.integration());
                    putIntegrationsConfigurationToBuildCache(context, resources.integrationsConfiguration());
                    putSourceConfigMapNamesToBuildCache(context, resources);
                    recordAppendObservations(context, resources, observations);
                });
    }

    /**
     * Records what this APPEND read for every object {@code addAppendConfigurationToContext} looks
     * at, so a later write can carry it as an optimistic-concurrency precondition (see
     * {@code MicroDomainService.deploy}).
     *
     * <p>An object that is absent, or has no name, is not recorded: the write derives its lookup key
     * from the generated document's own name, and a missing key leaves the write to resolve the
     * object with a read of its own. The Service, the ServiceMonitor, and the integrations-configuration
     * ConfigMap are per-domain, uncontended objects, so that read costs little.
     */
    private void recordAppendObservations(
            ResourceBuildContext<List<Snapshot>> context,
            MicroDomainService.IntegrationResources resources,
            Map<ResourceKey, V1ObjectMeta> observations
    ) {
        recordIfNamed(observations, "Integration", resources.integration());
        recordIfNamed(observations, "Service", resources.service());
        recordIfNamed(observations, "ServiceMonitor", resources.serviceMonitor());
        recordIfNamed(observations, "ConfigMap", resources.integrationsConfiguration());
        resources.integrationSources().forEach(configMap -> recordIfNamed(observations, "ConfigMap", configMap));
    }

    /** {@code obj}'s own name, or {@code null} when {@code obj} itself is absent. */
    private static String nameOrNull(KubernetesObject obj) {
        return obj == null ? null : getName(obj).orElse(null);
    }

    /** Records {@code live}'s metadata under {@code kind} and its name, when it has one. */
    private void recordIfNamed(
            Map<ResourceKey, V1ObjectMeta> observations,
            String kind,
            KubernetesObject live
    ) {
        String name = nameOrNull(live);
        if (name != null) {
            observations.put(new ResourceKey(kind, name), live.getMetadata());
        }
    }

    private void putIntegrationsConfigurationToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            V1ConfigMap configMap
    ) {
        if (isNull(configMap)) {
            return;
        }
        IntegrationsConfiguration cfg = integrationConfigurationSerdes.getFromConfigMap(configMap);
        String key = getName(configMap).orElse(null);
        context.getBuildCache().put(key, cfg);
    }

    private void putSourceConfigMapNamesToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            MicroDomainService.IntegrationResources resources
    ) {
        Map<String, V1ConfigMap> sourceBySnapshotId = resources.getSourceByLabelMap(SNAPSHOT_ID_LABEL);
        Map<String, V1ConfigMap> sourceByChainId = resources.getSourceByLabelMap(CHAIN_ID_LABEL);
        context.getData().forEach(snapshot -> {
            Optional.ofNullable(sourceBySnapshotId.get(snapshot.getId()))
                    .flatMap(KubeUtil::getName)
                    .ifPresent(name ->
                            sourceDslConfigMapNamingStrategy.useName(context.updateTo(snapshot), name));
            Optional.ofNullable(sourceByChainId.get(snapshot.getChain().getId()))
                    .flatMap(KubeUtil::getName)
                    .ifPresent(name ->
                            sourceDslConfigMapNamingStrategy.useName(context.updateTo(snapshot), name));
        });
    }

    /**
     * Seeds every existing {@code ServiceEntry}/{@code DestinationRule}'s current spec into the
     * build cache, keyed by {@code EgressRouteResourceBuilder}'s host-derived cache keys, so that
     * builder can merge its own port into whatever another domain already contributed for the same
     * host instead of overwriting it, without needing to talk to Kubernetes itself. There's no way
     * to know in advance which hosts this build's routes will touch, so every existing one is fetched
     * and seeded; {@code EgressRouteResourceBuilder} looks up only the keys it actually needs.
     *
     * <p>Skipping this call is safe only while {@code EgressRouteResourceBuilder} skips generating
     * those resources, which is why both read the same {@code cip.istio.host-resources.enabled}.
     * Keep the two gates in step. A build that generates a {@code ServiceEntry} or
     * {@code DestinationRule} from an unseeded cache sees an empty existing spec, and since the
     * document is written with a PUT, every field it does not carry is deleted from the cluster --
     * an operator's {@code tls.credentialName} and every other domain's ports along with it. The
     * write also loses the {@code resourceVersion} precondition recorded here, so it falls back to
     * a write-time read and stops detecting a concurrent update.
     */
    private void putHostResourceSpecsToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            Map<ResourceKey, V1ObjectMeta> observations
    ) {
        for (KubeCustomObject serviceEntry : microDomainService.getExistingServiceEntries()) {
            getName(serviceEntry).ifPresent(name -> {
                context.getBuildCache().put(serviceEntryCacheKey(name), serviceEntry.getSpec());
                recordIfNamed(observations, "ServiceEntry", serviceEntry);
            });
        }
        for (KubeCustomObject destinationRule : microDomainService.getExistingDestinationRules()) {
            getName(destinationRule).ifPresent(name -> {
                context.getBuildCache().put(destinationRuleCacheKey(name), destinationRule.getSpec());
                recordIfNamed(observations, "DestinationRule", destinationRule);
            });
        }
    }

    private void updateIntegrationResources(
            ResourceBuildContext<List<Snapshot>> context,
            CamelKIntegration integration
    ) {
        ResourceBuildOptions options = context.getBuildInfo().getOptions();
        Set<String> resources = new HashSet<>(Optional.ofNullable(integration.getSpec())
                .map(CamelKIntegration.IntegrationSpec::getTraits)
                .map(CamelKIntegration.IntegrationSpec.Traits::getMount)
                .map(CamelKIntegration.IntegrationSpec.Traits.MountTrait::getResources)
                .orElse(Collections.emptyList()));
        resources.addAll(Optional.ofNullable(options.getMount().getResources()).orElse(Collections.emptySet()));
        options.getMount().setResources(resources);
    }

    private void updateIntegrationEmptyDirs(
        ResourceBuildContext<List<Snapshot>> context,
        CamelKIntegration integration
    ) {
        ResourceBuildOptions options = context.getBuildInfo().getOptions();
        Set<String> emptyDirs = new HashSet<>(Optional.ofNullable(integration.getSpec())
                .map(CamelKIntegration.IntegrationSpec::getTraits)
                .map(CamelKIntegration.IntegrationSpec.Traits::getMount)
                .map(CamelKIntegration.IntegrationSpec.Traits.MountTrait::getEmptyDirs)
                .orElse(Collections.emptyList()));
        emptyDirs.addAll(options.getMount().getEmptyDirs());
        options.getMount().setEmptyDirs(emptyDirs);
    }
}
