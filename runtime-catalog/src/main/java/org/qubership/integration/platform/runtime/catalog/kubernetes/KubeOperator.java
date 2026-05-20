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

package org.qubership.integration.platform.runtime.catalog.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1PodList;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.PodRunningStatus;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class KubeOperator {
    private static final String BUILD_VERSION_LABEL = "app.kubernetes.io/version";
    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;

    private final String namespace;

    public KubeOperator() {
        coreApi = new CoreV1Api();
        appsApi = new AppsV1Api();
        customObjectsApi = new CustomObjectsApi();
        namespace = null;
    }

    public KubeOperator(ApiClient client, String namespace) {
        coreApi = new CoreV1Api();
        coreApi.setApiClient(client);

        appsApi = new AppsV1Api();
        appsApi.setApiClient(client);

        customObjectsApi = new CustomObjectsApi();
        customObjectsApi.setApiClient(client);

        this.namespace = namespace;
    }

    public List<KubeDeployment> getDeploymentsByLabel(String labelKey) throws KubeApiException {
        try {
            V1DeploymentList list = appsApi.listNamespacedDeployment(
                    namespace,
                    null,
                    null,
                    null,
                    null,
                    labelKey + " = true",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            return list.getItems().stream()
                    .map(item -> KubeDeployment.builder()
                            .id(Objects.requireNonNull(item.getMetadata().getUid()))
                            .name(Objects.requireNonNull(item.getMetadata()).getName())
                            .namespace(namespace)
                            .replicas(Objects.requireNonNull(item.getSpec().getReplicas()))
                            .version(Objects.requireNonNull(item.getMetadata().getLabels()).get(BUILD_VERSION_LABEL))
                            .build())
                    .collect(Collectors.toList());

        } catch (ApiException e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public List<KubePod> getPodsByLabel(String labelKey, String labelValue) throws KubeApiException {
        try {
            V1PodList list = coreApi.listNamespacedPod(
                    namespace,
                    null,
                    null,
                    null,
                    null,
                    labelKey + " = " + labelValue,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            return list.getItems().stream()
                    .map(item -> {
                        boolean ready = false;
                        if (item.getStatus() != null
                                && item.getStatus().getContainerStatuses() != null
                                && !item.getStatus().getContainerStatuses().isEmpty()) {
                            ready = item.getStatus().getContainerStatuses().get(0).getReady();
                        }

                        return KubePod.builder()
                                .name(Objects.requireNonNull(item.getMetadata().getName()))
                                .runningStatus(PodRunningStatus.get(Objects.requireNonNull(item.getStatus()).getPhase()))
                                .ready(ready)
                                .ip(item.getStatus().getPodIP())
                                .namespace(namespace)
                                .build();
                    })
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
