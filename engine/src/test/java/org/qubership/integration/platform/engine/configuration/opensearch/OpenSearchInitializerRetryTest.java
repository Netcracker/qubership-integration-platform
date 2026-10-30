package org.qubership.integration.platform.engine.configuration.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.indices.ExistsAliasRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchDocument;
import org.reflections.Reflections;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchInitializerRetryTest {

    @Mock
    private Environment environment;

    @Mock
    private DbaasOpensearchClient dbaasClient;

    @Mock
    private OpenSearchClient client;

    @Mock
    private OpenSearchGenericClient genericClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    private OpenSearchInitializer initializer;

    @BeforeEach
    void setUp() throws IOException {
        when(environment.getProperty(anyString())).thenReturn("cip-elements");
        when(dbaasClient.getClient()).thenReturn(client);
        when(dbaasClient.normalize("cip-elements")).thenReturn("cip-elements");
        when(client.generic()).thenReturn(genericClient);
        when(client.indices()).thenReturn(indicesClient);

        // OpenSearch is unreachable for every step, except where a test stubs the alias check
        when(genericClient.execute(any(Request.class))).thenThrow(new IOException("Connection refused"));
        doThrow(new IOException("Connection refused")).when(indicesClient).get(any(GetIndexRequest.class));
        doThrow(new IOException("Connection refused")).when(indicesClient).exists(existsRequest());

        initializer = spy(new OpenSearchInitializer(environment, new ObjectMapper(), dbaasClient));
    }

    @Test
    void shouldRetryUntilWriteAliasExists() throws IOException {
        doNothing().when(initializer).pause(anyLong());
        doThrow(new IOException("Connection refused"))
                .doReturn(new BooleanResponse(false))
                .doReturn(new BooleanResponse(true))
                .when(indicesClient).existsAlias(aliasRequest());

        try (MockedConstruction<Reflections> ignored = mockReflections()) {
            initializer.initialize();
        }

        verify(indicesClient, times(3)).existsAlias(aliasRequest());
        verify(initializer, times(2)).pause(anyLong());
        var order = inOrder(initializer);
        order.verify(initializer).pause(1000L);
        order.verify(initializer).pause(2000L);
    }

    @Test
    void shouldNotPauseWhenWriteAliasExistsOnFirstAttempt() throws IOException {
        doReturn(new BooleanResponse(true)).when(indicesClient).existsAlias(aliasRequest());

        try (MockedConstruction<Reflections> ignored = mockReflections()) {
            initializer.initialize();
        }

        ArgumentCaptor<Function<ExistsAliasRequest.Builder, ObjectBuilder<ExistsAliasRequest>>> request =
                ArgumentCaptor.captor();
        verify(indicesClient).existsAlias(request.capture());
        ExistsAliasRequest sent = request.getValue().apply(new ExistsAliasRequest.Builder()).build();
        assertEquals(List.of("cip-elements-*"), sent.index());
        assertEquals(List.of("cip-elements-session-elements"), sent.name());
        verify(initializer, never()).pause(anyLong());
    }

    @Test
    void shouldFailStartupWhenWriteAliasIsStillMissingAfterLastAttempt() throws IOException {
        doNothing().when(initializer).pause(anyLong());
        doThrow(new IOException("Connection refused")).when(indicesClient).existsAlias(aliasRequest());

        try (MockedConstruction<Reflections> ignored = mockReflections()) {
            assertThrows(IllegalStateException.class, initializer::initialize);
        }

        verify(indicesClient, times(OpenSearchInitializer.INIT_MAX_ATTEMPTS)).existsAlias(aliasRequest());
        verify(initializer, times(OpenSearchInitializer.INIT_MAX_ATTEMPTS - 1)).pause(anyLong());
        verify(initializer).pause(4000L);
        verify(initializer, times(OpenSearchInitializer.INIT_MAX_ATTEMPTS - 4)).pause(5000L);
    }

    private static Function<ExistsAliasRequest.Builder, ObjectBuilder<ExistsAliasRequest>> aliasRequest() {
        return any();
    }

    private static Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>> existsRequest() {
        return any();
    }

    private static MockedConstruction<Reflections> mockReflections() {
        return mockConstruction(Reflections.class, (mock, context) ->
                when(mock.getTypesAnnotatedWith(OpenSearchDocument.class))
                        .thenReturn(Set.of(SessionElementElastic.class)));
    }

}
