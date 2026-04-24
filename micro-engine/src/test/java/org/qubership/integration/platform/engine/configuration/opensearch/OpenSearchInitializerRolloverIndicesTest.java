package org.qubership.integration.platform.engine.configuration.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexState;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.qubership.integration.platform.engine.opensearch.ism.IndexStateManagementClient;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerRolloverIndicesTest {

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

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = ObjectMappers.getObjectMapper();
        initializer.jsonMapper = objectMapper;
    }

    @Test
    void shouldCreateRolloverIndex() throws Exception {
        initializer.properties = properties;
        stubShards(3);

        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        OpenSearchTestUtils.invokeVoid(
                initializer,
                "createRolloverIndex",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        );

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(genericClient).execute(requestCaptor.capture());

        Request request = requestCaptor.getValue();
        assertEquals("PUT", request.getMethod());
        assertEquals("/sessions-000001", request.getEndpoint());
        OpenSearchTestUtils.assertJsonBodyEquals(objectMapper, request, """
            {
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
              },
              "aliases": {
                "sessions-session-elements": {
                  "is_write_index": true
                }
              }
            }
            """);
    }

    @Test
    void shouldNotThrowWhenCreateRolloverIndexFailsWithIOException() throws Exception {
        initializer.properties = properties;
        stubShards(3);

        when(client.generic()).thenReturn(genericClient);
        doThrow(new IOException("boom")).when(genericClient).execute(any(Request.class));

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "createRolloverIndex",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        ));
    }

    @Test
    void shouldReturnWhenCreateOrUpdateRolloverIndicesFailsToGetIndices() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        doThrow(new IOException("boom")).when(indicesClient).get(any(GetIndexRequest.class));

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "createOrUpdateRolloverIndices",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        ));

        verify(indicesClient).get(any(GetIndexRequest.class));
    }

    @Test
    void shouldCreateRolloverIndexWhenNoMatchingIndicesExist() throws Exception {
        initializer.properties = properties;
        stubShards(3);

        GetIndexResponse getIndexResponse = org.mockito.Mockito.mock(GetIndexResponse.class);

        when(client.indices()).thenReturn(indicesClient);
        when(client.generic()).thenReturn(genericClient);
        when(indicesClient.get(any(GetIndexRequest.class))).thenReturn(getIndexResponse);
        when(getIndexResponse.result()).thenReturn(Map.of());
        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        OpenSearchTestUtils.invokeVoid(
                initializer,
                "createOrUpdateRolloverIndices",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "sessions",
                OpenSearchTestUtils.getMapping()
        );

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(genericClient).execute(requestCaptor.capture());

        Request request = requestCaptor.getValue();
        assertEquals("PUT", request.getMethod());
        assertEquals("/sessions-000001", request.getEndpoint());
    }

    @Test
    void shouldUpdateMappingsAndTryToAddPolicyForExistingRolloverIndices() throws Exception {
        GetIndexResponse getIndexResponse = org.mockito.Mockito.mock(GetIndexResponse.class);

        Map<String, IndexState> indices = new LinkedHashMap<>();
        indices.put("sessions", IndexState.of(builder -> builder));
        indices.put("sessions-000001", IndexState.of(builder -> builder));
        indices.put("sessions-000002", IndexState.of(builder -> builder));

        when(client.indices()).thenReturn(indicesClient);
        when(client.generic()).thenReturn(genericClient);
        when(indicesClient.get(any(GetIndexRequest.class))).thenReturn(getIndexResponse);
        when(getIndexResponse.result()).thenReturn(indices);
        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(IndexStateManagementClient.class)) {
            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "createOrUpdateRolloverIndices",
                    new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                    client,
                    "sessions",
                    OpenSearchTestUtils.getMapping()
            );

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(genericClient, times(2)).execute(requestCaptor.capture());

            Set<String> endpoints = requestCaptor.getAllValues().stream()
                    .map(Request::getEndpoint)
                    .collect(java.util.stream.Collectors.toSet());

            assertEquals(Set.of("/sessions-000001/_mapping", "/sessions-000002/_mapping"), endpoints);
            assertEquals(2, mocked.constructed().size());

            ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
            for (IndexStateManagementClient ismClient : mocked.constructed()) {
                verify(ismClient).addPolicy(indexCaptor.capture(), org.mockito.ArgumentMatchers.eq("sessions-rollover-policy"));
            }

            assertEquals(Set.of("sessions-000001", "sessions-000002"), Set.copyOf(indexCaptor.getAllValues()));
        }
    }

    private void stubShards(int shards) {
        when(properties.index()).thenReturn(indexProperties);
        when(indexProperties.elements()).thenReturn(elementsProperties);
        when(elementsProperties.shards()).thenReturn(shards);
    }
}
