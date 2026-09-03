package org.qubership.integration.platform.runtime.catalog.kubernetes;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the delete paths of {@link KubeOperator}. Deletion is idempotent by design: a 404 means
 * the object is already gone and is logged rather than raised, while any other API error becomes a
 * {@link KubeApiException}. The two named custom-object deletes are thin wrappers, so the tests
 * pin the API coordinates they pass on.
 */
class KubeOperatorDeletionTest {

    private static final String NAMESPACE = "qip";
    private static final String NAME = "order-chain";
    private static final String CAMEL_K_GROUP = "camel.apache.org";
    private static final String CAMEL_K_VERSION = "v1";
    private static final String INTEGRATIONS_PLURAL = "integrations";
    private static final String MONITORING_GROUP = "monitoring.coreos.com";
    private static final String MONITORING_VERSION = "v1";
    private static final String SERVICE_MONITORS_PLURAL = "servicemonitors";
    private static final String ISTIO_GROUP = "networking.istio.io";
    private static final String ISTIO_VERSION = "v1";
    private static final String SERVICE_ENTRIES_PLURAL = "serviceentries";

    private CoreV1Api coreApi;
    private CustomObjectsApi customObjectsApi;
    private KubeOperator kubeOperator;

    @BeforeEach
    void setUp() {
        kubeOperator = new KubeOperator();
        coreApi = mock(CoreV1Api.class);
        customObjectsApi = mock(CustomObjectsApi.class);
        ReflectionTestUtils.setField(kubeOperator, "coreApi", coreApi);
        ReflectionTestUtils.setField(kubeOperator, "customObjectsApi", customObjectsApi);
        ReflectionTestUtils.setField(kubeOperator, "namespace", NAMESPACE);
    }

    @Test
    void deleteConfigMapDeletesFromTheOperatorNamespace() throws ApiException {
        CoreV1Api.APIdeleteNamespacedConfigMapRequest request = stubConfigMapDelete();

        kubeOperator.deleteConfigMap(NAME);

        verify(coreApi).deleteNamespacedConfigMap(NAME, NAMESPACE);
        verify(request).execute();
    }

    @Test
    void deleteConfigMapIgnoresAConfigMapThatIsAlreadyGone() throws ApiException {
        CoreV1Api.APIdeleteNamespacedConfigMapRequest request = stubConfigMapDelete();
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertDoesNotThrow(() -> kubeOperator.deleteConfigMap(NAME));
    }

    @Test
    void deleteConfigMapWrapsApiFailures() throws ApiException {
        CoreV1Api.APIdeleteNamespacedConfigMapRequest request = stubConfigMapDelete();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.deleteConfigMap(NAME));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void deleteServiceDeletesFromTheOperatorNamespace() throws ApiException {
        CoreV1Api.APIdeleteNamespacedServiceRequest request = stubServiceDelete();

        kubeOperator.deleteService(NAME);

        verify(coreApi).deleteNamespacedService(NAME, NAMESPACE);
        verify(request).execute();
    }

    @Test
    void deleteServiceIgnoresAServiceThatIsAlreadyGone() throws ApiException {
        CoreV1Api.APIdeleteNamespacedServiceRequest request = stubServiceDelete();
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertDoesNotThrow(() -> kubeOperator.deleteService(NAME));
    }

    @Test
    void deleteServiceWrapsApiFailures() throws ApiException {
        CoreV1Api.APIdeleteNamespacedServiceRequest request = stubServiceDelete();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.deleteService(NAME));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void deleteSecretDeletesFromTheOperatorNamespace() throws ApiException {
        CoreV1Api.APIdeleteNamespacedSecretRequest request = stubSecretDelete();

        kubeOperator.deleteSecret(NAME);

        verify(coreApi).deleteNamespacedSecret(NAME, NAMESPACE);
        verify(request).execute();
    }

    @Test
    void deleteSecretIgnoresASecretThatIsAlreadyGone() throws ApiException {
        CoreV1Api.APIdeleteNamespacedSecretRequest request = stubSecretDelete();
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertDoesNotThrow(() -> kubeOperator.deleteSecret(NAME));
    }

