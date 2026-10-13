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
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
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
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.ResourceKey;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                monitoringEnabled,
                context -> "engine-routes",
                new K8sNameValidator());
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

    private String httpRouteYaml(String name) {
        return """
                apiVersion: gateway.networking.k8s.io/v1
                kind: HTTPRoute
                metadata:
                  name: %s
                spec:
                  rules: []
                """.formatted(name);
    }

    private String integrationYaml(String name) {
        return """
                apiVersion: camel.apache.org/v1
                kind: Integration
                metadata:
                  name: %s
                  labels:
                    qip.domain: payments
                spec: {}
                """.formatted(name);
    }

    @DisplayName("Deploys every document in the manifest through the kube operator")
    @Test
    void deploysEachDocument() throws Exception {
        String manifest = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-1\n";

        MicroDomainService service = newService(false);
        service.deploy(new BuiltResources(manifest, Map.of()));

        verify(kubeOperator).createOrUpdateResource(any(V1ConfigMap.class));
    }

    @DisplayName("Writes the Integration before the ConfigMaps and keeps the build order of the rest")
    @Test
    void deployWritesTheIntegrationFirst() {
        String manifest = String.join("---\n",
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: source-1\n",
                integrationYaml(INTEGRATION_RESOURCE_NAME),
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: source-2\n");

        MicroDomainService service = newService(false);
        // See deployStampsObservedResourceVersion for why this call is needed.
        service.init();
        service.deploy(new BuiltResources(manifest, Map.of()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator, times(3)).createOrUpdateResource(captor.capture());
        List<String> written = captor.getAllValues().stream()
                .map(resource -> ((KubernetesObject) resource).getMetadata().getName())
                .toList();
        assertEquals(List.of(INTEGRATION_RESOURCE_NAME, "source-1", "source-2"), written,
                "a ConfigMap written first makes the operator bump the Integration's resourceVersion");
    }

    @DisplayName("Wraps a failed apply in a MicroDomainDeployError that keeps the cause")
    @Test
    void wrapsDeployFailure() throws Exception {
        String manifest = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-1\n";
        RuntimeException cause = new RuntimeException("boom");
        doThrow(cause).when(kubeOperator).createOrUpdateResource(any());

        MicroDomainService service = newService(false);
        BuiltResources built = new BuiltResources(manifest, Map.of());
        MicroDomainDeployError error =
                assertThrows(MicroDomainDeployError.class, () -> service.deploy(built));

        assertSame(cause, error.getCause());
        assertEquals("Failed to deploy resources: boom", error.getMessage(),
                "the error message has to carry the API server's refusal, since a caller sees nothing else");
    }

    @DisplayName("Lets a KubeApiConflictException through unwrapped so a caller can retry it")
    @Test
    void deployDoesNotWrapAConflictException() {
        String manifest = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-1\n";
        KubeApiConflictException conflict = new KubeApiConflictException("conflict", null);
        doThrow(conflict).when(kubeOperator).createOrUpdateResource(any());

        MicroDomainService service = newService(false);
        BuiltResources built = new BuiltResources(manifest, Map.of());
        KubeApiConflictException thrown =
                assertThrows(KubeApiConflictException.class, () -> service.deploy(built));

        assertSame(conflict, thrown,
                "MicroDomainDeployError would hide the conflict from a caller's retry logic");
    }

    @DisplayName("Stamps the observed resourceVersion onto a document before writing it")
    @Test
    void deployStampsObservedResourceVersion() {
        V1ObjectMeta observed = new V1ObjectMeta().name("route").resourceVersion("42");
        BuiltResources built = new BuiltResources(
                httpRouteYaml("route"),
                Map.of(new ResourceKey("HTTPRoute", "route"), observed));

        MicroDomainService service = newService(false);
        // Registers HTTPRoute with the client's static ModelMapper (mirrors the real bean's
        // @PostConstruct), so Yaml.loadAll below resolves it to KubeCustomObject instead of
        // falling back to DynamicKubernetesObject.
        service.init();
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject written = (KubeCustomObject) captor.getValue();
        assertEquals("42", written.getMetadata().getResourceVersion());
    }

    @DisplayName("Keeps operator-owned annotations that the generated document does not declare")
    @Test
    void deployOverlaysGeneratedMetadataOntoObservedMetadata() {
        V1ObjectMeta observed = new V1ObjectMeta()
                .name("int-res")
                .resourceVersion("42")
                .annotations(new LinkedHashMap<>(Map.of("camel.apache.org/operator.id", "camel-k")))
                .labels(new LinkedHashMap<>(Map.of("qip.domain", "payments")));
        BuiltResources built = new BuiltResources(
                integrationYaml("int-res"),   // declares only the qip.domain label
                Map.of(new ResourceKey("Integration", "int-res"), observed));

        MicroDomainService service = newService(false);
        // Registers Integration with the client's static ModelMapper (mirrors the real bean's
        // @PostConstruct), so Yaml.loadAll below resolves it to CamelKIntegration instead of
        // falling back to DynamicKubernetesObject.
        service.init();
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        CamelKIntegration written = (CamelKIntegration) captor.getValue();
        assertEquals("camel-k", written.getMetadata().getAnnotations().get("camel.apache.org/operator.id"),
                "an annotation only the operator set must survive the write");
        assertEquals("payments", written.getMetadata().getLabels().get("qip.domain"));
    }

    @DisplayName("Stamps the observed resourceVersion onto a core-kind document too")
    @Test
    void deployStampsObservedResourceVersionOnACoreKindDocument() {
        // The other observation tests all drive custom kinds, which reach Yaml.loadAll through
        // ModelMapper. Core kinds take the built-in route, so this pins that getKind() is populated
        // there as well -- if it were not, the (kind, name) lookup would miss and every core-kind
        // write would silently fall back to last-write-wins.
        V1ObjectMeta observed = new V1ObjectMeta().name("cm-1").resourceVersion("42");
        BuiltResources built = new BuiltResources(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-1\n",
                Map.of(new ResourceKey("ConfigMap", "cm-1"), observed));

        MicroDomainService service = newService(false);
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        V1ConfigMap written = (V1ConfigMap) captor.getValue();
        assertEquals("ConfigMap", written.getKind(),
                "Yaml.loadAll must populate kind for a core kind, or the observation lookup misses");
        assertEquals("42", written.getMetadata().getResourceVersion());
    }

    @DisplayName("Keeps ownerReferences and finalizers the live object carried")
    @Test
    void deployPreservesLiveMetadataTheGeneratedDocumentDoesNotDeclare() {
        V1OwnerReference owner = new V1OwnerReference()
                .apiVersion("apps/v1")
                .kind("Deployment")
                .name("orders-operator")
                .uid("owner-uid");
        V1ObjectMeta observed = new V1ObjectMeta()
                .name("int-res")
                .resourceVersion("42")
                .uid("live-uid")
                .ownerReferences(new ArrayList<>(List.of(owner)))
                .finalizers(new ArrayList<>(List.of("qip.org/cleanup")));
        BuiltResources built = new BuiltResources(
                integrationYaml("int-res"),
                Map.of(new ResourceKey("Integration", "int-res"), observed));

        MicroDomainService service = newService(false);
        service.init();
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        V1ObjectMeta written = ((CamelKIntegration) captor.getValue()).getMetadata();
        assertEquals(List.of(owner), written.getOwnerReferences(),
                "a PUT replaces metadata wholesale, so dropping ownerReferences here would break "
                        + "garbage collection for an operator-owned object");
        assertEquals(List.of("qip.org/cleanup"), written.getFinalizers());
        assertEquals("live-uid", written.getUid());
        assertEquals("int-res", written.getName(), "the generated name must win over the live one");
        assertEquals("payments", written.getLabels().get("qip.domain"));
    }

    @DisplayName("Leaves a document Phase 1 never looked at to the write-time read")
    @Test
    void deployLeavesAnUnobservedDocumentToTheWriteTimeRead() {
        BuiltResources built = new BuiltResources(httpRouteYaml("route"), Map.of());

        MicroDomainService service = newService(false);
        // See deployStampsObservedResourceVersion for why this call is needed.
        service.init();
        service.deploy(built);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        assertNull(((KubeCustomObject) captor.getValue()).getMetadata().getResourceVersion());
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

    // Regression: the source ConfigMap's SNAPSHOT_ID_LABEL holds a K8sNameValidator-sanitized id,
    // which strips a leading digit, while the caller passes the raw id. Most snapshot UUIDs start
    // with a hex digit, so looking the label up with the raw id missed and cfgName fell back to "":
    // the snapshot's mount was left in place and its source ConfigMap was never deleted.
    @DisplayName("Finds the source config map when the snapshot id was sanitized into its label")
    @Test
    void deleteChainSnapshotResolvesTheSanitizedSnapshotIdLabel() {
        stubNamingStrategies();

        String rawId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
        String labelId = new K8sNameValidator().validate(rawId);
        assertNotEquals(rawId, labelId, "the fixture only proves anything if validate() rewrites the id");

        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait();
        mount.setResources(new ArrayList<>(List.of("configmap:src-uuid/x", "configmap:keep/y")));
        CamelKIntegration.IntegrationSpec.Traits traits = new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(spec);

        V1ConfigMap cfg = configMap(CFG_CONFIG_MAP_NAME, null);
        V1ConfigMap source = configMap("src-uuid", Map.of(SNAPSHOT_ID_LABEL, labelId));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of(cfg, source));

        IntegrationsConfiguration configuration = IntegrationsConfiguration.builder()
                .sources(new ArrayList<>(List.of(SourceDefinition.builder().id(rawId).build())))
                .build();
        when(integrationConfigurationSerdes.getFromConfigMap(cfg)).thenReturn(configuration);
        when(integrationConfigurationSerdes.toYaml(any())).thenReturn("yaml-out");

        MicroDomainService service = newService(false);
        service.deleteChainSnapshot(DOMAIN, rawId);

        assertEquals(List.of("configmap:keep/y"),
                integration.getSpec().getTraits().getMount().getResources(),
                "the removed snapshot's mount goes, and every other mount stays");
        verify(kubeOperator).deleteConfigMap("src-uuid");
    }

    @DisplayName("Writes the Integration back with its operator-owned metadata and live resourceVersion intact")
    @Test
    void deleteChainSnapshotPreservesOperatorMetadataOnTheIntegration() {
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
        // The operator sets this annotation outside QIP's own Handlebars template. A PUT that drops
        // unmodeled metadata would silently strip it; V1ObjectMeta models every field, so it survives
        // the CamelKIntegration round trip even though CamelKIntegration.IntegrationSpec does not.
        //
        // resourceVersion("42") is not test scaffolding: getIntegrationsByLabels returned this
        // Integration straight from the cluster, so this is the live version, and this method is a
        // read-modify-write. Left on the object, it becomes KubeOperator's optimistic-concurrency
        // precondition for this write -- deliberately, per applyPrecondition's contract. Clearing it
        // here would reintroduce the silent last-write-wins this whole branch exists to close.
        integration.setMetadata(new V1ObjectMeta()
                .name(INTEGRATION_RESOURCE_NAME)
                .resourceVersion("42")
                .annotations(new LinkedHashMap<>(Map.of("camel.apache.org/operator.id", "camel-k"))));

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

        verify(kubeOperator).createOrUpdateResource(integration);
        assertEquals("camel-k",
                integration.getMetadata().getAnnotations().get("camel.apache.org/operator.id"),
                "V1ObjectMeta is fully modeled, so a POJO round trip must not lose annotations");
        assertEquals("42", integration.getMetadata().getResourceVersion(),
                "the live resourceVersion must reach createOrUpdateResource unchanged -- it is this "
                        + "read-modify-write's optimistic-concurrency precondition, not a value to clear");
    }

    @DisplayName("Lets a conflict on the configuration config map through with its type intact")
    @Test
    void deleteChainSnapshotDoesNotWrapAConflictException() {
        stubNamingStrategies();

        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait();
        mount.setResources(new ArrayList<>(List.of("configmap:src-s1/x")));
        CamelKIntegration.IntegrationSpec.Traits traits = new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(spec);
        integration.setMetadata(new V1ObjectMeta().name(INTEGRATION_RESOURCE_NAME));

        V1ConfigMap cfg = configMap(CFG_CONFIG_MAP_NAME, null);
        V1ConfigMap source = configMap("src-s1", Map.of(SNAPSHOT_ID_LABEL, "s1"));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of(cfg, source));

        IntegrationsConfiguration configuration = IntegrationsConfiguration.builder()
                .sources(new ArrayList<>(List.of(SourceDefinition.builder().id("s1").build())))
                .build();
        when(integrationConfigurationSerdes.getFromConfigMap(cfg)).thenReturn(configuration);
        when(integrationConfigurationSerdes.toYaml(any())).thenReturn("yaml-out");

        KubeApiConflictException conflict = new KubeApiConflictException("conflict", null);
        doThrow(conflict).when(kubeOperator).createOrUpdateResource(cfg);

        MicroDomainService service = newService(false);
        KubeApiConflictException thrown = assertThrows(KubeApiConflictException.class,
                () -> service.deleteChainSnapshot(DOMAIN, "s1"));

        assertSame(conflict, thrown,
                "wrapping it in a RuntimeException would hide the conflict from a caller's retry logic");
    }

    @DisplayName("Deletes the HTTPRoutes labeled with the removed snapshot and keeps the others")
    @Test
    void deleteChainSnapshotDeletesTheHttpRoutesOfThatSnapshot() {
        stubNamingStrategies();

        CamelKIntegration.IntegrationSpec.Traits.MountTrait mount =
                new CamelKIntegration.IntegrationSpec.Traits.MountTrait();
        mount.setResources(new ArrayList<>(List.of("configmap:src-uuid/x")));
        CamelKIntegration.IntegrationSpec.Traits traits = new CamelKIntegration.IntegrationSpec.Traits();
        traits.setMount(mount);
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(spec);

        String rawId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
        String labelId = new K8sNameValidator().validate(rawId);
        V1ConfigMap source = configMap("src-uuid", Map.of(SNAPSHOT_ID_LABEL, labelId));
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of(integration));
        when(kubeOperator.getServicesByLabel(anyString(), anyString())).thenReturn(List.of());
        when(kubeOperator.getConfigMapsByLabel(anyString(), anyString())).thenReturn(List.of(source));
        when(kubeOperator.getCustomObjectsByLabels(anyString(), anyString(), anyString(), any())).thenReturn(List.of(
                httpRoute("route-removed-public", labelId),
                httpRoute("route-removed-egress", labelId),
                httpRoute("route-other-public", "s2")));

        MicroDomainService service = newService(false);
        service.deleteChainSnapshot(DOMAIN, rawId);

        verify(kubeOperator).deleteCustomObject(anyString(), anyString(), anyString(), eq("route-removed-public"));
        verify(kubeOperator).deleteCustomObject(anyString(), anyString(), anyString(), eq("route-removed-egress"));
        verify(kubeOperator, never()).deleteCustomObject(anyString(), anyString(), anyString(), eq("route-other-public"));
    }

    @DisplayName("Deletes the HTTPRoutes of every chain in the domain")
    @Test
    void deleteRemovesTheChainHttpRoutes() {
        when(kubeOperator.getIntegrationsByLabels(any())).thenReturn(List.of());
        when(kubeOperator.getCustomObjectsByLabels(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(httpRoute("route-a-public", "s1"), httpRoute("route-b-egress", "s2")));

        MicroDomainService service = newService(false);
        service.delete(DOMAIN);

        verify(kubeOperator).deleteCustomObject(anyString(), anyString(), anyString(), eq("route-a-public"));
        verify(kubeOperator).deleteCustomObject(anyString(), anyString(), anyString(), eq("route-b-egress"));
    }

    private KubeCustomObject httpRoute(String name, String snapshotLabel) {
        KubeCustomObject object = new KubeCustomObject();
        object.setMetadata(new V1ObjectMeta().name(name).labels(Map.of(SNAPSHOT_ID_LABEL, snapshotLabel)));
        return object;
    }
}
