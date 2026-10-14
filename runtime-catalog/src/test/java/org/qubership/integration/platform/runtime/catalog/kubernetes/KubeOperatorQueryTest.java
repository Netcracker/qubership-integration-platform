package org.qubership.integration.platform.runtime.catalog.kubernetes;

import com.coreos.monitoring.models.V1ServiceMonitor;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.PodRunningStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the read paths of {@link KubeOperator}: how a label selector is built from its arguments,
 * how Kubernetes models are mapped onto the catalog models, and the two failure conventions. A 404
 * on a whole-kind listing means the CRD is not installed and yields an empty result; every other
 * API error becomes a {@link KubeApiException} carrying the original as its
 * {@code originalException}.
 */
class KubeOperatorQueryTest {

    private static final String NAMESPACE = "qip";
    private static final String ISTIO_GROUP = "networking.istio.io";
    private static final String ISTIO_VERSION = "v1";
    private static final String SERVICE_ENTRIES_PLURAL = "serviceentries";
    private static final String DESTINATION_RULES_PLURAL = "destinationrules";
    private static final String CAMEL_K_GROUP = "camel.apache.org";
    private static final String CAMEL_K_VERSION = "v1";
    private static final String INTEGRATIONS_PLURAL = "integrations";
    private static final String MONITORING_GROUP = "monitoring.coreos.com";
    private static final String MONITORING_VERSION = "v1";
    private static final String SERVICE_MONITORS_PLURAL = "servicemonitors";
    private static final String MESH_GROUP = "core.netcracker.com";
    private static final String MESH_VERSION = "v1";
    private static final String MESHES_PLURAL = "meshes";

    private CoreV1Api coreApi;
    private AppsV1Api appsApi;
    private CustomObjectsApi customObjectsApi;
    private KubeOperator kubeOperator;

    @BeforeEach
    void setUp() {
        kubeOperator = new KubeOperator();
        coreApi = mock(CoreV1Api.class);
        appsApi = mock(AppsV1Api.class);
        customObjectsApi = mock(CustomObjectsApi.class);
        ReflectionTestUtils.setField(kubeOperator, "coreApi", coreApi);
        ReflectionTestUtils.setField(kubeOperator, "appsApi", appsApi);
        ReflectionTestUtils.setField(kubeOperator, "customObjectsApi", customObjectsApi);
        ReflectionTestUtils.setField(kubeOperator, "namespace", NAMESPACE);
    }

    @Test
    void getDeploymentsByLabelSelectsOnTheBareKeyWhenNoValueIsGiven() throws ApiException {
        AppsV1Api.APIlistNamespacedDeploymentRequest request = stubDeploymentList();
        when(request.execute()).thenReturn(new V1DeploymentList().items(List.of()));

        kubeOperator.getDeploymentsByLabel("app.kubernetes.io/part-of");

        verify(request).labelSelector("app.kubernetes.io/part-of");
    }

    @Test
    void getDeploymentsByLabelMapsDeploymentsOntoTheCatalogModel() throws ApiException {
        AppsV1Api.APIlistNamespacedDeploymentRequest request = stubDeploymentList();
        V1Deployment deployment = new V1Deployment()
                .metadata(new V1ObjectMeta()
                        .uid("2f1c4a90-0a1e-4a1f-9f2b-0d5a8c7e1234")
                        .name("engine-service")
                        .labels(Map.of("app", "engine-service", "app.kubernetes.io/version", "1.7.0")))
                .spec(new V1DeploymentSpec().replicas(3));
        when(request.execute()).thenReturn(new V1DeploymentList().items(List.of(deployment)));

        List<KubeDeployment> deployments = kubeOperator.getDeploymentsByLabel("app", "engine-service");

        verify(request).labelSelector("app=engine-service");
        assertEquals(1, deployments.size());
        KubeDeployment mapped = deployments.get(0);
        assertEquals("2f1c4a90-0a1e-4a1f-9f2b-0d5a8c7e1234", mapped.getId());
        assertEquals("engine-service", mapped.getName());
        assertEquals(NAMESPACE, mapped.getNamespace());
        assertEquals(3, mapped.getReplicas());
        assertEquals("1.7.0", mapped.getVersion());
        assertEquals("engine-service", mapped.getLabels().get("app"));
    }

