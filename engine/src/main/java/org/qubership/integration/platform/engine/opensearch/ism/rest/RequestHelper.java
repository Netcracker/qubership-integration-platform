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

package org.qubership.integration.platform.engine.opensearch.ism.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.ContentType;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.model.rest.GenericRequest;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public class RequestHelper {
    private static final String ISM_ENDPOINT_BASE = "_plugins/_ism";

    public static Request buildGetPolicyRequest(String policyId) {
        String endpoint = buildPolicyEndpoint(policyId);
        return new GenericRequest(HttpGet.METHOD_NAME, endpoint, Collections.emptyList());
    }

    public static Request buildCreatePolicyRequest(ObjectMapper objectMapper, Policy policy) throws JsonProcessingException {
        String endpoint = buildPolicyEndpoint(policy.getPolicyId());

        PolicyRequest policyRequest = PolicyRequest.builder().policy(policy).build();
        String requestText = objectMapper.writeValueAsString(policyRequest);
        return new GenericRequest(HttpPut.METHOD_NAME, endpoint, Collections.emptyList(), Collections.emptyMap(), Body.from(requestText.getBytes(), String.valueOf(ContentType.APPLICATION_JSON)));
    }

    public static Request buildUpdatePolicyRequest(ObjectMapper objectMapper, Policy policy, long seqNo, long primaryTerm) throws JsonProcessingException {
        String endpoint = buildPolicyEndpoint(policy.getPolicyId());

        PolicyRequest policyRequest = PolicyRequest.builder().policy(policy).build();
        String requestText = objectMapper.writeValueAsString(policyRequest);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("if_seq_no", Long.toString(seqNo));
        parameters.put("if_primary_term", Long.toString(primaryTerm));
        return new GenericRequest(HttpPut.METHOD_NAME, endpoint, Collections.emptyList(), parameters, Body.from(requestText.getBytes(), String.valueOf(ContentType.APPLICATION_JSON)));
    }

    public static Request buildAddPolicyRequest(ObjectMapper objectMapper, String indexName, String policyId) throws JsonProcessingException {
        String endpoint = new EndpointBuilder()
                .addPathPartAsIs(ISM_ENDPOINT_BASE)
                .addPathPartAsIs("add")
                .addPathPart(indexName)
                .build();
        String requestText = objectMapper.writeValueAsString(AddPolicyRequest.builder().policyId(policyId).build());
        return new GenericRequest(HttpPost.METHOD_NAME, endpoint, Collections.emptyList(), Collections.emptyMap(), Body.from(requestText.getBytes(), String.valueOf(ContentType.APPLICATION_JSON)));
    }

    public static Request buildRemovePolicyFromIndexRequest(String indexName) {
        String endpoint = new EndpointBuilder()
                .addPathPartAsIs(ISM_ENDPOINT_BASE)
                .addPathPartAsIs("remove")
                .addPathPart(indexName)
                .build();
        return new GenericRequest(HttpPost.METHOD_NAME, endpoint, Collections.emptyList());
    }

    private static String buildPolicyEndpoint(String policyId) {
        return new EndpointBuilder()
                .addPathPartAsIs(ISM_ENDPOINT_BASE)
                .addPathPartAsIs("policies")
                .addPathPart(policyId)
                .build();
    }

    public static Request buildPutIndexTemplateRequest(ObjectMapper objectMapper, String templateName, Map<String, Object> request) throws JsonProcessingException {
        String endpoint = new EndpointBuilder()
                .addPathPartAsIs("_index_template")
                .addPathPart(templateName)
                .build();
        String requestText = objectMapper.writeValueAsString(request);
        return new GenericRequest(HttpPut.METHOD_NAME, endpoint, Collections.emptyList(), Collections.emptyMap(), Body.from(requestText.getBytes(), String.valueOf(ContentType.APPLICATION_JSON)));
    }

    public static Request buildCreateIndexRequest(ObjectMapper objectMapper, String indexName, Map<String, Object> request) throws JsonProcessingException {
        String endpoint = new EndpointBuilder()
                .addPathPart(indexName)
                .build();
        String requestText = objectMapper.writeValueAsString(request);
        return new GenericRequest(HttpPut.METHOD_NAME, endpoint, Collections.emptyList(), Collections.emptyMap(), Body.from(requestText.getBytes(), String.valueOf(ContentType.APPLICATION_JSON)));
    }

    public static Request buildPutIndexMapping(ObjectMapper objectMapper, String indexName, Map<String, Object> request) throws JsonProcessingException {
        String endpoint = new EndpointBuilder()
                .addPathPart(indexName)
                .addPathPartAsIs("_mapping")
                .build();
        String requestText = objectMapper.writeValueAsString(request);
        return new GenericRequest(HttpPut.METHOD_NAME, endpoint, Collections.emptyList(), Collections.emptyMap(), Body.from(requestText.getBytes(), String.valueOf(ContentType.APPLICATION_JSON)));
    }

    static class EndpointBuilder {
        private final StringJoiner joiner = new StringJoiner("/", "/", "");

        EndpointBuilder addPathPart(String... parts) {
            for (String part : parts) {
                if (StringUtils.hasLength(part)) {
                    joiner.add(encodePart(part));
                }
            }
            return this;
        }

        EndpointBuilder addPathPartAsIs(String... parts) {
            for (String part : parts) {
                if (StringUtils.hasLength(part)) {
                    joiner.add(part);
                }
            }
            return this;
        }

        String build() {
            return joiner.toString();
        }

        private static String encodePart(String pathPart) {
            try {
                // encode each part (e.g. index, type and id) separately before merging them into the path
                // we prepend "/" to the path part to make this path absolute, otherwise there can be issues with
                // paths that start with `-` or contain `:`
                // the authority must be an empty string and not null, else paths that being with slashes could have them
                // misinterpreted as part of the authority.
                URI uri = new URI(null, "", "/" + pathPart, null, null);
                // manually encode any slash that each part may contain
                return uri.getRawPath().substring(1).replaceAll("/", "%2F");
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Path part [" + pathPart + "] couldn't be encoded", e);
            }
        }
    }

    public static boolean isHttpError(int status) {
        return jakarta.ws.rs.core.Response.Status.Family.familyOf(status) == jakarta.ws.rs.core.Response.Status.Family.CLIENT_ERROR;
    }

    public static void processHttpResponse(Response response) throws IOException {
        if (isHttpError(response.getStatus())) {
            throw new IOException("Opensearch request error: " + (response.getBody().isPresent() ? response.getBody().get().bodyAsString() : ""));
        }
    }
}
