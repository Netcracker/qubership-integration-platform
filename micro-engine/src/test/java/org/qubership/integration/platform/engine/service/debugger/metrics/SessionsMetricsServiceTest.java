package org.qubership.integration.platform.engine.service.debugger.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.Buckets;
import org.opensearch.client.opensearch._types.aggregations.ScriptedMetricAggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsAggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.aggregations.TopHitsAggregate;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.opensearch.client.transport.TransportOptions;
import org.qubership.integration.platform.engine.errorhandling.EngineRuntimeException;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.persistence.shared.entity.ChainDataAllocationSize;
import org.qubership.integration.platform.engine.persistence.shared.repository.CheckpointRepository;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SessionsMetricsServiceTest {

    private static final String INDEX_NAME = "qip-elements-local";
    private static final String NORMALIZED_SESSION_ELEMENTS_INDEX = "normalized-session-elements";
    private static final String CHAIN_1_NAME = "Test Chain One";
    private static final String CHAIN_2_NAME = "Test Chain Two";
    private static final String CHAIN_1_ID = "7243f0cf-d669-4dbf-88de-9cf6fa734bad";
    private static final String CHAIN_2_ID = "4c3745d6-952b-4379-b16c-d464915ceee3";

    private SessionsMetricsService sessionsMetricsService;

    @Mock
    MetricsStore metricsStore;

    @Mock
    OpenSearchClientSupplier openSearchClientSupplier;

    @Mock
    CheckpointRepository checkpointRepository;

    @Mock
    OpenSearchClient openSearchClient;

    @Mock
    OpenSearchClient openSearchClientWithOptions;

    @BeforeEach
    void setUp() {
        when(metricsStore.isMetricsEnabled()).thenReturn(true);
        sessionsMetricsService = new SessionsMetricsService(
                INDEX_NAME,
                metricsStore,
                openSearchClientSupplier,
                checkpointRepository
        );
    }

    @Test
    void shouldProcessSessionSizesFromOpenSearchBuckets() throws Exception {
        SearchResponse<SessionElementElastic> searchResponse = searchResponse(
                bucket(CHAIN_1_ID, CHAIN_1_NAME, 100L),
                bucket(CHAIN_2_ID, CHAIN_2_NAME, 250L)
        );
        when(openSearchClientSupplier.normalize(INDEX_NAME + "-session-elements"))
                .thenReturn(NORMALIZED_SESSION_ELEMENTS_INDEX);
        when(openSearchClientSupplier.getClient()).thenReturn(openSearchClient);
        when(openSearchClient.withTransportOptions(any(TransportOptions.class)))
                .thenReturn(openSearchClientWithOptions);
        when(openSearchClientWithOptions.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(searchResponse);

        sessionsMetricsService.processSessionsSizeMetrics();

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(openSearchClientWithOptions).search(requestCaptor.capture(), any(Class.class));
        SearchRequest request = requestCaptor.getValue();
        assertEquals(List.of(NORMALIZED_SESSION_ELEMENTS_INDEX), request.index());
        assertEquals(0, request.size());

        ArgumentCaptor<List<ChainDataAllocationSize>> sizesCaptor = ArgumentCaptor.forClass(List.class);
        verify(metricsStore).processChainSessionsSize(sizesCaptor.capture());
        List<ChainDataAllocationSize> sizes = sizesCaptor.getValue();
        assertEquals(2, sizes.size());
        assertChainDataAllocationSize(sizes.get(0), CHAIN_1_ID, CHAIN_1_NAME, 100L);
        assertChainDataAllocationSize(sizes.get(1), CHAIN_2_ID, CHAIN_2_NAME, 250L);
    }

    @Test
    void shouldUseNullChainNameWhenOpenSearchBucketHasNoTopHit() throws Exception {
        SearchResponse<SessionElementElastic> searchResponse = searchResponse(
                bucketWithoutChainName()
        );
        when(openSearchClientSupplier.normalize(INDEX_NAME + "-session-elements"))
                .thenReturn(NORMALIZED_SESSION_ELEMENTS_INDEX);
        when(openSearchClientSupplier.getClient()).thenReturn(openSearchClient);
        when(openSearchClient.withTransportOptions(any(TransportOptions.class)))
                .thenReturn(openSearchClientWithOptions);
        when(openSearchClientWithOptions.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(searchResponse);

        sessionsMetricsService.processSessionsSizeMetrics();

        ArgumentCaptor<List<ChainDataAllocationSize>> sizesCaptor = ArgumentCaptor.forClass(List.class);
        verify(metricsStore).processChainSessionsSize(sizesCaptor.capture());
        assertChainDataAllocationSize(sizesCaptor.getValue().getFirst(), CHAIN_1_ID, null, 100L);
    }

    @Test
    void shouldThrowEngineRuntimeExceptionWhenOpenSearchSearchFails() throws Exception {
        IOException cause = new IOException("search failed");
        when(openSearchClientSupplier.normalize(INDEX_NAME + "-session-elements"))
                .thenReturn(NORMALIZED_SESSION_ELEMENTS_INDEX);
        when(openSearchClientSupplier.getClient()).thenReturn(openSearchClient);
        when(openSearchClient.withTransportOptions(any(TransportOptions.class)))
                .thenReturn(openSearchClientWithOptions);
        when(openSearchClientWithOptions.search(any(SearchRequest.class), any(Class.class)))
                .thenThrow(cause);

        EngineRuntimeException result = assertThrows(
                EngineRuntimeException.class,
                () -> sessionsMetricsService.processSessionsSizeMetrics()
        );

        assertEquals("Unable to retrieve session metrics from opensearch", result.getMessage());
        assertSame(cause, result.getOriginalException());
        verify(metricsStore, never()).processChainSessionsSize(any());
    }

    @Test
    void shouldProcessCheckpointSizesFromRepository() {
        List<ChainDataAllocationSize> checkpointSizes = List.of(
                chainDataAllocationSize(CHAIN_1_ID, CHAIN_1_NAME, 64L),
                chainDataAllocationSize(CHAIN_2_ID, CHAIN_2_NAME, 128L)
        );
        when(checkpointRepository.findAllChainCheckpointSize()).thenReturn(checkpointSizes);

        sessionsMetricsService.processCheckpointSizeMetrics();

        verify(metricsStore).processChainCheckpointsSize(checkpointSizes);
    }

    @Test
    void shouldThrowEngineRuntimeExceptionWhenCheckpointRepositoryFails() {
        RuntimeException cause = new RuntimeException("repository failed");
        when(checkpointRepository.findAllChainCheckpointSize()).thenThrow(cause);

        EngineRuntimeException result = assertThrows(
                EngineRuntimeException.class,
                () -> sessionsMetricsService.processCheckpointSizeMetrics()
        );

        assertEquals("Unable to retrieve checkpoints metrics from postgres", result.getMessage());
        assertSame(cause, result.getOriginalException());
        verify(metricsStore, never()).processChainCheckpointsSize(any());
    }

    private SearchResponse<SessionElementElastic> searchResponse(StringTermsBucket... buckets) {
        return SearchResponse.searchResponseOf(builder -> builder
                .took(1)
                .timedOut(false)
                .shards(ShardStatistics.of(shards -> shards.total(1).successful(1).failed(0)))
                .hits(HitsMetadata.of(hits -> hits
                        .total(total -> total.value(0).relation(TotalHitsRelation.Eq))
                        .hits(List.of())))
                .aggregations("session_count", Aggregate.of(aggregate -> aggregate
                        .sterms(StringTermsAggregate.of(terms -> terms
                                .buckets(Buckets.of(currentBuckets -> currentBuckets.array(List.of(buckets))))
                                .sumOtherDocCount(0))))));
    }

    private StringTermsBucket bucket(String chainId, String chainName, long size) {
        return bucket(chainId, size, HitsMetadata.of(hits -> hits
                .total(total -> total.value(1).relation(TotalHitsRelation.Eq))
                .hits(List.of(Hit.of(hit -> hit.source(JsonData.of(Map.of("chainName", chainName))))))));
    }

    private StringTermsBucket bucket(String chainId, long size, HitsMetadata<JsonData> chainNameHits) {
        Aggregate chainNameAggregate = Aggregate.of(aggregate -> aggregate
                .topHits(TopHitsAggregate.of(topHits -> topHits.hits(chainNameHits))));
        Aggregate sizeAggregate = Aggregate.of(aggregate -> aggregate
                .scriptedMetric(ScriptedMetricAggregate.of(scriptedMetric -> scriptedMetric.value(JsonData.of(size)))));

        return StringTermsBucket.of(bucket -> bucket
                .key(chainId)
                .docCount(1)
                .aggregations("chain_name", chainNameAggregate)
                .aggregations("calculate_all_fields_size_bytes", sizeAggregate));
    }

    private StringTermsBucket bucketWithoutChainName() {
        return bucket(SessionsMetricsServiceTest.CHAIN_1_ID, 100L, HitsMetadata.of(hits -> hits
                .total(total -> total.value(0).relation(TotalHitsRelation.Eq))
                .hits(List.of())));
    }

    private ChainDataAllocationSize chainDataAllocationSize(String chainId, String chainName, long size) {
        return ChainDataAllocationSize.builder()
                .chainId(chainId)
                .chainName(chainName)
                .allocatedSize(size)
                .build();
    }

    private void assertChainDataAllocationSize(
            ChainDataAllocationSize actual,
            String chainId,
            String chainName,
            long size
    ) {
        assertEquals(chainId, actual.getChainId());
        assertEquals(chainName, actual.getChainName());
        assertEquals(size, actual.getAllocatedSize());
    }
}
