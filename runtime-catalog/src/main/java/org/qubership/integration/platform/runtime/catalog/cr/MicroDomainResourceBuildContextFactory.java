package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.strategies.BuildNamingContext;
import org.qubership.integration.platform.camelk.naming.strategies.SourceDslConfigMapNamingStrategy;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.adapters.SnapshotAdapter;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final SnapshotRepository snapshotRepository;
    private final NamingStrategy<BuildNamingContext> buildNamingStrategy;
    private final MicroDomainService microDomainService;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy;

    @Autowired
    public MicroDomainResourceBuildContextFactory(
            SnapshotRepository snapshotRepository,
            NamingStrategy<BuildNamingContext> buildNamingStrategy,
            MicroDomainService microDomainService,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,

            @Qualifier("sourceDslConfigMapNamingStrategy")
            SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy
    ) {
        this.snapshotRepository = snapshotRepository;
        this.buildNamingStrategy = buildNamingStrategy;
        this.microDomainService = microDomainService;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.sourceDslConfigMapNamingStrategy = sourceDslConfigMapNamingStrategy;
    }

    public ResourceBuildContext<List<Snapshot>> createResourceBuildContext(
            ResourceBuildRequest request,
            boolean appendToExising
    ) {
        List<Snapshot> snapshots = snapshotRepository.findAllByIdIn(request.getSnapshotIds())
            .stream()
            .<Snapshot>map(SnapshotAdapter::new)
            .toList();

        ResourceBuildOptions options = request.getOptions().toBuilder().build();
        BuildInfo buildInfo = createBuildInfo(options);
        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(buildInfo)
                .updateTo(snapshots);

        if (appendToExising) {
            addAppendConfigurationToContext(context);
        }

        // Unlike the rest of addAppendConfigurationToContext, this runs regardless of
        // appendToExising: ServiceEntry/DestinationRule are shared across every domain that targets
        // a given external host, not scoped to this one, so another domain's existing contribution
        // matters even on this domain's very first build.
        putHostResourceSpecsToBuildCache(context);

        return context;
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

    private void addAppendConfigurationToContext(ResourceBuildContext<List<Snapshot>> context) {
        microDomainService
                .getMainIntegrationResources(context.getBuildInfo().getOptions().getName())
                .ifPresent(resources -> {
                    updateIntegrationResources(context, resources.integration());
                    updateIntegrationEmptyDirs(context, resources.integration());
                    putIntegrationsConfigurationToBuildCache(context, resources.integrationsConfiguration());
                    putSourceConfigMapNamesToBuildCache(context, resources);
                    putHttpRouteRulesToBuildCache(context, resources);
                });
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
     */
    private void putHostResourceSpecsToBuildCache(ResourceBuildContext<List<Snapshot>> context) {
        for (KubeCustomObject serviceEntry : microDomainService.getExistingServiceEntries()) {
            getName(serviceEntry).ifPresent(name ->
                    context.getBuildCache().put(serviceEntryCacheKey(name), serviceEntry.getSpec()));
        }
        for (KubeCustomObject destinationRule : microDomainService.getExistingDestinationRules()) {
            getName(destinationRule).ifPresent(name ->
                    context.getBuildCache().put(destinationRuleCacheKey(name), destinationRule.getSpec()));
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
