package org.qubership.integration.platform.runtime.catalog.kubernetes;

import com.coreos.monitoring.models.V1ServiceMonitor;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.util.PatchUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainDeployError;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the dispatch that {@link KubeOperator#createOrUpdateResource} performs over the resource
 * types it supports, and the create-versus-patch decision each branch makes. Secrets are the one
 * exception: they are created once and never patched, so that an operator-managed secret does not
 * overwrite credentials rotated outside the platform.
 */
class KubeOperatorCreateOrUpdateTest {

    private static final String NAMESPACE = "qip";
    private static final String CAMEL_K_GROUP = "camel.apache.org";
    private static final String CAMEL_K_VERSION = "v1";
    private static final String INTEGRATIONS_PLURAL = "integrations";
    private static final String MONITORING_GROUP = "monitoring.coreos.com";
    private static final String MONITORING_VERSION = "v1";
    private static final String SERVICE_MONITORS_PLURAL = "servicemonitors";
    private static final String GATEWAY_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String MESH_GROUP = "core.netcracker.com";
    private static final String MESH_VERSION = "v1";
    private static final String MESHES_PLURAL = "meshes";

    private CoreV1Api coreApi;
    private CustomObjectsApi customObjectsApi;
    private GenericCustomResources genericCustomResources;
    private KubeOperator kubeOperator;

    @BeforeEach
    void setUp() {
        kubeOperator = new KubeOperator();
        coreApi = mock(CoreV1Api.class);
        customObjectsApi = mock(CustomObjectsApi.class);
        genericCustomResources = mock(GenericCustomResources.class);
        ReflectionTestUtils.setField(kubeOperator, "coreApi", coreApi);
        ReflectionTestUtils.setField(kubeOperator, "customObjectsApi", customObjectsApi);
        ReflectionTestUtils.setField(kubeOperator, "genericCustomResources", genericCustomResources);
        ReflectionTestUtils.setField(kubeOperator, "namespace", NAMESPACE);
    }

    @Test
    void createsAConfigMapThatDoesNotExistYet() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        stubConfigMapList();
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(configMap);

        verify(createRequest).execute();
    }

    @Test
    void patchesAConfigMapThatAlreadyExists() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        stubConfigMapList(configMap("order-chain-sources"));
        CoreV1Api.APIpatchNamespacedConfigMapRequest patchRequest =
                mock(CoreV1Api.APIpatchNamespacedConfigMapRequest.class);
        when(coreApi.patchNamespacedConfigMap(eq("order-chain-sources"), eq(NAMESPACE), any(V1Patch.class)))
                .thenReturn(patchRequest);
        when(patchRequest.fieldManager(anyString())).thenReturn(patchRequest);
        when(patchRequest.force(anyBoolean())).thenReturn(patchRequest);

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            stubPatchToInvokeTheCall(patchUtils);

            kubeOperator.createOrUpdateResource(configMap);
        }

        verify(coreApi).patchNamespacedConfigMap(eq("order-chain-sources"), eq(NAMESPACE), any(V1Patch.class));
        verify(patchRequest).fieldManager("kubectl-patch");
        verify(patchRequest).force(true);
        verify(coreApi, never()).createNamespacedConfigMap(anyString(), any(V1ConfigMap.class));
    }

    @Test
    void wrapsApiFailuresRaisedWhileApplyingAConfigMap() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIlistNamespacedConfigMapRequest listRequest = stubConfigMapList();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(listRequest.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.createOrUpdateResource(configMap));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void createsAServiceThatDoesNotExistYet() throws ApiException {
        V1Service service = service("order-chain-service");
        stubServiceList();
        CoreV1Api.APIcreateNamespacedServiceRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedServiceRequest.class);
        when(coreApi.createNamespacedService(NAMESPACE, service)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(service);

        verify(createRequest).execute();
    }

    @Test
    void patchesAServiceThatAlreadyExists() throws ApiException {
        V1Service service = service("order-chain-service");
        stubServiceList(service("order-chain-service"));
        CoreV1Api.APIpatchNamespacedServiceRequest patchRequest =
                mock(CoreV1Api.APIpatchNamespacedServiceRequest.class);
        when(coreApi.patchNamespacedService(eq("order-chain-service"), eq(NAMESPACE), any(V1Patch.class)))
                .thenReturn(patchRequest);
        when(patchRequest.fieldManager(anyString())).thenReturn(patchRequest);
        when(patchRequest.force(anyBoolean())).thenReturn(patchRequest);

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            stubPatchToInvokeTheCall(patchUtils);

            kubeOperator.createOrUpdateResource(service);
        }

        verify(coreApi).patchNamespacedService(eq("order-chain-service"), eq(NAMESPACE), any(V1Patch.class));
        verify(coreApi, never()).createNamespacedService(anyString(), any(V1Service.class));
    }

    @Test
    void createsACamelKIntegrationAtTheCamelKCoordinates() throws ApiException {
        CamelKIntegration integration = new CamelKIntegration(
                CAMEL_K_GROUP + "/" + CAMEL_K_VERSION, "Integration",
                new V1ObjectMeta().name("order-chain"), null);
        stubCustomObjectList(CAMEL_K_GROUP, CAMEL_K_VERSION, INTEGRATIONS_PLURAL, List.of());
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                stubCustomObjectCreate(CAMEL_K_GROUP, CAMEL_K_VERSION, INTEGRATIONS_PLURAL);

        kubeOperator.createOrUpdateResource(integration);

        verify(customObjectsApi).createNamespacedCustomObject(
                eq(CAMEL_K_GROUP), eq(CAMEL_K_VERSION), eq(NAMESPACE), eq(INTEGRATIONS_PLURAL), any());
        verify(createRequest).execute();
    }

    @Test
    void createsAServiceMonitorAtThePrometheusOperatorCoordinates() throws ApiException {
        V1ServiceMonitor serviceMonitor = new V1ServiceMonitor()
                .apiVersion(MONITORING_GROUP + "/" + MONITORING_VERSION)
                .kind("ServiceMonitor")
                .metadata(new V1ObjectMeta().name("order-chain-monitor"));
        stubCustomObjectList(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL, List.of());
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                stubCustomObjectCreate(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL);

        kubeOperator.createOrUpdateResource(serviceMonitor);

        verify(customObjectsApi).createNamespacedCustomObject(
                eq(MONITORING_GROUP), eq(MONITORING_VERSION), eq(NAMESPACE), eq(SERVICE_MONITORS_PLURAL), any());
        verify(createRequest).execute();
    }

    @Test
    void patchesAnHttpRouteThatAlreadyExists() throws ApiException {
        KubeCustomObject httpRoute = customObject(
                GATEWAY_GROUP + "/" + GATEWAY_VERSION, "HTTPRoute", "order-chain-public-routes");
        stubCustomObjectList(GATEWAY_GROUP, GATEWAY_VERSION, HTTP_ROUTES_PLURAL,
                List.of(rawObject("order-chain-public-routes")));
        CustomObjectsApi.APIpatchNamespacedCustomObjectRequest patchRequest =
                mock(CustomObjectsApi.APIpatchNamespacedCustomObjectRequest.class);
        when(customObjectsApi.patchNamespacedCustomObject(eq(GATEWAY_GROUP), eq(GATEWAY_VERSION), eq(NAMESPACE),
                eq(HTTP_ROUTES_PLURAL), eq("order-chain-public-routes"), any(V1Patch.class)))
                .thenReturn(patchRequest);
        when(patchRequest.fieldManager(anyString())).thenReturn(patchRequest);
        when(patchRequest.force(anyBoolean())).thenReturn(patchRequest);

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            stubPatchToInvokeTheCall(patchUtils);

            kubeOperator.createOrUpdateResource(httpRoute);
        }

        verify(customObjectsApi).patchNamespacedCustomObject(eq(GATEWAY_GROUP), eq(GATEWAY_VERSION), eq(NAMESPACE),
                eq(HTTP_ROUTES_PLURAL), eq("order-chain-public-routes"), any(V1Patch.class));
        verify(customObjectsApi, never()).createNamespacedCustomObject(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void resolvesAGenericCustomObjectThroughItsRegisteredDefinition() throws ApiException {
        KubeCustomObject mesh = customObject(MESH_GROUP + "/" + MESH_VERSION, "Mesh", "qip-mesh");
        when(genericCustomResources.definitionFor("Mesh")).thenReturn(meshDefinition(true));
        stubCustomObjectList(MESH_GROUP, MESH_VERSION, MESHES_PLURAL, List.of());
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                stubCustomObjectCreate(MESH_GROUP, MESH_VERSION, MESHES_PLURAL);

        kubeOperator.createOrUpdateResource(mesh);

        verify(customObjectsApi).createNamespacedCustomObject(
                eq(MESH_GROUP), eq(MESH_VERSION), eq(NAMESPACE), eq(MESHES_PLURAL), any());
        verify(createRequest).execute();
    }

    @Test
    void leavesAnExistingGenericCustomObjectAloneWhenItsDefinitionForbidsUpdates() throws ApiException {
        KubeCustomObject mesh = customObject(MESH_GROUP + "/" + MESH_VERSION, "Mesh", "qip-mesh");
        when(genericCustomResources.definitionFor("Mesh")).thenReturn(meshDefinition(false));
        stubCustomObjectList(MESH_GROUP, MESH_VERSION, MESHES_PLURAL, List.of(rawObject("qip-mesh")));

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            kubeOperator.createOrUpdateResource(mesh);

            patchUtils.verifyNoInteractions();
        }

        verify(customObjectsApi, never()).createNamespacedCustomObject(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsAGenericCustomObjectWhenNoDefinitionsAreRegistered() {
        ReflectionTestUtils.setField(kubeOperator, "genericCustomResources", null);
        KubeCustomObject mesh = customObject(MESH_GROUP + "/" + MESH_VERSION, "Mesh", "qip-mesh");

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.createOrUpdateResource(mesh));

        assertTrue(exception.getMessage().contains("Mesh"));
    }

    @Test
    void createsASecretThatDoesNotExistYet() throws ApiException {
        V1Secret secret = secret("order-chain-credentials");
        CoreV1Api.APIreadNamespacedSecretRequest readRequest = stubSecretRead("order-chain-credentials");
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
        CoreV1Api.APIcreateNamespacedSecretRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedSecretRequest.class);
        when(coreApi.createNamespacedSecret(NAMESPACE, secret)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(secret);

        verify(createRequest).execute();
    }

    @Test
    void leavesAnExistingSecretUntouched() throws ApiException {
        V1Secret secret = secret("order-chain-credentials");
        CoreV1Api.APIreadNamespacedSecretRequest readRequest = stubSecretRead("order-chain-credentials");
        when(readRequest.execute()).thenReturn(secret);

        kubeOperator.createOrUpdateResource(secret);

        verify(coreApi, never()).createNamespacedSecret(anyString(), any(V1Secret.class));
    }

    @Test
    void wrapsApiFailuresRaisedWhileReadingASecret() throws ApiException {
        V1Secret secret = secret("order-chain-credentials");
        CoreV1Api.APIreadNamespacedSecretRequest readRequest = stubSecretRead("order-chain-credentials");
        ApiException apiException = new ApiException(403, "Forbidden");
        when(readRequest.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.createOrUpdateResource(secret));

        assertSame(apiException, exception.getOriginalException());
        assertTrue(exception.getMessage().contains("order-chain-credentials"));
    }

    @Test
    void wrapsApiFailuresRaisedWhileCreatingASecret() throws ApiException {
        V1Secret secret = secret("order-chain-credentials");
        CoreV1Api.APIreadNamespacedSecretRequest readRequest = stubSecretRead("order-chain-credentials");
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
        CoreV1Api.APIcreateNamespacedSecretRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedSecretRequest.class);
        when(coreApi.createNamespacedSecret(NAMESPACE, secret)).thenReturn(createRequest);
        ApiException apiException = new ApiException(409, "Conflict");
        when(createRequest.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.createOrUpdateResource(secret));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void rejectsAResourceTypeItCannotApply() {
        V1Pod pod = new V1Pod().metadata(new V1ObjectMeta().name("engine-service-6d4f8b7c9d-abcde"));

        assertThrows(MicroDomainDeployError.class, () -> kubeOperator.createOrUpdateResource(pod));
    }

    private static void stubPatchToInvokeTheCall(MockedStatic<PatchUtils> patchUtils) {
        patchUtils.when(() -> PatchUtils.patch(any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, PatchUtils.PatchCallFunc.class).getCall();
                    return null;
                });
    }

    private static V1ConfigMap configMap(String name) {
        return new V1ConfigMap().metadata(new V1ObjectMeta().name(name)).data(Map.of("routes.yaml", ""));
    }

    private static V1Service service(String name) {
        return new V1Service().metadata(new V1ObjectMeta().name(name));
    }

    private static V1Secret secret(String name) {
        return new V1Secret().metadata(new V1ObjectMeta().name(name));
    }

    private static KubeCustomObject customObject(String apiVersion, String kind, String name) {
        return KubeCustomObject.builder()
                .apiVersion(apiVersion)
                .kind(kind)
                .metadata(new V1ObjectMeta().name(name))
                .spec(Map.of())
                .build();
    }

    private static GenericCustomResources.CustomResourceDefinition meshDefinition(boolean updateIfExists) {
        return new GenericCustomResources.CustomResourceDefinition(
                MESH_GROUP, MESH_VERSION, "Mesh", MESHES_PLURAL, updateIfExists);
    }

    private CoreV1Api.APIlistNamespacedConfigMapRequest stubConfigMapList(V1ConfigMap... existing) {
        CoreV1Api.APIlistNamespacedConfigMapRequest request = mock(CoreV1Api.APIlistNamespacedConfigMapRequest.class);
        when(coreApi.listNamespacedConfigMap(NAMESPACE)).thenReturn(request);
        try {
            when(request.execute()).thenReturn(new V1ConfigMapList().items(List.of(existing)));
        } catch (ApiException exception) {
            throw new IllegalStateException(exception);
        }
        return request;
    }

    private CoreV1Api.APIlistNamespacedServiceRequest stubServiceList(V1Service... existing) {
        CoreV1Api.APIlistNamespacedServiceRequest request = mock(CoreV1Api.APIlistNamespacedServiceRequest.class);
        when(coreApi.listNamespacedService(NAMESPACE)).thenReturn(request);
        try {
            when(request.execute()).thenReturn(new V1ServiceList().items(List.of(existing)));
        } catch (ApiException exception) {
            throw new IllegalStateException(exception);
        }
        return request;
    }

    private CoreV1Api.APIreadNamespacedSecretRequest stubSecretRead(String name) {
        CoreV1Api.APIreadNamespacedSecretRequest request = mock(CoreV1Api.APIreadNamespacedSecretRequest.class);
        when(coreApi.readNamespacedSecret(name, NAMESPACE)).thenReturn(request);
        return request;
    }

    private void stubCustomObjectList(String group, String version, String plural, List<Map<String, Object>> items)
            throws ApiException {
        CustomObjectsApi.APIlistNamespacedCustomObjectRequest request =
                mock(CustomObjectsApi.APIlistNamespacedCustomObjectRequest.class);
        when(customObjectsApi.listNamespacedCustomObject(group, version, NAMESPACE, plural)).thenReturn(request);
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("items", items);
        when(request.execute()).thenReturn(list);
    }

    private CustomObjectsApi.APIcreateNamespacedCustomObjectRequest stubCustomObjectCreate(
            String group, String version, String plural) {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest request =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(
                eq(group), eq(version), eq(NAMESPACE), eq(plural), any())).thenReturn(request);
        return request;
    }

    private static Map<String, Object> rawObject(String name) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("metadata", metadata);
        return object;
    }
}
