package org.qubership.integration.platform.runtime.catalog.cr;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.strategies.BuildNamingContext;
import org.qubership.integration.platform.camelk.naming.strategies.SourceDslConfigMapNamingStrategy;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MicroDomainResourceBuildContextFactoryHttpRouteTest {

    @Test
    void appendModeCachesExistingHttpRouteRules() {
        SnapshotRepository snapshotRepository = mock(SnapshotRepository.class);
        when(snapshotRepository.findAllByIdIn(any())).thenReturn(List.of());

        NamingStrategy<BuildNamingContext> buildNamingStrategy = ctx -> "build-1";
        MicroDomainService microDomainService = mock(MicroDomainService.class);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", List.of(Map.of("matches", List.of(Map.of("path", Map.of("value", "/qip-routes/a"))))));
        KubeCustomObject publicRoute = new KubeCustomObject();
        publicRoute.setSpec(spec);

        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(new CamelKIntegration.IntegrationSpec());
        MicroDomainService.IntegrationResources resources = new MicroDomainService.IntegrationResources(
                integration, null, null, null, List.of(), null, List.of(), publicRoute, null, null);
        when(microDomainService.getMainIntegrationResources("my-domain")).thenReturn(Optional.of(resources));

        MicroDomainResourceBuildContextFactory factory = new MicroDomainResourceBuildContextFactory(
                snapshotRepository,
                buildNamingStrategy,
                microDomainService,
                mock(IntegrationConfigurationSerdes.class),
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
