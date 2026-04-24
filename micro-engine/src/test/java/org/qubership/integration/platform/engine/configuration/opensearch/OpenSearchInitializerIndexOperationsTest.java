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
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.IndexState;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.UpdateAliasesRequest;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerIndexOperationsTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

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
    }

    @Test
    void shouldUpdateIndexMapping() throws Exception {
        Map<String, Object> mapping = OpenSearchTestUtils.getMapping();

        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        OpenSearchTestUtils.invokeVoid(
                initializer,
                "updateIndexMapping",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "index-1",
                mapping
        );

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(genericClient).execute(requestCaptor.capture());

        Request request = requestCaptor.getValue();
        assertEquals("PUT", request.getMethod());
        assertEquals("/index-1/_mapping", request.getEndpoint());
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
    void shouldNotThrowWhenUpdateIndexMappingFailsWithIOException() throws Exception {
        Map<String, Object> mapping = OpenSearchTestUtils.getMapping();

        when(client.generic()).thenReturn(genericClient);
        doThrow(new IOException("boom")).when(genericClient).execute(any(Request.class));

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "updateIndexMapping",
                new Class<?>[]{OpenSearchClient.class, String.class, Map.class},
                client,
                "index-1",
                mapping
        ));
    }

    @Test
    void shouldAddIndexToAlias() throws Exception {
        when(client.indices()).thenReturn(indicesClient);

        OpenSearchTestUtils.invokeVoid(
                initializer,
                "addIndexToAlias",
                new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                client,
                "index-1",
                "alias-1"
        );

        ArgumentCaptor<UpdateAliasesRequest> requestCaptor = ArgumentCaptor.forClass(UpdateAliasesRequest.class);
        verify(indicesClient).updateAliases(requestCaptor.capture());

        UpdateAliasesRequest request = requestCaptor.getValue();
        assertEquals(1, request.actions().size());
    }

    @Test
    void shouldNotThrowWhenAddIndexToAliasFailsWithIOException() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        doThrow(new IOException("boom")).when(indicesClient).updateAliases(any(UpdateAliasesRequest.class));

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "addIndexToAlias",
                new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                client,
                "index-1",
                "alias-1"
        ));
    }

    @Test
    void shouldReturnIndexCreationTimestamp() throws Exception {
        when(client.indices()).thenReturn(indicesClient);

        GetIndexResponse getIndexResponse = GetIndexResponse.of(builder -> builder.putResult(
                "index-1",
                IndexState.of(state -> state.settings(
                        IndexSettings.of(settings -> settings.creationDate("1710000000000"))
                ))
        ));

        when(indicesClient.get(any(GetIndexRequest.class))).thenReturn(getIndexResponse);

        Instant result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexCreationTimestamp",
                new Class<?>[]{OpenSearchClient.class, String.class},
                client,
                "index-1"
        );

        assertEquals(Instant.ofEpochMilli(1_710_000_000_000L), result);
    }

    @Test
    void shouldReturnTrueWhenIndexExists() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(true);

        boolean result = OpenSearchTestUtils.invoke(
                initializer,
                "indexExists",
                new Class<?>[]{OpenSearchClient.class, String.class},
                client,
                "index-1"
        );

        assertTrue(result);
        verify(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
    }

    @Test
    void shouldReturnFalseWhenIndexDoesNotExist() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(false);

        Boolean result = OpenSearchTestUtils.invoke(
                initializer,
                "indexExists",
                new Class<?>[]{OpenSearchClient.class, String.class},
                client,
                "index-1"
        );

        assertEquals(false, result);
        verify(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
    }
}
