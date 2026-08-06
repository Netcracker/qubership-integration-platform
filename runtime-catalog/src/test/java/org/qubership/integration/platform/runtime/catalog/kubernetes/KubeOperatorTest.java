package org.qubership.integration.platform.runtime.catalog.kubernetes;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.ModelMapper;
import io.kubernetes.client.util.Yaml;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObjectList;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    // Finding 1: createOrUpdateResource must be able to apply a parsed HTTPRoute even when
    // genericCustomResources is null (as it legitimately is under the "localdev" profile, where
    // GenericCustomResources.getCustomResourceDefinitions() returns an empty map). Before the fix,
    // the KubeCustomObject branch always resolved group/version/plural via
    // genericCustomResources.definitionFor(kind), which either threw
    // IllegalArgumentException("No generic custom resource definition for kind HTTPRoute") or, with
    // genericCustomResources itself null as here, threw via the orElseThrow.
    @Test
    void createOrUpdateResourceAppliesParsedHttpRouteWithoutGenericCustomResources() throws Exception {
        ModelMapper.addModelMap(GROUP, VERSION, "HTTPRoute", PLURAL, KubeCustomObject.class, KubeCustomObjectList.class);
        String httpRouteYaml = "apiVersion: gateway.networking.k8s.io/v1\n"
                + "kind: HTTPRoute\n"
                + "metadata:\n"
                + "  name: " + NAME + "\n"
                + "spec:\n"
                + "  parentRefs:\n"
                + "    - group: gateway.networking.k8s.io\n"
                + "      kind: Gateway\n"
                + "      name: public-gateway\n"
                + "  rules:\n"
                + "    - matches:\n"
                + "        - path:\n"
                + "            type: PathPrefix\n"
                + "            value: /qip-routes/a\n";
        List<Object> parsed = Yaml.loadAll(httpRouteYaml);
        assertEquals(1, parsed.size());
        Object resource = parsed.get(0);
        assertTrue(resource instanceof KubeCustomObject);

        CustomObjectsApi.APIlistNamespacedCustomObjectRequest listRequest =
                mock(CustomObjectsApi.APIlistNamespacedCustomObjectRequest.class);
        when(customObjectsApi.listNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL)).thenReturn(listRequest);
        Map<String, Object> emptyList = new LinkedHashMap<>();
        emptyList.put("items", List.of());
        when(listRequest.execute()).thenReturn(emptyList);

        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any()))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrUpdateResource(resource));

        verify(customObjectsApi).createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any());
    }
}
