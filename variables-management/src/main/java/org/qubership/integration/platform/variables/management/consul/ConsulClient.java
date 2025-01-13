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

package org.qubership.integration.platform.variables.management.consul;


import org.qubership.integration.platform.variables.management.model.consul.txn.KVResponse;
import org.qubership.integration.platform.variables.management.model.consul.txn.KeyResponse;
import org.qubership.integration.platform.variables.management.model.consul.txn.request.TxnKVRequest;
import org.qubership.integration.platform.variables.management.model.consul.txn.request.TxnRequest;
import org.qubership.integration.platform.variables.management.model.consul.txn.response.TxnResponse;
import org.qubership.integration.platform.variables.management.model.consul.txn.request.TxnVerb;
import org.qubership.integration.platform.variables.management.model.consul.txn.response.TxnResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
public class ConsulClient {
    public static final int MAX_TXN_SIZE = 64;
    public static final String CONSUL_TOKEN_HEADER = "X-Consul-Token";
    public static final String CONSUL_INDEX_HEADER = "X-Consul-Index";
    public static final String CONSUL_KV_PATH = "/v1/kv";
    public static final String CONSUL_TXN_PATH = "/v1/txn";
    public static final String CONSUL_KV_QUERY_PARAMS = "?recurse={recurse}&index={index}&wait={wait}";
    public static final String CONSUL_KV_DELETE_PARAMS = "?recurse={recurse}";

    private final String consulUrl;

    @Value("${consul.token}")
    private String consulToken;

    private final RestTemplate restTemplate;

    @Autowired
    public ConsulClient(@Qualifier("restTemplateMS") RestTemplate restTemplate,
                        @Value("${consul.url}") String consulUrl) {
        this.restTemplate = restTemplate;
        this.consulUrl = StringUtils.strip(consulUrl, "/");
    }

    public List<KeyResponse> getKV(String key, boolean recurse) throws KVNotFoundException {
        return waitForKVChanges(key, recurse, 0, "0").getRight();
    }

    /**
     * Throws ConsulException if at least one key in a list is not present in consul
     * @param keys path without leading slash (e.g. 'config/test/key')
     */
    public List<KVResponse> getKVsInTransaction(List<String> keys) throws ConsulException {
        return doTxnBatchedRequest(
                keys.stream()
                        .map(key -> new TxnRequest(
                                TxnKVRequest.builder()
                                        .verb(TxnVerb.GET)
                                        .key(key)
                                        .build()))
                        .toList()
        ).getResults().stream().map(TxnResponseResult::getKv).toList();
    }

