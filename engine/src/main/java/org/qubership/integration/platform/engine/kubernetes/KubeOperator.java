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

package org.qubership.integration.platform.engine.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class KubeOperator {

    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";

    private final ObjectMapper objectMapper;
    private final CoreV1Api coreApi;
    private final CustomObjectsApi customObjectsApi;

    private final String namespace;
    private final Boolean devmode;

    public KubeOperator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        coreApi = new CoreV1Api();
        customObjectsApi = new CustomObjectsApi();
        namespace = null;
        devmode = null;
    }

    public KubeOperator(
            ObjectMapper objectMapper,
            ApiClient client,
            String namespace,
            Boolean devmode
    ) {
        this.objectMapper = objectMapper;
        coreApi = new CoreV1Api(client);

        customObjectsApi = new CustomObjectsApi(client);

        this.namespace = namespace;
        this.devmode = devmode;
    }

    public Map<String, Map<String, String>> getAllSecretsWithLabel(Pair<String, String> label) {
        Map<String, Map<String, String>> secrets = new HashMap<>();

        try {
            V1SecretList secretList = coreApi.listNamespacedSecret(
                namespace,
                null,
                null,
                null,
                null,
                label.getKey() + "=" + label.getValue(),
                null,
                null,
                null,
                null,
                null,
                null
            );

            List<V1Secret> secretListItems = secretList.getItems();
            for (V1Secret secret : secretListItems) {
                V1ObjectMeta metadata = secret.getMetadata();
                if (metadata == null) {
                    continue;
                }

                ConcurrentMap<String, String> dataMap = new ConcurrentHashMap<>();
                if (secret.getData() != null) {
                    secret.getData().forEach((k, v) -> dataMap.put(k, new String(v)));
                }
                secrets.put(metadata.getName(), dataMap);
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                if (!isDevmode()) {
                    log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                }
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }

        return secrets;
    }

    public void createOrReplaceCustomObject(KubeCustomObjectRequest request) {
        String resourceVersion = getCustomObjectResourceVersion(request);
        try {
            if (resourceVersion != null) {
                request.getBody().getMetadata().setResourceVersion(resourceVersion);
                customObjectsApi.replaceNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        namespace,
                        request.getResourceNamePlural(),
                        request.getBody().getMetadata().getName(),
                        request.getBody(),
                        null,
                        null
                );
            } else {
                customObjectsApi.createNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        namespace,
                        request.getResourceNamePlural(),
                        request.getBody(),
                        null,
                        null,
                        null
                );
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                if (!isDevmode()) {
                    log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                }
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public Boolean isDevmode() {
        return devmode;
    }

    private String getCustomObjectResourceVersion(KubeCustomObjectRequest request) {
        try {
            Object response = customObjectsApi.getNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    namespace,
                    request.getResourceNamePlural(),
                    request.getBody().getMetadata().getName()
            );

            JsonNode responseNode = objectMapper.convertValue(response, JsonNode.class);
            JsonNode resourceVersion = responseNode.path("metadata").path("resourceVersion");
            return resourceVersion.isMissingNode() || resourceVersion.isNull() ? null : resourceVersion.asText();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return null;
            } else {
                if (!isDevmode()) {
                    log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                }
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }
}
