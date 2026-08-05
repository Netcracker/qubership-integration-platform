package org.qubership.integration.platform.runtime.catalog.cr;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.BuildNamingContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.SourceDslConfigMapNamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomResourceBuildContextFactoryTest {

    @Test
    void appendModeCachesExistingHttpRouteRules() {
        SnapshotRepository snapshotRepository = mock(SnapshotRepository.class);
        when(snapshotRepository.findAllByIdIn(any())).thenReturn(List.of());

        NamingStrategy<BuildNamingContext> buildNamingStrategy = ctx -> "build-1";
        CustomResourceService customResourceService = mock(CustomResourceService.class);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", List.of(Map.of("matches", List.of(Map.of("path", Map.of("value", "/qip-routes/a"))))));
        KubeCustomObject publicRoute = new KubeCustomObject();
        publicRoute.setSpec(spec);

        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(new CamelKIntegration.IntegrationSpec());
        CustomResourceService.IntegrationResources resources = new CustomResourceService.IntegrationResources(
                integration, null, null, null, List.of(), null, List.of(), publicRoute, null);
        when(customResourceService.getMainIntegrationResources("my-domain")).thenReturn(Optional.of(resources));

        CustomResourceBuildContextFactory factory = new CustomResourceBuildContextFactory(
                snapshotRepository,
                buildNamingStrategy,
                customResourceService,
                mock(IntegrationConfigurationSerdes.class),
                ctx -> "integration-name",
                mock(SourceDslConfigMapNamingStrategy.class)
        );

        ResourceBuildRequest request = ResourceBuildRequest.builder()
                .options(ResourceBuildOptions.builder().name("my-domain").build())
                .snapshotIds(List.of())
                .build();

        ResourceBuildContext<List<Snapshot>> context = factory.createResourceBuildContext(request, true);

        assertEquals(spec, context.getBuildCache().get("publicHttpRoute"));
    }
}
