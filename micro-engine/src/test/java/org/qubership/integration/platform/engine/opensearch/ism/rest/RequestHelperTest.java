package org.qubership.integration.platform.engine.opensearch.ism.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.ContentType;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RequestHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Response response;

    @Test
    void shouldBuildGetPolicyRequest() {
        Request request = RequestHelper.buildGetPolicyRequest("policy-1");

        assertEquals(HttpGet.METHOD_NAME, request.getMethod());
        assertEquals("/_plugins/_ism/policies/policy-1", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertTrue(request.getBody().isEmpty());
    }

    @Test
    void shouldBuildCreatePolicyRequest() throws Exception {
        Policy policy = Policy.builder()
                .policyId("policy-1")
                .build();

        Request request = RequestHelper.buildCreatePolicyRequest(objectMapper, policy);

        assertEquals(HttpPut.METHOD_NAME, request.getMethod());
        assertEquals("/_plugins/_ism/policies/policy-1", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertEquals(String.valueOf(ContentType.APPLICATION_JSON), request.getBody().orElseThrow().contentType());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
                {
                  "policy": {
                    "policy_id": "policy-1"
                  }
                }
                """);
    }

    @Test
    void shouldBuildUpdatePolicyRequest() throws Exception {
        Policy policy = Policy.builder()
                .policyId("policy-1")
                .build();

        Request request = RequestHelper.buildUpdatePolicyRequest(objectMapper, policy, 11L, 17L);

        assertEquals(HttpPut.METHOD_NAME, request.getMethod());
        assertEquals("/_plugins/_ism/policies/policy-1", request.getEndpoint());
        assertEquals(Map.of(
                "if_seq_no", "11",
                "if_primary_term", "17"
        ), request.getParameters());
        assertTrue(request.getHeaders().isEmpty());
        assertEquals(String.valueOf(ContentType.APPLICATION_JSON), request.getBody().orElseThrow().contentType());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
                {
                  "policy": {
                    "policy_id": "policy-1"
                  }
                }
                """);
    }

    @Test
    void shouldBuildAddPolicyRequest() throws Exception {
        Request request = RequestHelper.buildAddPolicyRequest(objectMapper, "index-1", "policy-1");

        assertEquals(HttpPost.METHOD_NAME, request.getMethod());
        assertEquals("/_plugins/_ism/add/index-1", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertEquals(String.valueOf(ContentType.APPLICATION_JSON), request.getBody().orElseThrow().contentType());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
                {
                  "policy_id": "policy-1"
                }
                """);
    }

    @Test
    void shouldBuildRemovePolicyFromIndexRequest() {
        Request request = RequestHelper.buildRemovePolicyFromIndexRequest("index-1");

        assertEquals(HttpPost.METHOD_NAME, request.getMethod());
        assertEquals("/_plugins/_ism/remove/index-1", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertTrue(request.getBody().isEmpty());
    }

    @Test
    void shouldBuildPutIndexTemplateRequest() throws Exception {
        Map<String, Object> requestBody = Map.of("index_patterns", new String[] { "sessions-*" });

        Request request = RequestHelper.buildPutIndexTemplateRequest(objectMapper, "template-1", requestBody);

        assertEquals(HttpPut.METHOD_NAME, request.getMethod());
        assertEquals("/_index_template/template-1", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertEquals(String.valueOf(ContentType.APPLICATION_JSON), request.getBody().orElseThrow().contentType());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
                {
                  "index_patterns": ["sessions-*"]
                }
                """);
    }

    @Test
    void shouldBuildCreateIndexRequest() throws Exception {
        Map<String, Object> requestBody = Map.of("settings", Map.of("number_of_shards", 1));

        Request request = RequestHelper.buildCreateIndexRequest(objectMapper, "index-1", requestBody);

        assertEquals(HttpPut.METHOD_NAME, request.getMethod());
        assertEquals("/index-1", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertEquals(String.valueOf(ContentType.APPLICATION_JSON), request.getBody().orElseThrow().contentType());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
                {
                  "settings": {
                    "number_of_shards": 1
                  }
                }
                """);
    }

    @Test
    void shouldBuildPutIndexMapping() throws Exception {
        Map<String, Object> requestBody = OpenSearchTestUtils.getMapping();

        Request request = RequestHelper.buildPutIndexMapping(objectMapper, "index-1", requestBody);

        assertEquals(HttpPut.METHOD_NAME, request.getMethod());
        assertEquals("/index-1/_mapping", request.getEndpoint());
        assertTrue(request.getParameters().isEmpty());
        assertTrue(request.getHeaders().isEmpty());
        assertEquals(String.valueOf(ContentType.APPLICATION_JSON), request.getBody().orElseThrow().contentType());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
                {
                  "properties": {
                    "field1": {
                      "type": "keyword"
                    }
                  }
                }
                """);
    }

    @Test
    void shouldEncodePathPartsAndIgnoreBlankOnes() {
        String endpoint = new RequestHelper.EndpointBuilder()
                .addPathPart("index/name", " ", null, "a b", "x:y")
                .build();

        assertEquals("/index%2Fname/a%20b/x:y", endpoint);
    }

    @ParameterizedTest
    @CsvSource({
            "400, true",
            "404, true",
            "200, false",
            "500, false"
    })
    void shouldDetectClientHttpErrorsOnly(int status, boolean expected) {
        assertEquals(expected, RequestHelper.isHttpError(status));
    }

    @Test
    void shouldThrowIOExceptionWhenClientErrorResponseContainsBody() {
        when(response.getStatus()).thenReturn(404);
        when(response.getBody()).thenReturn(Optional.of(
                Body.from("policy not found".getBytes(StandardCharsets.UTF_8), "text/plain")
        ));

        IOException exception = assertThrows(
                IOException.class,
                () -> RequestHelper.processHttpResponse(response)
        );

        assertEquals("Opensearch request error: policy not found", exception.getMessage());
    }

    @Test
    void shouldThrowIOExceptionWhenClientErrorResponseHasNoBody() {
        when(response.getStatus()).thenReturn(400);
        when(response.getBody()).thenReturn(Optional.empty());

        IOException exception = assertThrows(
                IOException.class,
                () -> RequestHelper.processHttpResponse(response)
        );

        assertEquals("Opensearch request error: ", exception.getMessage());
    }

    @Test
    void shouldNotThrowWhenResponseIsNotClientError() {
        when(response.getStatus()).thenReturn(500);

        assertDoesNotThrow(() -> RequestHelper.processHttpResponse(response));
    }
}
