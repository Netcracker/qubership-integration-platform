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

package org.qubership.integration.platform.engine.opensearch.ism;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.rest.ISMStatusResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.PolicyResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.RequestHelper;

import java.io.IOException;
import java.util.Optional;

public class IndexStateManagementClient {
    private final OpenSearchGenericClient client;

    private final ObjectMapper jsonMapper;

    public IndexStateManagementClient(OpenSearchClient client, ObjectMapper jsonMapper) {
        this.client = client.generic();
        this.jsonMapper = jsonMapper;
    }

    public PolicyResponse getPolicy(String policyId, boolean optional) throws IOException {
        Response response = getRestClient().execute(RequestHelper.buildGetPolicyRequest(policyId));
        if (optional && response.getStatus() == HttpStatus.SC_NOT_FOUND) {
            return null;
        }
        return deserializeResponseData(response, PolicyResponse.class);
    }

    public Optional<PolicyResponse> tryGetPolicy(String policyId) throws IOException {
        try {
            return Optional.ofNullable(getPolicy(policyId, true));
        } catch (ResponseException exception) {
            if (exception.status() == HttpStatus.SC_NOT_FOUND) {
                return Optional.empty();
            } else {
                throw exception;
            }
        }
    }

    public PolicyResponse createPolicy(Policy policy) throws IOException {
        Response response = getRestClient().execute(RequestHelper.buildCreatePolicyRequest(jsonMapper, policy));
        return deserializeResponseData(response, PolicyResponse.class);
    }

    public PolicyResponse updatePolicy(Policy policy, long seqNo, long primaryTerm) throws IOException {
        Response response = getRestClient().execute(RequestHelper.buildUpdatePolicyRequest(jsonMapper, policy, seqNo, primaryTerm));
        return deserializeResponseData(response, PolicyResponse.class);
    }

    public ISMStatusResponse addPolicy(String indexName, String policyId) throws IOException {
        Response response = getRestClient().execute(RequestHelper.buildAddPolicyRequest(jsonMapper, indexName, policyId));
        return deserializeResponseData(response, ISMStatusResponse.class);
    }

    public ISMStatusResponse removePolicy(String indexName) throws IOException {
        Response response = getRestClient().execute(RequestHelper.buildRemovePolicyFromIndexRequest(indexName));
        return deserializeResponseData(response, ISMStatusResponse.class);
    }

    private OpenSearchGenericClient getRestClient() {
        return client;
    }

    private <T> T deserializeResponseData(Response response, Class<T> cls) throws IOException {
        RequestHelper.processHttpResponse(response);
        return response.getBody().isPresent() ? jsonMapper.readValue(response.getBody().get().bodyAsString(), cls) : null;
    }
}
