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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1PolicyRule;
import io.kubernetes.client.openapi.models.V1Preconditions;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1Secret;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Response;
import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class KubeOperator {

    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";
    private static final String SECRETS_RESOURCE = "secrets";
    private static final String GET_VERB = "get";

    private final ObjectMapper objectMapper;
    private final CoreV1Api coreApi;
    private final CustomObjectsApi customObjectsApi;
    private final RbacAuthorizationV1Api rbacApi;

    private final String namespace;
    private final Boolean devMode;

    public KubeOperator(ObjectMapper objectMapper, boolean devMode) {
        this.objectMapper = objectMapper;
        coreApi = new CoreV1Api();
        customObjectsApi = new CustomObjectsApi();
        rbacApi = new RbacAuthorizationV1Api();
        namespace = null;
        this.devMode = devMode;
    }

    public KubeOperator(
            ObjectMapper objectMapper,
            ApiClient client,
            String namespace,
            boolean devMode
    ) {
        this.objectMapper = objectMapper;
        coreApi = new CoreV1Api(client);
        customObjectsApi = new CustomObjectsApi(client);
        rbacApi = new RbacAuthorizationV1Api(client);
        this.namespace = namespace;
        this.devMode = devMode;
    }

    KubeOperator(
            ObjectMapper objectMapper,
            CoreV1Api coreApi,
            CustomObjectsApi customObjectsApi,
            RbacAuthorizationV1Api rbacApi,
            String namespace,
            boolean devMode
    ) {
        this.objectMapper = objectMapper;
        this.coreApi = coreApi;
        this.customObjectsApi = customObjectsApi;
        this.rbacApi = rbacApi;
        this.namespace = namespace;
        this.devMode = devMode;
    }

    /**
     * Reads the given Role's own rules to discover which Secret names this ServiceAccount is
     * allowed to {@code get} (the {@code resourceNames} of its {@code secrets}/{@code get} rule),
     * then fetches each of those Secrets by name. This avoids granting an unrestricted {@code list}
     * on Secrets: the Role itself is the single source of truth for which names are allowed, and
     * it is already kept up to date by whoever provisions the Role (the catalog).
     */
    public Map<String, Map<String, String>> getSecretsFromRole(String roleName) {
        Map<String, Map<String, String>> secrets = new HashMap<>();
        List<String> resourceNames;

        try {
            V1Role role = rbacApi.readNamespacedRole(roleName, getNotNullNamespace()).execute();
            resourceNames = extractSecretResourceNames(role);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.warn("Role '{}' not found in namespace '{}', no secured secrets will be read",
                    roleName, namespace);
                return secrets;
            }
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }

        for (String secretName : resourceNames) {
            try {
                V1Secret secret = coreApi.readNamespacedSecret(secretName, getNotNullNamespace()).execute();
                ConcurrentMap<String, String> dataMap = new ConcurrentHashMap<>();
                if (secret.getData() != null) {
                    secret.getData().forEach((k, v) -> dataMap.put(k, new String(v)));
                }
                secrets.put(secretName, dataMap);
            } catch (ApiException e) {
                if (e.getCode() == 404) {
                    log.warn("Secret '{}' listed in role '{}' resourceNames was not found, skipping it",
                        secretName, roleName);
                    continue;
                }
                if (!devMode) {
                    log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
                }
                throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
        }

        return secrets;
    }

    private List<String> extractSecretResourceNames(V1Role role) {
        List<V1PolicyRule> rules = role.getRules();
        if (rules == null) {
            return List.of();
        }
        return rules.stream()
            .filter(rule -> rule.getResources() != null && rule.getResources().contains(SECRETS_RESOURCE))
            .filter(rule -> rule.getVerbs() != null && rule.getVerbs().contains(GET_VERB))
            .flatMap(rule -> rule.getResourceNames() == null ? List.<String>of().stream() : rule.getResourceNames().stream())
            .distinct()
            .toList();
    }

    public void createOrReplaceCustomObject(KubeCustomObjectRequest request) {
        String resourceVersion = request.getBody().getMetadata().getResourceVersion();
        try {
            if (resourceVersion != null) {
                customObjectsApi.replaceNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        getNotNullNamespace(),
                        request.getResourceNamePlural(),
                        getNotNullCustomResourceName(request),
                        request.getBody()
                ).execute();
            } else {
                customObjectsApi.createNamespacedCustomObject(
                        request.getGroup(),
                        request.getVersion(),
                        getNotNullNamespace(),
                        request.getResourceNamePlural(),
                        request.getBody()
                ).execute();
            }
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                throw new KubeApiConflictException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public Optional<KubeCustomObject> getCustomObject(KubeCustomObjectRequest request) {
        try {
            Call call = customObjectsApi.getNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    getNotNullNamespace(),
                    request.getResourceNamePlural(),
                    getNotNullCustomResourceName(request)
            ).buildCall(null);

            // Jackson parses the body because the client's Gson reads every number in an untyped
            // Object as a Double, and the ServiceEntry CRD rejects a port written back as 80.0.
            try (Response response = call.execute()) {
                String body = response.body().string();
                if (!response.isSuccessful()) {
                    throw new ApiException(response.message(), response.code(), response.headers().toMultimap(), body);
                }
                return Optional.of(objectMapper.readValue(body, KubeCustomObject.class));
            }
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Optional.empty();
            }
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public void deleteCustomObject(KubeCustomObjectRequest request) {
        String resourceVersion = request.getBody().getMetadata().getResourceVersion();
        try {
            CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest = customObjectsApi.deleteNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    getNotNullNamespace(),
                    request.getResourceNamePlural(),
                    getNotNullCustomResourceName(request)
            );
            if (resourceVersion != null) {
                deleteRequest.body(new V1DeleteOptions()
                        .preconditions(new V1Preconditions().resourceVersion(resourceVersion)));
            }
            deleteRequest.execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return;
            }
            if (e.getCode() == 409) {
                throw new KubeApiConflictException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
            }
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!devMode) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public boolean isDevMode() {
        return devMode;
    }

    private String getNotNullNamespace() {
        return Objects.requireNonNull(namespace, "The namespace is missing");
    }

    private String getNotNullCustomResourceName(KubeCustomObjectRequest customObjectRequest) {
        return Objects.requireNonNull(
            customObjectRequest.getBody().getMetadata().getName(),
            "Custom resource metadata.name must not be null"
        );
    }
}
