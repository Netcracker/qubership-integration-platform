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

package org.qubership.integration.platform.engine.consul;

import org.qubership.integration.platform.engine.model.consul.CreateSessionResponse;
import org.qubership.integration.platform.engine.model.consul.KeyResponse;
import org.qubership.integration.platform.engine.model.consul.CreateSessionRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class ConsulClient {
    public static final String CONSUL_TOKEN_HEADER = "X-Consul-Token";
    public static final String CONSUL_INDEX_HEADER = "X-Consul-Index";
    public static final String CONSUL_KV_PATH = "/v1/kv";
    public static final String CREATE_SESSION_PATH = "/v1/session/create";
    public static final String DELETE_SESSION_PATH = "/v1/session/destroy/{sessionId}";
    public static final String RENEW_SESSION_PATH = "/v1/session/renew";
    public static final String CONSUL_KV_QUERY_PARAMS = "?recurse={recurse}&index={index}&wait={wait}";


    private final String consulUrl;

    @Value("${consul.token}")
    private String consulToken;

    private final RestTemplate restTemplate;

    @Autowired
    public ConsulClient(@Qualifier("consulRestTemplateMS") RestTemplate restTemplate,
                        @Value("${consul.url}") String consulUrl) {
        this.restTemplate = restTemplate;
        this.consulUrl = StringUtils.strip(consulUrl, "/");
    }

    public void renewSession(String activeSessionId) {
        HttpEntity<Object> entity = new HttpEntity<>(buildCommonHeaders());
        ResponseEntity<String> response = restTemplate.exchange(consulUrl + RENEW_SESSION_PATH + "/" + activeSessionId,
            HttpMethod.PUT, entity, String.class);

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Failed to renew session in consul, code: {}, body: {}",
                response.getStatusCode(), response.getBody());
            throw new RuntimeException("Failed to renew session in consul, response with non 2xx code");
        }
    }

    public String createSession(String name, String behavior, String ttl) {
        HttpEntity<CreateSessionRequest> entity = new HttpEntity<>(
            CreateSessionRequest.builder().name(name).behavior(behavior).ttl(ttl).build(),
            buildCommonHeaders());
        ResponseEntity<CreateSessionResponse> response = restTemplate.exchange(consulUrl + CREATE_SESSION_PATH,
            HttpMethod.PUT, entity, CreateSessionResponse.class);

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Failed to create session in consul, code: {}, body: {}",
                response.getStatusCode(), response.getBody());
            throw new RuntimeException("Failed to create session in consul, response with non 2xx code");
        }

        return response.getBody().getId();
    }

    public void deleteSession(String previousSessionId) {
        log.info("Delete old consul session: {}", previousSessionId);
        ResponseEntity<String> response = restTemplate.exchange(consulUrl + DELETE_SESSION_PATH,
            HttpMethod.PUT, new HttpEntity<>(buildCommonHeaders()),
            String.class, Map.of("sessionId", previousSessionId));

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Failed to delete session from consul, code: {}, body: {}",
                response.getStatusCode(), response.getBody());
            throw new RuntimeException("Failed to delete session from consul, response with non 2xx code");
        }

        if (!"true".equalsIgnoreCase(response.getBody())) {
            throw new RuntimeException("Failed delete session from consul, response: " + response);
        }
    }

    public void createOrUpdateKVWithSession(String key, Object value, String sessionId) {
        createOrUpdateKV(key + "?acquire=" + sessionId, value);
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
            throw new RuntimeException("Failed update/create KV in consul, response: " + response.getBody());
        }
    }

    public Pair<Long, List<KeyResponse>> waitForKVChanges(String key, boolean recurse, long index, String waitTimeout) throws KVNotFoundException {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(buildCommonHeaders());
            ResponseEntity<List<KeyResponse>> response = restTemplate.exchange(
                consulUrl + CONSUL_KV_PATH + key + CONSUL_KV_QUERY_PARAMS,
                HttpMethod.GET, entity, new ParameterizedTypeReference<>() {
                },
                Map.of("recurse", recurse,
                    "index", index,
                    "wait", waitTimeout));

            if (response.getStatusCode() != HttpStatus.OK) {
                log.error("Failed to get KV from consul, code: {}, body: {}",
                    response.getStatusCode(), response.getBody());
                throw new RuntimeException(
                    "Failed to get KV from consul, response with non 200 code");
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
