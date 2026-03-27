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

package org.qubership.integration.platform.sessions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldSort;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.*;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.InnerHits;
import org.opensearch.client.opensearch.core.search.InnerHitsResult;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Options;
import org.opensearch.client.transport.httpclient5.HttpAsyncResponseConsumerFactory;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.dto.SessionElement;
import org.qubership.integration.platform.sessions.dto.SessionSearchResponse;
import org.qubership.integration.platform.sessions.dto.filter.FilterCondition;
import org.qubership.integration.platform.sessions.dto.filter.FilterRequest;
import org.qubership.integration.platform.sessions.dto.filter.FilterRequestAndSearchDTO;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import org.qubership.integration.platform.sessions.exception.SearchException;
import org.qubership.integration.platform.sessions.mapper.SessionAggregateMapper;
import org.qubership.integration.platform.sessions.mapper.SessionElementMapper;
import org.qubership.integration.platform.sessions.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

import static java.util.Objects.isNull;

@Service
@Slf4j
public class SessionService {
    private static final String[] EXCLUDE_FIELD_IN_SESSIONS = {
            "bodyBefore", "bodyAfter",
            "headersBefore", "headersAfter",
            "exchangePropertiesBefore", "exchangePropertiesAfter",
            "propertiesAfter", "propertiesBefore",
            "contextBefore", "contextAfter",
    };
    private static final String AGGREGATION_COLUMN = "sessionId";
    private static final List<String> SESSION_OPENSEARCH_FIELDS = Arrays.asList("sessionId", "sessionStarted", "sessionFinished",
            "sessionDuration", "sessionExecutionStatus", "chainId", "chainName", "engineAddress", "loggingLevel", "sessionSyncDuration");
    private static final int SCROLL_WINDOW = 300;
    private static final String ELEMENT_EXECUTION_ERROR_MESSAGE = "Error during element execution";
    public static final String SESSION_ID_KEY = "sessionId";
    public static final String CHAIN_ID_KEY = "chainId";
    public static final String EXTERNAL_SESSION_ID_KEY = "externalSessionId";
    public static final String STARTED_KEY = "started";

    private static final String ID_KEY = "id";
    private static final String SESSION_DURATION_KEY = "sessionDuration";
    private static final String INNER_HIT_NAME = "most_recent";

    private final String indexName;

    private final SessionAggregateMapper sessionMapper;
    private final SessionElementMapper sessionElementMapper;
    private final OpenSearchClientSupplier openSearchClientSupplier;

    private final HttpAsyncResponseConsumerFactory consumerFactory;

    @Autowired
    public SessionService(SessionAggregateMapper sessionMapper,
                          OpenSearchClientSupplier openSearchClientSupplier,
                          ObjectMapper mapper,
                          SessionElementMapper sessionElementMapper,
                          OpenSearchProperties openSearchProperties) {
        this.indexName = openSearchProperties.index().elements().name();
        this.sessionMapper = sessionMapper;
        this.openSearchClientSupplier = openSearchClientSupplier;
        this.sessionElementMapper = sessionElementMapper;

        this.consumerFactory = new HttpAsyncResponseConsumerFactory.HeapBufferedResponseConsumerFactory(
            openSearchProperties.session().defaultBufferLimit());
    }

    public Session findByExternalSessionId(String externalSessionId, boolean includeElements) {
        return findById(externalSessionId, EXTERNAL_SESSION_ID_KEY, !includeElements, includeElements);
    }

    public Session findById(String id, String idKey, boolean light, boolean includeElements) {
        List<SessionElementElastic> elements = new ArrayList<>();

        List<SessionElementElastic> singleResponse;
        int i = 0;
        do {
            singleResponse = getSearchResponse(getScrollSearchRequest(id, idKey, light, i * SCROLL_WINDOW));
            elements.addAll(singleResponse);
            i++;
        } while (!singleResponse.isEmpty());

        return sessionMapper.toSession(elements, includeElements);
    }

    private SearchRequest getScrollSearchRequest(String id, String idKey, boolean light, int scrollWindow) {
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .size(SCROLL_WINDOW)
                .query(new TermQuery.Builder().field(idKey).value(FieldValue.of(id)).build().toQuery())
                .sort(new SortOptions.Builder().field(new FieldSort.Builder().field(STARTED_KEY).order(SortOrder.Asc).build()).build());
        configureSessionElementsCollapseBy(requestBuilder, ID_KEY);

        if (light) {
            requestBuilder.source(builder -> builder.filter(builder1 -> builder1.excludes(Arrays.asList(EXCLUDE_FIELD_IN_SESSIONS))));
        }
        requestBuilder.from(scrollWindow);
        return requestBuilder.build();
    }

