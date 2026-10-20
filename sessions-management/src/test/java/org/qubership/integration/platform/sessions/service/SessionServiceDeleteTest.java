package org.qubership.integration.platform.sessions.service;

import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceDeleteTest {

    private static final String INDEX = "session-elements";

    private OpenSearchClient client;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        client = mock(OpenSearchClient.class);
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

    @ParameterizedTest(name = "delete {0} proceeds past version conflicts")
    @MethodSource("deletes")
    void deleteProceedsPastVersionConflicts(String name, Consumer<SessionService> delete) throws IOException {
        delete.accept(sessionService);

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(client).deleteByQuery(captor.capture());
        assertThat(captor.getValue().conflicts()).isEqualTo(Conflicts.Proceed);
    }
}
