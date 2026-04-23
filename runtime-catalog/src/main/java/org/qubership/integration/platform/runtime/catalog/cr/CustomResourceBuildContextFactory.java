package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.BuildNamingContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.SourceDslConfigMapNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.cr.builders.chain.SourceConfigMapBuilder.CHAIN_ID_LABEL;
import static org.qubership.integration.platform.runtime.catalog.cr.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;
import static org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil.getName;

@Component
public class CustomResourceBuildContextFactory {
    private final SnapshotRepository snapshotRepository;
    private final NamingStrategy<BuildNamingContext> buildNamingStrategy;
    private final CustomResourceService customResourceService;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy;

    @Autowired
    public CustomResourceBuildContextFactory(
            SnapshotRepository snapshotRepository,
            NamingStrategy<BuildNamingContext> buildNamingStrategy,
            CustomResourceService customResourceService,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Qualifier("sourceDslConfigMapNamingStrategy")
            SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy
    ) {
        this.snapshotRepository = snapshotRepository;
        this.buildNamingStrategy = buildNamingStrategy;
        this.customResourceService = customResourceService;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.sourceDslConfigMapNamingStrategy = sourceDslConfigMapNamingStrategy;
    }

    public ResourceBuildContext<List<Snapshot>> createResourceBuildContext(
            ResourceBuildRequest request,
            boolean appendToExising
    ) {
        List<Snapshot> snapshots = snapshotRepository.findAllByIdIn(request.getSnapshotIds());

        ResourceBuildOptions options = appendToExising
                ? request.getOptions().toBuilder()
                        .monitoring(request.getOptions().getMonitoring().toBuilder()
                                .enabled(false)
                                .build())
                        .service(request.getOptions().getService().toBuilder()
                                .enabled(false)
                                .build())
                        .build()
                : request.getOptions().toBuilder().build();
        BuildInfo buildInfo = createBuildInfo(options);
        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(buildInfo)
                .updateTo(snapshots);

        if (appendToExising) {
            addAppendConfigurationToContext(context);
        }

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
        customResourceService
                .getIntegrationResources(integrationResourceNamingStrategy.getName(context))
                .ifPresent(resources -> {
                    updateIntegrationResources(context, resources.integration());
                    putIntegrationsConfigurationToBuildCache(context, resources.integrationsConfiguration());
                    putSourceConfigMapNamesToBuildCache(context, resources);
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
            CustomResourceService.IntegrationResources resources
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

    private void updateIntegrationResources(
            ResourceBuildContext<List<Snapshot>> context,
            CamelKIntegration integration
    ) {
        ResourceBuildOptions options = context.getBuildInfo().getOptions();
        Set<String> resources = new HashSet<>(integration.getSpec().getTraits().getMount().getResources());
        resources.addAll(options.getResources());
        options.setResources(resources);
    }
}
