package org.qubership.integration.platform.runtime.catalog.kubernetes;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        kubeOperator = new KubeOperator();
        customObjectsApi = mock(CustomObjectsApi.class);
        ReflectionTestUtils.setField(kubeOperator, "customObjectsApi", customObjectsApi);
        ReflectionTestUtils.setField(kubeOperator, "namespace", NAMESPACE);
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

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(GROUP, VERSION, PLURAL, NAME);

        assertTrue(result.isPresent());
        assertEquals("HTTPRoute", result.get().getKind());
        assertEquals(NAME, result.get().getMetadata().getName());
    }

    @Test
    void getCustomObjectReturnsEmptyOn404() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        when(getRequest.execute()).thenThrow(new ApiException(404, "Not Found"));

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(GROUP, VERSION, PLURAL, NAME);

        assertTrue(result.isEmpty());
    }

    @Test
    void getCustomObjectThrowsKubeApiExceptionOnOtherFailure() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        when(getRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThrows(KubeApiException.class,
                () -> kubeOperator.getCustomObject(GROUP, VERSION, PLURAL, NAME));
    }
}
