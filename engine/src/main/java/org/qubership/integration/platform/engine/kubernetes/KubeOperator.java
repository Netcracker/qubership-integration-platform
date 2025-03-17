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

    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;

    private final String namespace;
    private final Boolean devmode;

    public KubeOperator() {
        coreApi = new CoreV1Api();
        appsApi = new AppsV1Api();
        namespace = null;
        devmode = null;
    }

    public KubeOperator(ApiClient client,
        String namespace,
        Boolean devmode) {

        coreApi = new CoreV1Api();
        coreApi.setApiClient(client);

        appsApi = new AppsV1Api();
        appsApi.setApiClient(client);

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

    public Boolean isDevmode() {
        return devmode;
    }
}
