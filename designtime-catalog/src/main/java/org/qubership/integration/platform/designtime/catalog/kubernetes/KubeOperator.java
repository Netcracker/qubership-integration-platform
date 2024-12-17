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

package org.qubership.integration.platform.designtime.catalog.kubernetes;

import org.qubership.integration.platform.designtime.catalog.exception.exceptions.KubeApiException;
import org.qubership.integration.platform.designtime.catalog.model.kubernetes.KubeService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1ServicePort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class KubeOperator {
    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";
    private static final String REGEX_FOR_SEARCH_BLUEGREEN_SERVICE_NAME = ".*-v\\d+$";

    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;

    private final String namespace;

    public KubeOperator() {
        coreApi = new CoreV1Api();
        appsApi = new AppsV1Api();
        namespace = null;
    }

    public KubeOperator(ApiClient client, String namespace) {
        coreApi = new CoreV1Api();
        coreApi.setApiClient(client);

        appsApi = new AppsV1Api();
        appsApi.setApiClient(client);

        this.namespace = namespace;
    }

    public List<KubeService> getServices() {
        try {
            V1ServiceList list = coreApi.listNamespacedService(
                    namespace,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            return list.getItems().stream()
                    .filter(item -> !(Objects.requireNonNull(Objects.requireNonNull(item.getMetadata()).getName()).matches(REGEX_FOR_SEARCH_BLUEGREEN_SERVICE_NAME)))
                    .map(item -> KubeService.builder()
                            .id(Objects.requireNonNull(Objects.requireNonNull(item.getMetadata()).getUid()))
                            .name(Objects.requireNonNull(item.getMetadata().getName()))
                            .namespace(namespace)
                            .ports(
                                    Objects.requireNonNull(Objects.requireNonNull(item.getSpec()).getPorts()).stream()
                                            .map(V1ServicePort::getPort).collect(Collectors.toList()
                                            )).build())
                    .collect(Collectors.toList());
        } catch (ApiException e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }
}