    public void createOrUpdateKV(String key, Object value) {
        HttpEntity<Object> entity = new HttpEntity<>(value, buildCommonHeaders());
        ResponseEntity<String> response = restTemplate.exchange(consulUrl + CONSUL_KV_PATH + key,
                HttpMethod.PUT, entity, String.class);

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Failed to create or update KV in consul, code: {}, body: {}",
                    response.getStatusCode(), response.getBody());
            throw new RuntimeException("Failed to create or update KV in consul, response with non 2xx code");
        }

        if (!"true".equalsIgnoreCase(response.getBody())) {
            throw new RuntimeException("Failed update/create KV in consul, response: " + response);
        }
    }

    /**
     * @param map key/value mapping, key - path without a leading slash (e.g. 'config/test/key')
     */
    public void createOrUpdateKVsInTransaction(Map<String, String> map) {
        doTxnBatchedRequest(
                map.entrySet().stream()
                        .map(entry -> new TxnRequest(
                                TxnKVRequest.builder()
                                        .verb(TxnVerb.SET)
                                        .key(entry.getKey())
                                        .value(entry.getValue())
                                        .build()))
                        .toList()
                );
    }

    public void deleteKV(String key, boolean recurse) {
        HttpEntity<Object> entity = new HttpEntity<>(buildCommonHeaders());
        ResponseEntity<String> response = restTemplate.exchange(consulUrl + CONSUL_KV_PATH + key + CONSUL_KV_DELETE_PARAMS,
                HttpMethod.DELETE, entity, String.class,
                Map.of("recurse", recurse));

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Failed to remove KEY in consul, code: {}, body: {}",
                    response.getStatusCode(), response.getBody());
            throw new RuntimeException("Failed to remove KEY in consul, response with non 2xx code");
        }

        if (!"true".equalsIgnoreCase(response.getBody())) {
            throw new RuntimeException("Failed remove KEY in consul, response: " + response);
        }
    }

    /**
     * @param keys path without leading slash (e.g. 'config/test/key')
     */
    public void deleteKVsInTransaction(List<String> keys) {
        doTxnBatchedRequest(
                keys.stream()
                        .map(key -> new TxnRequest(
                                TxnKVRequest.builder()
                                        .verb(TxnVerb.DELETE)
                                        .key(key)
                                        .build()))
                        .toList()
        );
    }

    private TxnResponse doTxnBatchedRequest(List<TxnRequest> request) throws ConsulException {
        if (request.size() <= MAX_TXN_SIZE) {
            return doTxnRequest(request);
        }

        TxnResponse result = new TxnResponse();
        for (List<TxnRequest> txnRequests : ListUtils.partition(request, MAX_TXN_SIZE)) {
            result.merge(doTxnRequest(txnRequests));
        }
        return result;
    }

    /**
     * Consul constraint - maximum {@link ConsulClient#MAX_TXN_SIZE} operations per txn request
     */
    private TxnResponse doTxnRequest(List<TxnRequest> request) throws ConsulException {
        ResponseEntity<TxnResponse> response = null;
        try {
            HttpEntity<List<TxnRequest>> entity = new HttpEntity<>(request, buildCommonHeaders());
            response = restTemplate.exchange(
                    consulUrl + CONSUL_TXN_PATH, HttpMethod.PUT, entity, new ParameterizedTypeReference<>() {});
            if (response.getStatusCode() != HttpStatus.OK) {
                log.error("Failed to complete txn consul request, code: {}, body: {}",
                        response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to complete txn consul request, response with non 200 code");
            }
            if (response.getBody().getErrors() != null) {
                throw new RuntimeException("Failed to complete txn consul request, response have errors");
            }
            return response.getBody();
        } catch (HttpClientErrorException hcee) {
            if (hcee.getStatusCode() == HttpStatus.CONFLICT) {
                log.error("Consul txn request failed with code 409 conflict: {}", hcee.getMessage());
                throw new TxnConflictException("Consul txn request failed with code 409 conflict", hcee);
            } else {
                log.error("Consul txn request failed with http error", hcee);
                throw new ConsulException("Consul txn request failed with http error", hcee);
            }
        } catch (Exception e) {
            String errorMessage = "Consul txn request failed";
            if (response != null && response.getBody() != null && response.getBody().getErrors() != null) {
                errorMessage = errorMessage + " , response errors: " + response.getBody().getErrors();
            }
            log.error(errorMessage, e);
            throw new ConsulException("Consul txn request failed", e);
        }
    }

    public Pair<Long, List<KeyResponse>> waitForKVChanges(String key, boolean recurse, long index, String waitTimeout) throws KVNotFoundException {
        HttpEntity<Object> entity = new HttpEntity<>(buildCommonHeaders());
        try {
            ResponseEntity<List<KeyResponse>> response = restTemplate.exchange(consulUrl + CONSUL_KV_PATH + key + CONSUL_KV_QUERY_PARAMS,
                    HttpMethod.GET, entity, new ParameterizedTypeReference<>() {
                    },
                    Map.of("recurse", recurse,
                            "index", index,
                            "wait", waitTimeout));

            if (response.getStatusCode() != HttpStatus.OK) {
                log.error("Failed to get KV from consul, code: {}, body: {}",
                        response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to get KV from consul, response with non 200 code");
            }

            return Pair.of(
                    Long.parseLong(response.getHeaders().get(CONSUL_INDEX_HEADER).get(0)),
                    response.getBody() == null ? Collections.emptyList() : response.getBody());
        } catch (HttpClientErrorException hcee) {
            if (hcee.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new KVNotFoundException("KV not present in consul");
            }
            throw hcee;
        }
    }

    private HttpHeaders buildCommonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CONSUL_TOKEN_HEADER, consulToken);
        return headers;
    }
}
