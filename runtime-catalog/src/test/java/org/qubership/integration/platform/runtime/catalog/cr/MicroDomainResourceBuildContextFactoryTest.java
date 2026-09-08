/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.MountOptions;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.CHAIN_ID_LABEL;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;

class MicroDomainResourceBuildContextFactoryTest {

    private static final String DOMAIN = "payments";
    private static final String BUILD_NAME = "build-name";
    // The name httpRoutePublicNamingStrategy yields for DOMAIN in this fixture; stubbed below
    // rather than computed by a real strategy instance, since the factory only needs some name
    // that both the mock and the "observed absent" test agree on.
    private static final String PUBLIC_ROUTE_NAME = "payments-chain-public-routes";
    private static final String PRIVATE_ROUTE_NAME = "payments-chain-private-routes";
    private static final String EGRESS_ROUTE_NAME = "payments-chain-egress-routes";

    private SnapshotRepository snapshotRepository;
    private NamingStrategy<BuildNamingContext> buildNamingStrategy;
    private MicroDomainService microDomainService;
    private IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy;
    private NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private MicroDomainResourceBuildContextFactory factory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        snapshotRepository = mock(SnapshotRepository.class);
        buildNamingStrategy = mock(NamingStrategy.class);
        microDomainService = mock(MicroDomainService.class);
        integrationConfigurationSerdes = mock(IntegrationConfigurationSerdes.class);
        sourceDslConfigMapNamingStrategy = mock(SourceDslConfigMapNamingStrategy.class);
        httpRoutePublicNamingStrategy = mock(NamingStrategy.class);
        httpRoutePrivateNamingStrategy = mock(NamingStrategy.class);
        httpRouteEgressNamingStrategy = mock(NamingStrategy.class);
        when(snapshotRepository.findAllByIdIn(any())).thenReturn(List.of());
        when(buildNamingStrategy.getName(any())).thenReturn(BUILD_NAME);
        when(httpRoutePublicNamingStrategy.getName(any())).thenReturn(PUBLIC_ROUTE_NAME);
        when(httpRoutePrivateNamingStrategy.getName(any())).thenReturn(PRIVATE_ROUTE_NAME);
        when(httpRouteEgressNamingStrategy.getName(any())).thenReturn(EGRESS_ROUTE_NAME);
        factory = factoryWithHostResources(true);
    }

    private MicroDomainResourceBuildContextFactory factoryWithHostResources(boolean hostResourcesEnabled) {
        return new MicroDomainResourceBuildContextFactory(
                snapshotRepository,
                buildNamingStrategy,
                microDomainService,
                integrationConfigurationSerdes,
                sourceDslConfigMapNamingStrategy,
                httpRoutePublicNamingStrategy,
                httpRoutePrivateNamingStrategy,
                httpRouteEgressNamingStrategy,
                hostResourcesEnabled);
    }

    private ResourceBuildRequest request(ResourceBuildOptions options) {
        return ResourceBuildRequest.builder()
                .options(options)
                .snapshotIds(List.of("snap-1"))
                .build();
    }

    private ResourceBuildOptions options() {
        return ResourceBuildOptions.builder().name(DOMAIN).build();
    }

    private ResourceBuildRequest buildRequest(String domain) {
        return request(ResourceBuildOptions.builder().name(domain).build());
    }

    private V1ConfigMap configMap(String name, Map<String, String> labels) {
        return new V1ConfigMap().metadata(new V1ObjectMeta().name(name).labels(labels));
    }

    private MicroDomainService.IntegrationResources resources(
            CamelKIntegration integration,
            V1ConfigMap integrationsConfiguration,
            List<V1ConfigMap> sources
    ) {
        return resources(integration, integrationsConfiguration, sources, null);
    }

    private MicroDomainService.IntegrationResources resources(
            CamelKIntegration integration,
            V1ConfigMap integrationsConfiguration,
            List<V1ConfigMap> sources,
            KubeCustomObject publicHttpRoute
    ) {
        return new MicroDomainService.IntegrationResources(
                integration, null, null, integrationsConfiguration, sources, null, List.of(),
                publicHttpRoute, null, null);
    }

    /** IntegrationResources with only the fields a given test cares about; the rest read as absent. */
    private MicroDomainService.IntegrationResources resources(
            CamelKIntegration integration, KubeCustomObject publicHttpRoute) {
        return new MicroDomainService.IntegrationResources(
                integration, null, null, null, List.of(), null, List.of(),
                publicHttpRoute, null, null);
    }

    private CamelKIntegration integrationWithVersion(String name, String resourceVersion) {
        CamelKIntegration integration = new CamelKIntegration();
        integration.setMetadata(new V1ObjectMeta().name(name).resourceVersion(resourceVersion));
        return integration;
    }

    private KubeCustomObject hostResource(String kind, String name, String resourceVersion) {
        KubeCustomObject object = new KubeCustomObject();
        object.setKind(kind);
        object.setMetadata(new V1ObjectMeta().name(name).resourceVersion(resourceVersion));
        object.setSpec(new LinkedHashMap<>());
        return object;
    }

    @DisplayName("Stamps a fresh build id and the strategy name onto the context, and skips the append step")
    @Test
    void buildsContextWithGeneratedBuildInfo() {
        ResourceBuildContext<List<Snapshot>> context =
                factory.createResourceBuildContext(request(options()), false).context();

        assertEquals(BUILD_NAME, context.getBuildInfo().getName());
        assertEquals(DOMAIN, context.getBuildInfo().getOptions().getName());
        assertDoesNotThrow(() -> UUID.fromString(context.getBuildInfo().getId()),
                "the build id must be a generated UUID");
        assertTrue(context.getData().isEmpty());
        verify(snapshotRepository).findAllByIdIn(List.of("snap-1"));
        verify(microDomainService, never()).getMainIntegrationResources(any());
    }

    @DisplayName("Leaves the options untouched when appending finds no existing resources")
    @Test
    void appendWithNoResourcesLeavesOptionsUntouched() {
        when(microDomainService.getMainIntegrationResources(DOMAIN)).thenReturn(java.util.Optional.empty());
        ResourceBuildOptions options = options();
        options.getMount().setResources(Set.of("/from-options"));

        ResourceBuildContext<List<Snapshot>> context =
                factory.createResourceBuildContext(request(options), true).context();

        assertEquals(Set.of("/from-options"), context.getBuildInfo().getOptions().getMount().getResources());
        verify(microDomainService).getMainIntegrationResources(DOMAIN);
    }

    @DisplayName("Merges the integration's mount resources and empty dirs into the build options")
    @Test
    void appendMergesMountResourcesAndEmptyDirs() {
        CamelKIntegration integration = new CamelKIntegration();
        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait(
                        List.of("/from-integration"), List.of("/dir-integration"), false);
        CamelKIntegration.IntegrationSpec.Traits traits =
                new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        integration.setSpec(spec);
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(java.util.Optional.of(resources(integration, null, List.of())));

        ResourceBuildOptions options = options();
        options.getMount().setResources(new java.util.HashSet<>(Set.of("/from-options")));
        options.getMount().setEmptyDirs(new java.util.HashSet<>(Set.of("/dir-options")));

        ResourceBuildContext<List<Snapshot>> context =
                factory.createResourceBuildContext(request(options), true).context();

        MountOptions merged = context.getBuildInfo().getOptions().getMount();
        assertEquals(Set.of("/from-integration", "/from-options"), merged.getResources());
        assertEquals(Set.of("/dir-integration", "/dir-options"), merged.getEmptyDirs());
    }

    @DisplayName("Merges into its own copy of the mount options, leaving the caller's request untouched")
    @Test
    void appendDoesNotWriteBackIntoTheRequestedOptions() {
        CamelKIntegration integration = new CamelKIntegration();
        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait(
                        List.of("/from-integration"), List.of("/dir-integration"), false);
        CamelKIntegration.IntegrationSpec.Traits traits =
                new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        integration.setSpec(spec);
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(java.util.Optional.of(resources(integration, null, List.of())));

        ResourceBuildOptions options = options();
        options.getMount().setResources(new java.util.HashSet<>(Set.of("/from-options")));
        options.getMount().setEmptyDirs(new java.util.HashSet<>(Set.of("/dir-options")));
        MountOptions requested = options.getMount();

        factory.createResourceBuildContext(request(options), true);

        // toBuilder().build() copies the reference, so without a real copy the merge would land in
        // the caller's own MountOptions and a second build from the same request would union against
        // the first build's result.
        assertEquals(Set.of("/from-options"), requested.getResources(),
                "the merge must not reach the mount options the caller still holds");
        assertEquals(Set.of("/dir-options"), requested.getEmptyDirs());
    }

    @DisplayName("Caches the parsed integrations configuration under the config map name")
    @Test
    void appendCachesIntegrationsConfiguration() {
        CamelKIntegration integration = new CamelKIntegration();
        V1ConfigMap cfgMap = configMap("cfg-map", null);
        IntegrationsConfiguration parsed = new IntegrationsConfiguration();
        when(integrationConfigurationSerdes.getFromConfigMap(cfgMap)).thenReturn(parsed);
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(java.util.Optional.of(resources(integration, cfgMap, List.of())));

        ResourceBuildContext<List<Snapshot>> context =
                factory.createResourceBuildContext(request(options()), true).context();

        assertSame(parsed, context.getBuildCache().get("cfg-map"));
    }

    @DisplayName("Reuses existing source config map names keyed by both snapshot id and chain id")
    @Test
    void appendReusesExistingSourceConfigMapNames() {
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain chain =
                new org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain();
        chain.setId("chain-1");
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot snapshot =
                new org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot();
        snapshot.setId("snap-1");
        snapshot.setChain(chain);
        when(snapshotRepository.findAllByIdIn(any())).thenReturn(List.of(snapshot));

        CamelKIntegration integration = new CamelKIntegration();
        V1ConfigMap sourceBySnapshot = configMap("src-snap", Map.of(SNAPSHOT_ID_LABEL, "snap-1"));
        V1ConfigMap sourceByChain = configMap("src-chain", Map.of(CHAIN_ID_LABEL, "chain-1"));
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(java.util.Optional.of(
                        resources(integration, null, List.of(sourceBySnapshot, sourceByChain))));

        factory.createResourceBuildContext(request(options()), true);

        verify(sourceDslConfigMapNamingStrategy).useName(any(), eq("src-snap"));
        verify(sourceDslConfigMapNamingStrategy).useName(any(), eq("src-chain"));
    }

    @DisplayName("Caches the public tier's existing HTTPRoute rules so append-mode builds can preserve them")
    @Test
    void appendModeCachesExistingHttpRouteRules() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", List.of(Map.of("matches", List.of(Map.of("path", Map.of("value", "/qip-routes/a"))))));
        KubeCustomObject publicRoute = new KubeCustomObject();
        publicRoute.setSpec(spec);

        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(new CamelKIntegration.IntegrationSpec());
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(java.util.Optional.of(resources(integration, null, List.of(), publicRoute)));

        ResourceBuildContext<List<Snapshot>> context =
                factory.createResourceBuildContext(request(options()), true).context();

        assertEquals(spec, context.getBuildCache().get("publicHttpRoute"));
    }

    @Test
    void recordsTheLiveMetadataOfEveryObjectItReadUnderAppendMode() {
        CamelKIntegration integration = integrationWithVersion("int-res", "42");
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(Optional.of(resources(integration, null)));

        var built = factory.createResourceBuildContext(buildRequest(DOMAIN), true);

        Optional<V1ObjectMeta> observed =
                built.observations().get(new MicroDomainService.ResourceKey("Integration", "int-res"));
        assertNotNull(observed, "an object Phase 1 read must be recorded, not omitted");
        assertTrue(observed.isPresent());
        assertEquals("42", observed.get().getResourceVersion());
    }

    @Test
    void recordsAnAbsentObjectAsObservedEmptyRatherThanOmittingIt() {
        // publicHttpRoute is null: getMainIntegrationResources looked and found nothing.
        when(microDomainService.getMainIntegrationResources(DOMAIN))
                .thenReturn(Optional.of(resources(integrationWithVersion("int-res", "42"), null)));

        var built = factory.createResourceBuildContext(buildRequest(DOMAIN), true);

        Optional<V1ObjectMeta> observed = built.observations()
                .get(new MicroDomainService.ResourceKey("HTTPRoute", PUBLIC_ROUTE_NAME));
        assertNotNull(observed, "looked-and-absent must be distinguishable from never-looked");
        assertTrue(observed.isEmpty());
    }

    @Test
    void skipsHostResourceSpecsWhenHostResourcesAreDisabled() {
        var built = factoryWithHostResources(false).createResourceBuildContext(buildRequest(DOMAIN), false);

        assertFalse(
                built.observations().containsKey(new MicroDomainService.ResourceKey("ServiceEntry", "example-com")),
                "nothing writes host resources when the flag is off, so nothing needs their preconditions");
        // The flag also spares every build two cluster-wide LIST calls.
        verify(microDomainService, never()).getExistingServiceEntries();
        verify(microDomainService, never()).getExistingDestinationRules();
    }

    // The test that fails if someone collapses the map to two states.
    @Test
    void omitsKeysItNeverLookedAtUnderRewriteMode() {
        when(microDomainService.getExistingServiceEntries())
                .thenReturn(List.of(hostResource("ServiceEntry", "example-com", "7")));
        when(microDomainService.getExistingDestinationRules()).thenReturn(List.of());

        var built = factory.createResourceBuildContext(buildRequest(DOMAIN), false);

        assertFalse(
                built.observations().containsKey(new MicroDomainService.ResourceKey("Integration", "int-res")),
                "REWRITE performs no Phase 1 read of the Integration, so its key must be absent, "
                        + "not present-and-empty -- present-and-empty would make the write attempt a create");
        assertTrue(
                built.observations().containsKey(new MicroDomainService.ResourceKey("ServiceEntry", "example-com")),
                "host resources are read in both modes and must still be recorded");
        verify(microDomainService, never()).getMainIntegrationResources(anyString());
    }
}