    @Test
    void deleteSecretWrapsApiFailures() throws ApiException {
        CoreV1Api.APIdeleteNamespacedSecretRequest request = stubSecretDelete();
        ApiException apiException = new ApiException(500, "Internal Server Error");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception =
                assertThrows(KubeApiException.class, () -> kubeOperator.deleteSecret(NAME));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void deleteCustomObjectDeletesFromTheOperatorNamespace() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest request =
                stubCustomObjectDelete(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL);

        kubeOperator.deleteCustomObject(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL, NAME);

        verify(customObjectsApi)
                .deleteNamespacedCustomObject(ISTIO_GROUP, ISTIO_VERSION, NAMESPACE, SERVICE_ENTRIES_PLURAL, NAME);
        verify(request).execute();
    }

    @Test
    void deleteCustomObjectIgnoresAnObjectThatIsAlreadyGone() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest request =
                stubCustomObjectDelete(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL);
        when(request.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertDoesNotThrow(() ->
                kubeOperator.deleteCustomObject(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL, NAME));
    }

    @Test
    void deleteCustomObjectWrapsApiFailures() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest request =
                stubCustomObjectDelete(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL);
        ApiException apiException = new ApiException(409, "Conflict");
        when(request.execute()).thenThrow(apiException);

        KubeApiException exception = assertThrows(KubeApiException.class, () ->
                kubeOperator.deleteCustomObject(ISTIO_GROUP, ISTIO_VERSION, SERVICE_ENTRIES_PLURAL, NAME));

        assertSame(apiException, exception.getOriginalException());
    }

    @Test
    void deleteServiceMonitorTargetsThePrometheusOperatorCoordinates() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest request =
                stubCustomObjectDelete(MONITORING_GROUP, MONITORING_VERSION, SERVICE_MONITORS_PLURAL);

        kubeOperator.deleteServiceMonitor(NAME);

        verify(customObjectsApi).deleteNamespacedCustomObject(
                MONITORING_GROUP, MONITORING_VERSION, NAMESPACE, SERVICE_MONITORS_PLURAL, NAME);
        verify(request).execute();
    }

    @Test
    void deleteCamelKIntegrationTargetsTheCamelKCoordinates() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest request =
                stubCustomObjectDelete(CAMEL_K_GROUP, CAMEL_K_VERSION, INTEGRATIONS_PLURAL);

        kubeOperator.deleteCamelKIntegration(NAME);

        verify(customObjectsApi).deleteNamespacedCustomObject(
                CAMEL_K_GROUP, CAMEL_K_VERSION, NAMESPACE, INTEGRATIONS_PLURAL, NAME);
        verify(request).execute();
    }

    private CoreV1Api.APIdeleteNamespacedConfigMapRequest stubConfigMapDelete() {
        CoreV1Api.APIdeleteNamespacedConfigMapRequest request =
                mock(CoreV1Api.APIdeleteNamespacedConfigMapRequest.class);
        when(coreApi.deleteNamespacedConfigMap(NAME, NAMESPACE)).thenReturn(request);
        return request;
    }

    private CoreV1Api.APIdeleteNamespacedServiceRequest stubServiceDelete() {
        CoreV1Api.APIdeleteNamespacedServiceRequest request =
                mock(CoreV1Api.APIdeleteNamespacedServiceRequest.class);
        when(coreApi.deleteNamespacedService(NAME, NAMESPACE)).thenReturn(request);
        return request;
    }

    private CoreV1Api.APIdeleteNamespacedSecretRequest stubSecretDelete() {
        CoreV1Api.APIdeleteNamespacedSecretRequest request =
                mock(CoreV1Api.APIdeleteNamespacedSecretRequest.class);
        when(coreApi.deleteNamespacedSecret(NAME, NAMESPACE)).thenReturn(request);
        return request;
    }

    private CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest stubCustomObjectDelete(
            String group, String version, String plural) {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest request =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(group, version, NAMESPACE, plural, NAME))
                .thenReturn(request);
        return request;
    }
}
