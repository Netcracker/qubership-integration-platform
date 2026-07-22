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

import com.coreos.monitoring.models.V1ServiceMonitor;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.camelk.builders.IntegrationsConfigurationConfigMapBuilder;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.integrations.configuration.SourceDefinition;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;

class MicroDomainServiceTest {

    private static final String DOMAIN = "payments";
    private static final String INTEGRATION_RESOURCE_NAME = "int-res";
    private static final String CFG_CONFIG_MAP_NAME = "int-cfg";

    private KubeOperator kubeOperator;
    private NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy;
    private IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private GenericCustomResources genericCustomResources;
    private IntegrationServiceCatalog integrationServiceCatalog;

    @SuppressWarnings("unchecked")
    private MicroDomainService newService(boolean monitoringEnabled) {
        MicroDomainService service = new MicroDomainService(
                kubeOperator,
                integrationResourceNamingStrategy,
                integrationsConfigurationConfigMapNamingStrategy,
                integrationConfigurationSerdes,
                genericCustomResources,
                integrationServiceCatalog,
                monitoringEnabled);
        // The @Value fields are package-private, so the test in this package sets them directly.
        service.domainLabel = "qip.domain";
        service.bgVersionLabel = "qip.bgVersion";
        service.bgVersion = "v1";
        return service;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kubeOperator = mock(KubeOperator.class);
        integrationResourceNamingStrategy = mock(NamingStrategy.class);
        integrationsConfigurationConfigMapNamingStrategy = mock(NamingStrategy.class);
        integrationConfigurationSerdes = mock(IntegrationConfigurationSerdes.class);
        genericCustomResources = mock(GenericCustomResources.class);
        integrationServiceCatalog = mock(IntegrationServiceCatalog.class);
    }

    private void stubNamingStrategies() {
        when(integrationResourceNamingStrategy.getName(any())).thenReturn(INTEGRATION_RESOURCE_NAME);
        when(integrationsConfigurationConfigMapNamingStrategy.getName(any())).thenReturn(CFG_CONFIG_MAP_NAME);
    }

    private V1ConfigMap configMap(String name, Map<String, String> labels) {
        return new V1ConfigMap().metadata(new V1ObjectMeta().name(name).labels(labels));
    }

    // ---- getIntegrationResources branches ----

    @DisplayName("Returns empty and skips further lookups when no integration matches the domain")
    @Test
    void returnsEmptyWhenNoIntegrationFound() {
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of());

        MicroDomainService service = newService(false);
        Optional<MicroDomainService.IntegrationResources> result =
                service.getMainIntegrationResources(DOMAIN);

