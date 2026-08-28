package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.MountOptions;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.strategies.BuildNamingContext;
import org.qubership.integration.platform.camelk.naming.strategies.SourceDslConfigMapNamingStrategy;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.adapters.SnapshotAdapter;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.ResourceKey;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder.EGRESS_HTTP_ROUTE_CACHE_KEY;
import static org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder.destinationRuleCacheKey;
import static org.qubership.integration.platform.camelk.builders.chain.EgressRouteResourceBuilder.serviceEntryCacheKey;
import static org.qubership.integration.platform.camelk.builders.chain.HttpRouteResourceBuilder.PRIVATE_HTTP_ROUTE_CACHE_KEY;
import static org.qubership.integration.platform.camelk.builders.chain.HttpRouteResourceBuilder.PUBLIC_HTTP_ROUTE_CACHE_KEY;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.CHAIN_ID_LABEL;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;
import static org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil.getName;

@Component
public class MicroDomainResourceBuildContextFactory {
    private static final String HTTP_ROUTE_KIND = "HTTPRoute";

    private final SnapshotRepository snapshotRepository;
    private final NamingStrategy<BuildNamingContext> buildNamingStrategy;
    private final MicroDomainService microDomainService;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private final boolean hostResourcesEnabled;

    @Autowired
    public MicroDomainResourceBuildContextFactory(
            SnapshotRepository snapshotRepository,
            NamingStrategy<BuildNamingContext> buildNamingStrategy,
            MicroDomainService microDomainService,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,

            @Qualifier("sourceDslConfigMapNamingStrategy")
            SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy,

            // Needed only to name a tier HTTPRoute that Phase 1 looked for and did not find, so its
            // absence can be recorded under the real key Phase 2 will later generate (see
            // recordAppendObservations). MicroDomainService already carries the identical three
            // strategies for the same reason; this is a second Spring-managed reference to the same
            // beans, not a duplicate implementation.
            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,
            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,
            @Qualifier("httpRouteEgressNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,

            @Value("${qip.istio.host-resources.enabled:true}") boolean hostResourcesEnabled
    ) {
        this.snapshotRepository = snapshotRepository;
        this.buildNamingStrategy = buildNamingStrategy;
        this.microDomainService = microDomainService;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.sourceDslConfigMapNamingStrategy = sourceDslConfigMapNamingStrategy;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.httpRouteEgressNamingStrategy = httpRouteEgressNamingStrategy;
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
            Map<ResourceKey, Optional<V1ObjectMeta>> observations
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
        BuildInfo buildInfo = createBuildInfo(options);
        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(buildInfo)
                .updateTo(snapshots);

        Map<ResourceKey, Optional<V1ObjectMeta>> observations = new LinkedHashMap<>();

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

    private BuildInfo createBuildInfo(ResourceBuildOptions options) {
        String id = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        BuildNamingContext buildNamingContext = BuildNamingContext.builder()
                .id(id)
                .timestamp(timestamp)
                .build();
        return BuildInfo.builder()
                .id(id)
                .timestamp(timestamp)
                .name(buildNamingStrategy.getName(buildNamingContext))
                .options(options)
                .build();
    }

    private void addAppendConfigurationToContext(
            ResourceBuildContext<List<Snapshot>> context,
            Map<ResourceKey, Optional<V1ObjectMeta>> observations
    ) {
        microDomainService
                .getMainIntegrationResources(context.getBuildInfo().getOptions().getName())
                .ifPresent(resources -> {
                    updateIntegrationResources(context, resources.integration());
                    updateIntegrationEmptyDirs(context, resources.integration());
                    putIntegrationsConfigurationToBuildCache(context, resources.integrationsConfiguration());
                    putSourceConfigMapNamesToBuildCache(context, resources);
                    putHttpRouteRulesToBuildCache(context, resources);
                    recordAppendObservations(context, resources, observations);
                });
    }

    /**
     * Records what this APPEND read for every object {@code addAppendConfigurationToContext} looks
     * at, so a later write can carry it as an optimistic-concurrency precondition (see
     * {@code MicroDomainService.deploy}).
     *
     * <p>The Integration is never null here: reaching this method at all means
     * {@code getMainIntegrationResources} found one. The tier HTTPRoutes are named independently of
     * whether they were found, via the same naming strategies {@code HttpRouteResourceBuilder} uses
     * to name them in the generated YAML, so an absent tier is recorded under the exact key Phase 3
     * will look up -- recording it as observed-absent under that key lets the write create it
     * directly, without paying for a GET first. Those tiers are worth the naming machinery because
     * they are the objects a concurrent deploy of the same domain can actually contend on.
     *
     * <p>Service, ServiceMonitor, and the integrations-configuration ConfigMap have no such strategy
     * wired into this factory, so when one of them is absent {@link #recordIfNamed} skips it rather
     * than recording an entry under a name nothing could ever look up: the write derives its lookup
     * key from the generated document's own name, so an unnamed entry could never match, and would
     * misreport "never looked" as "looked, confirmed absent". Skipping leaves the key entirely out
     * of the map, so the write correctly falls through to Task 1's write-time GET-then-PUT for that
     * one kind -- the same behavior as before this optimization existed, just without the false
     * "confirmed absent" claim. These three are per-domain, uncontended objects, so the GET they pay
     * for on the "never observed" branch is not worth three more naming strategies wired into this
     * factory to avoid.
     */
    private void recordAppendObservations(
            ResourceBuildContext<List<Snapshot>> context,
            MicroDomainService.IntegrationResources resources,
            Map<ResourceKey, Optional<V1ObjectMeta>> observations
    ) {
        recordObservation(observations, "Integration", nameOrNull(resources.integration()), resources.integration());
        recordIfNamed(observations, "Service", resources.service());
        recordIfNamed(observations, "ServiceMonitor", resources.serviceMonitor());
        recordIfNamed(observations, "ConfigMap", resources.integrationsConfiguration());
        resources.integrationSources().forEach(configMap -> recordIfNamed(observations, "ConfigMap", configMap));
        recordObservation(observations, HTTP_ROUTE_KIND,
                httpRoutePublicNamingStrategy.getName(context), resources.publicHttpRoute());
        recordObservation(observations, HTTP_ROUTE_KIND,
                httpRoutePrivateNamingStrategy.getName(context), resources.privateHttpRoute());
        recordObservation(observations, HTTP_ROUTE_KIND,
                httpRouteEgressNamingStrategy.getName(context), resources.egressHttpRoute());
    }

    /** {@code obj}'s own name, or {@code null} when {@code obj} itself is absent. */
    private static String nameOrNull(KubernetesObject obj) {
        return obj == null ? null : getName(obj).orElse(null);
    }

    /**
     * Records an observation only when {@code live} resolves to a name. The write derives its
     * lookup key from the generated document's own name, so an entry with no name could never
     * match -- and would misreport "never looked" as "looked, confirmed absent".
     */
    private void recordIfNamed(
            Map<ResourceKey, Optional<V1ObjectMeta>> observations,
            String kind,
            KubernetesObject live
    ) {
        String name = nameOrNull(live);
        if (name != null) {
            recordObservation(observations, kind, name, live);
        }
    }

    /**
     * Records what Phase 1 saw for one {@code (kind, name)} slot: {@code live}'s metadata when it
     * was found, or {@link Optional#empty()} when this call site looked and found nothing. A slot
     * this method is never called for stays entirely absent from the map, which
     * {@code MicroDomainService.deploy} reads as "Phase 1 never looked" -- the third state, distinct
     * from both of the ones this method produces.
     */
    private void recordObservation(
            Map<ResourceKey, Optional<V1ObjectMeta>> observations,
            String kind,
            String name,
            KubernetesObject live
    ) {
        observations.put(new ResourceKey(kind, name),
                live == null ? Optional.empty() : Optional.ofNullable(live.getMetadata()));
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

    private void putHttpRouteRulesToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            MicroDomainService.IntegrationResources resources
    ) {
        if (resources.publicHttpRoute() != null) {
            context.getBuildCache().put(PUBLIC_HTTP_ROUTE_CACHE_KEY, resources.publicHttpRoute().getSpec());
        }
        if (resources.privateHttpRoute() != null) {
            context.getBuildCache().put(PRIVATE_HTTP_ROUTE_CACHE_KEY, resources.privateHttpRoute().getSpec());
        }
        if (resources.egressHttpRoute() != null) {
            context.getBuildCache().put(EGRESS_HTTP_ROUTE_CACHE_KEY, resources.egressHttpRoute().getSpec());
        }
    }

    /**
     * Seeds every existing {@code ServiceEntry}/{@code DestinationRule}'s current spec into the
     * build cache, keyed by {@code EgressRouteResourceBuilder}'s host-derived cache keys, so that
     * builder can merge its own port into whatever another domain already contributed for the same
     * host instead of overwriting it -- without needing to talk to Kubernetes itself. Unlike
     * {@link #putHttpRouteRulesToBuildCache} (three fixed, per-domain-named CRs), there's no way to
     * know in advance which hosts this build's routes will touch, so every existing one is fetched
     * and seeded; {@code EgressRouteResourceBuilder} looks up only the keys it actually needs.
     *
     * <p>Skipping this call is safe only while {@code EgressRouteResourceBuilder} skips generating
     * those resources, which is why both read the same {@code qip.istio.host-resources.enabled}.
     * Keep the two gates in step. A build that generates a {@code ServiceEntry} or
     * {@code DestinationRule} from an unseeded cache sees an empty existing spec, and since the
     * document is written with a PUT, every field it does not carry is deleted from the cluster --
     * an operator's {@code tls.credentialName} and every other domain's ports along with it. The
     * write also loses the {@code resourceVersion} precondition recorded here, so it falls back to
     * a write-time read and stops detecting a concurrent update.
     */
    private void putHostResourceSpecsToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            Map<ResourceKey, Optional<V1ObjectMeta>> observations
    ) {
        for (KubeCustomObject serviceEntry : microDomainService.getExistingServiceEntries()) {
            getName(serviceEntry).ifPresent(name -> {
                context.getBuildCache().put(serviceEntryCacheKey(name), serviceEntry.getSpec());
                recordObservation(observations, "ServiceEntry", name, serviceEntry);
            });
        }
        for (KubeCustomObject destinationRule : microDomainService.getExistingDestinationRules()) {
            getName(destinationRule).ifPresent(name -> {
                context.getBuildCache().put(destinationRuleCacheKey(name), destinationRule.getSpec());
                recordObservation(observations, "DestinationRule", name, destinationRule);
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
