package org.qubership.integration.platform.engine.configuration.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerTemplateTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Mock
    private OpenSearchProperties properties;

    @Mock
    private OpenSearchProperties.IndexProperties indexProperties;

    @Mock
    private OpenSearchProperties.ElementsProperties elementsProperties;

    @Mock
    private OpenSearchClient client;

    @Mock
    private OpenSearchGenericClient genericClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    @Mock
    private Response response;

    @Mock
    private BooleanResponse booleanResponse;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = ObjectMappers.getObjectMapper();
        initializer.jsonMapper = objectMapper;
        initializer.properties = properties;
    }

    @Test
    void shouldReturnIndexSettingsWithConfiguredShardCountAndAlias() throws Exception {
        stubShards(3);

        Map<String, Object> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexSettings",
                new Class<?>[]{String.class},
                "sessions"
        );

        assertEquals(3, result.get("index.number_of_shards"));
        assertEquals("sessions-session-elements", result.get("plugins.index_state_management.rollover_alias"));
    }

    @Test
    void shouldReturnIndexPatterns() throws Exception {
        List<String> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexPatterns",
                new Class<?>[]{String.class},
                "sessions"
        );

        assertEquals(List.of("sessions", "sessions-*"), result);
    }

    @Test
    void shouldUpdateTemplate() throws Exception {
        stubShards(3);

        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        OpenSearchTestUtils.invokeVoid(
                initializer,
                "updateTemplate",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        );

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(genericClient).execute(requestCaptor.capture());

        Request request = requestCaptor.getValue();
        assertEquals("PUT", request.getMethod());
        assertEquals("/_index_template/sessions_template", request.getEndpoint());

        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
            {
              "index_patterns": [
                "sessions",
                "sessions-*"
              ],
              "priority": 1,
              "version": 4,
              "template": {
                "settings": {
                  "index.number_of_shards": 3,
                  "plugins.index_state_management.rollover_alias": "sessions-session-elements"
                },
                "mappings": {
                  "properties": {
                    "field1": {
                      "type": "keyword"
                    }
                  }
                }
              }
            }
            """);
    }

    @Test
    void shouldNotThrowWhenUpdateTemplateFailsWithIOException() throws Exception {
        stubShards(3);

        when(client.generic()).thenReturn(genericClient);
        doThrow(new IOException("boom")).when(genericClient).execute(any(Request.class));

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "updateTemplate",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        ));
    }

    @Test
    void shouldUpdateIndices() throws Exception {
        when(client.indices()).thenReturn(indicesClient);

        doThrow(new IOException("boom")).when(indicesClient).get(any(GetIndexRequest.class));
        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(false);

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "updateIndices",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        ));

        verify(indicesClient).get(any(GetIndexRequest.class));
        verify(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        verifyNoInteractions(genericClient);
    }

    private void stubShards(int shards) {
        when(properties.index()).thenReturn(indexProperties);
        when(indexProperties.elements()).thenReturn(elementsProperties);
        when(elementsProperties.shards()).thenReturn(shards);
    }
}
