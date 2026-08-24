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
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private SnapshotRepository snapshotRepository;
    private NamingStrategy<BuildNamingContext> buildNamingStrategy;
    private MicroDomainService microDomainService;
    private IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private SourceDslConfigMapNamingStrategy sourceDslConfigMapNamingStrategy;
    private MicroDomainResourceBuildContextFactory factory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        snapshotRepository = mock(SnapshotRepository.class);
        buildNamingStrategy = mock(NamingStrategy.class);
        microDomainService = mock(MicroDomainService.class);
        integrationConfigurationSerdes = mock(IntegrationConfigurationSerdes.class);
        sourceDslConfigMapNamingStrategy = mock(SourceDslConfigMapNamingStrategy.class);
        when(snapshotRepository.findAllByIdIn(any())).thenReturn(List.of());
        when(buildNamingStrategy.getName(any())).thenReturn(BUILD_NAME);
        factory = new MicroDomainResourceBuildContextFactory(
                snapshotRepository,
                buildNamingStrategy,
                microDomainService,
                integrationConfigurationSerdes,
                sourceDslConfigMapNamingStrategy);
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

    private V1ConfigMap configMap(String name, Map<String, String> labels) {
        return new V1ConfigMap().metadata(new V1ObjectMeta().name(name).labels(labels));
    }

    private MicroDomainService.IntegrationResources resources(
            CamelKIntegration integration,
            V1ConfigMap integrationsConfiguration,
            List<V1ConfigMap> sources
    ) {
        return new MicroDomainService.IntegrationResources(
                integration, null, null, integrationsConfiguration, sources, null, List.of());
    }

    @DisplayName("Stamps a fresh build id and the strategy name onto the context, and skips the append step")
    @Test
    void buildsContextWithGeneratedBuildInfo() {
        ResourceBuildContext<List<Snapshot>> context =
                factory.createResourceBuildContext(request(options()), false);

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
                factory.createResourceBuildContext(request(options), true);

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
                factory.createResourceBuildContext(request(options), true);

        MountOptions merged = context.getBuildInfo().getOptions().getMount();
        assertEquals(Set.of("/from-integration", "/from-options"), merged.getResources());
        assertEquals(Set.of("/dir-integration", "/dir-options"), merged.getEmptyDirs());
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
                factory.createResourceBuildContext(request(options()), true);

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
}
