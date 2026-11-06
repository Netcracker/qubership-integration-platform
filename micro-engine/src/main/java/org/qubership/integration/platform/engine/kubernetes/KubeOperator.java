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

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.V1PolicyRule;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1Secret;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.nonNull;

@Slf4j
public class KubeOperator {
    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";
    private static final String SECRETS_RESOURCE = "secrets";
    private static final String GET_VERB = "get";

    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final RbacAuthorizationV1Api rbacApi;

    private final String namespace;
    private final Boolean devmode;

    public KubeOperator() {
        coreApi = new CoreV1Api();
        appsApi = new AppsV1Api();
        rbacApi = new RbacAuthorizationV1Api();
        namespace = null;
        devmode = null;
    }

    public KubeOperator(
            ApiClient client,
            String namespace,
            Boolean devmode
    ) {
        coreApi = new CoreV1Api();
        coreApi.setApiClient(client);

        appsApi = new AppsV1Api();
        appsApi.setApiClient(client);

        rbacApi = new RbacAuthorizationV1Api();
        rbacApi.setApiClient(client);

        this.namespace = namespace;
        this.devmode = devmode;
    }

    KubeOperator(
            CoreV1Api coreApi,
            AppsV1Api appsApi,
            RbacAuthorizationV1Api rbacApi,
            String namespace,
            Boolean devmode
    ) {
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.rbacApi = rbacApi;
        this.namespace = namespace;
        this.devmode = devmode;
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
            V1Role role = rbacApi.readNamespacedRole(roleName, namespace, null);
            resourceNames = extractSecretResourceNames(role);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.warn("Role '{}' not found in namespace '{}', no secured secrets will be read",
                    roleName, namespace);
                return secrets;
            }
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }

        for (String secretName : resourceNames) {
            try {
                V1Secret secret = coreApi.readNamespacedSecret(secretName, namespace, null);
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
                if (!isDevmode()) {
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

    public Boolean isDevmode() {
        return nonNull(devmode) && devmode;
    }
}