        assertTrue(result.isEmpty());
        verify(kubeOperator, never()).getServicesByLabel(anyString(), anyString());
        verify(kubeOperator, never()).getConfigMapsByLabel(anyString(), anyString());
    }

    @DisplayName("Splits config maps into the integrations-configuration map and the remaining sources")
    @Test
    void partitionsConfigMapsIntoConfigurationAndSources() {
        stubNamingStrategies();
        CamelKIntegration integration = new CamelKIntegration();
        V1Service service1 = new V1Service().metadata(new V1ObjectMeta().name("svc"));
        V1ConfigMap cfg = configMap(CFG_CONFIG_MAP_NAME, null);
        V1ConfigMap source1 = configMap("source-1", null);
        V1ConfigMap source2 = configMap("source-2", null);
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of(service1));
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString()))
                .thenReturn(List.of(cfg, source1, source2));

        MicroDomainService service = newService(false);
        MicroDomainService.IntegrationResources resources =
                service.getMainIntegrationResources(DOMAIN).orElseThrow();

        assertSame(integration, resources.integration());
        assertSame(service1, resources.service());
        assertSame(cfg, resources.integrationsConfiguration());
        assertEquals(List.of(source1, source2), resources.integrationSources());
        assertNull(resources.serviceMonitor(), "monitoring is disabled, so no monitor is fetched");
        assertNull(resources.secret(), "the main view omits the secret");
        assertTrue(resources.customResources().isEmpty());
        verify(kubeOperator, never()).getServiceMonitorsByLabel(anyString(), anyString());
        verify(kubeOperator, never()).getSecretsByLabel(anyString(), anyString());
    }

    @DisplayName("Fetches the service monitor only when monitoring is enabled")
    @Test
    void fetchesServiceMonitorWhenMonitoringEnabled() {
        stubNamingStrategies();
        V1ServiceMonitor monitor = new V1ServiceMonitor();
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(new CamelKIntegration()));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getServiceMonitorsByLabel(anyString(), anyString())).thenReturn(List.of(monitor));

        MicroDomainService service = newService(true);
        MicroDomainService.IntegrationResources resources =
                service.getMainIntegrationResources(DOMAIN).orElseThrow();

        assertSame(monitor, resources.serviceMonitor());
    }

    @DisplayName("Adds the secret to the full resource view but leaves it out of the main view")
    @Test
    void includesSecretInAllResourcesView() {
        stubNamingStrategies();
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta().name("secret"));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(new CamelKIntegration()));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getSecretsByLabel(anyString(), anyString())).thenReturn(List.of(secret));
        when(genericCustomResources.getCustomResourceDefinitions()).thenReturn(Map.of());

        MicroDomainService service = newService(false);
        MicroDomainService.IntegrationResources resources =
                service.getAllIntegrationResources(DOMAIN).orElseThrow();

        assertSame(secret, resources.secret());
    }

    // ---- IntegrationResources record ----

    @DisplayName("Groups sources by label value and folds label-less maps under the empty key")
    @Test
    void groupsSourcesByLabel() {
        V1ConfigMap withS1 = configMap("cm-1", Map.of(SNAPSHOT_ID_LABEL, "s1"));
        V1ConfigMap withS2 = configMap("cm-2", Map.of(SNAPSHOT_ID_LABEL, "s2"));
        V1ConfigMap withoutLabel = configMap("cm-3", Map.of());
        MicroDomainService.IntegrationResources resources = new MicroDomainService.IntegrationResources(
                null, null, null, null,
                List.of(withS1, withS2, withoutLabel), null, List.of());

        Map<String, V1ConfigMap> byLabel = resources.getSourceByLabelMap(SNAPSHOT_ID_LABEL);

        assertSame(withS1, byLabel.get("s1"));
        assertSame(withS2, byLabel.get("s2"));
        assertSame(withoutLabel, byLabel.get(""));
    }

    // ---- deploy ----

    @DisplayName("Deploys every document in the manifest through the kube operator")
    @Test
    void deploysEachDocument() throws Exception {
        String manifest = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-1\n";

        MicroDomainService service = newService(false);
        service.deploy(manifest);

        verify(kubeOperator).createOrUpdateResource(any(V1ConfigMap.class));
    }

    @DisplayName("Wraps a failed apply in a MicroDomainDeployError that keeps the cause")
    @Test
    void wrapsDeployFailure() throws Exception {
        String manifest = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-1\n";
        RuntimeException cause = new RuntimeException("boom");
        doThrow(cause).when(kubeOperator).createOrUpdateResource(any());

        MicroDomainService service = newService(false);
        MicroDomainDeployError error =
                assertThrows(MicroDomainDeployError.class, () -> service.deploy(manifest));

        assertSame(cause, error.getCause());
    }

    // ---- delete ----

    @DisplayName("Deletes nothing when the domain has no integration")
    @Test
    void deleteIsNoOpWhenIntegrationAbsent() {
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of());

        MicroDomainService service = newService(false);
        service.delete(DOMAIN);

        verify(kubeOperator, never()).deleteCamelKIntegration(anyString());
        verify(kubeOperator, never()).deleteService(anyString());
        verify(kubeOperator, never()).deleteConfigMap(anyString());
        verify(kubeOperator, never()).deleteSecret(anyString());
    }

    @DisplayName("Deletes the named integration, service, config maps, and secret it discovers")
    @Test
    void deleteRemovesDiscoveredResources() {
        stubNamingStrategies();
        CamelKIntegration integration = new CamelKIntegration();
        integration.setMetadata(new V1ObjectMeta().name(INTEGRATION_RESOURCE_NAME));
        V1Service service1 = new V1Service().metadata(new V1ObjectMeta().name("svc"));
        V1ConfigMap cfg = configMap(CFG_CONFIG_MAP_NAME, null);
        V1ConfigMap source1 = configMap("source-1", null);
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta().name("secret"));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of(service1));
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of(cfg, source1));
        when(kubeOperator.getSecretsByLabel(anyString(), anyString())).thenReturn(List.of(secret));
        when(genericCustomResources.getCustomResourceDefinitions()).thenReturn(Map.of());

        MicroDomainService service = newService(false);
        service.delete(DOMAIN);

        verify(kubeOperator).deleteCamelKIntegration(INTEGRATION_RESOURCE_NAME);
        verify(kubeOperator).deleteService("svc");
        verify(kubeOperator).deleteConfigMap(CFG_CONFIG_MAP_NAME);
        verify(kubeOperator).deleteConfigMap("source-1");
        verify(kubeOperator).deleteSecret("secret");
    }

    // ---- deleteChainSnapshot ----

    @DisplayName("Does nothing when the domain has no integration")
    @Test
    void deleteChainSnapshotIsNoOpWhenIntegrationAbsent() {
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of());

        MicroDomainService service = newService(false);
        service.deleteChainSnapshot(DOMAIN, "s1");

        verify(kubeOperator, never()).createOrUpdateResource(any());
        verify(kubeOperator, never()).deleteConfigMap(anyString());
    }

    @DisplayName("Unmounts the snapshot source, drops its configuration entry, and deletes its config map")
    @Test
    void deleteChainSnapshotRemovesTheSnapshotSourceMountAndConfiguration() {
        stubNamingStrategies();

        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait();
        mount.setResources(new ArrayList<>(List.of("configmap:src-s1/x", "configmap:keep/y")));
        CamelKIntegration.IntegrationSpec.Traits traits = new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(spec);

        V1ConfigMap cfg = configMap(CFG_CONFIG_MAP_NAME, null);
        V1ConfigMap source = configMap("src-s1", Map.of(SNAPSHOT_ID_LABEL, "s1"));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of(cfg, source));

        IntegrationsConfiguration configuration = IntegrationsConfiguration.builder()
                .sources(new ArrayList<>(List.of(
                        SourceDefinition.builder().id("s1").build(),
                        SourceDefinition.builder().id("s2").build())))
                .build();
        when(integrationConfigurationSerdes.getFromConfigMap(cfg)).thenReturn(configuration);
        when(integrationConfigurationSerdes.toYaml(any())).thenReturn("yaml-out");

        MicroDomainService service = newService(false);
        service.deleteChainSnapshot(DOMAIN, "s1");

        assertEquals(List.of("configmap:keep/y"),
                integration.getSpec().getTraits().getMount().getResources(),
                "the mount that referenced the snapshot's source config map is removed");
        assertEquals("camel.apache.org/v1", integration.getApiVersion());
        assertEquals("Integration", integration.getKind());
        verify(kubeOperator).createOrUpdateResource(integration);

        ArgumentCaptor<IntegrationsConfiguration> captor = ArgumentCaptor.forClass(IntegrationsConfiguration.class);
        verify(integrationConfigurationSerdes).toYaml(captor.capture());
        assertEquals(List.of("s2"),
                captor.getValue().getSources().stream().map(SourceDefinition::getId).toList(),
                "the deleted snapshot's source is dropped from the integrations configuration");
        assertEquals("yaml-out", cfg.getData().get(IntegrationsConfigurationConfigMapBuilder.CONTENT_KEY));
        verify(kubeOperator).createOrUpdateResource(cfg);

        verify(kubeOperator).deleteConfigMap("src-s1");
    }
}