    public SessionElement getElementById(String elementId) {
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .size(SCROLL_WINDOW)
                .query(new TermQuery.Builder().field(ID_KEY).value(FieldValue.of(elementId)).build().toQuery())
                .sort(new SortOptions.Builder().field(new FieldSort.Builder().field(STARTED_KEY).order(SortOrder.Asc).build()).build())
                ;
        configureSessionElementsCollapseBy(requestBuilder, ID_KEY);

        List<SessionElementElastic> response = getSearchResponse(requestBuilder.build());
        return response.stream().findFirst().map(sessionElementMapper::toSessionElement).orElse(null);
    }

    public void deleteBySessionId(String sessionId) {
        deleteByField(SESSION_ID_KEY, sessionId, false);
    }

    public void deleteByChainId(String chainId) {
        deleteByField(CHAIN_ID_KEY, chainId, true);
    }

    public void deleteAllSessions() {
        DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .query(new MatchAllQuery.Builder().build().toQuery())
                .refresh(true)
                .build();
        delete(request);
    }

    public void deleteByField(String fieldName, String value, boolean refresh) {
        DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .query(new TermQuery.Builder().field(fieldName).value(FieldValue.of(value)).build().toQuery())
                .refresh(refresh)
                .build();
        delete(request);
    }

    public void deleteByFieldValues(String fieldName, List<String> values, boolean refresh) {
        Query query = new TermsQuery.Builder()
                .field(fieldName)
                .terms(new TermsQueryField.Builder()
                        .value(values.stream().map(FieldValue::of).toList())
                        .build())
                .build()
                .toQuery();
        DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .query(query)
                .refresh(true)
                .build();
        delete(request);
    }

    public void deleteAllByChainIds(List<String> chainIds) {
        deleteByFieldValues(CHAIN_ID_KEY, chainIds, true);
    }

    public void deleteBySessionIds(List<String> sessionIds) {
        deleteByFieldValues(SESSION_ID_KEY, sessionIds, true);
    }

    public SessionSearchResponse getSessions(String chainId,
                                             int offset,
                                             int limit,
                                             String sortColumn,
                                             FilterRequestAndSearchDTO filterRequest) {
        if (offset < 0 || limit < 1) {
            return new SessionSearchResponse(0, Collections.emptyList());
        }

        if (!SESSION_OPENSEARCH_FIELDS.contains(sortColumn)) {
            throw new IllegalArgumentException("Can't sort results on this column. Valid columns are: "
                    + StringUtils.join(SESSION_OPENSEARCH_FIELDS, ", "));
        }

        Map<String, SessionElementElastic> resultSessions = new LinkedHashMap<>();

        List<SessionElementElastic> lightSessionElements = executeLightSessionElementsQuery(chainId,
                offset, limit, sortColumn, filterRequest);
        lightSessionElements.forEach(element -> resultSessions.put(element.getSessionId(), element));

        return new SessionSearchResponse(offset + resultSessions.size(), sessionMapper.toPreview(new ArrayList<>(resultSessions.values())));
    }

    private void configureSessionElementsCollapseBy(SearchRequest.Builder builder, String field) {
        FieldCollapse collapse = new FieldCollapse.Builder()
                .field(field)
                .innerHits(new InnerHits.Builder()
                        .name(INNER_HIT_NAME)
                        .sort(new SortOptions.Builder().field(new FieldSort.Builder().field(SESSION_DURATION_KEY).order(SortOrder.Desc).build()).build())
                        .size(1)
                        .build())
                .build();
        builder.collapse(collapse);
    }