    @Test
    void getDeploymentsByLabelWrapsApiFailures() throws ApiException {
        AppsV1Api.APIlistNamespacedDeploymentRequest request = stubDeploymentList();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getDeploymentsByLabel("app", "engine-service"));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getPodsByLabelMapsPhaseAddressAndFirstContainerReadiness() throws ApiException {
        CoreV1Api.APIlistNamespacedPodRequest request = stubPodList();
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta().name("engine-service-6d4f8b7c9d-abcde"))
                .status(new V1PodStatus()
                        .phase("Running")
                        .podIP("10.128.4.17")
                        .containerStatuses(List.of(new V1ContainerStatus().ready(true))));
        when(request.execute()).thenReturn(new V1PodList().items(List.of(pod)));

        List<KubePod> pods = kubeOperator.getPodsByLabel("app", "engine-service");

        verify(request).labelSelector("app=engine-service");
        assertEquals(1, pods.size());
        KubePod mapped = pods.get(0);
        assertEquals("engine-service-6d4f8b7c9d-abcde", mapped.getName());
        assertEquals(PodRunningStatus.RUNNING, mapped.getRunningStatus());
        assertEquals("10.128.4.17", mapped.getIp());
        assertEquals(NAMESPACE, mapped.getNamespace());
        assertTrue(mapped.isReady());
    }

    @Test
    void getPodsByLabelReportsNotReadyWhenTheStatusCarriesNoContainers() throws ApiException {
        CoreV1Api.APIlistNamespacedPodRequest request = stubPodList();
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta().name("engine-service-6d4f8b7c9d-fghij"))
                .status(new V1PodStatus().phase("Pending"));
        when(request.execute()).thenReturn(new V1PodList().items(List.of(pod)));

        List<KubePod> pods = kubeOperator.getPodsByLabel("app", "engine-service");

        assertEquals(1, pods.size());
        assertEquals(PodRunningStatus.PENDING, pods.get(0).getRunningStatus());
        assertFalse(pods.get(0).isReady());
        assertNull(pods.get(0).getIp());
    }

    @Test
    void getPodsByLabelWrapsApiFailures() throws ApiException {
        CoreV1Api.APIlistNamespacedPodRequest request = stubPodList();
        ApiException apiException = new ApiException(503, "Service Unavailable");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getPodsByLabel("app", "engine-service"));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getServicesSkipsBlueGreenVersionedServices() throws ApiException {
        CoreV1Api.APIlistNamespacedServiceRequest request = stubServiceList();
        V1Service service = new V1Service()
                .metadata(new V1ObjectMeta().uid("6b0f2a13-7c88-4a5e-9b31-2c9f7a1d5e42").name("engine-service"))
                .spec(new V1ServiceSpec().ports(List.of(
                        new V1ServicePort().port(8080),
                        new V1ServicePort().port(8443))));
        V1Service blueGreenService = new V1Service()
                .metadata(new V1ObjectMeta().uid("9d1a5c77-2e64-4f0b-8a19-3f7c6b2e8a90").name("engine-service-v2"))
                .spec(new V1ServiceSpec().ports(List.of(new V1ServicePort().port(8080))));
        when(request.execute()).thenReturn(new V1ServiceList().items(List.of(service, blueGreenService)));

        List<KubeService> services = kubeOperator.getServices();

        assertEquals(1, services.size());
        KubeService mapped = services.get(0);
        assertEquals("engine-service", mapped.getName());
        assertEquals("6b0f2a13-7c88-4a5e-9b31-2c9f7a1d5e42", mapped.getId());
        assertEquals(NAMESPACE, mapped.getNamespace());
        assertEquals(List.of(8080, 8443), mapped.getPorts());
    }

    @Test
    void getServicesWrapsApiFailures() throws ApiException {
        CoreV1Api.APIlistNamespacedServiceRequest request = stubServiceList();
        ApiException apiException = new ApiException(403, "Forbidden");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class, () -> kubeOperator.getServices());

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getIntegrationsByLabelsJoinsEveryLabelIntoOneSelector() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(CAMEL_K_GROUP, CAMEL_K_VERSION, INTEGRATIONS_PLURAL);
        when(request.execute()).thenReturn(rawList(List.of(rawObject("Integration", "order-chain"))));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/part-of", "qip");
        labels.put("qip.microdomain", "default");

        List<CamelKIntegration> integrations = kubeOperator.getIntegrationsByLabels(labels);

        verify(request).labelSelector("app.kubernetes.io/part-of=qip,qip.microdomain=default");
        assertEquals(1, integrations.size());
        assertEquals("order-chain", integrations.get(0).getMetadata().getName());
    }

    @Test
    void getIntegrationsByLabelsWrapsApiFailures() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(CAMEL_K_GROUP, CAMEL_K_VERSION, INTEGRATIONS_PLURAL);
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);
        Map<String, String> labels = Map.of("app", "qip");

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getIntegrationsByLabels(labels));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getServiceMonitorsByLabelReturnsTheListedItems() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL);
        when(request.execute()).thenReturn(rawList(List.of(rawObject("ServiceMonitor", "order-chain-monitor"))));

        List<V1ServiceMonitor> serviceMonitors = kubeOperator.getServiceMonitorsByLabel("app", "qip");

        verify(request).labelSelector("app=qip");
        assertEquals(1, serviceMonitors.size());
        assertEquals("order-chain-monitor", serviceMonitors.get(0).getMetadata().getName());
    }

    @Test
    void getServiceMonitorsByLabelWrapsApiFailures() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL);
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getServiceMonitorsByLabel("app", "qip"));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getCustomObjectsByLabelAndDefinitionQueriesTheCoordinatesOfTheDefinition() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(MESH_GROUP, MESH_VERSION, MESHES_PLURAL);
        when(request.execute()).thenReturn(rawList(List.of(rawObject("Mesh", "qip-mesh"))));

        List<KubeCustomObject> customObjects =
                kubeOperator.getCustomObjectsByLabelAndDefinition("app", "qip", meshDefinition());

        verify(request).labelSelector("app=qip");
        assertEquals(1, customObjects.size());
        assertEquals("qip-mesh", customObjects.get(0).getMetadata().getName());
    }

    @Test
    void getCustomObjectsByLabelAndDefinitionWrapsApiFailures() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(MESH_GROUP, MESH_VERSION, MESHES_PLURAL);
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);
        GenericCustomResources.CustomResourceDefinition definition = meshDefinition();

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getCustomObjectsByLabelAndDefinition("app", "qip", definition));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getServiceEntriesListsEveryIstioServiceEntryInTheNamespace() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL);
        when(request.execute()).thenReturn(rawList(List.of(rawObject("ServiceEntry", "api-example-com"))));

        List<KubeCustomObject> serviceEntries = kubeOperator.getServiceEntries();

        assertEquals(1, serviceEntries.size());
        assertEquals("api-example-com", serviceEntries.get(0).getMetadata().getName());
    }

    @Test
    void getDestinationRulesListsEveryIstioDestinationRuleInTheNamespace() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(ISTIO_GROUP, ISTIO_VERSION, DESTINATION_RULES_PLURAL);
        when(request.execute()).thenReturn(rawList(List.of(rawObject("DestinationRule", "api-example-com"))));

        List<KubeCustomObject> destinationRules = kubeOperator.getDestinationRules();

        assertEquals(1, destinationRules.size());
        assertEquals("api-example-com", destinationRules.get(0).getMetadata().getName());
    }

    @Test
    void getServiceEntriesReturnsNoneWhenTheIstioCrdIsNotInstalled() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL);
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertTrue(kubeOperator.getServiceEntries().isEmpty());
    }

    @Test
    void getDestinationRulesWrapsApiFailuresOtherThanNotFound() throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                stubCustomObjectList(ISTIO_GROUP, ISTIO_VERSION, DESTINATION_RULES_PLURAL);
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class, () -> kubeOperator.getDestinationRules());

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getServicesByLabelReturnsTheKubernetesServicesUnchanged() throws ApiException {
        CoreV1Api.APIlistNamespacedServiceRequest request = stubServiceList();
        V1Service service = new V1Service().metadata(new V1ObjectMeta().name("engine-service"));
        when(request.execute()).thenReturn(new V1ServiceList().items(List.of(service)));

        List<V1Service> services = kubeOperator.getServicesByLabel("app", "qip");

        verify(request).labelSelector("app=qip");
        assertEquals(List.of(service), services);
    }

    @Test
    void getServicesByLabelWrapsApiFailures() throws ApiException {
        CoreV1Api.APIlistNamespacedServiceRequest request = stubServiceList();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getServicesByLabel("app", "qip"));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getConfigMapsByLabelReturnsTheKubernetesConfigMapsUnchanged() throws ApiException {
        CoreV1Api.APIlistNamespacedConfigMapRequest request = stubConfigMapList();
        V1ConfigMap configMap = new V1ConfigMap().metadata(new V1ObjectMeta().name("order-chain-sources"));
        when(request.execute()).thenReturn(new V1ConfigMapList().items(List.of(configMap)));

        List<V1ConfigMap> configMaps = kubeOperator.getConfigMapsByLabel("app", "qip");

        verify(request).labelSelector("app=qip");
        assertEquals(List.of(configMap), configMaps);
    }

    @Test
    void getConfigMapsByLabelWrapsApiFailures() throws ApiException {
        CoreV1Api.APIlistNamespacedConfigMapRequest request = stubConfigMapList();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getConfigMapsByLabel("app", "qip"));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void getSecretsByLabelReturnsTheKubernetesSecretsUnchanged() throws ApiException {
        CoreV1Api.APIlistNamespacedSecretRequest request = stubSecretList();
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta().name("order-chain-credentials"));
        when(request.execute()).thenReturn(new V1SecretList().items(List.of(secret)));

        List<V1Secret> secrets = kubeOperator.getSecretsByLabel("app", "qip");

        verify(request).labelSelector("app=qip");
        assertEquals(List.of(secret), secrets);
    }

    @Test
    void getSecretsByLabelWrapsApiFailures() throws ApiException {
        CoreV1Api.APIlistNamespacedSecretRequest request = stubSecretList();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.getSecretsByLabel("app", "qip"));

        assertSame(apiException, exception.getOriginalException());
    }

    private static GenericCustomResources.CustomResourceDefinition meshDefinition() {
        return new GenericCustomResources.CustomResourceDefinition(
                MESH_GROUP, MESH_VERSION, "Mesh", MESHES_PLURAL, true);
    }

    private AppsV1Api.APIlistNamespacedDeploymentRequest stubDeploymentList() {
        AppsV1Api.APIlistNamespacedDeploymentRequest request =
                mock(AppsV1Api.APIlistNamespacedDeploymentRequest.class);
        when(appsApi.listNamespacedDeployment(NAMESPACE)).thenReturn(request);
        when(request.labelSelector(anyString())).thenReturn(request);
        return request;
    }

    private CoreV1Api.APIlistNamespacedPodRequest stubPodList() {
        CoreV1Api.APIlistNamespacedPodRequest request = mock(CoreV1Api.APIlistNamespacedPodRequest.class);
        when(coreApi.listNamespacedPod(NAMESPACE)).thenReturn(request);
        when(request.labelSelector(anyString())).thenReturn(request);
        return request;
    }

    private CoreV1Api.APIlistNamespacedServiceRequest stubServiceList() {
        CoreV1Api.APIlistNamespacedServiceRequest request = mock(CoreV1Api.APIlistNamespacedServiceRequest.class);
        when(coreApi.listNamespacedService(NAMESPACE)).thenReturn(request);
        when(request.labelSelector(anyString())).thenReturn(request);
        return request;
    }

    private CoreV1Api.APIlistNamespacedConfigMapRequest stubConfigMapList() {
        CoreV1Api.APIlistNamespacedConfigMapRequest request = mock(CoreV1Api.APIlistNamespacedConfigMapRequest.class);
        when(coreApi.listNamespacedConfigMap(NAMESPACE)).thenReturn(request);
        when(request.labelSelector(anyString())).thenReturn(request);
        return request;
    }

    private CoreV1Api.APIlistNamespacedSecretRequest stubSecretList() {
        CoreV1Api.APIlistNamespacedSecretRequest request = mock(CoreV1Api.APIlistNamespacedSecretRequest.class);
        when(coreApi.listNamespacedSecret(NAMESPACE)).thenReturn(request);
        when(request.labelSelector(anyString())).thenReturn(request);
        return request;
    }

    private CustomObjectsApi.APIlistNamespacedCustomObjectRequest stubCustomObjectList(
            String group, String version, String plural) {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                mock(CustomObjectsApi.APIlistNamespacedCustomObjectRequest.class);
        when(customObjectsApi.listNamespacedCustomObject(group, version, NAMESPACE, plural)).thenReturn(request);
        when(request.labelSelector(anyString())).thenReturn(request);
        return request;
    }

    private static Map<String, Object> rawList(List<Map<String, Object>> items) {
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("items", items);
        return list;
    }

    private static Map<String, Object> rawObject(String kind, String name) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("kind", kind);
        object.put("metadata", metadata);
        return object;
    }
}
