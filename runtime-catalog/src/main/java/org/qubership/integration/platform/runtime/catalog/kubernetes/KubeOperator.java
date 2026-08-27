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
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.*;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainDeployError;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegrationList;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObjectList;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
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
    private static final String HTTP_ROUTE_KIND = "HTTPRoute";
    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String SERVICE_ENTRY_KIND = "ServiceEntry";
    private static final String DESTINATION_RULE_KIND = "DestinationRule";
    private static final String ISTIO_NETWORKING_API_GROUP = "networking.istio.io";
    private static final String ISTIO_NETWORKING_API_VERSION = "v1";
    private static final String SERVICE_ENTRIES_PLURAL = "serviceentries";
    private static final String DESTINATION_RULES_PLURAL = "destinationrules";
    private static final String CAMEL_API_GROUP = "camel.apache.org";
    private static final String INTEGRATIONS_PLURAL = "integrations";
    private static final String MONITORING_API_GROUP = "monitoring.coreos.com";
    private static final String SERVICE_MONITORS_PLURAL = "servicemonitors";
    private static final String APPLY_RESOURCE_LOG_FORMAT = "Applying {} name={}";
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;
    private final GenericCustomResources genericCustomResources;

    private final String namespace;

    public KubeOperator() {
        coreApi = new CoreV1Api();
        appsApi = new AppsV1Api();
        customObjectsApi = new CustomObjectsApi();
        namespace = null;
        genericCustomResources = null;
    }

    public KubeOperator(ApiClient client, String namespace, GenericCustomResources genericCustomResources) {
        coreApi = new CoreV1Api();
        coreApi.setApiClient(client);

        appsApi = new AppsV1Api();
        appsApi.setApiClient(client);

        customObjectsApi = new CustomObjectsApi();
        customObjectsApi.setApiClient(client);

        this.namespace = namespace;

        this.genericCustomResources = genericCustomResources;
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
        createOrUpdateResource(resource, false);
    }

    /**
     * Writes {@code resource}, deciding create versus replace from a write-time read unless
     * {@code observedAbsent} says the caller already knows the object was not there.
     *
     * <p>{@code observedAbsent} carries the deploy path's observed-absent state (see
     * {@code MicroDomainService.deploy}): Phase 1 looked for this object and found nothing. It skips
     * the read and creates outright. Reading anyway would find an object a racing writer created
     * during the build, and the replace that followed would overwrite it whole, because a build that
     * saw nothing had nothing to merge against. Creating instead turns that race into a 409
     * AlreadyExists, which {@link #toKubeException} reports as {@link KubeApiConflictException} so
     * the caller can rebuild and retry. Callers outside the deploy path pass {@code false} and keep
     * read-then-decide.
     */
    public void createOrUpdateResource(Object resource, boolean observedAbsent) throws KubeApiException {
        log.debug("Processing resource of type: {}", resource.getClass().getSimpleName());
        if (resource instanceof V1ConfigMap cm) {
            createOrUpdateConfigMap(cm, observedAbsent);
        } else if (resource instanceof V1Service service) {
            createOrUpdateService(service, observedAbsent);
        } else if (resource instanceof CamelKIntegration integration) {
            createOrUpdateCustomResource(CAMEL_API_GROUP, "v1", INTEGRATIONS_PLURAL, integration, true, observedAbsent);
        } else if (resource instanceof V1ServiceMonitor serviceMonitor) {
            createOrUpdateCustomResource(MONITORING_API_GROUP, "v1", SERVICE_MONITORS_PLURAL, serviceMonitor, true,
                    observedAbsent);
        } else if (resource instanceof KubeCustomObject customObject && HTTP_ROUTE_KIND.equals(customObject.getKind())) {
            // HTTPRoute is handled directly (not through GenericCustomResources) because that map
            // returns empty under the "localdev" profile, which would make definitionFor() throw
            // there too. It's also always safe to update in place if it already exists.
            log.debug(APPLY_RESOURCE_LOG_FORMAT, customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, customObject, true,
                    observedAbsent);
        } else if (resource instanceof KubeCustomObject customObject && SERVICE_ENTRY_KIND.equals(customObject.getKind())) {
            // Same rationale as HTTPRoute above: handled directly, not through GenericCustomResources.
            log.debug(APPLY_RESOURCE_LOG_FORMAT, customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(ISTIO_NETWORKING_API_GROUP, ISTIO_NETWORKING_API_VERSION, SERVICE_ENTRIES_PLURAL,
                    customObject, true, observedAbsent);
        } else if (resource instanceof KubeCustomObject customObject && DESTINATION_RULE_KIND.equals(customObject.getKind())) {
            // Same rationale as HTTPRoute above: handled directly, not through GenericCustomResources.
            log.debug(APPLY_RESOURCE_LOG_FORMAT, customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(ISTIO_NETWORKING_API_GROUP, ISTIO_NETWORKING_API_VERSION, DESTINATION_RULES_PLURAL,
                    customObject, true, observedAbsent);
        } else if (resource instanceof KubeCustomObject customObject) {
            GenericCustomResources.CustomResourceDefinition resourceDefinition =
                Optional.ofNullable(genericCustomResources)
                    .orElseThrow(() -> new KubeApiException("No generic custom resource definition for kind: " + customObject.getKind()))
                    .definitionFor(customObject.getKind());
            boolean updateIfExists = resourceDefinition.updateIfExists();

            log.debug(APPLY_RESOURCE_LOG_FORMAT + ", updateIfExists={}",
                    customObject.getKind(), getName(customObject).orElse(""), updateIfExists);
            createOrUpdateCustomResource(resourceDefinition.group(), resourceDefinition.version(), resourceDefinition.plural(), customObject,
                    updateIfExists, observedAbsent);
        } else if (resource instanceof V1Secret secret) {
            createSecretIfAbsent(secret);
        } else {
            log.error("Unsupported resource type: {}", resource.getClass().getName());
            throw new MicroDomainDeployError("Unsupported resource type: " + resource);
        }
    }

    private void createOrUpdateConfigMap(V1ConfigMap cm, boolean observedAbsent) throws KubeApiException {
        String name = getName(cm).orElseThrow(() -> new KubeApiException("Failed to get config map name"));
        V1ConfigMap live = observedAbsent
                ? null
                : readOrNull(() -> coreApi.readNamespacedConfigMap(name, namespace).execute());
        try {
            if (live == null) {
                clearResourceVersionForCreate(cm.getMetadata());
                coreApi.createNamespacedConfigMap(namespace, cm).execute();
            } else {
                applyPrecondition(cm.getMetadata(), live.getMetadata());
                coreApi.replaceNamespacedConfigMap(name, namespace, cm).execute();
            }
        } catch (ApiException e) {
            throw toKubeException("Failed to create or update ConfigMap", e);
        }
    }

    private void createOrUpdateService(V1Service service, boolean observedAbsent) throws KubeApiException {
        String name = getName(service).orElseThrow(() -> new KubeApiException("Failed to get service name"));
        V1Service live = observedAbsent
                ? null
                : readOrNull(() -> coreApi.readNamespacedService(name, namespace).execute());
        try {
            if (live == null) {
                clearResourceVersionForCreate(service.getMetadata());
                coreApi.createNamespacedService(namespace, service).execute();
            } else {
                applyPrecondition(service.getMetadata(), live.getMetadata());
                coreApi.replaceNamespacedService(name, namespace, service).execute();
            }
        } catch (ApiException e) {
            throw toKubeException("Failed to create or update Service", e);
        }
    }

    private <T extends KubernetesObject> void createOrUpdateCustomResource(
            String group,
            String version,
            String plural,
            T obj,
            boolean updateIfExists,
            boolean observedAbsent
    ) throws KubeApiException {
        String name = getName(obj).orElseThrow(() -> new KubeApiException("Failed to get custom object name"));
        Object rawLive = observedAbsent
                ? null
                : readOrNull(() ->
                        customObjectsApi.getNamespacedCustomObject(group, version, namespace, plural, name).execute());
        try {
            if (rawLive == null) {
                clearResourceVersionForCreate(obj.getMetadata());
                customObjectsApi.createNamespacedCustomObject(group, version, namespace, plural, obj).execute();
                return;
            }
            if (!updateIfExists) {
                log.info("Custom object {}/{} already exists, skipping update as not needed for this kind",
                        obj.getKind(), name);
                return;
            }
            KubeCustomObject liveCustomObject = fromRawObject(rawLive, KubeCustomObject.class);
            applyPrecondition(obj.getMetadata(), liveCustomObject.getMetadata());
            customObjectsApi.replaceNamespacedCustomObject(group, version, namespace, plural, name, obj).execute();
        } catch (ApiException e) {
            throw toKubeException("Failed to create or update custom object", e);
        }
    }

    /**
     * Copies {@code live}'s {@code resourceVersion} onto {@code outgoing} unless the caller already
     * set one. A caller-supplied version is a deliberate precondition taken from an earlier read;
     * overwriting it with the version we just fetched would make the check always pass and defeat
     * the point.
     *
     * <p>A caller-supplied version has two legitimate origins, and both are honored on purpose:
     * a precondition captured during an earlier read for exactly this write, and a live object a
     * read-modify-write caller is writing straight back (its own {@code resourceVersion} came from
     * the cluster, so it is just as real a precondition). Clearing it in the second case to force
     * last-write-wins would reintroduce the silent-overwrite bug this method exists to close.
     */
    private static void applyPrecondition(V1ObjectMeta outgoing, V1ObjectMeta live) {
        if (outgoing == null || live == null) {
            return;
        }
        if (outgoing.getResourceVersion() == null || outgoing.getResourceVersion().isBlank()) {
            outgoing.setResourceVersion(live.getResourceVersion());
        }
    }

    /**
     * Drops any resourceVersion the outgoing object carries, because the API server rejects a create
     * that declares one and reports the rejection outside the 409 range -- so the deploy retry never
     * fires and the deploy stays broken until it is re-issued by hand. A version reaches a create
     * whenever Phase 1 observed the object and something deleted it before the write.
     */
    private static void clearResourceVersionForCreate(V1ObjectMeta metadata) {
        if (metadata != null) {
            metadata.setResourceVersion(null);
        }
    }

    /** Runs a single-object read, returning null for 404 and propagating every other failure. */
    private <T> T readOrNull(ApiReader<T> reader) throws KubeApiException {
        try {
            return reader.read();
        } catch (ApiException e) {
            if (e.getCode() == HttpStatus.NOT_FOUND.value()) {
                return null;
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        }
    }

    @FunctionalInterface
    private interface ApiReader<T> {
        T read() throws ApiException;
    }

    /** Maps HTTP 409 -- a lost race on replace, or AlreadyExists on create -- onto the typed conflict. */
    private static KubeApiException toKubeException(String message, ApiException e) {
        if (e.getCode() == HttpStatus.CONFLICT.value()) {
            return new KubeApiConflictException(message + ": " + e.getResponseBody(), e);
        }
        return new KubeApiException(message, e);
    }

    private void createSecretIfAbsent(V1Secret secret) throws KubeApiException {
        String name = getName(secret).orElseThrow(() -> new KubeApiException("Failed to get secret name"));
        try {
            coreApi.readNamespacedSecret(name, namespace).execute();
            log.info("Secret {} already exists, no need to patch it", name);
        } catch (ApiException e) {
            if (e.getCode() != HttpStatus.NOT_FOUND.value()) {
                throw new KubeApiException("Failed to read Secret: " + name, e);
            }
            try {
                clearResourceVersionForCreate(secret.getMetadata());
                coreApi.createNamespacedSecret(namespace, secret).execute();
            } catch (ApiException createException) {
                throw new KubeApiException("Failed to create Secret: " + name, createException);
            }
        }
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
            Object rawListObj = customObjectsApi.listNamespacedCustomObject(CAMEL_API_GROUP, "v1", namespace, INTEGRATIONS_PLURAL)
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
                    .listNamespacedCustomObject(MONITORING_API_GROUP, "v1", namespace, SERVICE_MONITORS_PLURAL)
                    .labelSelector(toSelector(labelName, labelValue))
                    .execute();
            V1ServiceMonitorList listObject = fromRawObject(rawListObj, new TypeToken<V1ServiceMonitorList>() {}.getType());
            return listObject.getItems();
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get service monitors.", exception);
        }
    }

    public List<KubeCustomObject> getCustomObjectsByLabelAndDefinition(String labelName, String labelValue,
                                                                       GenericCustomResources.CustomResourceDefinition crDefinition) throws KubeApiException {
        try {
            Object rawListObj = customObjectsApi
                .listNamespacedCustomObject(crDefinition.group(), crDefinition.version(), namespace, crDefinition.plural())
                .labelSelector(toSelector(labelName, labelValue))
                .execute();
            KubeCustomObjectList listObject = fromRawObject(rawListObj, new TypeToken<KubeCustomObjectList>() {}.getType());
            List<KubeCustomObject> items = listObject.getItems();
            log.debug("Found {} {} object(s) with label {}={}", items.size(), crDefinition.kind(), labelName, labelValue);
            return items;
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get custom objects.", exception);
        }
    }

    public List<KubeCustomObject> getServiceEntries() throws KubeApiException {
        return listCustomObjects(ISTIO_NETWORKING_API_GROUP, ISTIO_NETWORKING_API_VERSION, SERVICE_ENTRIES_PLURAL);
    }

    public List<KubeCustomObject> getDestinationRules() throws KubeApiException {
        return listCustomObjects(ISTIO_NETWORKING_API_GROUP, ISTIO_NETWORKING_API_VERSION, DESTINATION_RULES_PLURAL);
    }

    /**
     * Lists every object of the given kind in this namespace, unfiltered by label -- unlike
     * {@link #getCustomObjectsByLabelAndDefinition}, which scopes to one domain's own resources.
     * {@code ServiceEntry}/{@code DestinationRule} are shared across every domain that targets a
     * given external host, so there's no single domain label to filter by; callers that only need
     * specific ones filter the result themselves. A 404 (the CRD itself isn't installed, e.g. a
     * Core-mesh cluster with no Istio CRDs) is treated as "none exist" rather than an error.
     */
    private List<KubeCustomObject> listCustomObjects(String group, String version, String plural) throws KubeApiException {
        try {
            Object rawListObj = customObjectsApi.listNamespacedCustomObject(group, version, namespace, plural).execute();
            KubeCustomObjectList listObject = fromRawObject(rawListObj, new TypeToken<KubeCustomObjectList>() {}.getType());
            return listObject.getItems();
        } catch (ApiException exception) {
            if (exception.getCode() == HttpStatus.NOT_FOUND.value()) {
                return List.of();
            }
            throw new KubeApiException("Failed to list custom objects.", exception);
        }
    }

    public Optional<KubeCustomObject> getCustomObject(String group, String version, String plural, String name)
            throws KubeApiException {
        try {
            Object rawObj = customObjectsApi.getNamespacedCustomObject(group, version, namespace, plural, name)
                    .execute();
            KubeCustomObject customObject = fromRawObject(rawObj, KubeCustomObject.class);
            return Optional.of(customObject);
        } catch (ApiException exception) {
            if (exception.getCode() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw new KubeApiException("Failed to get object: " + name, exception);
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

    public List<V1Secret> getSecretsByLabel(String labelName, String labelValue) throws KubeApiException {
        try {
            return coreApi.listNamespacedSecret(namespace)
                .labelSelector(toSelector(labelName, labelValue))
                .execute()
                .getItems();
        } catch (ApiException exception) {
            throw new KubeApiException("Failed to get secrets.", exception);
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

    public void deleteSecret(String name) throws KubeApiException {
        try {
            coreApi.deleteNamespacedSecret(name, namespace).execute();
        } catch (ApiException exception) {
            if (exception.getCode() == HttpStatus.NOT_FOUND.value()) {
                log.warn("Secret with name {} not found.", name);
            } else {
                throw new KubeApiException("Failed to delete secret: " + name, exception);
            }
        }
    }

    public void deleteServiceMonitor(String name) throws KubeApiException {
        deleteCustomObject(MONITORING_API_GROUP, "v1", SERVICE_MONITORS_PLURAL, name);
    }

    public void deleteCamelKIntegration(String name) throws KubeApiException {
        deleteCustomObject(CAMEL_API_GROUP, "v1", INTEGRATIONS_PLURAL, name);
    }

    public void deleteCustomObject(String group, String version, String plural, String name) throws KubeApiException {
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