    /**
     * Execute query for sessions element. Excludes {@link #EXCLUDE_FIELD_IN_SESSIONS} fields from resulting query
     */
    private List<SessionElementElastic> executeLightSessionElementsQuery(String chainId,
                                                                         int offset, int count,
                                                                         String sortColumn,
                                                                         FilterRequestAndSearchDTO filterAndSearch) {
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName));
        BoolQuery.Builder queryBuilder = new BoolQuery.Builder();

        if (StringUtils.isNotEmpty(chainId)) {
            queryBuilder.must(new TermQuery.Builder().field(CHAIN_ID_KEY).value(FieldValue.of(chainId)).build().toQuery());
        }

        String searchString = filterAndSearch.getSearchString();
        if (StringUtils.isNotEmpty(searchString)) {
            queryBuilder.must(
                    List.of(new BoolQuery.Builder()
                            .should(
                                    new TermQuery.Builder().field("sessionId").value(FieldValue.of(searchString)).build().toQuery(),
                                    new MultiMatchQuery.Builder()
                                            .query(searchString)
                                            .type(TextQueryType.PhrasePrefix)
                                            .fields("bodyAfter",
                                                    "bodyBefore",
                                                    "headersAfter",
                                                    "headersBefore",
                                                    "exchangePropertiesAfter",
                                                    "exchangePropertiesBefore",
                                                    "contextAfter",
                                                    "contextBefore")
                                    .build().toQuery())
                    .build().toQuery())
            );
        }

        for (FilterRequest filterRequest : filterAndSearch.getFilterRequestList()) {
            switch (filterRequest.getFeature()) {
                case ENGINE ->
                        getPredicate(filterRequest.getCondition(), queryBuilder, "engineAddress", filterRequest.getValue());
                case STATUS ->
                        getPredicate(filterRequest.getCondition(), queryBuilder, "sessionExecutionStatus", filterRequest.getValue());
                case CHAIN_NAME ->
                        getPredicate(filterRequest.getCondition(), queryBuilder, "chainName", filterRequest.getValue());
                case START_TIME ->
                        getPredicate(filterRequest.getCondition(), queryBuilder, "sessionStarted", filterRequest.getValue());
                case FINISH_TIME ->
                        getPredicate(filterRequest.getCondition(), queryBuilder, "sessionFinished", filterRequest.getValue());
            }
        }

        requestBuilder
                .query(queryBuilder.build().toQuery())
                .sort(new SortOptions.Builder().field(new FieldSort.Builder().field(sortColumn).order(SortOrder.Desc).build()).build())
                .sort(new SortOptions.Builder().field(new FieldSort.Builder().field(AGGREGATION_COLUMN).build()).build())
                .from(offset)
                .size(count)
                .sort(new SortOptions.Builder().field(new FieldSort.Builder().field(STARTED_KEY).order(SortOrder.Asc).build()).build())
                .source(builder -> builder.filter(builder1 -> builder1.excludes(Arrays.asList(EXCLUDE_FIELD_IN_SESSIONS))));
        configureSessionElementsCollapseBy(requestBuilder, SESSION_ID_KEY);

        return getSearchResponse(requestBuilder.build());
    }

    private void getPredicate(FilterCondition condition, BoolQuery.Builder queryBuilder, String fieldName, String value) {
        switch (condition) {
            case IN -> queryBuilder.must(new TermsQuery.Builder().field(fieldName).terms(new TermsQueryField.Builder().value(Arrays.stream(value.split(",")).map(FieldValue::of).toList()).build()).build().toQuery());
            case NOT_IN -> queryBuilder.mustNot(new TermsQuery.Builder().field(fieldName).terms(new TermsQueryField.Builder().value(Arrays.stream(value.split(",")).map(FieldValue::of).toList()).build()).build().toQuery());
            case CONTAINS -> queryBuilder.must(new WildcardQuery.Builder().field(fieldName).value("*" + value + "*").build().toQuery());
            case DOES_NOT_CONTAIN -> queryBuilder.mustNot(new WildcardQuery.Builder().field(fieldName).value("*" + value + "*").build().toQuery());
            case STARTS_WITH -> queryBuilder.must(new MatchPhrasePrefixQuery.Builder().field(fieldName).query(value).build().toQuery());
            case ENDS_WITH -> queryBuilder.must(new WildcardQuery.Builder().field(fieldName).value("*" + value).build().toQuery());
            case IS_AFTER -> {
                Date date = new Date(Long.parseLong(value));
                queryBuilder.must(new RangeQuery.Builder().field(fieldName).gte(JsonData.of(date)).build().toQuery());
            }
            case IS_BEFORE -> {
                Date date = new Date(Long.parseLong(value));
                queryBuilder.must(new RangeQuery.Builder().field(fieldName).lte(JsonData.of(date)).build().toQuery());
            }
            case IS_WITHIN -> {
                String[] values = value.split(",");
                queryBuilder.must(new RangeQuery.Builder().field("sessionFinished").gte(JsonData.of(new Date(Long.parseLong(values[0])))).lte(JsonData.of(new Date(Long.parseLong(values[1])))).build().toQuery());
            }
        }
    }

    private List<SessionElementElastic> getSearchResponse(SearchRequest request) {
        SearchResponse response;
        try {
            ApacheHttpClient5Options.Builder optionsBuilder = ApacheHttpClient5Options.DEFAULT.toBuilder();
            optionsBuilder.setHttpAsyncResponseConsumerFactory(consumerFactory);
            response = openSearchClientSupplier.getClient().withTransportOptions(optionsBuilder.build()).search(request, SessionElementElastic.class);
        } catch (IOException e) {
            throw new SearchException(ELEMENT_EXECUTION_ERROR_MESSAGE, e);
        }
        return isNull(response)
                ? Collections.emptyList()
                : Arrays.stream(response.hits().hits().toArray())
                .map(hit -> ((InnerHitsResult) ((Hit) hit).innerHits().get(INNER_HIT_NAME)).hits().hits())
                    .flatMap(Collection::stream)
                    .map(hit -> hit.source().to(SessionElementElastic.class))
                    .toList();
    }

    private void delete(DeleteByQueryRequest request) {
        try {
            openSearchClientSupplier.getClient().deleteByQuery(request);
        } catch (IOException e) {
            throw new SearchException("Unable to perform delete from OpenSearch", e);
        }
    }
}
