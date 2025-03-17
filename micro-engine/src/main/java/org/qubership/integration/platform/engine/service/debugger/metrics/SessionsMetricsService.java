/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.debugger.metrics;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.InlineScript;
import org.opensearch.client.opensearch._types.Script;
import org.opensearch.client.opensearch._types.aggregations.*;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Options;
import org.opensearch.client.transport.httpclient5.HttpAsyncResponseConsumerFactory;
import org.qubership.integration.platform.engine.errorhandling.EngineRuntimeException;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.persistence.shared.entity.ChainDataAllocationSize;
import org.qubership.integration.platform.engine.persistence.shared.repository.CheckpointRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;


@Slf4j
public class SessionsMetricsService {

    private static final long SCHEDULER_INTERVAL = 60000;
    private static final String UNABLE_TO_RETRIEVE_SESSION_METRICS_ERROR_MESSAGE = "Unable to retrieve session metrics from opensearch";
    private static final String UNABLE_TO_RETRIEVE_CHECKPOINTS_METRICS_ERROR_MESSAGE = "Unable to retrieve checkpoints metrics from postgres";

    @Value("${qip.opensearch.index.elements.name}")
    private String indexName;

    private final MetricsStore metricsStore;
    private final OpenSearchClientSupplier openSearchClientSupplier;
    private final HttpAsyncResponseConsumerFactory consumerFactory;
    private final CheckpointRepository  checkpointRepository;

    public SessionsMetricsService(MetricsStore metricsStore,
                                  OpenSearchClientSupplier openSearchClientSupplier,
                                  CheckpointRepository checkpointRepository
    ) {
        this.metricsStore = metricsStore;
        this.openSearchClientSupplier = openSearchClientSupplier;
        this.checkpointRepository = checkpointRepository;
        this.consumerFactory = HttpAsyncResponseConsumerFactory.DEFAULT;
    }


    @Scheduled(fixedDelay = SCHEDULER_INTERVAL)
    public void processSessionsSizeMetrics() {

        ScriptedMetricAggregation sizeMetricAgg = AggregationBuilders.scriptedMetric()
                .initScript(new Script.Builder().inline(new InlineScript.Builder().lang("painless")
                        .source( "state.docSizes = []").build()).build())
                .mapScript(new Script.Builder().inline(new InlineScript.Builder().lang("painless")
                        .source( "state.docSizes.add(doc.toString().length())").build()).build())
                .combineScript(new Script.Builder().inline(new InlineScript.Builder().lang("painless")
                        .source( "return state.docSizes").build()).build())
                .reduceScript(new Script.Builder().inline(new InlineScript.Builder().lang("painless")
                        .source( "def totalSize = 0; for (state in states) { for (size in state) { totalSize += size } } return totalSize")
                        .build()).build()).build();

        TopHitsAggregation chainNameAgg = AggregationBuilders.topHits()
                .size(1)
                .source(s -> s.filter(f -> f.includes("chainName")))
                .build();

        TermsAggregation sessionCountAgg = AggregationBuilders.terms()
                .field("chainId")
                .size(1000)
                .build();

        Aggregation aggregation = new Aggregation.Builder()
                .terms(sessionCountAgg)
                .aggregations(Map.of("calculate_all_fields_size_bytes", sizeMetricAgg._toAggregation(),
                        "chain_name", chainNameAgg._toAggregation()))
                .build();

        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName.concat("-session-elements")))
                .aggregations(Map.of("session_count", aggregation))
                .size(0)
                .build();

        SearchResponse response;
        try {
            ApacheHttpClient5Options.Builder optionsBuilder = ApacheHttpClient5Options.DEFAULT.toBuilder();
            optionsBuilder.setHttpAsyncResponseConsumerFactory(consumerFactory);
            response = openSearchClientSupplier.getClient().withTransportOptions(optionsBuilder.build()).search(searchRequest, SessionElementElastic.class);

            StringTermsAggregate responseSessionCountAgg = ((Aggregate) response.aggregations().get("session_count")).sterms();
            Buckets<StringTermsBucket> buckets = responseSessionCountAgg.buckets();
            Collection<StringTermsBucket> bucketsList = buckets.isArray() ? buckets.array() : buckets.keyed().values();
            List<ChainDataAllocationSize> chainSessionsSizes = new ArrayList<>();
            for (StringTermsBucket bucket : bucketsList) {
                  String chainId = bucket.key();

                  TopHitsAggregate topHitsAgg = bucket.aggregations().get("chain_name").topHits();
                  List<Hit<JsonData>> hits = topHitsAgg.hits().hits();
                  String chainName = !hits.isEmpty() ? hits.getFirst().source().to(Map.class).get("chainName").toString() : null;

                  ScriptedMetricAggregate sizeMetric = bucket.aggregations().get("calculate_all_fields_size_bytes").scriptedMetric();
                  Long sessionsSize = sizeMetric.value().to(Long.class);

                  ChainDataAllocationSize chainSessionsSize = ChainDataAllocationSize.builder()
                          .chainId(chainId)
                          .chainName(chainName)
                          .allocatedSize(sessionsSize)
                          .build();

                  chainSessionsSizes.add(chainSessionsSize);
            }

            metricsStore.processChainSessionsSize(chainSessionsSizes);
        } catch (IOException e) {
            throw new EngineRuntimeException(UNABLE_TO_RETRIEVE_SESSION_METRICS_ERROR_MESSAGE, e);
        }
    }

    @Scheduled(fixedDelay = SCHEDULER_INTERVAL)
    public void processCheckpointSizeMetrics() {
        try {
            List<ChainDataAllocationSize> chainCheckpointSizes = new ArrayList<>();
            List<Object[]> checkpointSizeResult = checkpointRepository.findAllChainCheckpointSize();
            checkpointSizeResult.forEach(row -> {
                        ChainDataAllocationSize chainCheckpointSize = ChainDataAllocationSize.builder()
                                .chainId((String) row[0])
                                .chainName((String) row[1])
                                .allocatedSize(Long.parseLong(row[2].toString()))
                                .build();

                        chainCheckpointSizes.add(chainCheckpointSize);
                    }
            );
            metricsStore.processChainCheckpointsSize(chainCheckpointSizes);
        } catch (Exception e) {
            throw new EngineRuntimeException(UNABLE_TO_RETRIEVE_CHECKPOINTS_METRICS_ERROR_MESSAGE, e);
        }
    }
}
