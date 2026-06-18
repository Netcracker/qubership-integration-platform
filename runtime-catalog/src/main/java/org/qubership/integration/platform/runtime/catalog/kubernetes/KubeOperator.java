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

import com.coreos.monitoring.models.V1ServiceMonitor;
import com.coreos.monitoring.models.V1ServiceMonitorList;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.PatchUtils;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.CustomResourceDeployError;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegrationList;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.PodRunningStatus;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil.getName;

@Slf4j
public class KubeOperator {
    private static final String BUILD_VERSION_LABEL = "app.kubernetes.io/version";
    private static final String DEFAULT_ERR_MESSAGE = "Invalid k8s cluster parameters or API error. ";
    private static final String REGEX_FOR_SEARCH_BLUEGREEN_SERVICE_NAME = ".*-v\\d+$";
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

    public List<KubeDeployment> getDeploymentsByLabel(String labelKey) {
        return getDeploymentsByLabel(labelKey, null);
    }

    public List<KubeDeployment> getDeploymentsByLabel(String labelKey, String labelValue) throws KubeApiException {
        try {
            V1DeploymentList list = appsApi.listNamespacedDeployment(namespace)
                    .labelSelector(toSelector(labelKey, labelValue))
                    .execute();

            return list.getItems().stream()
                    .map(item -> KubeDeployment.builder()
                            .id(Objects.requireNonNull(item.getMetadata().getUid()))
                            .name(Objects.requireNonNull(item.getMetadata()).getName())
                            .labels(Objects.requireNonNull(item.getMetadata()).getLabels())
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
            V1PodList list = coreApi.listNamespacedPod(namespace)
                    .labelSelector(toSelector(labelKey, labelValue))
                    .execute();

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

    public List<KubeService> getServices() {
        try {
            V1ServiceList list = coreApi.listNamespacedService(namespace).execute();

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

    public void createOrUpdateResource(Object resource) throws KubeApiException {
        if (resource instanceof V1ConfigMap cm) {
            createOrUpdateConfigMap(cm);
        } else if (resource instanceof V1Service service) {
            createOrUpdateService(service);
        } else if (resource instanceof CamelKIntegration integration) {
            createOrUpdateCustomResource("camel.apache.org", "v1", "integrations",
                    integration, new TypeToken<CamelKIntegrationList>() {
                    }.getType());
        } else if (resource instanceof V1ServiceMonitor serviceMonitor) {
            createOrUpdateCustomResource("monitoring.coreos.com", "v1", "servicemonitors",
                    serviceMonitor, new TypeToken<V1ServiceMonitorList>() {
                    }.getType());
        } else {
            throw new CustomResourceDeployError("Unsupported resource type: " + resource);
        }
    }

    private void createOrUpdateConfigMap(V1ConfigMap cm) throws KubeApiException {
        try {
            V1ConfigMapList configMapList = coreApi.listNamespacedConfigMap(namespace).execute();
            if (listContains(configMapList, cm)) {
                PatchUtils.patch(
                        V1ConfigMap.class,
                        () -> coreApi.patchNamespacedConfigMap(
                                getName(cm).orElseThrow(() -> new KubeApiException("Failed to get config map name")),
                                namespace,
                                new V1Patch(JSON.serialize(cm))
                        )
                                .fieldManager("kubectl-patch")
                                .force(true)
                                .buildCall(null),
                        V1Patch.PATCH_FORMAT_APPLY_YAML,
                        coreApi.getApiClient()
                );
            } else {
                coreApi.createNamespacedConfigMap(namespace, cm).execute();
            }
        } catch (ApiException e) {
            throw new KubeApiException("Failed to create or update ConfigMap", e);
        }
    }

    private void createOrUpdateService(V1Service service) throws KubeApiException {
        try {
            V1ServiceList serviceList = coreApi.listNamespacedService(namespace).execute();
            if (listContains(serviceList, service)) {
                PatchUtils.patch(
                        V1Service.class,
                        () -> coreApi.patchNamespacedService(
                                getName(service).orElseThrow(() -> new KubeApiException("Failed to get service name")),
                                namespace,
                                new V1Patch(JSON.serialize(service))
                        )
                                .fieldManager("kubectl-patch")
                                .force(true)
                                .buildCall(null),
                        V1Patch.PATCH_FORMAT_APPLY_YAML,
                        coreApi.getApiClient()
                );
            } else {
                coreApi.createNamespacedService(namespace, service).execute();
            }
        } catch (ApiException e) {
            throw new KubeApiException("Failed to create or update Service", e);
        }
    }

    private <T extends KubernetesObject> void createOrUpdateCustomResource(
            String group,
            String version,
            String plural,
            T obj,
            Type listType
    ) throws KubeApiException {
        try {
            Object rawListObj = customObjectsApi.listNamespacedCustomObject(group, version, namespace, plural).execute();
            KubernetesListObject listObject = fromRawObject(rawListObj, listType);
            Optional<String> name = getName(obj);
            Optional<V1ObjectMeta> existingItemMetadata = listObject.getItems()
                    .stream()
                    .filter(item -> getName(item).equals(name))
                    .map(KubernetesObject::getMetadata)
                    .findAny();
            boolean alreadyExists = existingItemMetadata.isPresent();
            if (alreadyExists) {
                PatchUtils.patch(
                        Object.class,
                        () -> customObjectsApi.patchNamespacedCustomObject(
                                group,
                                version,
                                namespace,
                                plural,
                                name.orElseThrow(() -> new KubeApiException("Failed to get custom object name")),
                                new V1Patch(JSON.serialize(obj))
                        )
                                .fieldManager("kubectl-patch")
                                .force(true)
                                .buildCall(null),
                        V1Patch.PATCH_FORMAT_APPLY_YAML,
                        customObjectsApi.getApiClient()
                );
            } else {
                customObjectsApi.createNamespacedCustomObject(group, version, namespace, plural, obj).execute();
            }
        } catch (ApiException e) {
            throw new KubeApiException("Failed to create or update custom object", e);
        }
    }

    private boolean listContains(KubernetesListObject objectList, KubernetesObject object) {
        Optional<String> name = getName(object);
        return objectList.getItems().stream()
                .anyMatch(m -> getName(m).equals(name));
    }

    private String toSelector(String labelName, String labelValue) {
        return isNull(labelValue) ? labelName : String.format("%s=%s", labelName, labelValue);
    }

    private String toSelector(Map<String, String> labelValues) {
        return labelValues.entrySet().stream()
            .map(e -> toSelector(e.getKey(), e.getValue()))
            .collect(Collectors.joining(","));
    }

    private <T> T fromRawObject(Object obj, Type type) {
        return JSON.deserialize(JSON.serialize(obj), type);
    }

    public List<CamelKIntegration> getIntegrationsByLabels(Map<String, String> labelValues) throws KubeApiException {
        try {
            Object rawListObj = customObjectsApi.listNamespacedCustomObject("camel.apache.org", "v1", namespace, "integrations")
                .labelSelector(toSelector(labelValues))
                .execute();
            CamelKIntegrationList listObject = fromRawObject(rawListObj, new TypeToken<CamelKIntegrationList>() {}.getType());
            return listObject.getItems();
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get Camel K integrations", exception);
        }
    }

    public List<V1ServiceMonitor> getServiceMonitorsByLabel(String labelName, String labelValue) throws KubeApiException {
        try {
            Object rawListObj = customObjectsApi
                    .listNamespacedCustomObject("monitoring.coreos.com", "v1", namespace, "servicemonitors")
                    .labelSelector(toSelector(labelName, labelValue))
                    .execute();
            V1ServiceMonitorList listObject = fromRawObject(rawListObj, new TypeToken<V1ServiceMonitorList>() {}.getType());
            return listObject.getItems();
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get service monitors.", exception);
        }
    }

    public List<V1Service> getServicesByLabel(String labelName, String labelValue) throws KubeApiException {
        try {
            return coreApi.listNamespacedService(namespace)
                    .labelSelector(toSelector(labelName, labelValue))
                    .execute()
                    .getItems();
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get services.", exception);
        }
    }

    public List<V1ConfigMap> getConfigMapsByLabel(String labelName, String labelValue) throws KubeApiException {
        try {
            return coreApi.listNamespacedConfigMap(namespace)
                    .labelSelector(toSelector(labelName, labelValue))
                    .execute()
                    .getItems();
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get config maps.", exception);
        }
    }

    public void deleteConfigMap(String name) throws KubeApiException {
        try {
            coreApi.deleteNamespacedConfigMap(name, namespace).execute();
        } catch (ApiException exception) {
            if (exception.getCode() == HttpStatus.NOT_FOUND.value()) {
                log.warn("Config map with name {} not found.", name);
            } else {
                throw new KubeApiException("Failed to delete config map: " + name, exception);
            }
        }
    }

    public void deleteService(String name) throws KubeApiException {
        try {
            coreApi.deleteNamespacedService(name, namespace).execute();
        } catch (ApiException exception) {
            if (exception.getCode() == HttpStatus.NOT_FOUND.value()) {
                log.warn("Service with name {} not found.", name);
            } else {
                throw new KubeApiException("Failed to delete service: " + name, exception);
            }
        }
    }

    public void deleteServiceMonitor(String name) throws KubeApiException {
        deleteCustomObject("monitoring.coreos.com", "v1", "servicemonitors", name);
    }

    public void deleteCamelKIntegration(String name) throws KubeApiException {
        deleteCustomObject("camel.apache.org", "v1", "integrations", name);
    }

    private void deleteCustomObject(String group, String version, String plural, String name) throws KubeApiException {
        try {
            customObjectsApi.deleteNamespacedCustomObject(group, version, namespace, plural, name).execute();
        } catch (ApiException exception) {
            if (exception.getCode() == HttpStatus.NOT_FOUND.value()) {
                log.warn("Object with name {} not found.", name);
            } else {
                throw new KubeApiException("Failed to delete object: " + name, exception);
            }
        }
    }
}
