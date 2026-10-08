package org.qubership.integration.platform.engine.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

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
    void getCustomObjectReturnsParsedBodyOn200() throws Exception {
        stubGet(200, """
                {"apiVersion": "%s/%s", "kind": "HTTPRoute", "metadata": {"name": "%s"}, "spec": {"rules": []}}
                """.formatted(GROUP, VERSION, NAME));

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(request());

        assertTrue(result.isPresent());
        assertEquals("HTTPRoute", result.get().getKind());
        assertEquals(NAME, result.get().getMetadata().getName());
        assertEquals(List.of(), result.get().getSpec().get("rules"));
    }

    @Test
    void getCustomObjectKeepsIntegerFieldsAsIntegers() throws Exception {
        stubGet(200, """
                {"apiVersion": "networking.istio.io/v1", "kind": "ServiceEntry",
                 "metadata": {"name": "%s"},
                 "spec": {"ports": [{"number": 80, "name": "http-80", "protocol": "HTTP"}]}}
                """.formatted(NAME));

        KubeCustomObject result = kubeOperator.getCustomObject(request()).orElseThrow();

        List<?> ports = (List<?>) result.getSpec().get("ports");
        assertEquals(80, ((Map<?, ?>) ports.get(0)).get("number"));
    }

    @Test
    void getCustomObjectReturnsEmptyOn404() throws Exception {
        stubGet(404, "{\"kind\": \"Status\", \"code\": 404}");

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(request());

        assertTrue(result.isEmpty());
    }

    @Test
    void getCustomObjectThrowsKubeApiExceptionOnOtherFailure() throws Exception {
        stubGet(500, "{\"kind\": \"Status\", \"code\": 500}");
        KubeCustomObjectRequest req = request();

        assertThrows(KubeApiException.class, () -> kubeOperator.getCustomObject(req));
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
        KubeCustomObjectRequest req = request();

        assertThrows(KubeApiException.class, () -> kubeOperator.deleteCustomObject(req));
    }

    @Test
    void deleteCustomObjectPassesResourceVersionAsPreconditionWhenSet() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.deleteCustomObject(req));

        ArgumentCaptor<V1DeleteOptions> optionsCaptor = ArgumentCaptor.forClass(V1DeleteOptions.class);
        verify(deleteRequest).body(optionsCaptor.capture());
        assertEquals("12345", optionsCaptor.getValue().getPreconditions().getResourceVersion());
        verify(deleteRequest).execute();
    }

    @Test
    void deleteCustomObjectThrowsConflictExceptionOn409() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.deleteCustomObject(req));
    }

    @Test
    void createOrReplaceCustomObjectCreatesWhenNoResourceVersionIsSet() throws ApiException {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any(KubeCustomObject.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrReplaceCustomObject(request()));

        verify(createRequest).execute();
        verify(customObjectsApi, never()).getNamespacedCustomObject(any(), any(), any(), any(), any());
    }

    @Test
    void createOrReplaceCustomObjectReplacesWhenResourceVersionIsSet() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(
                eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), eq(NAME), any(KubeCustomObject.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrReplaceCustomObject(req));

        verify(replaceRequest).execute();
        verify(customObjectsApi, never()).getNamespacedCustomObject(any(), any(), any(), any(), any());
    }

    @Test
    void createOrReplaceCustomObjectThrowsConflictExceptionOn409DuringCreate() throws ApiException {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any(KubeCustomObject.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(409, "AlreadyExists"));
        KubeCustomObjectRequest req = request();

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrReplaceCustomObject(req));
    }

    @Test
    void createOrReplaceCustomObjectThrowsConflictExceptionOn409DuringReplace() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(
                eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), eq(NAME), any(KubeCustomObject.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenThrow(new ApiException(409, "Conflict"));

        assertThrows(KubeApiConflictException.class, () -> kubeOperator.createOrReplaceCustomObject(req));
    }

    @Test
    void createOrReplaceCustomObjectThrowsKubeApiExceptionOnOtherCreateFailure() throws ApiException {
        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), any(KubeCustomObject.class)))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));
        KubeCustomObjectRequest req = request();

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.createOrReplaceCustomObject(req));
        assertFalse(exception instanceof KubeApiConflictException);
    }

    @Test
    void createOrReplaceCustomObjectThrowsKubeApiExceptionOnOtherReplaceFailure() throws ApiException {
        KubeCustomObjectRequest req = request();
        req.getBody().getMetadata().setResourceVersion("12345");

        CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest replaceRequest =
                mock(CustomObjectsApi.APIreplaceNamespacedCustomObjectRequest.class);
        when(customObjectsApi.replaceNamespacedCustomObject(
                eq(GROUP), eq(VERSION), eq(NAMESPACE), eq(PLURAL), eq(NAME), any(KubeCustomObject.class)))
                .thenReturn(replaceRequest);
        when(replaceRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        KubeApiException exception = assertThrows(KubeApiException.class,
                () -> kubeOperator.createOrReplaceCustomObject(req));
        assertFalse(exception instanceof KubeApiConflictException);
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

    private void stubGet(int code, String body) throws Exception {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        Call call = mock(Call.class);
        when(getRequest.buildCall(null)).thenReturn(call);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url("https://kubernetes.default.svc/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("HTTP " + code)
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build());
    }
}
