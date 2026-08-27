package org.qubership.integration.platform.runtime.catalog.kubernetes;

import com.coreos.monitoring.models.V1ServiceMonitor;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.PatchUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainDeployError;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the dispatch that {@link KubeOperator#createOrUpdateResource} performs over the resource
 * types it supports, and the create-versus-replace decision each branch makes. Secrets are the one
 * exception: they are created once and never replaced, so that an operator-managed secret does not
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
        stubConfigMapReadAsNotFound("order-chain-sources");
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(configMap);

        verify(createRequest).execute();
    }

    @Test
    void replacesAConfigMapThatAlreadyExists() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(configMap("order-chain-sources"));
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(configMap);

        verify(replaceRequest).execute();
        verify(coreApi, never()).createNamespacedConfigMap(anyString(), any(V1ConfigMap.class));
    }

    @Test
    void replacesAConfigMapThatAlreadyExistsInsteadOfPatchingIt() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(configMap);

        verify(replaceRequest).execute();
        assertEquals("101", configMap.getMetadata().getResourceVersion(),
                "the live version becomes the precondition when the caller supplied none");
    }

    // Task 2 sets the version from the Phase 1 read. If the write path overwrote it with the
    // version it just fetched, the precondition would always match and never detect a race.
    @Test
    void keepsACallerSuppliedResourceVersionInsteadOfOverwritingIt() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        configMap.getMetadata().setResourceVersion("77");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(configMap);

        assertEquals("77", configMap.getMetadata().getResourceVersion());
    }

    @Test
    void raisesAConflictExceptionWhenAReplaceLosesTheRace() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrUpdateResource(configMap));
    }

    @Test
    void raisesAConflictExceptionWhenACreateFindsTheObjectAlreadyThere() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "AlreadyExists"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrUpdateResource(configMap));
    }

    @Test
    void createsWithoutReadingWhenTheCallerObservedTheObjectAbsent() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(configMap, true);

        verify(createRequest).execute();
        // Reading first would find an object a racing writer created during the build, and the
        // replace that followed would overwrite it wholesale instead of raising a conflict.
        verify(coreApi, never()).readNamespacedConfigMap(anyString(), anyString());
        verify(coreApi, never()).replaceNamespacedConfigMap(anyString(), anyString(), any(V1ConfigMap.class));
    }

    @Test
    void raisesAConflictExceptionWhenAnObservedAbsentCreateLosesTheRace() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "AlreadyExists"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrUpdateResource(configMap, true));
    }

    // The API server rejects a create that declares a resourceVersion, and not with a 409, so the
    // deploy retry would never fire. A version reaches this branch whenever Phase 1 observed the
    // object and something deleted it before the write.
    @Test
    void clearsAStaleResourceVersionBeforeCreatingAConfigMap() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        configMap.getMetadata().setResourceVersion("77");
        stubConfigMapReadAsNotFound("order-chain-sources");
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(configMap);

        verify(createRequest).execute();
        assertNull(configMap.getMetadata().getResourceVersion());
    }

    @Test
    void clearsAStaleResourceVersionBeforeCreatingAService() throws ApiException {
        V1Service service = service("order-chain-service");
        service.getMetadata().setResourceVersion("77");
        stubServiceReadAsNotFound("order-chain-service");
        CoreV1Api.APIcreateNamespacedServiceRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedServiceRequest.class);
        when(coreApi.createNamespacedService(NAMESPACE, service)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(service);

        verify(createRequest).execute();
        assertNull(service.getMetadata().getResourceVersion());
    }

    @Test
    void clearsAStaleResourceVersionBeforeCreatingACustomObject() throws ApiException {
        KubeCustomObject httpRoute = customObject(GATEWAY_GROUP + "/" + GATEWAY_VERSION, "HTTPRoute", "orders-public");
        httpRoute.getMetadata().setResourceVersion("77");
        stubCustomObjectReadAsNotFound(GATEWAY_GROUP, GATEWAY_VERSION, HTTP_ROUTES_PLURAL, "orders-public");
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                stubCustomObjectCreate(GATEWAY_GROUP, GATEWAY_VERSION, HTTP_ROUTES_PLURAL);

        kubeOperator.createOrUpdateResource(httpRoute);

        verify(createRequest).execute();
        assertNull(httpRoute.getMetadata().getResourceVersion());
    }

    @Test
    void neverUsesServerSideApplyForAnyKind() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenThrow(new ApiException(404, "Not Found"));
        CoreV1Api.APIcreateNamespacedConfigMapRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedConfigMapRequest.class);
        when(coreApi.createNamespacedConfigMap(NAMESPACE, configMap)).thenReturn(createRequest);

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            kubeOperator.createOrUpdateResource(configMap);
            patchUtils.verifyNoInteractions();
        }
    }

    // The create-path check above passes trivially: a create never had a patch to skip. This is
    // the case that would have caught a regression back to PatchUtils, since the old code patched
    // exactly when the object already existed.
    @Test
    void neverUsesServerSideApplyWhenTheObjectAlreadyExists() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        V1ConfigMap live = configMap("order-chain-sources");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);

        try (MockedStatic<PatchUtils> patchUtils = mockStatic(PatchUtils.class)) {
            kubeOperator.createOrUpdateResource(configMap);
            patchUtils.verifyNoInteractions();
        }
    }

    @Test
    void wrapsApiFailuresRaisedWhileApplyingAConfigMap() throws ApiException {
        V1ConfigMap configMap = configMap("order-chain-sources");
        CoreV1Api.APIreadNamespacedConfigMapRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap("order-chain-sources", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(configMap("order-chain-sources"));
        CoreV1Api.APIreplaceNamespacedConfigMapRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedConfigMapRequest.class);
        when(coreApi.replaceNamespacedConfigMap("order-chain-sources", NAMESPACE, configMap))
                .thenReturn(replaceRequest);
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(replaceRequest.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.createOrUpdateResource(configMap));

        assertSame(apiException, exception.getOriginalException());
        assertFalse(exception instanceof KubeApiConflictException,
                "a 500 is not a lost optimistic-concurrency race");
    }

    @Test
    void createsAServiceThatDoesNotExistYet() throws ApiException {
        V1Service service = service("order-chain-service");
        stubServiceReadAsNotFound("order-chain-service");
        CoreV1Api.APIcreateNamespacedServiceRequest createRequest =
                mock(CoreV1Api.APIcreateNamespacedServiceRequest.class);
        when(coreApi.createNamespacedService(NAMESPACE, service)).thenReturn(createRequest);

        kubeOperator.createOrUpdateResource(service);

        verify(createRequest).execute();
    }

    @Test
    void replacesAServiceThatAlreadyExists() throws ApiException {
        V1Service service = service("order-chain-service");
        CoreV1Api.APIreadNamespacedServiceRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedServiceRequest.class);
        when(coreApi.readNamespacedService("order-chain-service", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(service("order-chain-service"));
        CoreV1Api.APIreplaceNamespacedServiceRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedServiceRequest.class);
        when(coreApi.replaceNamespacedService("order-chain-service", NAMESPACE, service))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(service);

        verify(replaceRequest).execute();
        verify(coreApi, never()).createNamespacedService(anyString(), any(V1Service.class));
    }

    // Same property as keepsACallerSuppliedResourceVersionInsteadOfOverwritingIt, pinned for the
    // Service path too rather than inferred from shared code: a caller-supplied version is a
    // deliberate precondition, and overwriting it with the version this call just fetched would make
    // the precondition always match and never detect a race.
    @Test
    void keepsACallerSuppliedResourceVersionInsteadOfOverwritingItForAService() throws ApiException {
        V1Service service = service("order-chain-service");
        service.getMetadata().setResourceVersion("77");
        V1Service live = service("order-chain-service");
        live.getMetadata().setResourceVersion("101");
        CoreV1Api.APIreadNamespacedServiceRequest readRequest =
                mock(CoreV1Api.APIreadNamespacedServiceRequest.class);
        when(coreApi.readNamespacedService("order-chain-service", NAMESPACE)).thenReturn(readRequest);
        when(readRequest.execute()).thenReturn(live);
        CoreV1Api.APIreplaceNamespacedServiceRequest replaceRequest =
                mock(CoreV1Api.APIreplaceNamespacedServiceRequest.class);
        when(coreApi.replaceNamespacedService("order-chain-service", NAMESPACE, service))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(service);

        assertEquals("77", service.getMetadata().getResourceVersion());
    }

    @Test
    void createsACamelKIntegrationAtTheCamelKCoordinates() throws ApiException {
        CamelKIntegration integration = new CamelKIntegration(
                CAMEL_K_GROUP + "/" + CAMEL_K_VERSION, "Integration",
                new V1ObjectMeta().name("order-chain"), null);
        stubCustomObjectReadAsNotFound(CAMEL_K_GROUP, CAMEL_K_VERSION, INTEGRATIONS_PLURAL, "order-chain");
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
        stubCustomObjectReadAsNotFound(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL,
                "order-chain-monitor");
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                stubCustomObjectCreate(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL);

        kubeOperator.createOrUpdateResource(serviceMonitor);

        verify(customObjectsApi).createNamespacedCustomObject(
                eq(MONITORING_GROUP), eq(MONITORING_VERSION), eq(NAMESPACE), eq(SERVICE_MONITORS_PLURAL), any());
        verify(createRequest).execute();
    }

    @Test
    void replacesAnHttpRouteThatAlreadyExists() throws ApiException {
        KubeCustomObject httpRoute = customObject(
                GATEWAY_GROUP + "/" + GATEWAY_VERSION, "HTTPRoute", "order-chain-public-routes");
        stubCustomObjectRead(GATEWAY_GROUP, GATEWAY_VERSION, HTTP_ROUTES_PLURAL, "order-chain-public-routes",
                rawObjectWithResourceVersion("order-chain-public-routes", "101"));
        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(eq(GATEWAY_GROUP), eq(GATEWAY_VERSION), eq(NAMESPACE),
                eq(HTTP_ROUTES_PLURAL), eq("order-chain-public-routes"), eq(httpRoute)))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(httpRoute);

        verify(replaceRequest).execute();
        verify(customObjectsApi, never()).createNamespacedCustomObject(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    // Same property as keepsACallerSuppliedResourceVersionInsteadOfOverwritingIt, pinned for the
    // custom-object path too rather than inferred from shared code: a caller-supplied version is a
    // deliberate precondition, and overwriting it with the version this call just fetched would make
    // the precondition always match and never detect a race.
    @Test
    void keepsACallerSuppliedResourceVersionInsteadOfOverwritingItForACustomObject() throws ApiException {
        KubeCustomObject httpRoute = customObject(
                GATEWAY_GROUP + "/" + GATEWAY_VERSION, "HTTPRoute", "order-chain-public-routes");
        httpRoute.getMetadata().setResourceVersion("77");
        stubCustomObjectRead(GATEWAY_GROUP, GATEWAY_VERSION, HTTP_ROUTES_PLURAL, "order-chain-public-routes",
                rawObjectWithResourceVersion("order-chain-public-routes", "101"));
        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(eq(GATEWAY_GROUP), eq(GATEWAY_VERSION), eq(NAMESPACE),
                eq(HTTP_ROUTES_PLURAL), eq("order-chain-public-routes"), eq(httpRoute)))
                .thenReturn(replaceRequest);

        kubeOperator.createOrUpdateResource(httpRoute);

        assertEquals("77", httpRoute.getMetadata().getResourceVersion());
    }

    @Test
    void resolvesAGenericCustomObjectThroughItsRegisteredDefinition() throws ApiException {
        KubeCustomObject mesh = customObject(MESH_GROUP + "/" + MESH_VERSION, "Mesh", "qip-mesh");
        when(genericCustomResources.definitionFor("Mesh")).thenReturn(meshDefinition(true));
        stubCustomObjectReadAsNotFound(MESH_GROUP, MESH_VERSION, MESHES_PLURAL, "qip-mesh");
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
        stubCustomObjectRead(MESH_GROUP, MESH_VERSION, MESHES_PLURAL, "qip-mesh", rawObject("qip-mesh"));

        kubeOperator.createOrUpdateResource(mesh);

        verify(customObjectsApi, never()).replaceNamespacedCustomObject(
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
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

    private CoreV1Api.APIreadNamespacedSecretRequest stubSecretRead(String name) {
        CoreV1Api.APIreadNamespacedSecretRequest request = mock(CoreV1Api.APIreadNamespacedSecretRequest.class);
        when(coreApi.readNamespacedSecret(name, NAMESPACE)).thenReturn(request);
        return request;
    }

    private void stubConfigMapReadAsNotFound(String name) throws ApiException {
        CoreV1Api.APIreadNamespacedConfigMapRequest request = mock(CoreV1Api.APIreadNamespacedConfigMapRequest.class);
        when(coreApi.readNamespacedConfigMap(name, NAMESPACE)).thenReturn(request);
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));
    }

    private void stubServiceReadAsNotFound(String name) throws ApiException {
        CoreV1Api.APIreadNamespacedServiceRequest request = mock(CoreV1Api.APIreadNamespacedServiceRequest.class);
        when(coreApi.readNamespacedService(name, NAMESPACE)).thenReturn(request);
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));
    }

    private void stubCustomObjectReadAsNotFound(String group, String version, String plural, String name)
            throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest request =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(group, version, NAMESPACE, plural, name)).thenReturn(request);
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));
    }

    private void stubCustomObjectRead(String group, String version, String plural, String name, Object rawObject)
            throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest request =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(group, version, NAMESPACE, plural, name)).thenReturn(request);
        when(request.execute()).thenReturn(rawObject);
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

    private static Map<String, Object> rawObjectWithResourceVersion(String name, String resourceVersion) {
        Map<String, Object> object = rawObject(name);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) object.get("metadata");
        metadata.put("resourceVersion", resourceVersion);
        return object;
    }
}
