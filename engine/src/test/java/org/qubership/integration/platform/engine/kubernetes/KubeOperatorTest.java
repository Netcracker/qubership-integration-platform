package org.qubership.integration.platform.engine.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubeOperatorTest {

    private static final String GROUP = "gateway.networking.k8s.io";
    private static final String VERSION = "v1";
    private static final String NAMESPACE = "qip";
    private static final String PLURAL = "httproutes";
    private static final String NAME = "engine-service-chain-public-routes";

    private CustomObjectsApi customObjectsApi;
    private KubeOperator kubeOperator;

    @BeforeEach
    void setUp() {
        CoreV1Api coreApi = mock(CoreV1Api.class);
        customObjectsApi = mock(CustomObjectsApi.class);
        kubeOperator = new KubeOperator(new ObjectMapper(), coreApi, customObjectsApi, NAMESPACE, false);
    }

    @Test
    void getCustomObjectReturnsParsedBodyOn200() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);

        Map<String, Object> rawObject = new LinkedHashMap<>();
        rawObject.put("apiVersion", GROUP + "/" + VERSION);
        rawObject.put("kind", "HTTPRoute");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", NAME);
        rawObject.put("metadata", metadata);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", List.of());
        rawObject.put("spec", spec);
        when(getRequest.execute()).thenReturn(rawObject);

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(request());

        assertTrue(result.isPresent());
        assertEquals("HTTPRoute", result.get().getKind());
        assertEquals(NAME, result.get().getMetadata().getName());
        assertEquals(List.of(), result.get().getSpec().get("rules"));
    }

    @Test
    void getCustomObjectReturnsEmptyOn404() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        when(getRequest.execute()).thenThrow(new ApiException(404, "Not Found"));

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(request());

        assertTrue(result.isEmpty());
    }

    @Test
    void getCustomObjectThrowsKubeApiExceptionOnOtherFailure() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        when(getRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThrows(KubeApiException.class, () -> kubeOperator.getCustomObject(request()));
    }

    @Test
    void deleteCustomObjectSucceedsOn200() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.deleteCustomObject(request()));

        verify(deleteRequest).execute();
    }

    @Test
    void deleteCustomObjectTreats404AsNoOp() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertDoesNotThrow(() -> kubeOperator.deleteCustomObject(request()));
    }

    @Test
    void deleteCustomObjectThrowsKubeApiExceptionOnOtherFailure() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThrows(KubeApiException.class, () -> kubeOperator.deleteCustomObject(request()));
    }

    private KubeCustomObjectRequest request() {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(NAME);

        return KubeCustomObjectRequest.builder()
                .group(GROUP)
                .version(VERSION)
                .resourceNamePlural(PLURAL)
                .body(KubeCustomObject.builder()
                        .metadata(metadata)
                        .build())
                .build();
    }
}
