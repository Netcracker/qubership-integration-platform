package org.qubership.integration.platform.sessions.service;

import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.qubership.integration.platform.sessions.mapper.SessionAggregateMapper;
import org.qubership.integration.platform.sessions.mapper.SessionElementMapper;
import org.qubership.integration.platform.sessions.properties.opensearch.ElementsIndexProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.IndexProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.SessionProperties;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionServiceDeleteTest {

    private static final String INDEX = "session-elements";

    private OpenSearchClient client;
    private OpenSearchIndicesClient indices;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        client = mock(OpenSearchClient.class);
        indices = mock(OpenSearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        DbaasOpensearchClient opensearchClient = mock(DbaasOpensearchClient.class);
        when(opensearchClient.getClient()).thenReturn(client);
        when(opensearchClient.normalize(INDEX)).thenReturn(INDEX);

        OpenSearchProperties properties = new OpenSearchProperties(
                null,
                new SessionProperties(1024),
                new IndexProperties(null, new ElementsIndexProperties(INDEX)));

        sessionService = new SessionService(
                mock(SessionAggregateMapper.class),
                opensearchClient,
                mock(SessionElementMapper.class),
                properties);
    }

    static Stream<Arguments> deletes() {
        return Stream.of(
                Arguments.of("by chain id", (Consumer<SessionService>) s -> s.deleteByChainId("chain")),
                Arguments.of("by session ids", (Consumer<SessionService>) s -> s.deleteBySessionIds(List.of("session"))),
                Arguments.of("all", (Consumer<SessionService>) SessionService::deleteAllSessions));
    }

    @ParameterizedTest(name = "delete {0} refreshes the index, then proceeds past version conflicts")
    @MethodSource("deletes")
    void deleteRefreshesThenProceedsPastVersionConflicts(String name, Consumer<SessionService> delete) throws IOException {
        delete.accept(sessionService);

        ArgumentCaptor<RefreshRequest> refresh = ArgumentCaptor.forClass(RefreshRequest.class);
        ArgumentCaptor<DeleteByQueryRequest> deleteByQuery = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        InOrder order = inOrder(indices, client);
        order.verify(indices).refresh(refresh.capture());
        order.verify(client).deleteByQuery(deleteByQuery.capture());
        assertThat(refresh.getValue().index()).containsExactly(INDEX);
        assertThat(deleteByQuery.getValue().conflicts()).isEqualTo(Conflicts.Proceed);
    }
}
