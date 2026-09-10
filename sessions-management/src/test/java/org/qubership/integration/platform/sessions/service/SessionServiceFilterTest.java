package org.qubership.integration.platform.sessions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.qubership.integration.platform.sessions.dto.filter.FilterRequestAndSearchDTO;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import org.qubership.integration.platform.sessions.exception.SearchException;
import org.qubership.integration.platform.sessions.mapper.SessionAggregateMapper;
import org.qubership.integration.platform.sessions.mapper.SessionElementMapper;
import org.qubership.integration.platform.sessions.properties.opensearch.ElementsIndexProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.IndexProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.sessions.properties.opensearch.SessionProperties;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter list arrives from a body the caller writes, so it is absent whenever the caller names
 * no filters. These read it the way the endpoint does, from JSON, rather than from a builder that
 * could not produce the absent case.
 */
class SessionServiceFilterTest {

    private static final String INDEX = "session-elements";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenSearchClient client;
    private SessionService sessionService;

    @BeforeEach
    void setUp() throws IOException {
        client = mock(OpenSearchClient.class);
        when(client.withTransportOptions(any())).thenReturn(client);
        // The production code reads a null response as no hits, which keeps the search itself out
        // of these tests: only the request it built is under examination.
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
    @DisplayName("A body that names no filters searches without them")
    void absentFilterListIsReadAsNoFilters() throws IOException {
        FilterRequestAndSearchDTO request = MAPPER.readValue("{}", FilterRequestAndSearchDTO.class);
        assertThat(request.getFilterRequestList()).isNull();

        assertThatCode(() -> sessionService.getSessions(null, 0, 20, "sessionStarted", request))
                .doesNotThrowAnyException();

        assertThat(mustClausesOf(capturedRequest())).isEmpty();
    }

    @Test
    @DisplayName("A body that names a filter still applies it")
    void namedFilterIsStillApplied() throws IOException {
        FilterRequestAndSearchDTO request = MAPPER.readValue(
                "{\"filterRequestList\":[{\"feature\":\"CHAIN_NAME\",\"condition\":\"CONTAINS\",\"value\":\"order-sync\"}]}",
                FilterRequestAndSearchDTO.class);

        sessionService.getSessions(null, 0, 20, "sessionStarted", request);

        assertThat(mustClausesOf(capturedRequest()))
                .singleElement()
                .satisfies(query -> {
                    assertThat(query.wildcard().field()).isEqualTo("chainName");
                    assertThat(query.wildcard().value()).contains("order-sync");
                });
    }

    @Test
    @DisplayName("A column the search cannot sort on is a search error, not a fault")
    void unsortableColumnIsASearchError() throws IOException {
        FilterRequestAndSearchDTO request = MAPPER.readValue("{\"filterRequestList\":[]}", FilterRequestAndSearchDTO.class);

        assertThatThrownBy(() -> sessionService.getSessions(null, 0, 20, "nonsense", request))
                .isInstanceOf(SearchException.class)
                .hasMessageContaining("Valid columns are");
    }

    private SearchRequest capturedRequest() throws IOException {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(SessionElementElastic.class));
        return captor.getValue();
    }

    private static List<Query> mustClausesOf(SearchRequest request) {
        return request.query().bool().must();
    }
}
