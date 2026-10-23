package org.qubership.integration.platform.sessions.service;

import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.search.SourceConfig;
import org.qubership.integration.platform.sessions.dto.filter.FilterRequestAndSearchDTO;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import org.qubership.integration.platform.sessions.mapper.SessionAggregateMapper;
import org.qubership.integration.platform.sessions.mapper.SessionElementMapper;
import org.qubership.integration.platform.sessions.properties.opensearch.ElementsIndexProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.IndexProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.SessionProperties;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service reads the collapse inner hits, so only a {@code _source} filter on them changes what it
 * returns. These check which requests carry one.
 */
class SessionServiceSourceFilterTest {
    private static final String INDEX = "session-elements";

    private OpenSearchClient client;
    private SessionService sessionService;

    @BeforeEach
    void setUp() throws IOException {
        client = mock(OpenSearchClient.class);
        when(client.withTransportOptions(any())).thenReturn(client);
        // getSearchResponse reads a null response as no hits, which keeps the search itself out of these tests.
        when(client.search(any(SearchRequest.class), eq(SessionElementElastic.class))).thenReturn(null);

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

    @Test
    @DisplayName("The list excludes the body, header, property, and context fields from the inner hits it reads")
    void listAppliesSourceExcludesToInnerHits() throws IOException {
        sessionService.getSessions(null, 0, 20, "sessionStarted", new FilterRequestAndSearchDTO());

        SearchRequest built = capturedRequest();
        SourceConfig innerSource = built.collapse().innerHits().get(0).source();
        assertThat(innerSource).as("the collapse inner hits carry a _source filter").isNotNull();
        assertThat(innerSource.filter().excludes())
                .containsExactlyInAnyOrderElementsOf(built.source().filter().excludes())
                .contains("bodyBefore", "bodyAfter");
    }

    @Test
    @DisplayName("A light session read keeps the body, header, property, and context fields in its inner hits")
    void lightSessionReadKeepsInnerHitsUnfiltered() throws IOException {
        // GET /v1/sessions/{sessionId} passes light=true and still returns the bodies of every step.
        sessionService.findById("session-1", SessionService.SESSION_ID_KEY, true, true);

        assertThat(capturedRequest().collapse().innerHits().get(0).source()).isNull();
    }

    private SearchRequest capturedRequest() throws IOException {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(SessionElementElastic.class));
        return captor.getValue();
    }
}
